# Roadmap

Mapped to the phases of the original "Assistant — Build Roadmap" (Rev. 3).

## Done in v0.1

* Phase 1 — Assistant core: one-shot and hands-free listening, TTS queue, typed input.
* Phase 2 — Local LLM bridge: llama.cpp via JNI, streaming, UTF-8-safe, abortable.
* Phase 4 — Memory owner: rebuilt (see MEMORY.md), encrypted protected lane.
* Phase 5 (part) — Model-load policy: one model, CPU-only, background load, atomic import.
* Phase 6 — Source-tree consolidation: done by starting clean.
* Phase 8 (part) — Action firewall: the model can't act; only anchored local intents run actions.

## Next, in order

1. **Phone validation** on the Galaxy A15: latency (STT final → first spoken word),
   memory recall by voice, hands-free with the screen off, model load time.
2. **Barge-in**: say "stop" while Jarvis is speaking (needs echo handling).
3. **Mic/media gate**: don't listen during calls or music; pause when audio focus is lost.
4. **Reminders hand**: real scheduled reminders ("remind me at 6 to call mom").
5. **Contact fuzzy-confirm**: "Did you mean Ravi Kumar or Ravi Teja?" before calling.
6. **Encrypted memory backup / restore** (reinstall-safe), then QR device migration.
7. **Per-model prompt formats** so smaller/faster models (Qwen, Llama 3.2 1B/3B) can be used.
8. **Wake word** (on-device, e.g. openWakeWord / Porcupine) — needs battery testing.
9. **Masked online layer** for fresh facts (OFF · ASK · AUTO), never storing results in memory.
10. **Release**: privacy policy, data-safety form, permission declarations, staged rollout.

## Deferred to V2 (unchanged from the original plan)

Telugu / Tenglish + persona, behaviour learning, universal app understanding,
multi-device sync, vision.
