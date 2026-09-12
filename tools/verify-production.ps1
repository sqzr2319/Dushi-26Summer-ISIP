# Production-path regression check: is it really on the NPU, is the output really
# correct, and do the fallback branches really fall back?
#
# Usage (from the repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-production.ps1
#
# Why this script exists: the most expensive mistake in this project was measuring
# only latency and never the generated text, which turned a Vulkan compute bug into
# a wrong "the model cannot handle real photos" conclusion and let the production
# path emit garbage for a long time. So every case below records BOTH the backend
# that actually took effect AND the description text that came out.
#
# Three constraints, all learned the hard way:
#   1. Launch through the on-device script (run_photos.sh). An inline `am start`
#      loses quoting across adb -> PowerShell -> sh (the package name once became
#      `is` and the run silently never happened).
#   2. Read logs with `cat` and filter on the host. Do NOT pass non-ASCII grep
#      patterns into adb; that dies with "no closing quote" in sh.
#   3. THIS FILE MUST STAY PURE ASCII. Windows PowerShell 5.1 reads a BOM-less .ps1
#      as ANSI, so any non-ASCII text here is mis-decoded, which breaks string
#      quoting and makes the whole script misbehave (it once reported every case as
#      "never started" purely because of this). The device logs are Chinese, so we
#      match them by their ASCII substrings only - see the patterns below.
#
# Results are written next to this script (tools/verify-production.txt, UTF-8).
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot 'verify-production.txt'
Remove-Item $out -ErrorAction SilentlyContinue

function Log($text) { Add-Content -Path $out -Value $text -Encoding utf8 }

& $adb push (Join-Path $PSScriptRoot 'run_photos.sh') /data/local/tmp/run_photos.sh 2>&1 | Out-Null

# ASCII-only fingerprints for the Chinese log lines they live in:
#   accelMode=            -> "device selection: accelMode=N -> K devices"
#   new session           -> Hexagon HTP session created
#   HTP0-REPACK           -> weights really resident on the NPU
#   offloaded             -> "offloaded N/M layers"
#   NPU(Hexagon HTP)      -> "actually active accel level: NPU"
#   GPU(Vulkan)           -> "actually active accel level: GPU"
#   ": CPU"               -> "actually active accel level: CPU"
#   EOG                   -> finished on its own instead of hitting the budget
$RE_DECISION = 'accelMode=|NPU\(Hexagon HTP\)|GPU\(Vulkan\)|: CPU$'
$RE_PLACEMENT = 'new session|HTP0-REPACK|offloaded'
$RE_EOG = 'EOG|tokens,'

function Run-Case($tag, $photoCount, $npuState, $backend = "AUTO") {
    & $adb shell "am force-stop com.example.isip" | Out-Null
    & $adb shell "run-as com.example.isip rm -f files/bench/benchmark.txt files/bench/native.log" | Out-Null

    # run_photos.sh arg order: count imageSide maxTokens skip npuState backend
    # 768 / 512 are the production defaults, spelled out so the mapping between
    # this benchmark and the shipped config stays visible.
    & $adb shell "sh /data/local/tmp/run_photos.sh $photoCount 768 512 500 $npuState $backend" 2>&1 | Out-Null

    $deadline = (Get-Date).AddMinutes(60)
    $ended = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 15
        if (-not (& $adb shell "pidof com.example.isip")) { break }
        if ((& $adb shell "run-as com.example.isip grep -ac 'PHOTO-ANALYSIS END' files/bench/benchmark.txt 2>/dev/null") -eq "1") {
            $ended = $true; break
        }
    }

    Log ""
    Log "########## $tag [$(if ($ended) { 'ok' } else { 'FAILED-or-timeout' })] ##########"

    $native = & $adb shell "run-as com.example.isip cat files/bench/native.log 2>/dev/null"
    $bench  = & $adb shell "run-as com.example.isip cat files/bench/benchmark.txt 2>/dev/null"
    if (-not $native) { Log "  (native.log missing - the app never started)" }

    Log "--- decision chain: policy -> device hit -> actually active ---"
    $native | Select-String -Pattern $RE_DECISION | ForEach-Object { Log "  $($_.Line)" }
    Log "--- weight placement (proof it runs on the NPU, not merely registered) ---"
    $native | Select-String -Pattern $RE_PLACEMENT | ForEach-Object { Log "  $($_.Line)" }
    Log "--- truncation check (EOG means it finished on its own) ---"
    $native | Select-String -Pattern $RE_EOG | ForEach-Object { Log "  $($_.Line)" }
    Log "--- OUTPUT (the point of this script; Chinese is expected and correct) ---"
    $bench | ForEach-Object { Log "  $_" }
}

Run-Case "A: AUTO / NPU preferred, 5 real photos" 5 "reset"
Run-Case "B: user disabled NPU -> expect CPU, 1 photo" 1 "disable"
Run-Case "C: failure threshold reached -> expect CPU, 1 photo" 1 "exhaust"

# Case D answers "is the App itself on 2B+NPU", which is a different question from
# "is the analyzer on 2B+NPU": it runs the exact object graph of GalleryViewModel
# (AnalyzePhotosUseCase + HybridPhotoContentAnalyzer + MobileClipProvider) and then
# checks the photo_ai table, because AnalyzeImageSkill may skip Qwen entirely when
# MobileCLIP is available, and saveAnalysisResult silently drops the row when the
# photo lookup misses.
#
# NOTE: this case WRITES analysis rows into the app database (force=true).
function Run-AppPathCase($photoCount) {
    & $adb shell "am force-stop com.example.isip" | Out-Null
    & $adb shell "run-as com.example.isip rm -f files/bench/benchmark.txt files/bench/native.log" | Out-Null
    & $adb shell "am start -S -W --activity-clear-task -n com.example.isip/.MultimodalTestActivity --ez benchmark true --ez appPath true --es npuState reset --ei photoSkip 600 --ei photoCount $photoCount" 2>&1 | Out-Null

    $deadline = (Get-Date).AddMinutes(60)
    $ended = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 15
        if (-not (& $adb shell "pidof com.example.isip")) { break }
        if ((& $adb shell "run-as com.example.isip grep -ac 'APP-PATH END' files/bench/benchmark.txt 2>/dev/null") -eq "1") {
            $ended = $true; break
        }
    }
    Log ""
    Log "########## D: real App object graph (GalleryViewModel-equivalent) [$(if ($ended) { 'ok' } else { 'FAILED-or-timeout' })] ##########"
    $native = & $adb shell "run-as com.example.isip cat files/bench/native.log 2>/dev/null"
    $bench  = & $adb shell "run-as com.example.isip cat files/bench/benchmark.txt 2>/dev/null"
    Log "--- which backend actually took effect ---"
    $native | Select-String -Pattern 'accelMode=|NPU\(Hexagon HTP\)|GPU\(Vulkan\)|: CPU$' | ForEach-Object { Log "  $($_.Line)" }
    $native | Select-String -Pattern 'HTP0-REPACK|offloaded' | ForEach-Object { Log "  $($_.Line)" }
    Log "--- MobileCLIP decision + result + write-back (the point of case D) ---"
    $bench | ForEach-Object { Log "  $_" }
}
Run-AppPathCase 2

Log ""
Log "########## DONE ##########"
Get-Content $out -Encoding utf8
