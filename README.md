# Jarvis Revived

A private, voice-first assistant for Android that runs **entirely on the phone**:
speech in, a local language model (llama.cpp), memory you control, speech out.
No account, no cloud, no data leaving the device.

This is a clean rebuild of the original Jarvis project. It keeps what worked
(the speech loop ideas, the llama.cpp bridge, the call/command rules, the
memory "fault lines") and replaces the 67,000-line MemorySpine with a small,
fully tested memory core.

## What it does today (v0.1)

| Area | Works now |
|---|---|
| Talk | Tap **Talk** for one question, or **Hands-free** to keep listening (with a Stop notification). You can also type. |
| Memory | "My name is …", "remember my wife's name is …", "who is my wife?", "actually it's …", "never mind", "forget my …", "what do you know about me", "where did I park?" (free-form notes), "what was my old address?" (history). Passwords/PINs go to an encrypted protected lane and are never spoken. |
| Device | "Call Ravi", "open WhatsApp", "set an alarm for 6:30 am", "set a timer for 10 minutes", "what time is it", "what's the date", "battery level". |
| Brain | Any other question goes to the on-device model with your relevant memories and the last few turns as context. Replies stream sentence by sentence. |
| Screens | Conversation, model import, and a **Memory** screen showing everything stored (tap to reveal protected items, long-press to forget, or forget everything). |

The model **cannot act**: only the local command parser can call, open or set
anything. Model output is only ever spoken.

## Build

Requirements: Android Studio (recent), JDK 17, Android SDK 35, NDK + CMake 3.22.1
(Android Studio installs these on first sync).

```bash
./gradlew :core:test          # memory + routing tests, runs on any computer, no phone needed
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
```

Every push runs both on GitHub Actions (see the **Build** workflow); the debug
APK is attached to each run as an artifact.

## Add a model

Jarvis needs one GGUF chat model on the phone. Recommended:
**Phi-3-mini-4k-instruct, Q4_K_M** (~2.3 GB). The prompts use the Phi-3 chat
format; other model families will work less well until a per-model prompt
format is added.

1. Copy the `.gguf` file to the phone (USB, Files app, Drive…).
2. Open Jarvis → **Import model** → pick the file. It is copied into Jarvis'
   private storage (no storage permission needed) and loaded.

The status line shows `Brain: ready` when it's loaded.

## Project layout

```
core/   Plain Java. All decisions live here and are unit-tested on the JVM.
  memory/  MemoryEngine (the single memory owner), parser, store contract, prompt context
  turn/    TurnRouter (stop → device → memory → model), local intents, compound commands
  llm/     Prompt builder, answer cleaning, conversation window, ChatModel contract
app/    Thin Android layer: speech, SQLite store, llama.cpp bridge, device actions, UI
  src/main/cpp/  jarvis_llm.cpp (JNI) + a trimmed, CPU-only copy of llama.cpp (8 MB)
docs/   ARCHITECTURE.md, MEMORY.md, ROADMAP.md
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/MEMORY.md](docs/MEMORY.md).

## Licences

llama.cpp / ggml are MIT-licensed; their licence is kept in
`app/src/main/cpp/llama/LICENSE`.
