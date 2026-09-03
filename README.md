# Kernel Panic

Kernel Panic is an offline Android app that listens to popcorn cooking in a microwave, detects pop-like acoustic events, follows the rise and decline of the popping rate, and alerts the user when the active cycle appears to be ending.

It is a convenience aid, not a cooking safety system. Stay near the microwave, follow the popcorn manufacturer's directions, and never put a phone inside a microwave.

## What Kernel Panic Does

Tap **Start Listening** when the microwave starts. Kernel Panic requests microphone access at that moment, analyzes live audio while the screen remains visible, and shows a real microphone-level visualization, detected pop-event count, interval estimate, and lifecycle state. A completed session is stored locally in History. Raw microphone audio is never stored or sent anywhere in release builds.

When done is detected, the app provides a large visual state, haptic feedback, and text-to-speech (or an alarm tone fallback). If the microwave continues to sound active, the message escalates from **POPCORN IS DONE!** to **TAKE IT OUT!** and then **STOP THE MICROWAVE!**.

## Architecture

```text
AudioRecord / debug WAV / synthetic fixture
  → overlapping PCM frames
  → high-pass filter + Hann window + FFT feature extraction
  → adaptive multi-feature transient classifier
  → merged/debounced accepted pop events
  → rolling event rates and robust interval statistics
  → explicit popcorn lifecycle state machine
  → StateFlow-backed Compose UI
  → derived session statistics saved with Room
```

The main boundaries are:

- `audio/`: `AudioSource`, physical `MicrophoneAudioSource`, and WAV input.
- `detector/`: feature extraction, transient classification, lifecycle inference, and centralized `DetectorConfig`.
- `data/`: Room entity, DAO, database, and coroutine-safe repository.
- `ui/`: Compose screens, theme, live charts, and original Canvas mascot.
- `testing/`: deterministic microwave, pop, speech, impact, failure, and stop scenarios.

The detector consumes PCM and timestamps implied by the sample stream. It has no dependency on `AudioRecord`, Room, or Compose.

## Running the App

Prerequisites: Android Studio with Android SDK 36 installed and a JDK compatible with Android Gradle Plugin 9.2 (Android Studio's bundled JDK works).

1. Open this repository folder in Android Studio.
2. Allow Gradle sync to finish.
3. On a Pixel 8, enable **Developer options** and **USB debugging**.
4. Connect the phone by USB and accept its debugging prompt.
5. Select the `app` run configuration and the Pixel 8, then click **Run**.
6. Tap **Start Listening** and grant microphone permission.

Command-line alternatives from PowerShell:

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

The release build is created with `.\gradlew.bat assembleRelease`. It is unsigned until a signing configuration is supplied through Android Studio or a local, uncommitted Gradle configuration.

## Testing

Run all local tests and static checks:

```powershell
.\gradlew.bat test lint assembleDebug assembleRelease
```

The deterministic suite covers:

- three minutes of microwave-only background;
- a normal rising, active, declining, and sparse lifecycle;
- one early 2–3 second gap followed by resumed rapid popping;
- isolated early transients;
- rapid successive pops and within-event debouncing;
- loud low-frequency knocks;
- speech-like energy;
- low signal-to-noise pops;
- exact-zero input failure;
- microwave stopping before activity, after done, and continuing through warning/critical states.

State-machine tests separately prove that silence, a lone early pop, and missing input cannot unlock `DONE`. An Android Compose test covers first-run onboarding. Instrumented tests require a connected device or emulator.

## Debug Audio Lab

Debug APKs add **Debug Audio Lab** to the main app's overflow menu while retaining a single launcher icon. The lab can run every bundled synthetic fixture, import a mono 16-bit PCM WAV file, or explicitly record a real microwave session to an app-specific WAV file. Its **Saved recordings** list provides Play/Stop and Analyze controls for every captured session, newest first. Recording one actual cooking session once provides a representative fixture that can be replayed repeatedly without using another bag during every tuning pass.

All three inputs use the production `PopcornDetector`. The lab displays accepted/rejected status, score, RMS and noise floor, spectral flux, high-frequency ratio, crest factor, flatness, attack ratio, lifecycle transitions, peak rate, current gap, active evidence, and completion timestamp. Recorded files can also be retrieved with Android Studio's Device Explorer for comparison on a development machine.

The activity, recorder, and raw-file writer live under `app/src/debug`, so they are not compiled into release builds. Release builds contain no recording/export feature.

## Detector Tuning

All meaningful thresholds are documented in [`DetectorConfig.kt`](app/src/main/java/app/kernelpanic/detector/DetectorConfig.kt): frame/hop sizes, bands, adaptive energy and flux gates, event merge/separation durations, active evidence, decline ratio, sparse interval statistics, no-pop completion, signal loss, appliance-stop persistence, and alert escalation delays.

Doneness is based on the observed acoustic lifecycle, never an expected cook duration or an absolute position in a recording. Microwave-band drop detection can close an unfinished session without claiming doneness; it cannot produce or strengthen a `DONE` decision.

Tune from real recordings by importing WAV files into Debug Audio Lab, then run the full regression suite. Avoid optimizing one kitchen recording at the expense of early-done safety regressions.

## Privacy

Kernel Panic has no backend, account, ads, analytics, cloud storage, or networking. Its only sensitive runtime permission is `RECORD_AUDIO`; the normal `VIBRATE` permission powers haptic alerts. It deliberately does not request `INTERNET`. See [PRIVACY.md](PRIVACY.md).

## Limitations

This release uses deterministic signal processing and statistical heuristics, not a trained machine-learning model. Microwave acoustics, automatic microphone gain, rooms, phone placement, bags, and brands vary. External transient sounds can resemble pops, quiet pops can be missed, and simultaneous kernels can merge into one accepted acoustic event. The displayed event count is therefore not yet an exact popped-kernel estimate. The state machine is intentionally conservative: a slightly late alert is preferred to an alert during active popping. Real cooking sessions on several appliances remain essential for field calibration.

## License

Released under the MIT License. See [LICENSE](LICENSE).
