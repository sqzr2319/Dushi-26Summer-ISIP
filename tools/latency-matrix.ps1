# Latency comparison for the on-device VLM path, with a memory gate.
#
# Why the memory gate: this device runs close to its memory limit (13k-photo
# library, other apps, ~300 MB MemFree as the steady state). When the engine is
# restarted repeatedly the model weights (1.9 GB, mmap'd) get evicted and the
# process drops to VmRSS ~204 MB with every decode re-reading weights from flash.
# Observed in that state: decode token 0 taking 4386 ms instead of ~80 ms,
# CPU 670-713% idle, all threads sleeping. Any comparison made under that
# condition is measuring swap thrash, not the setting under test.
#
# So every case first waits for MemAvailable to come back above a threshold.
#
# !! KEEP THIS FILE PURE ASCII !!
# Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI; non-ASCII text here breaks
# string quoting and the whole script fails to parse. Device logs are Chinese, so
# they are matched only by ASCII substrings (clip_image_batch_encode, eval_chunks,
# tokens, EOG, accelMode=, HTP0-REPACK) or dumped whole.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools/latency-matrix.ps1
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot 'latency-matrix.txt'
Remove-Item $out -ErrorAction SilentlyContinue
function Log($t) { Add-Content -Path $out -Value $t -Encoding utf8 }

& $adb push (Join-Path $PSScriptRoot 'run_photos.sh') /data/local/tmp/run_photos.sh 2>&1 | Out-Null

function MemAvailableKb {
    $m = & $adb shell "grep MemAvailable /proc/meminfo"
    if ($m -match '(\d+)') { return [int]$Matches[1] }
    return 0
}

# Block until the device has enough free memory for a representative run.
function Wait-ForMemory($tag, $minKb = 3500000, $timeoutSec = 300) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        $avail = MemAvailableKb
        if ($avail -ge $minKb) {
            Log "  [gate] $tag : MemAvailable=$([int]($avail/1024)) MB after $([int]$sw.Elapsed.TotalSeconds)s"
            return $true
        }
        Start-Sleep -Seconds 20
    }
    Log "  [gate] $tag : TIMEOUT, MemAvailable only $([int]((MemAvailableKb)/1024)) MB"
    return $false
}

$RE_STAGE = 'clip_image_batch_encode|eval_chunks|tokens,|EOG'
$RE_BACKEND = 'accelMode=|NPU\(Hexagon HTP\)|CLIP using|HTP0-REPACK'

# run_photos.sh args: count side maxTokens skip npuState backend prompt threads mmprojGpu
function Run-Case($tag, $side, $threads) {
    & $adb shell "am force-stop com.example.isip" | Out-Null
    $null = Wait-ForMemory $tag
    & $adb shell "run-as com.example.isip rm -f files/bench/benchmark.txt files/bench/native.log" | Out-Null
    # Two photos; the second is the reported one (the first warms the vision tower).
    & $adb shell "sh /data/local/tmp/run_photos.sh 2 $side 512 500 reset AUTO '' $threads" 2>&1 | Out-Null

    $deadline = (Get-Date).AddMinutes(20)
    $ended = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 15
        if (-not (& $adb shell "pidof com.example.isip")) { break }
        if ((& $adb shell "run-as com.example.isip grep -ac 'PHOTO-ANALYSIS END' files/bench/benchmark.txt 2>/dev/null") -eq "1") { $ended = $true; break }
    }
    Log ""
    Log "########## $tag (side=$side threads=$threads) [$(if ($ended) { 'ok' } else { 'HUNG-or-timeout' })] ##########"
    $native = & $adb shell "run-as com.example.isip cat files/bench/native.log 2>/dev/null"
    $bench  = & $adb shell "run-as com.example.isip cat files/bench/benchmark.txt 2>/dev/null"
    Log "--- backend / vision tower placement ---"
    $native | Select-String -Pattern $RE_BACKEND | ForEach-Object { Log "  $($_.Line)" }
    Log "--- stage boundaries (derives vision tower / prefill / decode durations) ---"
    $native | Select-String -Pattern $RE_STAGE | ForEach-Object { Log "  $($_.Line)" }
    Log "--- full benchmark log (photo [1] is the result of record) ---"
    $bench | ForEach-Object { Log "  $_" }
    if (-not $ended) { & $adb shell "am force-stop com.example.isip" | Out-Null }
    Log "  memory after run: MemAvailable=$([int]((MemAvailableKb)/1024)) MB"
}

# NOTE ON THREADS: numThreads=8 was tried and HANGS, twice, both times parked in
# prefill (right after the "non-consecutive token position" / "failed to allocate
# graph" lines). The second attempt had MemAvailable=4.38 GB, so it is not memory
# pressure - 8 threads is simply unusable for this engine on this device. Cases
# below therefore stay at the production value of 4 threads; the lever under test
# is imageSide, which scales the vision tower (the single longest stage).
Run-Case "BASELINE: production defaults (side 768)" 768 4
Run-Case "A: side 640" 640 4
Run-Case "B: side 512" 512 4

Log ""
Log "########## DONE ##########"
Get-Content $out -Encoding utf8
