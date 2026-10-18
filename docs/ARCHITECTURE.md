# Architecture

## One turn, start to finish

```
mic ──► SpeechInput (on-device) ──► AssistantController.process(text)      [main thread]
                                        │
                                        ▼  single "jarvis-brain" thread
                                  TurnRouter.handle(text)
                                    0. open "yes or no?" question  → run or cancel that action
                                    1. stop / cancel               → STOP
                                    2. CommandRules (instant)      → ACTION / LOCAL / CONFIRM / PLANNED
                                    3. memory request?             → MEMORY (MemoryEngine)
                                    4. CommandTranslator (model)   → same as 2, if it looks like a task
                                    5. otherwise                   → MODEL (chat, streamed)
                                        │
                                        ▼
                                  SpeechOutput (TTS queue) ──► idle ──► listen again if hands-free
```

* **Main thread** only touches speech and UI. Memory, the model and actions
  run on one background thread, so turns are strictly ordered and the UI never
  freezes. The vault is unlocked as that thread's first job, so app start
  never waits for the Keystore.
* **Hands-free** mode starts a microphone foreground service so listening
  survives the screen turning off. It is not sticky: after a crash it never
  restarts listening by itself.

## The command layer (`core/command`)

The model is a translator, not a decision maker.

* **`CommandCatalog`** lists every task Jarvis can be asked for: name, typed
  arguments (text, number with a range, or a choice), risk (`SAFE` or
  `CONFIRM`) and status (`AVAILABLE` or `PLANNED`, with the honest reply for
  the latter).
* **`CommandRules`** match the common phrasings instantly, anchored to the
  whole sentence, so "my camera is broken" never opens the camera.
* **`CommandTranslator`** handles other phrasings. A cheap gate first decides
  whether the sentence looks like a request at all; questions go straight to
  chat. Then the model answers under a **GBNF grammar** built from the catalog
  (`CommandGrammar`), so it can only produce
  `{"cmd": <a catalog name>, "args": {...}}` or `{"cmd": "none"}`.
* **Validation** (`CommandTranslator.parse`): types, ranges and choices are
  checked, and every content word of a text argument must appear in what the
  user said. The model can't invent a contact, place or message.
* **`TurnRouter`** executes the command through `DeviceActions`. `CONFIRM`
  commands (calls now; messages, deletions and bookings later) store a pending
  action and ask "… Say yes or no"; it expires after 60 seconds and anything
  else cancels it. `PLANNED` commands get their honest reply.
* **Saved places**: `MemoryEngine.placeFor` resolves "the Shiva temple" to a
  saved fact (exact key, then "… address"/"… location", then word match; a tie
  is never guessed), and Maps gets the saved address.

## The model runtime (`jarvis_llm.cpp` + `LlamaModel`)

* **Any GGUF chat model.** Prompts are model-neutral messages (`ChatPrompt`:
  system, user, assistant). The app formats them with the model's own chat
  template through `llama_chat_apply_template`; a model without a template
  falls back to the Phi-3 format. User text is sanitized so it can't fake
  special tokens.
* **Context sized from the model** (`ContextPlanner`): the KV cache costs
  `2 × layers × kv_heads × head_dim × 2 bytes` per token, within a 448 MB
  budget. Qwen2.5-1.5B (grouped-query attention, ~28 KB/token) gets 4,096 tokens
  × 2 slots; Phi-3-mini (~384 KB/token) gets 1,024 × 1. If the planned context
  can't be allocated, it falls back to 1,024 × 1.
* **Two cache slots**: slot 0 keeps the chat system prompt warm, slot 1 keeps
  the translator's prompt (catalog plus examples) warm. Both are decoded once
  after loading, so a task request only pays for the user's few words.
* **Grammar sampling** with a fast path: the top token is checked against the
  grammar first, and the whole vocabulary is filtered only if it fails. A
  grammar that doesn't parse refuses to generate (fails closed).
* One model loaded at a time, CPU only, generation serialized by a native
  mutex, abortable, with a 90-second thinking limit.

## The encrypted vault (`app/memory`)

* `SqliteMemoryStore` extends SQLCipher's `SQLiteOpenHelper`: three tables
  (facts, protected items, conversation log) in one encrypted file,
  `jarvis_vault.db`, with every value bound as a parameter.
* `VaultKey`: 32 random bytes used as a raw SQLCipher key, stored only wrapped
  with AES-256-GCM by an Android Keystore key (StrongBox when available).
* `MemoryVault.open`: migrates v0.1's plain database once, retries a
  Keystore that is still starting, and falls back to a RAM-only store rather
  than ever writing unencrypted. `DeferredMemoryStore` (core) lets the app hand
  `MemoryEngine` a store that is opened on the brain thread.

Details and trade-offs: [SECURITY.md](SECURITY.md).

## Modules

| Module | Android? | Contents |
|---|---|---|
| `:core` | No | `command/` CommandCatalog, CommandSpec, Command, CommandRules, CommandGrammar, CommandTranslator, MiniJson · `turn/` TurnRouter, LocalIntent, CommandShapeRules, DeviceActions, CallTarget, YesNo · `memory/` MemoryEngine, MemoryParser, KeyNormalizer, SensitiveKeys, TemporalAnchor, PromptMemory, MemoryExtractor, DeferredMemoryStore, VolatileMemoryStore · `llm/` ChatPrompt, PromptBuilder, ContextPlanner, AnswerGuard, ConversationWindow, ChatModel · `text/` TextTools |
| `:app` | Yes | `assistant/` AssistantController, AssistantService · `speech/` SpeechInput, SpeechOutput · `llm/` LlamaBridge, LlamaModel, ModelFiles · `memory/` MemoryVault, VaultKey, SqliteMemoryStore, KeystoreCipher · `actions/` AndroidActions, ActionLauncher · `ui/` MainActivity, MemoryActivity · `cpp/` jarvis_llm.cpp + llama.cpp |

Rule: if a decision can be made without Android, it lives in `:core` and has a test.

## Rules that don't change

1. **Memory has one owner.** Only `MemoryEngine` decides what is saved,
   replaced or forgotten. The store just stores.
2. **The model can't act on its own.** It can only name a catalog command;
   the command is validated and grounded in the user's words, and risky ones
   wait for "yes". Chat output is cleaned and spoken, never executed.
3. **No internet permission** in the Jarvis app, enforced by the manifest and
   CI (see SECURITY.md). Network-needing features live in separate apps.
4. **Everything stored is encrypted** in the vault; nothing personal goes to
   logs (only route and command name).
5. **Memory and fetched content are data, never instructions.** They enter
   prompts sanitized and fenced (`<memory>…</memory>`).
6. **Test on the real phone.** Emulator mic and performance numbers don't count.

## Speech privacy

* `SpeechInput.availableMode`: on-device recognition when the phone has it
  (`createOnDeviceSpeechRecognizer`, Android 12+); the phone's ordinary
  recognizer only if the user switched on online speech; otherwise none, and
  the app offers typing.
* A missing offline language pack triggers `triggerModelDownload`
  (Android 13+); an unsupported en-IN falls back to en-US once.
* `SpeechOutput` switches the TTS engine to an installed offline voice for the
  language when there is one, and the Privacy panel reports it.

## Planned: the mail bridge (two apps)

```
 ┌──────────────── Jarvis (no INTERNET) ────────────────┐      ┌──── Mail bridge (INTERNET) ────┐
 │ model · memory · files · photos · screen automation  │      │ IMAP/SMTP over TLS to the mail │
 │ encrypted vault (mail index lives here)              │◄─────│ server only; stores its login  │
 │                                                      │ mail │ (Keystore-encrypted) and its   │
 │ drafts a reply ─────────────────────────────────────►│─────►│ sync position; sends only from │
 │                                                      │ draft│ its own confirm screen         │
 └──────────────────────────────────────────────────────┘      └────────────────────────────────┘
       channel between them: signature-level permission (same signing key only)
```

The app that sees personal files and screens can't reach the internet; the
app that can reach the internet sees only mail and can't send without a tap
on its own screen.

## What was carried over from the old repo

| From | To | Notes |
|---|---|---|
| `assistant.cpp` (+ Patch 19 fixes) | `jarvis_llm.cpp` | UTF-8-safe strings, real sentence ends, repetition penalty, KV recovery. Now with chat templates, grammar sampling and two cache slots. |
| `Phi3Manager` answer guard | `core/llm/AnswerGuard` | Empty output stays empty (no fake "I'm here to help."). |
| `CommandShapeRules` (Patch 19) | `core/turn/CommandShapeRules` | Same rules and tests. |
| GoogleSpeechManager lessons | `SpeechInput` | Stale-session guard, recreate after BUSY/CLIENT, silence ≠ error. |
| `MEMORY_FAULT_LINES.md` | `MemoryFaultLineTest` | Fault lines restated as executable tests. |
| Backup / protected-lane ideas | `KeystoreCipher` | Protected values AES-GCM with an Android Keystore key; fails closed. |
| llama.cpp (169 MB vendored) | `app/src/main/cpp/llama` (8 MB) | Only what a CPU-only build needs; verified by a full host build. |

## What was left behind, and why

* **MemorySpine (≈63k lines)**: rule-based language understanding (≈700
  hard-coded phrase checks, ≈250 regexes) plus parallel stores, each with an
  in-memory twin. Replaced by `core/memory` (≈1.5k lines) and, for tasks, the
  command catalog.
* **Debug-only activities, MemoryV2 shadow paths, `.bak` files, backups,
  `llama_old/`, training JSONL**: not part of the running app.
* **OpenAI client**: the app has no internet permission.
* **AndroidX UI**: the UI uses framework widgets. The only AndroidX library is
  `androidx.sqlite`, which SQLCipher needs.
