# Mish AI

Android AI voice assistant (Kotlin + Jetpack Compose) — built & hosted on GitHub.

## Developed by Cyber Tech Agency
- Website: https://www.cybertechagency.com/mish
- Facebook: https://facebook.com/cybertechagencyy
- YouTube: https://youtube.com/@cybertechagency
- WhatsApp / Call: +92 322 4278925
- CEO & Developer: Mehar Ahmad Raza

## Features
- **Splash screen**: 8-second auto-playing `loading video.mp4`, then main UI.
- **App icon**: `mish ai logo.png`.
- **First-time onboarding**: name → role (boss/sir/madam/maam/bhai/jani/jan/yar) → gender → emergency number → permissions (mic, camera, overlay, background).
- **Wake word "Mish"**: say "Mish" from any app and the female AI assistant activates, with a floating animation overlay.
- **Mood-aware replies**: `Voice → Noise reduction → STT → Mood analysis → LLM → TTS`, adjusting wording, tone and speech speed.
- Moods: Happy 😄 Sad 😔 Angry 😠 Stressed 😣 Excited 🤩 Normal 🙂 Confused 🤔 Tired 😴
- **Emergency**: user says "help" or "bachao" → SMS + live Google Maps location sent to the emergency number, then a call is opened.
- **Camera**: open camera, take a photo, Mish describes what she sees (needs a vision endpoint).
- **Branding header** with all Cyber Tech Agency links.

## Build
```bash
# Requires JDK 17 + Android SDK
.\gradlew.bat assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Backend
The app talks to an OpenAI-compatible / Hugging Face speech-to-speech style endpoint.
Set the endpoint URL from code (`PreferencesManager.setEndpoint`) — suggested free/open stack:
- TTS: Qwen3-TTS (female, Urdu/Roman Urdu) or Aegis female Urdu (Piper/ONNX)
- STT: Parakeet TDT / Whisper
- LLM: Gemma 4 / Qwen
- Framework: Hugging Face speech-to-speech

Repository: structured for GitHub Actions build (Kotlin + Compose).