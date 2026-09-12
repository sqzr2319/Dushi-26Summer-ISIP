#!/system/bin/sh
# Launch the real-photo analysis harness with a fixed, correctly-quoted arg set.
#
# See run_mm.sh for why this is a script and not an inline `am start`: quoting
# survives none of the adb -> PowerShell -> sh layers, and a mangled intent fails
# silently in a way that looks like an app crash.
#
# Deploy before use (tools/verify-production.ps1 does this automatically):
#   adb push tools/run_photos.sh /data/local/tmp/run_photos.sh
#
# Usage:
#   sh /data/local/tmp/run_photos.sh <count> <imageSide> <maxTokens> [skip] [npuState] [backend] [prompt] [threads] [mmprojGpu]
#
# Production defaults are 768 / 512 / threads 4 (see
# HybridPhotoContentAnalyzer.PRODUCTION_CONFIG); pass them explicitly in a
# benchmark so the mapping between the benchmark and the shipped config stays
# visible.
#
# imageSide: longest-edge in px fed to the vision tower (384 .. 1024).
#            Image tokens scale ~quadratically with it: 448 -> 144, 640 -> 220,
#            768 -> 336, 1024 -> 576. This is the single biggest lever on
#            latency, because the vision tower runs on the CPU.
# skip:      offset into the non-screenshot photo list, for picking other samples.
# npuState:  reset | disable | exhaust | clear (the production degradation branches).
# backend:   CPU | GPU | NPU | AUTO. NOTE: this now also applies to the photo path,
#            not only to the synthetic benchmark - that mismatch is exactly what hid
#            the Vulkan compute bug for so long.
# prompt:    optional plain-text prompt. Passed as a complete prompt (no JSON
#            schema appended). Omit to use the production prompt.
# threads:   CPU threads for BOTH the LLM and the vision tower (SM8750 has 8 cores;
#            the vision tower used to be hardcoded to 4).
# mmprojGpu: 1 = let the vision tower use the accelerator (experimental; HANGS/aborts
#            - the Hexagon skel lacks the conv2d/im2col ops a ViT needs, and the
#            backend calls ggml_abort() instead of falling back to CPU).
# mmprojThreads: separate CPU thread count for the vision tower only (it has its own
#            ggml thread pool and is the longest stage).

COUNT="${1:-1}"
SIDE="${2:-768}"
MAXTOK="${3:-512}"
SKIP="${4:-500}"
NPU="${5:-reset}"
BACKEND="${6:-AUTO}"
THREADS="${8:-0}"
MMPROJGPU="${9:-}"
MMPROJTHREADS="${10:-}"
KVQUANT="${11:-}"

EXTRA=""
[ "$THREADS" != "0" ] && [ -n "$THREADS" ] && EXTRA="$EXTRA --ei threads $THREADS"
[ -n "$MMPROJGPU" ] && EXTRA="$EXTRA --ei mmprojGpu $MMPROJGPU"
[ -n "$MMPROJTHREADS" ] && EXTRA="$EXTRA --ei mmprojThreads $MMPROJTHREADS"
[ -n "$KVQUANT" ] && EXTRA="$EXTRA --ei kvQuant $KVQUANT"

if [ -n "$7" ]; then
    exec am start -S -W \
        --activity-clear-task \
        -n com.example.isip/.MultimodalTestActivity \
        --ez benchmark true \
        --ez photoAnalysis true \
        --es npuState "$NPU" \
        --es backend "$BACKEND" \
        --ez photoExcludeScreenshots true \
        --es promptOverride "$7" \
        --ei photoSkip "$SKIP" \
        --ei photoCount "$COUNT" \
        --ei imageSide "$SIDE" \
        --ei maxTokens "$MAXTOK" \
        $EXTRA
fi

exec am start -S -W \
    --activity-clear-task \
    -n com.example.isip/.MultimodalTestActivity \
    --ez benchmark true \
    --ez photoAnalysis true \
    --es npuState "$NPU" \
    --es backend "$BACKEND" \
    --ez photoExcludeScreenshots true \
    --ei photoSkip "$SKIP" \
    --ei photoCount "$COUNT" \
    --ei imageSide "$SIDE" \
    --ei maxTokens "$MAXTOK" \
    $EXTRA
