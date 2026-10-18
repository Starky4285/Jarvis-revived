# Roadmap (Rev. 4, October 2026)

Rev. 3 (June 2026) aimed for a JARVIS-style memory that understands any
sentence. That approach kept needing another rule for every new phrasing, so
Rev. 4 changes course: **Jarvis is a private helper for phone tasks.** The
on-device model translates what you say into one task from a fixed list, and
ordinary Android code does the task. Memory stays small and visible: facts and
places you tell it, shown on the Memory screen.

## The promises

These hold for every release. A change that breaks one doesn't ship.

1. **Nothing leaves the phone.** The Jarvis app has no internet permission, so
   Android itself blocks every connection it tries. The build strips the
   permission even if a library asks for it, and CI fails if the APK has it.
2. **Everything Jarvis stores is encrypted.** One SQLCipher database holds
   memory, the conversation log and, later, every index. Its key is wrapped by
   the Android Keystore. See [SECURITY.md](SECURITY.md).
3. **The model translates; code acts.** The model can only name a task from the
   catalog (a grammar forces valid JSON), its arguments are checked, and words
   such as contact names and places must come from what you actually said.
   Common phrasings skip the model entirely.
4. **Anything that reaches other people or can't be undone waits for "yes".**
   Calls today. Messages, deletions, bookings and sending mail when they arrive.
5. **What Jarvis reads is data, never instructions.** Text from mail,
   notifications, files or other apps' screens can't trigger an action.
6. **Honest about limits.** A task that isn't built yet is recognized and gets
   "that comes in the next update", not an invented answer.

## Three levels of difficulty

| Level | How it works | Reliability |
|---|---|---|
| 1. Android does it | Intents and Android APIs (Maps, alarms, the dialer, files, our own camera and recorder) | Reliable, quick to build |
| 2. Private index | An index built on the phone overnight while charging (faces, what was said in recordings, text in images). Afterwards answers are instant | Reliable once indexed |
| 3. Screen automation | Accessibility taps through another app's screens, then stops for your tap at the final step (Book, Pay, Send) | Breaks when that app's screens change; never pays for you |

The example tasks this plan was written for:

| Example | Level | When |
|---|---|---|
| Directions to Domino's from my location | 1 | Step 1 (done) |
| Call Amma, set alarms and timers, open apps | 1 | Step 1 (done) |
| Camera in 10 s, 3 continuous shots, or a video | 1 | Step 2 |
| Voice recordings over 5 minutes; start recording | 1 | Step 2 |
| Open Sheet1 of the balances file | 1 | Step 2 (shown inside Jarvis; picking the sheet inside Excel itself is level 3) |
| Storage analyzer | 1 | Step 2 |
| Read, search and summarize all my mail | Mail bridge | Step 3 |
| Oldest photo with my sister | 2 | Step 4 (tag her face once) |
| Search what was said in recordings | 2 | Step 4 |
| Rapido bike to the Shiva temple I always visit | 3 | Step 5 (the temple is a saved place from Step 1) |
| Trains to a destination in Where Is My Train | 3 | Step 5 |

## Build order

Each step works on its own and is tested on the Galaxy A15 before the next.

### Step 1: private automation core (v0.2, this release)

* Encrypted vault (SQLCipher, Keystore-wrapped key). The v0.1 plain database
  is moved into it once and deleted.
* No internet permission, enforced in the manifest and by a CI check; a
  **Privacy** panel shows the live state on the phone.
* Speech is recognized on the phone by default. The phone's online recognizer
  is used only if you switch it on in Privacy. Replies use an offline voice
  when the phone has one.
* Command layer: catalog of tasks, instant rules for common phrasings, the
  model as a grammar-forced translator for the rest, checked arguments.
* Confirm before calling ("Call Ravi? Say yes or no", or the buttons).
* Directions with Google Maps (driving, walking, two-wheeler, transit), or any
  maps app. Saved places: "remember Shiva temple is …", then "take me to the
  Shiva temple".
* Works with modern GGUF models (each model's own chat template), sizes the
  context window from the model, keeps two warm caches (chat and tasks).
* Every example task in the table above is recognized; the ones not built yet
  say when they are coming.

### Step 2: level 1 phone tasks

* Camera screen inside Jarvis: countdown, burst of N photos, video of a set
  length. Files are saved to the Gallery like any photo.
* Voice recorder, and search over recordings by length and date.
* Files: find by name, open in the right app, show a spreadsheet's sheet inside
  Jarvis (read-only) so you can ask about it.
* Storage analyzer: biggest, duplicate and old files; deleting asks first.
* Reminders that actually fire; messages (SMS / WhatsApp) prefilled for you to
  send; "save this place" from the current location.

### Step 3: mail bridge

Reading the whole mailbox needs the internet, which the Jarvis app must never
have. So mail gets a second, small app from the same project:

* **Jarvis** (no internet) keeps the model, your memory, files, photos and,
  later, screen automation.
* **Mail bridge** (internet, mail server only) logs in with IMAP and a Gmail
  app password (2-Step Verification required), downloads mail and hands it to
  Jarvis, which encrypts and indexes it. The bridge itself keeps only its
  encrypted login and its sync position.
* Sending happens only from the bridge's own confirm screen, so nothing in
  Jarvis can send mail silently.
* New mail is summarized as it arrives. Old mail is searchable at once and
  summarized when you ask: a small model needs seconds per email, so years of
  mail are not summarized in advance. The first sync runs on Wi-Fi while
  charging; text first, attachments when opened.
* Later: "Sign in with Google" instead of an app password. That needs Google
  to verify the app for Gmail access, whichever store it comes from; until
  then Google allows at most 100 users, each seeing a warning screen.

### Step 4: level 2 private indexes

Built overnight while charging, stored in the vault:

* Faces in your photos (you tag a person once, e.g. your sister).
* Transcripts of recordings, so "what did I say about the rent" works.
* Text in screenshots, photos and PDFs.

### Step 5: level 3 screen automation

* Recipes for Rapido and Where Is My Train, one app at a time.
* Always stops before the final Book / Pay step for your tap.
* Banking, UPI and password apps are blocked; text read from screens is never
  stored.

## Not planned yet

Wake word, barge-in (talking over Jarvis), Telugu and Tenglish, an encrypted
backup you can restore after a reset, a fingerprint lock for the Memory screen,
and the strict "vault locks with the screen" mode in SECURITY.md. Each is a
good next item once Steps 1 to 3 are solid.

## Getting the app to people (no Play Store for v1)

* **The developer's own phone:** install from GitHub Releases
  (`debug-latest`), Android Studio or `adb install`. Nothing below blocks this.
* **Google's developer verification:** certified Android phones increasingly
  require apps to come from a registered developer, even when installed from a
  website. It began in a few countries on 30 September 2026 and reaches all
  regions in 2027. Installs over USB (ADB) and from Android Studio stay exempt.
  To share the APK from a blog, register: free (up to 20 devices) or a full,
  ID-verified account. People can still install unregistered apps through an
  advanced option in Developer settings.
* **Restricted settings:** Android blocks Accessibility and notification access
  for apps installed from a browser or file manager until you allow it once:
  App info → ⋮ → Allow restricted settings. Step 5 needs this.
* **Stores:** Samsung Galaxy Store may question the Accessibility use; a
  personal blog stays the fallback. A Play version would need fewer features
  (Play limits Accessibility agents, all-files access and SMS).

## Choosing the model

| GGUF (Q4_K_M) | Cache per token | What Jarvis gets (448 MB cache budget) | Notes |
|---|---|---|---|
| **Qwen2.5-1.5B-Instruct** (about 1 GB) | ~28 KB | 4,096 tokens × 2 caches | Recommended: fast on the A15, good at short JSON |
| Llama 3.2 1B Instruct | ~32 KB | 4,096 tokens × 2 caches | Fastest; weaker at following instructions |
| Llama 3.2 3B Instruct | ~112 KB | 2,048 tokens × 2 caches | Smarter but slower; worth benchmarking |
| Phi-3-mini-4k-instruct (2.3 GB) | ~384 KB | 1,024 tokens × 1 cache | Still works, but slow and too small for mail |

Pick by testing on the phone: time from the end of speech to the first spoken
word, and how often the command translator picks the right task. The status
line shows the context plan and timing of every answer.
