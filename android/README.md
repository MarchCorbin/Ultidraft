# Ultidraft for Android

Listen to the draft on the walk, mark the sentence that snags, and find the note waiting
on the PC. Second client, same files — not a rewrite.

The desktop app is the editor. This one is the ears.

## What it does

- Open a `.md` or `.txt` draft from a folder you choose
- Chapter list, sentence highlight that follows the voice, play / pause, sentence and
  paragraph skip, speed
- Keeps reading with the screen off, with play/pause/skip on the lock screen and on
  headphone buttons
- Typed **or spoken** notes on the sentence you just heard
- Edit the paragraph at the playhead and write it back to the file
- Writes the same `*.ultidraft.json` sidecar and `listening-notes.md` the desktop writes

There is no account and no server. The phone reads and writes the same folder the PC does;
Syncthing carries it between them.

## The contract with the desktop app

`app/src/main/java/com/ultidraft/domain/` is a port of `ultidraft.domain` — the chapter
and sentence parser, the sidecar, the Cursor export, the pronunciation lexicon. It is
plain Kotlin with no Android imports, and it is held to the desktop's behaviour by a
differential test: both parsers were run over the same fifteen documents and produced
identical chapters, sentences, paragraph indices, spans and export markdown, down to the
byte. If you change the sentence splitter on one side, change it on the other.

Two deliberate differences:

| | Desktop | Android |
|---|---|---|
| Note ids | `N001`, `N002`, … | `M001`, `M002`, … |
| Voice | Windows voices, Edge neural | The phone's own TTS engine, offline |
| Editing | The whole manuscript | The paragraph at the playhead |

The note-id prefix is what makes two-way sync safe. The desktop's `next_note_id` ignores
any id it cannot read as `N<number>`, so the phone's `M`-numbered notes never shift the
desktop's sequence and the two can never mint the same id for different notes.

Sidecar writes re-read the file and merge rather than overwrite, because Syncthing can
land a newer copy between the read and the write. Notes are unioned by id, the local edit
wins for ids present in both, and a note deleted on the PC stays deleted.

Playback position is written to the sidecar only when you pause — not on every sentence —
so a synced folder does not churn. Between pauses it is kept locally.

## Building

Nothing to install: push the branch and GitHub Actions builds it.

1. Push. `.github/workflows/android.yml` runs the domain tests, builds the debug APK, and
   attaches it to the `android-latest` release.
2. On the phone, open
   `https://github.com/MarchCorbin/Ultidraft/releases/tag/android-latest` and download
   `ultidraft-debug.apk`.
3. Android will ask once for permission to install from your browser. Allow it, then open
   the download.

To build locally instead, open the `android/` folder in Android Studio and press Run, or:

```powershell
cd C:\Users\march\Desktop\Ultidraft\android
.\gradlew.bat :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Setting up the folder

1. Install Syncthing on the PC and on the phone.
2. Share the folder your draft lives in — the one holding `SODOM.md` and
   `SODOM.ultidraft.json` — and let it sync.
3. Open Ultidraft on the phone, tap **Choose the book folder**, and pick the synced folder.
   Android remembers it; you only do this once.
4. Pick the book. It resumes where you left off.

Notes you take on the phone appear in `listening-notes.md` in that same folder, so the
Cursor step on the PC is unchanged.

## Tests

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest
```

Thirty checks over the parser, the sidecar, the export, the lexicon, the spoken-span
offsets, and the sync merge. The bodies live in `DomainChecks.kt` with no test-framework
imports, so the same assertions can be run by a bare `kotlinc` on a machine that cannot
reach Maven — which is how the port was verified against Python in the first place.

## Known edges

- Android's TextToSpeech has no pause. Pausing stops the utterance; resuming re-speaks
  from the sentence the voice had reached.
- The highlight follows the voice through `onRangeStart`. Engines that do not report
  ranges will still read correctly, but the highlight will move a span at a time.
- Voices only appear in the settings sheet after the speech engine has started, which
  happens on the first play.
