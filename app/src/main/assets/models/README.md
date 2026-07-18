# On-device model files

The app uses the following Gemma 4 vision-language model files:

- `gemma-4-E2B_q4_0-it.gguf` — main Q4_0 model
- `gemma-4-E2B-it-mmproj.gguf` — multimodal projection model

They are intentionally not included in Git or the APK (together they require
about 4.3 GB). The Android app resolves both files from its private directory:

```
/data/user/0/com.example.isip/files/models/
```

After building and installing the debug app, deploy the files that were
downloaded to the development PC with:

```powershell
python tools/prepare_gemma4_model.py --serial emulator-5554
```

Omit `--serial` when exactly one device is connected. To use another download
location, add `--model-dir <folder>`. The app will return a basic rule-based
result if these model files are missing; it does not use a network-model
fallback.

## MobileCLIP

Semantic search and duplicate detection use a separate MobileCLIP model in
`files/clip/`. Install it after the app is on the device:

```powershell
python tools/prepare_mobileclip.py --serial emulator-5554
```

MobileCLIP needs all four files below before it is enabled:

- `mobileclip_image.tflite`
- `mobileclip_text.tflite`
- `vocab.json`
- `merges.txt`
