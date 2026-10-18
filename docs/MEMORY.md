# Memory design

## The idea in one line

**Simple storage, honest feedback.** Store facts plainly, decide every write in
one place, and say every write back to the user so mistakes are fixed by
voice ("no, it's blue" / "never mind") instead of by ever more parsing rules.

## Write path

```
utterance ─► MemoryParser (shapes) ─┐
                                    ├─► MemoryEngine decides ─► MemoryStore writes ─► spoken confirmation
"remember …" with no clear shape ───┘        ▲
     └─► MemoryExtractor (model, grammar-forced JSON) ─► validate (grounded, short, not instruction-like)
```

* **Explicit** — anything after "remember / note / save / keep in mind" is
  always kept: as `key = value` when the shape is obvious ("my X is Y",
  "X is Y"), else the model may propose a key/value (it must use the user's own
  words), else it is saved as a searchable **note**.
* **Implicit** — without "remember", only unambiguous shapes are saved:
  "my name is / call me", "my <…name / favourite … / birthday / number /
  email / address / password …> is …", "I live in", "I work at/as",
  "I'm allergic to". Chatter like "my phone is dying" is never stored, and a
  negation ("my dog is not a terrier") is never stored as a positive fact.
* **Corrections** — "actually my X is Y" replaces X. "No, it's Y" fixes the
  thing saved in the previous turn, and the wrong value is dropped, not kept
  as history.
* **Undo** — "never mind / scratch that / forget that" in the turn right after
  a save removes it (and restores the previous value after an overwrite).
* **Relative dates** are resolved when written: "dentist tomorrow" said on
  14 Oct is stored as "on Thursday 15 October".

## Read path

* "What/who/when/where is my X" → exact key, then token match on keys
  ("who is my wife" finds `wife name`). A miss is answered explicitly:
  "I don't have your blood group saved yet."
* "Where did I …", "what did I tell you about …" → search over facts, notes and
  the conversation log. If nothing matches, the question goes to the model.
* Questions without "my" are answered from memory only when every content word
  matches one saved fact ("when is rent due"), so general questions still
  reach the model.
* **Prompt context**: `PromptMemory` picks the user's name plus facts whose
  words overlap the question, within 400 characters, sanitized, inside
  `<memory>…</memory>`.

## Saved places

A fact can be a place: "remember Shiva temple is Ramalingeswara Swamy temple,
Kukatpally". When a directions request names it ("take me to the Shiva
temple"), `MemoryEngine.placeFor` looks for the exact key, then the key plus
"address" or "location", then a saved key containing every word of the
request (fewest extra words wins). Two equally good matches are never guessed
between ("the temple" with two temples saved); Maps then searches the words
as said. Notes and protected items are never used as places.

## Storage (one SQLCipher-encrypted file, app-private, never backed up)

The whole database file, `jarvis_vault.db`, is encrypted with SQLCipher; its
key is wrapped by the Android Keystore (see [SECURITY.md](SECURITY.md)).

| Table | Holds | Notes |
|---|---|---|
| `facts` | key, label, value, source, created_at, superseded_at | one active row per key; older rows are history until "forget" |
| `protected_items` | key, label, value_encrypted | encrypted again with AES-256-GCM and its own Keystore key; if that fails the secret is **not** stored |
| `episodes` | rolling log of the last 1,000 turns | secrets are redacted before logging |

Protected items (passwords, PINs, OTPs, card/account/Aadhaar/PAN numbers,
any 12–19 digit number) are never spoken, never put in a prompt and never
logged. They can be viewed on the Memory screen.

## Fault-line coverage

The old design's ten fault lines are now tests in
`core/src/test/java/com/jarvis/core/memory/MemoryFaultLineTest.java`.

| Fault line | Status |
|---|---|
| episodic vs current state | ✅ current value + "what was my X before" |
| singular state overwrite | ✅ one active value per key, history kept |
| temporal anchoring | ✅ relative days resolved at write time |
| modality / observed-not-owned | ✅ "I saw a Vision Pro" is not stored |
| entity identity (same thing, different words) | ✅ synonyms + token match (lightweight) |
| scoped aliases / rules | ⏳ not yet |
| retraction / correction order | ✅ "never mind", "no, it's …", "actually …" |
| unknown / negative constraints | 🟡 negations never stored as positives; no constraint store yet |
| dependency / expiration | ⏳ not yet |
| security: memory is data | ✅ SQL-looking and prompt-injection text stored literally, fenced in prompts; secrets protected |

## Not yet (next steps)

* Encrypted backup / restore (the old PBKDF2 + AES-GCM design is a good base).
* Embedding search for fuzzier recall ("what's my sister's place called?").
* Reminders with real scheduling (today "remember … tomorrow" is a note, not an alarm).
