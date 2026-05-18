# GoldenAid ⚕️

**Field Medical Triage Assistant**

GoldenAid is an on-device, multimodal AI assistant designed for emergency road accident triage. It uses vision, voice, and a local Large Language Model to guide untrained bystanders through high-stakes medical emergencies, providing immediate, actionable advice before paramedics arrive.

## Features

- **100% On-Device AI:** Runs entirely locally after an initial model download. No internet connection required during emergencies, ensuring absolute privacy and zero latency.
- **Multimodal Analysis:**
  - **Vision:** Uses YOLOv11 for person detection and MediaPipe for posture/pose analysis (e.g., detecting if a person is lying down or in an unnatural position).
  - **Voice:** Hands-free operation with speech-to-text input and text-to-speech spoken instructions.
- **Gemma 4 E2B:** Powered by Google's Gemma 4 2B Instruct model via LiteRT-LM, acting as the core reasoning engine to assess triage levels (RED, YELLOW, GREEN, DECEASED) and provide step-by-step first aid instructions.
- **Smart Runtime Download:** The app is lightweight (~20MB APK). The heavy 2.6GB Gemma model is downloaded automatically from HuggingFace on first launch, complete with background resume support.
- **State Stability:** Built-in safeguards to prevent triage oscillation (e.g., once marked RED, it stays RED) and automatic conversation resets to prevent AI hallucination over long periods.

## Tech Stack

- **Platform:** Native Android (Kotlin & Jetpack Compose)
- **LLM Runtime:** Google LiteRT-LM (`gemma-4-E2B-it.litertlm`)
- **Computer Vision:** TensorFlow Lite (YOLO11n) & MediaPipe Tasks Vision (Pose Landmarker)
- **UI:** Custom Dark/Glassmorphism Compose UI

## Installation

1. Download and install the latest APK from the Releases section.
2. Grant Camera and Microphone permissions on first launch.
3. The app will automatically download the Gemma 4 model (~2.6GB) from HuggingFace. **Keep the app open until the download completes.**
4. Once downloaded, the app is ready for offline use.

> **Note:** The model download requires a HuggingFace account with access granted to the `gemma-4-E2B-it` repository.

## Usage

1. Point the camera at the patient.
2. The AI will automatically assess their posture and orientation.
3. Speak to the AI (e.g., "They are bleeding heavily from the leg" or "They are not breathing").
4. Follow the spoken, step-by-step instructions provided by the AI.
5. Tap the `Call 108` button to immediately dial emergency services.
6. Tap `Next Patient` to reset the session context for a new emergency.
