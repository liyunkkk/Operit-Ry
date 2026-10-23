# Voice modernization

## User behavior

- Speech settings retain independent recognition and synthesis profiles. Existing profiles and the
  legacy sherpa-ncnn wake engine are preserved; upgrading never downloads or replaces a user's model.
- The local model list shows model size and source. Downloading uses the existing confirmation,
  progress, cancellation and SHA-256 verification flow on every network type. Only a completed
  download changes the chosen profile; only its model fields are changed.
- Downloaded model cards show reclaimable file size and a delete action with confirmation.
  The selected model must be switched first. Deletion keeps profiles, includes partial files,
  and is serialized with native model loading; active downloads cannot be deleted.
- The recognition catalog also lists legacy Sherpa NCNN files left by older releases. Their
  deletion requires confirmation and an unused local engine; wake-word leases also block deletion.
  Settings remain intact, and subsequent legacy recognition/wake-up requires a confirmed download.
- Hold-to-speak transcribes into an editable draft. Continuous voice chat sends completed recognition.
  Pause microphone, stop playback and end voice chat are separate controls. Stopping playback does
  not cancel the main Agent or modify chat history.
- Test pages use the selected speech profile. Changing a test voice is temporary.
- The main chat composer has a separate dictation microphone. Its result replaces the current
  selection in the editable draft; it never sends a message. The headset opens voice conversation.
  Cancelling or switching chats disposes capture; empty results and failures allow recording again.
- Spoken interruption is experimental and disabled by default. It is enabled only for players
  reporting actual wired/USB/LE Audio headphone output. Generic Bluetooth A2DP/SCO cannot reliably
  distinguish headphones from speakers and is excluded. System TTS and providers without route
  reporting retain turn-taking behavior.
- An interruption stops playback, not the Agent. Its transcript becomes an editable draft. While
  the Agent is busy, send is disabled and the draft is retained. Editing or recognition does not
  trigger the voice inactivity timeout.

## Local model selection

The catalog is in `LocalVoiceModels.kt`. Revisions, file lengths and SHA-256 digests are immutable.
These are alternatives, not claims that a single model wins on every phone.

| Model | Role | Download, approximately | Selection rationale |
|---|---|---:|---|
| SenseVoice Small INT8 | Recognition | 228.5 MiB | Multilingual option: Chinese, Cantonese, English, Japanese, Korean |
| Paraformer Small INT8 | Recognition | 78.1 MiB | Smaller Chinese/English alternative |
| AISHELL3 VITS INT8 | Synthesis | 40.2 MiB | Low CPU-cost Chinese synthesis; 174 speakers, 8 kHz bandwidth |
| Melo TTS INT8 | Synthesis | 57.8 MiB | Chinese/English alternative; heavier CPU cost |

Public comparison sources:

- [SenseVoice official evaluation and model description](https://github.com/QwenAudio/SenseVoice)
- [Sherpa Paraformer models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html)
- [Sherpa TTS runtime benchmark](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/rtf.html)

The published Raspberry Pi 4 four-thread TTS benchmark reports AISHELL3 RTF 0.156 and Melo RTF 2.518.
It is CPU-oriented selection evidence, **not** a measurement of these INT8 artifacts on Android.
Device-specific latency, accuracy, battery use and perceived voice quality still require listening
and recording tests. Larger Qwen3-ASR/Qwen3-TTS/CosyVoice server models are not advertised as phone
downloads or assumed to use an interchangeable HTTP protocol. Existing cloud profiles remain usable.

## Runtime and cancellation

- sherpa-onnx 1.13.8 static-ORT AAR is version- and byte-pinned. `preBuild` verifies its digest.
- Recognition capture resources are serialized separately from network inference. Session epochs
  cancel outstanding HTTP calls and reject late success/error publication.
- Native synthesis callbacks catch exceptions and return cancellation to JNI; exceptions propagate
  only after returning to Kotlin.
- Realtime synthesis streams PCM directly into an utterance-owned AudioTrack. Short writes, pause,
  initial playback thresholds, drain and cancellation are handled without buffering the whole WAV.
- The fullscreen speech queue holds eight sentences and applies backpressure. HTTP's existing
  preparation/playback queue remains separate, with bounded pending requests and cancellable
  preparation. Providers are not blindly called concurrently to invent prefetch support.
- System TTS completion follows utterance completion, including pause/resume continuity. Audio
  focus is shared across overlapping requests and released after the final request.
- Local message read-aloud uses one AudioTrack across bounded text segments, with at most eight
  seconds of queued PCM, plus the current write, pending block and current bounded native result.
  Native synthesis prepares subsequent speech while
  playback runs. Pause retains the owning job; stop cancels the producer and consumer. Model
  inference latency, especially initial audio, is still device- and model-dependent.
- `VoiceTiming` reports fixed phase names, a local session counter and elapsed milliseconds. It
  does not add transcript text, audio or credentials to diagnostics.

## Sources and licenses

Model weights are downloaded from the linked immutable conversion repositories, not bundled in
the APK. Licenses for model weights are distinct from the engine's code license.

| Component | License evidence |
|---|---|
| sherpa-onnx v1.13.8 | Apache-2.0 source license; AAR itself does not include LICENSE/NOTICE |
| SenseVoice weights | FunASR Model Open Source License Agreement; not the code's MIT license |
| Melo conversion | MyShell.ai 2024 MIT, included in the pinned conversion repository |
| AISHELL3 | Original model card declares Apache-2.0; conversion repository has no separate license |
| Paraformer Small | Original damo model declares Apache-2.0; intermediate conversion repositories do not attach a separate license |

- [SenseVoice license](https://github.com/QwenAudio/SenseVoice#license)
- [Melo pinned license](https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/blob/a0d5c6a264c0ef92d70d8661d8cc502d79627cd6/LICENSE)
- [AISHELL3 original model](https://huggingface.co/jackyqs/vits-aishell3-175-chinese)
- [Paraformer original metadata](https://modelscope.cn/api/v1/models/damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-tensorflow1)

## Validation boundary

Static audits and JVM tests do not validate microphone hardware, Bluetooth routing, audio quality,
AEC quality, native inference memory or background microphone policy. Device acceptance should cover
rapid start/cancel, configuration changes, pause/resume, focus loss, headset unplugging, silent input,
slow transcription and long answers. Preserve app data when installing.
