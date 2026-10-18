# Jarvis Revived

A private, voice-first helper for phone tasks that runs **entirely on the
phone**: speech in, a local language model (llama.cpp), an encrypted memory
vault, speech out. No account, no cloud. The app has **no internet
permission**, so Android itself stops it from sending anything anywhere.

The on-device model is a translator, not the boss: it turns what you say into
one task from a fixed list, and plain Android code does the task. Anything
that reaches another person waits for your "yes".

## What it does today (v0.2)

| Area | Works now |
|---|---|
| Talk | Tap **Talk** for one request, or **Hands-free** to keep listening (with a Stop notification). You can also type. Speech is recognized on the phone. |
| Directions | "Directions to Domino's", "take me to the railway station", "how do I get to Charminar on foot", "navigate to office by bike". Opens Google Maps (or any maps app) from your current location. |
| Saved places | "Remember Shiva temple is Ramalingeswara Swamy temple, Kukatpally", then "take me to the Shiva temple". |
| Calls | "Call Ravi", "dial 9848012345". Jarvis asks "Call Ravi? Say yes or no" (or tap **Yes, do it** / **Cancel**). |
| Phone basics | "Open WhatsApp", "set an alarm for 6:30 am", "set a timer for 10 minutes", time, date, battery. |
| Memory | "My name is …", "remember …", "who is my wife?", "actually it's …", "never mind", "forget my …", "what do you know about me". Passwords and PINs are encrypted twice and never spoken. The **Memory** screen shows everything. |
| Chat | Anything else goes to the on-device model, with relevant memories as context. Replies are spoken sentence by sentence. |
| Privacy | The **Privacy** panel shows the live checks: no internet permission, memory encrypted, where speech is recognized, offline voice, backups off. |
| Coming next | Understood today, with an honest "comes in the next update": camera with timer and burst, video, voice recorder and recordings search, opening files and sheets, storage analyzer, messages, mail, photos by person, Rapido, trains. See the [roadmap](docs/ROADMAP.md). |

## Privacy in one paragraph

No internet permission (stripped in the manifest, checked by CI on every
build). Everything Jarvis stores is in one SQLCipher-encrypted database whose
key is wrapped by the Android Keystore, so a copy of the app's files is
useless without this phone. Google backup and phone-to-phone transfer are off.
Logs never contain what you said. Details and limits: [SECURITY.md](docs/SECURITY.md).

## Install

Each push to `main` publishes the APK as the **Latest debug build** release:
<https://github.com/Starky4285/Jarvis-revived/releases/tag/debug-latest>.
Open it on the phone and install `jarvis-revived-debug.apk`.

All debug builds are signed with the same key (`app/debug.keystore`), so a new
build installs over the old one and Jarvis keeps its memory. Coming from v0.1?
Your memory is moved into the encrypted vault on first start, and the old
unencrypted file is deleted.

## Add a model

Jarvis needs one GGUF chat model. Recommended: **Qwen2.5-1.5B-Instruct,
Q4_K_M** (about 1 GB). Jarvis uses each model's own chat format and sizes the
context window from the model: Qwen 2.5 gets 4,096 tokens and two warm caches
(chat and tasks). Llama 3.2 Instruct models work too. Phi-3-mini still works,
but it only fits a 1,024-token window and is slower.

1. Copy the `.gguf` file to the phone (USB, Files app, Drive…).
2. Open Jarvis → **Import model** → pick the file. It is copied into Jarvis'
   private storage (no storage permission needed) and loaded.

The status line shows the model, its context plan and the timing of each answer.

## Offline speech

Jarvis listens with the phone's on-device speech recognition (Android 12+).
If it says offline speech isn't available:

* On Android 13+, Jarvis asks the phone to download the offline language pack;
  try again after a minute.
* Otherwise install the offline English pack in the phone's speech-recognition
  settings.
* Or type, or switch on **Allow online speech recognition** in Privacy (your
  voice may then go to Google; everything else stays on the phone).

## Build

Requirements: Android Studio (recent), JDK 17, Android SDK 36, NDK and CMake
3.22.1 (Android Studio installs these on first sync).

```bash
./gradlew :core:test          # commands, memory and routing tests; runs on any computer, no phone needed
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
```

Every push runs both on GitHub Actions (the **Build** workflow), plus a check
that fails the build if the APK ever declares the internet permission.

## Project layout

```
core/   Plain Java. Every decision lives here and is unit-tested on the JVM.
  command/ Catalog of tasks, instant rules, grammar-forced model translator, validation
  turn/    TurnRouter (yes/no → stop → rules → memory → translator → chat), device-action contract
  memory/  MemoryEngine (the single memory owner), parser, saved places, store contract
  llm/     Model-neutral chat prompts, context planner, answer cleaning, ChatModel contract
app/    Thin Android layer: speech, encrypted vault, llama.cpp bridge, device actions, UI
  src/main/cpp/  jarvis_llm.cpp (JNI) + a trimmed, CPU-only copy of llama.cpp
docs/   ROADMAP.md, SECURITY.md, ARCHITECTURE.md, MEMORY.md
```

## Licences

llama.cpp / ggml are MIT-licensed; their licence is kept in
`app/src/main/cpp/llama/LICENSE`. SQLCipher for Android is BSD-style licensed
by Zetetic.
