# Privacy and security

Jarvis makes two promises: **nothing leaves the phone**, and **everything it
stores is encrypted**. This page explains how each is enforced, where the
limits are, and what is planned.

## 1. Nothing leaves the phone

**The app has no internet permission.** On Android, an app without
`android.permission.INTERNET` can't open network connections at all; the
system refuses them. This is stronger than a promise in the code: even a bug,
or a library with its own ideas, can't send data out.

How the permission is kept out:

* `AndroidManifest.xml` removes it with `tools:node="remove"`, so it stays out
  even if a library's manifest asks for it.
* CI checks every APK with `aapt2 dump permissions` and fails the build if
  `android.permission.INTERNET` appears.
* On the phone, **Privacy** shows the live check: whether the installed app
  asks Android for the internet permission at all.

What this doesn't cover, by design:

* **Apps Jarvis opens for you** (Maps, the dialer, Rapido) are separate apps
  that use the internet as they always do. Jarvis hands them only what you
  asked for, such as the destination.
* **Speech recognition.** By default Jarvis uses the phone's *on-device*
  recognizer (Android 12 and later). The phone's ordinary recognizer belongs
  to another app (usually Google's) and may send audio to its servers, so
  Jarvis uses it only if you switch on "Allow online speech recognition" in
  Privacy. Without either, you type.
* **Speech output.** The voice is the phone's text-to-speech engine, another
  app. Jarvis switches it to an offline voice when one is installed, and
  Privacy says if only an online voice is available.

## 2. Everything stored is encrypted

### The vault

Everything Jarvis remembers lives in one database, `jarvis_vault.db`:
facts, notes, saved places, protected items and the conversation log. Later
steps add the file, photo, recording and mail indexes to the same vault.

* The whole file is encrypted with **SQLCipher 4** (AES-256, every page
  authenticated with HMAC-SHA512), including its write-ahead log. Without the
  key the file is random bytes.
* The key is **32 random bytes** from `SecureRandom`, used directly as the
  SQLCipher key.
* Those bytes are stored only **wrapped**: encrypted with AES-256-GCM by a key
  that lives inside the **Android Keystore** (in the StrongBox security chip
  when the phone has one, otherwise in the processor's secure area, the TEE).
  Keystore keys can be used on the phone but can't be read out of it.
* So a copy of Jarvis' files (from a backup, a rooted phone's file dump or a
  forensic image) can't be decrypted on any other device.

### Protected items

Passwords, PINs, OTPs and card, account, Aadhaar or PAN numbers get a second
layer: each value is encrypted again with its own Keystore key
(`jarvis_protected_v1`) before it goes into the vault. If that encryption
fails, the value isn't stored at all. Protected values are never spoken, never
put in a model prompt and never logged; the Memory screen shows them on tap.

### Other places data could leak, and what's done

| Where | Protection |
|---|---|
| Google backup, phone-to-phone transfer | Off: `allowBackup="false"` and data-extraction rules exclude everything |
| Logs (logcat) | Only the route and command name, e.g. `maps.directions [rules]`; never what you said |
| The model file | Holds no personal data; prompts are built in memory and never written to disk |
| v0.1's unencrypted database | Copied into the vault on first start of v0.2, then deleted |
| Secure storage failing | Memory stays in RAM for that session and you're told nothing is saved; it is never written unencrypted |

## 3. Key choices and trade-offs

**The vault key does not require the screen to be unlocked.** This lets
background work, such as indexing photos or mail at night while the phone
charges, open the vault. The trade-off: while the phone is on and has been
unlocked once since it started, code running *as Jarvis* could use the key.
On a normal (not rooted) phone, other apps can't run as Jarvis.

Planned **strict mode** (an option): the wrapping key gets
`setUnlockedDeviceRequired(true)`, so the vault opens only while the phone is
unlocked. Background indexing then pauses whenever the phone is locked.

**If the key can't be unwrapped.**

* Certainly unusable (damaged key file, Keystore key missing or invalidated,
  authentication failure): the unreadable vault is deleted, a new one is
  started, and Jarvis tells you.
* Possibly temporary (for example the Keystore still starting after a reboot):
  Jarvis retries once, then keeps memory in RAM for that session and tries
  again next launch. Only after three launches in a row is the vault replaced.

There is no backup yet, so a lost key means lost memory. An encrypted export
you can restore is on the roadmap.

## 4. What encryption can't protect against

1. **Someone who has your phone and your PIN** can open Jarvis like you can.
   A fingerprint lock for the Memory screen is planned.
2. **Files that belong in other apps:** photos and videos Jarvis takes will be
   saved to the Gallery as normal media, outside the vault.
3. **Screen automation (Step 5)** lets Jarvis read other apps' screens while it
   works. Banking, UPI and password apps will be blocked, and screen text will
   never be stored.
4. **What you send.** Messages and, later, mail go to their recipients once
   you confirm them.

## 5. The mail bridge (planned, Step 3)

Reading a whole mailbox needs the internet, so it can't live in Jarvis. The
plan is a second, small app built from the same project:

* **Mail bridge** has the internet permission and talks only to your mail
  server over TLS (IMAP to read, SMTP to send). It stores only its login,
  encrypted with its own Keystore key, and its sync position.
* It hands downloaded mail to Jarvis through a channel protected by a
  signature permission, which only apps signed with the same key can use.
  Jarvis encrypts and indexes the mail in the vault.
* Mail is sent only from the bridge's own confirm screen, after your tap. A
  bug in Jarvis can't make the bridge send anything.
* Text inside emails is treated as data, never as instructions to Jarvis.

So the app that sees your files, photos and screen can't reach the internet,
and the app that reaches the internet sees only your mail, which your mail
provider already has.

## Reporting a problem

Open an issue on GitHub. For anything that could expose someone's data,
describe the problem without posting personal data.
