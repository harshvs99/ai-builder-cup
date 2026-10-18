# Voice spike: Gemma 4 E2B on-device, audio -> Relay "need card"

Question: can Gemma 4 E2B, fully on-device via Google LiteRT-LM on Android, take a short voice clip (no separate
speech recognizer, no internet) and return a structured need card? Standalone Gradle project; **not** part of
`../../android` (it only compiles `../../android/core-protocol/src/main/kotlin` read-only, for `RuleExtractor`).

## Stack (verified)
- LiteRT-LM Kotlin API `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (latest on Google Maven, 2026-10-06).
  Needs a **Kotlin 2.4 toolchain** (the library and its stdlib are compiled with 2.4.0; Kotlin 2.2.x cannot read them),
  so this project uses Kotlin 2.4.0 + AGP 9.1.1 + Gradle 9.3.1 (the `android/` project stays on 2.2.10).
- Model: `litert-community/gemma-4-E2B-it-litert-lm`, file `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes,
  sha256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, Apache-2.0, not gated).
- Audio path: `Content.AudioBytes(wavBytes)` + `Content.Text(prompt)` in one `Contents`; `EngineConfig(backend = GPU or CPU,
  audioBackend = CPU)`. Bytes are a canonical WAV (44-byte header, PCM16, mono, 16 kHz), same as Google's Gallery app.

## Build
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd "ml/voice-spike"
./gradlew :app:assembleDebug            # or: ./gradlew installDebug  (single attached device)
```
Install to the S24 FE only: `adb -s RZCY21LMG0J install -r app/build/outputs/apk/debug/app-debug.apk`
(`./gradlew installDebug` installs to every attached device, including the emulator, so prefer adb with `-s`).

## Model: download and push
```bash
mkdir -p models && curl -L -C - -o models/gemma-4-E2B-it.litertlm \
  https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
shasum -a 256 models/gemma-4-E2B-it.litertlm     # must equal the sha256 above
adb -s RZCY21LMG0J shell pm grant org.relay.voicespike android.permission.RECORD_AUDIO   # after install
adb -s RZCY21LMG0J shell am start -n org.relay.voicespike/.MainActivity                  # creates the files dir
adb -s RZCY21LMG0J push models/gemma-4-E2B-it.litertlm /sdcard/Android/data/org.relay.voicespike/files/
```
`models/` is gitignored. The app loads `<externalFilesDir>/gemma-4-E2B-it.litertlm`; nothing is bundled in the APK.

## Guided recording (default screen) - build a labelled test set
The app opens on **Record**: 34 prompts (15 English in Indian-English phrasing, 15 Hindi in Devanagari, 4 Hinglish in Latin
script; all 7 categories, all 5 vulnerability flags, counts, landmarks with and without "near", negation and implicit-meaning
cases, plus the real utterance that fooled `RuleExtractor`). Source of truth: `eval/guided_prompts.json`
(`id`, `lang` en/hi - Hinglish is `hi` -, `text`, `expected{category,urgency,people,vuln[],item,landmark}`); the build copies it into
the APK assets, so edit that file and rebuild. `people` = headcount stated or clearly meant, `0` = unknown; `item` is one bare
noun; `landmark` is the bare place noun.
- **Hold** the big button and read the sentence (16 kHz mono WAV, max 15 s, under 0.8 s is discarded). **Play** to check.
  **Re-record** deletes the take (so a stray touch cannot overwrite it). **Next / Prev / Skip**; "Next pending" jumps to the
  first prompt without a take. Position, skips and takes survive an app restart.
- Takes live in app-internal storage, `files/guided/<id>.wav`, with `manifest.json` (`file, lang, expected, reference_transcript`)
  kept in sync after every save/delete, `state.json` (position) and, after an eval, `results.jsonl` + `summary.txt`.
- **Eval** tab: "Run eval on my recordings" runs the batch eval over `guided/` (Gemma on audio, Gemma on the prompt text,
  `RuleExtractor` on the prompt text) and shows the summary tables and a per-clip miss list. It also logs `EVAL DONE ...` and
  `EVAL SUMMARY ...` lines. Recording is locked while it runs. **Free-form** is the old record/text screen (unchanged,
  saves to `recordings/` under the external files dir).
- The model starts loading as soon as the app opens ("Loading model... 12 s", GPU ~4-15 s); you can record while it loads and the eval
  waits for it.

Get the takes and results onto the Mac:
```bash
eval/pull_guided.sh            # run-as tar of files/guided -> eval/work/guided/, then summarize.py on results.jsonl if present
```
**Gotcha (permissions):** files written by the app are not readable by `adb pull`, and files pushed by `adb shell` into the app's
external dir are not readable by the app (EACCES) unless `chmod 777`. So the app writes only app-internal paths and you read
anything it wrote with `adb exec-out run-as org.relay.voicespike cat files/guided/<name>` (works for the external dir too:
`run-as org.relay.voicespike cat /sdcard/Android/data/org.relay.voicespike/files/recordings/<name>`). The app is debuggable, so
`run-as` works. Always update with `adb install -r` (never uninstall or `pm clear`: that deletes the takes, and the model push survives
only `install -r`).

## Scoring
`eval/summarize.py` (and `Summary.kt` on the device, same logic): item and landmark are normalised (lowercase, punctuation stripped,
leading near/by/at/behind/the/a/an dropped repeatedly, trailing Hindi "के पास" dropped) and then match if equal or similarity >= 0.8
(difflib ratio). Also reported: category, urgency within +/-1, people exact match, micro-F1 over vulnerability flags, validity,
latency, and a per-clip list of every miss (`--diff-all` for all three systems).

## Record mode (UI, "Free-form" tab)
Pick a language chip, hold the big button (max 15 s, 16 kHz mono PCM16 via `AudioRecord`), release. The screen shows the
Gemma transcript, the extracted JSON, a latency line (model load / audio->first token / total / PSS), and `RuleExtractor`
run on Gemma's transcript. Each recording is saved as `recordings/rec_<ts>.wav` + `.json` under
`/sdcard/Android/data/org.relay.voicespike/files/`. The first run loads the model (GPU first, CPU fallback).
Text mode: type a sentence, "Run text mode" (same prompt, text instead of audio).

## adb drivers (all via `MainActivity` extras)
```bash
A="adb -s RZCY21LMG0J"; N=org.relay.voicespike/.MainActivity
$A shell am start -n $N --es text "my mother needs insulin, near the school" --es lang en      # one text run
$A shell am start -n $N --es wav /sdcard/Download/x.wav --es lang hi                          # one audio run (app must be able to read the path)
$A shell am start -n $N --es eval eval1                                                       # batch (dir relative to the app files dir, or absolute)
$A shell am start -n $N --es backend cpu --ez load true                                       # force CPU / just load
$A logcat -s VoiceSpike                                                                        # RESULT {...} lines, EVAL DONE n=...
```

## Batch eval
```bash
eval/make_clips.sh                                    # macOS `say` -> eval/work/clips/*.wav + manifest.json (from eval/sentences.csv)
D=/sdcard/Android/data/org.relay.voicespike/files/eval1
adb -s RZCY21LMG0J shell mkdir -p $D && adb -s RZCY21LMG0J push eval/work/clips/. $D/
adb -s RZCY21LMG0J logcat -c
adb -s RZCY21LMG0J shell am start -n org.relay.voicespike/.MainActivity --es eval eval1
adb -s RZCY21LMG0J logcat -s VoiceSpike | grep -m1 "EVAL DONE"
adb -s RZCY21LMG0J pull $D/results.jsonl eval/work/results.jsonl
python3 -I eval/summarize.py eval/work/results.jsonl
```
Per clip, `results.jsonl` has: `gemma_audio` (raw output, parsed JSON, `valid`, issues/repairs, latencies, backend),
`gemma_text` (same prompt on `reference_transcript`), `rule` (`RuleExtractor` on `reference_transcript`), `expected`,
`audio_seconds`, and PSS (MB) after each Gemma run. The model is loaded once per batch. The screen is kept on.
Valid = a JSON object with all keys, legal enums, numeric urgency/people; clamps and byte-truncations are recorded as
`repairs` and do not count as invalid. `summarize.py` prints per-language tables for the three systems plus the miss list.

macOS has voices only for en_IN and hi_IN (Rishi, Tara, Lekha); or/tl/war/ceb cannot be synthesized here, so those need
real recordings (record mode) or a text-only manifest entry (omit `file`).
