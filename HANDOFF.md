# HANDOFF - SKY-SPY-Aware App Icon Generation via ComfyUI

Save this file was written by the previous opencode session. Continue the task below.
Updated: Sun Aug 09 2026 (during the session).

## CURRENT TASK
Generate a high-quality app icon for the Android app **SKY-SPY-Aware**
(repo: `F:\OUI-SPY\SKY-SPY-Aware-android`, package `com.suteny0r.skyspyaware`),
a drone-detection/monitoring app. The current icon is a crude vector drawable.

User instruction: DO NOT base the work on the user's history in `F:\comfyui`
(those photo/face workflows are unrelated). Use the best tools available in
ComfyUI for app-icon generation, installing components if needed.

## COMFYUI STATUS (server should still be running)
- ComfyUI portable install: `F:\ComfyUI_windows_portable\ComfyUI` (v0.3.71, frontend 1.28.9)
- Running: `python_embeded\python.exe main.py --listen 127.0.0.1 --port 8188`
  (launched hidden via Start-Process; logs at `F:\TEMP\opencode\comfy.log` and `comfy_err.log`)
- API: `http://127.0.0.1:8188`  (verify with `/system_stats`; GPU = RTX 3090 24GB, cuda:0)
- If down, relaunch with:
  `Start-Process -FilePath "F:\ComfyUI_windows_portable\python_embeded\python.exe" -ArgumentList "main.py","--listen","127.0.0.1","--port","8188" -WorkingDirectory "F:\ComfyUI_windows_portable\ComfyUI" -WindowStyle Hidden -RedirectStandardOutput "F:\TEMP\opencode\comfy.log" -RedirectStandardError "F:\TEMP\opencode\comfy_err.log"`
  Then poll `/system_stats` (takes ~20-40s).

## MODELS (Z-Image Turbo setup, in `F:\comfyui\models`)
- `diffusion_models\z_image_turbo_bf16.safetensors`  (UNETLoader, weight_dtype=default)
- `text_encoders\qwen_3_4b.safetensors`             (CLIPLoader)
- `vae\ae.safetensors`                              (VAELoader)
- Model config wired via `F:\ComfyUI_windows_portable\ComfyUI\extra_model_paths.yaml` (created):
  `base_path: F:\comfyui`, maps diffusion_models/text_encoders/vae.

## THE BLOCKER (fix before regenerating)
CLIPLoader `type="lumina2"` FAILS with the Qwen3 4B encoder: size mismatch
2560 (checkpoint) vs 4096 (expected). The prompt runs ~0.8s then fails with
no output; repeated failures crashed the server once.

Available CLIPLoader types (queried from /object_info): stable_diffusion,
stable_cascade, sd3, stable_audio, mochi, ltxv, pixart, cosmos, lumina2, wan,
hidream, chroma, ace, omnigen2, **qwen_image**, hunyuan_image.

=> Use `"type": "qwen_image"` for Z-Image's Qwen3 text encoder (likely fix;
`lumina2` was the wrong arch).

## WORKFLOW (API format, verified node inputs)
Node chain: CLIPLoader(clip_name=qwen_3_4b.safetensors, type=?) -> CLIP
-> CLIPTextEncode(text=POS/NEG); UNETLoader(z_image_turbo_bf16.safetensors,
weight_dtype=default) -> ModelSamplingAuraFlow(model, shift=6);
VAELoader(ae.safetensors); EmptySD3LatentImage(1024,1024,1);
KSampler(model, seed, steps=8, cfg=1.0, sampler_name=euler, scheduler=beta,
positive, negative, latent_image, denoise=1.0); VAEDecode; SaveImage.

Full script with prompt + 3 seeds: `F:\TEMP\opencode\gen_icons.py`
(easy to edit; change the `"type": "lumina2"` to `"qwen_image"` and rerun:
`python F:\TEMP\opencode\gen_icons.py`).

Prompt used (POS): "Modern flat vector mobile app icon on dark navy blue
background, a stylized white quadcopter drone viewed from above with four rotor
arms and glowing cyan rotor rings, a bright green circular location target
beneath it, subtle cyan radar sweep arcs radiating outward, bold simple
geometric shapes, crisp clean edges, high contrast, large subject centered with
generous margin, premium professional app icon, no text, no letters"
NEG: "blurry, low quality, text, letters, words, watermark, signature, photo,
photograph, realistic, 3d render, cluttered, busy, border, frame"

Generated images save to `F:\TEMP\opencode\gen\skyspy_<seed>.png` (1024x1024).

## NEXT STEPS
1. Fix CLIPLoader type to `qwen_image`, rerun gen script (3 seeds).
2. Inspect candidates visually (the user enabled an image-handling plugin, so
   Read/vision should now work on the PNGs; if not, crop+upscale the center
   and compare pixel stats). Pick the best; iterate prompt if needed.
3. Build Android icon assets from the chosen 1024x1024:
   - Adaptive icon: `res/mipmap-anydpi-v26/ic_launcher.xml` (+ round), a solid
     background color, foreground PNG scaled so content sits in the center
     ~66% safe zone (mask crops to a circle).
   - Foreground PNGs in `mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi`
     (108dp base -> 108/162/216/324/432px at dpi, foreground layer).
   - Legacy launcher PNGs (ic_launcher.png) same densities (48/72/96/144/192px)
     for API 24-25 (minSdk=24).
   - Update `AndroidManifest.xml`: `android:icon="@mipmap/ic_launcher"`.
   - Remove/replace the old `res/drawable/ic_launcher.xml` reference.
   - Use PIL to render (python on this machine: `C:\Users\User\.pyenv\pyenv-win\versions\3.11.9\python.exe`).
4. Build: `gradlew assembleDebug` (JAVA_HOME=`C:\Program Files\Android\Android Studio\jbr`,
   ANDROID_HOME=`$env:LOCALAPPDATA\Android\Sdk`), install via adb
   (device serial R5CN70YWT5Z; it intermittently drops - `adb kill-server`/`start-server`
   then poll `adb devices`). Verify on the launcher.
5. Commit + push; build release (`gradlew assembleRelease`); tag + publish:
   `gh release create v1.1.5 app\build\outputs\apk\release\app-release.apk ...`

## ENVIRONMENT GOTCHAS
- Screenshots: use `adb shell screencap -p /sdcard/x.png` then `adb pull`
  (PowerShell `exec-out > file` corrupts binary PNGs).
- pyenv python 3.11.9 briefly threw a weird `json -> re` import error when run
  from F:\TEMP\opencode; retry or use PowerShell `Invoke-WebRequest` for API calls.
- App icon / release workflow established in prior commits (v1.0.0 ... v1.1.4).
