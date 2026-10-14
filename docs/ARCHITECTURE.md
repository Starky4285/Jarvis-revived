# Architecture

## One turn, start to finish

```
mic ──► SpeechInput ──► AssistantController.process(text)        [main thread]
                              │
                              ▼  single "jarvis-brain" thread
                        TurnRouter.handle(text)
                          1. stop / cancel?        → STOP   (silence TTS, abort model)
                          2. strict device command → ACTION (AndroidActions) / LOCAL (time, date)
                          3. memory request?       → MEMORY (MemoryEngine)
                          4. otherwise             → MODEL  (LlamaModel, streamed)
                              │
                              ▼
                        SpeechOutput (TTS queue) ──► idle ──► listen again if hands-free
```

* **Main thread** only touches speech and UI. Memory, the model and actions
  run on one background thread, so turns are strictly ordered and the UI never
  freezes. (The old app did MemorySpine database work on the main thread.)
* **Hands-free** mode starts a microphone foreground service so listening
  survives the screen turning off. It is not sticky: after a crash it never
  restarts listening by itself.

## Modules

| Module | Android? | Contents |
|---|---|---|
| `:core` | No | `memory/` MemoryEngine, MemoryParser, KeyNormalizer, SensitiveKeys, TemporalAnchor, PromptMemory, MemoryExtractor · `turn/` TurnRouter, LocalIntent, CommandShapeRules, DeviceActions · `llm/` PromptBuilder, AnswerGuard, ConversationWindow, ChatModel · `text/` TextTools |
| `:app` | Yes | `assistant/` AssistantController, AssistantService · `speech/` SpeechInput, SpeechOutput · `llm/` LlamaBridge, LlamaModel, ModelFiles · `memory/` SqliteMemoryStore, KeystoreCipher · `actions/` AndroidActions, ActionLauncher · `ui/` MainActivity, MemoryActivity · `cpp/` jarvis_llm.cpp + llama.cpp |

Rule: if a decision can be made without Android, it lives in `:core` and has a test.

## Rules that don't change (from the original roadmap)

1. **Memory has one owner.** Only `MemoryEngine` decides what is saved,
   replaced or forgotten. The store just stores.
2. **The model can't act.** Device actions come only from `LocalIntent`'s
   anchored patterns. Model text is cleaned and spoken, never executed.
3. **Offline first.** Nothing in this build uses the network.
4. **One model loaded at a time**, CPU only, 1024-token context (fits a
   4–6 GB phone next to a ~2.3 GB Phi-3 Q4 model).
5. **Memory is data, never instructions.** It enters the prompt sanitized and
   fenced in `<memory>…</memory>`.
6. **Test on the real phone.** Emulator mic and performance numbers don't count.

## What was carried over from the old repo

| From | To | Notes |
|---|---|---|
| `assistant.cpp` (+ Patch 19 fixes) | `jarvis_llm.cpp` | UTF-8-safe strings, real sentence ends, repetition penalty, KV recovery. Mode and sentence budget are now explicit arguments. |
| `Phi3Manager` answer guard | `core/llm/AnswerGuard` | Empty output stays empty (no fake "I'm here to help."). |
| `CommandShapeRules` (Patch 19) | `core/turn/CommandShapeRules` | Same rules and tests. |
| GoogleSpeechManager lessons | `SpeechInput` | Stale-session guard, recreate after BUSY/CLIENT, silence ≠ error. |
| `MEMORY_FAULT_LINES.md` | `MemoryFaultLineTest` | Fault lines restated as executable tests. |
| Backup / protected-lane ideas | `KeystoreCipher` | Protected values AES-GCM with an Android Keystore key; fails closed. |
| llama.cpp (169 MB vendored) | `app/src/main/cpp/llama` (8 MB) | Only what a CPU-only build needs; verified by a full host build. |

## What was left behind, and why

* **MemorySpine (≈63k lines)** — rule-based language understanding (≈700
  hard-coded phrase checks, ≈250 regexes) plus parallel stores (facts,
  states, beliefs, conditional, transient, cardinality, vector), each with an
  in-memory twin. Replaced by `core/memory` (≈1.5k lines).
* **Debug-only activities, MemoryV2 shadow paths, `.bak` files, backups,
  `llama_old/`, training JSONL** — not part of the running app.
* **OpenAI client** — the app is offline; the masked online layer is a later
  phase (see ROADMAP).
* **AndroidX** — the UI uses framework widgets, so there are no AndroidX
  dependencies to keep updated.
