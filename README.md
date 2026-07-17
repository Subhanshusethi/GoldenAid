# GoldenAid: On-Device Multimodal Bystander Triage System

Demo Video:[YouTube](https://youtu.be/QtHA8KkiIGw?si=T6isd4L7TUwlTLuW)
Kaggle WriteUp:[KaggleWriteUp](https://share.google/hRGFJ1dGOPQSWXBce)


GoldenAid is a native Android application designed to provide real-time, conversational medical triage assistance during high-stress emergencies, specifically road traffic accidents. By combining edge-deployed Large Language Models (Gemma 4), computer vision (YOLOv11), and posture analysis (MediaPipe), GoldenAid acts as a digital first-responder to guide untrained bystanders through critical first-aid protocols before paramedics arrive.

Crucially, the entire inference pipeline runs **100% on-device** with zero cloud dependency, ensuring absolute privacy and zero latency in areas with poor network connectivity.

---

## The Problem

India records over 500,000 road accidents annually, resulting in 150,000 deaths. More than 50% of these fatalities occur because victims do not receive basic care during the "Golden Hour." While bystanders are present at over 80% of accidents, less than 2% of the population has formal first aid training. 

Existing solutions like static text-based apps, web-dependent cloud LLMs, or CPR-only guides fail in dynamic, high-stress environments where users cannot read menus or wait for cloud API responses. GoldenAid solves this by using hands-free voice interactions and live camera feeds to autonomously assess the situation and deliver immediate, actionable guidance.

---

## System Architecture

GoldenAid is built entirely natively for Android (Kotlin & Jetpack Compose). The request lifecycle takes approximately 3-4 seconds end-to-end and relies on a sophisticated orchestration of local models.

### Core Technology Stack

- **LLM Runtime:** Google LiteRT-LM (formerly TensorFlow Lite for Large Language Models)
- **Base Model:** Gemma 4 E2B Instruct (4-bit quantized, `gemma-4-E2B-it.litertlm`)
- **Computer Vision:** YOLO11n (for precise patient cropping and bounding boxes)
- **Pose Estimation:** MediaPipe Tasks Vision (BlazePose for posture and orientation analysis)
- **Voice IO:** Native Android `SpeechRecognizer` and `TextToSpeech` APIs
- **UI Framework:** Jetpack Compose

### The Multimodal Inference Pipeline

1. **Trigger:** The bystander speaks into the device while pointing the camera at the patient.
2. **Vision Pass:** When speech input completes, CameraX captures a 640x480 JPEG frame. YOLO11n detects the patient and crops the image to remove background noise. MediaPipe analyzes the frame to extract physical orientation data (e.g., `is_lying_down=True`).
3. **Context Assembly:** The system assembles a comprehensive context payload containing:
   - The cropped image tensor.
   - The transcribed voice input.
   - The extracted pose string.
   - A highly restrictive system prompt enforcing WHO bystander protocols.
4. **LLM Execution:** The payload is passed to the Gemma 4 E2B model running via LiteRT-LM on the device's GPU.
5. **Output Parsing & TTS:** Gemma generates a structured response (guidance, urgency level, injury assessment). The guidance is immediately spoken aloud via Android's TextToSpeech engine, while the UI updates the patient's triage status (Red/Yellow/Green).

---

## Gemma Implementation Details

The core reasoning engine of GoldenAid is Google's **Gemma 4 E2B Instruct** model, deployed using the LiteRT-LM Android SDK.

### 1. LiteRT-LM Integration & Mobile GPU Acceleration
Achieving stable, low-latency inference on a mobile device required rigorous optimization. 
- **GPU Delegation:** The `LlmInference` engine is configured to explicitly target the mobile GPU, bypassing the CPU to achieve the ~3.2s latency target.
- **Single-Session Constraint:** LiteRT-LM currently supports only one active conversational session at a time in memory. Early iterations attempted to run vision and text passes separately, resulting in session crashes. The architecture was refactored so that a single `Conversation` object handles both the vision injection and language reasoning sequentially within the same context window.

### 2. Strict Protocol Enforcement via Prompt Engineering
Base LLMs tend to generate overly verbose or clinical responses. GoldenAid utilizes an aggressive system prompt to enforce strict constraints:
- Responses must be under two sentences.
- Responses must begin with an actionable verb.
- The model must strictly classify urgency into predefined categories (RED, YELLOW, GREEN, DECEASED).
- The model is barred from suggesting the user perform invasive medical procedures, strictly adhering to bystander-safe actions (e.g., "Apply pressure," "Do not move the neck").

### 3. Dynamic Runtime Model Download Manager
Bundling a 2.6GB `.litertlm` model within the Android APK is impractical for distribution. We implemented a robust background download pipeline:
- The base APK is kept highly lightweight (~135MB, containing only the vision models and native `.so` libraries).
- On first launch, a custom `ModelDownloadManager` utilizes an HTTP Range-based downloader to fetch the Gemma 4 model directly from the Hugging Face hub.
- The manager supports connection resumption, handles Hugging Face CDN redirects, and safely atomically renames the temporary file upon completion to prevent the engine from loading a corrupted model.

---

## Fine-Tuning Methodology

While the production Android client currently utilizes the base Gemma 4 E2B weights with strict prompting (due to ongoing tooling limitations in converting Gemma 4 fine-tuned weights to the `.litertlm` format), a specialized bystander-first-aid version of the model has been trained.

- **Dataset:** `i-am-mushfiq/FirstAidQA` (5,500 QA pairs covering trauma, bleeding, and burns).
- **Framework:** Unsloth + QLoRA.
- **Configuration:** 4-bit quantization, `r=16`, `lora_alpha=16`, targeting `q_proj`, `k_proj`, `v_proj`, `o_proj`.
- **Training Environment:** Kaggle GPU T4 x 2, 3 epochs, LR 2e-4.

This fine-tuning shifts the model's tone from informational ("It is advisable to apply pressure...") to directive ("Press your hand hard on the wound right now").

---

## Building and Installation

### Prerequisites
- Android Studio Jellyfish or newer.
- Physical Android device with a minimum of 8GB RAM (12GB recommended for optimal GPU inference).
- *Note: Android Emulators are not supported due to the lack of hardware GPU delegation required by LiteRT-LM.*

### Build Instructions
1. Clone the repository:
   ```bash
   git clone https://github.com/Subhanshusethi/GoldenAid.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle dependencies.
4. Select `release` or `debug` build variant and deploy directly to your physical device.

### First Run
Upon opening the app and granting Camera/Microphone permissions, the application will automatically initialize the download of the Gemma 4 model from Hugging Face. Ensure the device screen remains on and connected to WiFi during this initial ~2.6GB download. Subsequent launches will load the model instantly from local storage.

---

## Performance Metrics
- **End-to-End Latency:** 3.2 seconds average (from the end of voice input to the start of audio playback).
- **Model Size:** 2.59 GB (Gemma 4 E2B `.litertlm`).
- **APK Size:** ~135 MB.
- **Network Requirement:** 0 bytes (fully offline after the initial setup).

---

## Future Roadmap
- **Hindi Localization:** Implement native Hindi STT/TTS and fine-tune the LLM for rural Indian deployments.
- **LiteRT Conversion Update:** Migrate from base Gemma 4 to the custom fine-tuned LoRA weights once the Google AI Edge conversion pipeline supports Gemma 4 architectures natively.
- **Automated Emergency Dispatch:** Integrate GPS-tagged JSON incident logs directly with local emergency response APIs (108 services).
