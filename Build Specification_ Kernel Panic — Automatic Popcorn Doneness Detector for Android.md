# Build Specification: Kernel Panic — Automatic Popcorn Doneness Detector for Android

## Objective

Build a complete, production-quality Android application called **Kernel Panic**.

Kernel Panic listens to the sound of popcorn cooking in a microwave, identifies individual popcorn-pop acoustic events, tracks how the popping rate changes over time, and alerts the user when the popcorn has reached the end of its active popping cycle.

The application should solve the problem addressed by instructions commonly printed on microwave popcorn bags: instead of manually listening for the interval between pops to reach approximately 1–2 seconds, the application performs that listening and timing automatically.

This is intended to be a small, playful application and a demonstration project, but the implementation itself must be real and functional. Do not build a UI prototype with mocked detection. The application must use the physical Android microphone, process live audio, detect actual popcorn-like transient sounds, infer the popping phase, and make a real-time doneness decision.

The primary physical test device is a **Google Pixel 8**.

Do not use the word **Shazam** anywhere in the product name, package branding, store-facing copy, icons, or UI. “Popcorn Shazam” is only an internal description of the concept.

Use **Kernel Panic** as the working product name.

---

# 1. Implementation Expectations

Treat this as a complete implementation task, not an exploratory prototype.

Do not stop after scaffolding the project.

Do not leave:

- TODO comments for required functionality
- mocked microphone data
- placeholder detection logic
- fake history
- fake graphs
- non-functional buttons
- commented-out implementations
- empty screens
- incomplete permission handling
- hardcoded demonstration results

Implement all functionality described below.

Run the project's build, unit tests, lint/static checks, and relevant instrumented tests. Fix failures before considering the implementation complete.

If an Android project already exists in the repository, inspect it and preserve appropriate existing architecture and configuration. Otherwise, create the Android project.

---

# 2. Platform and Technical Stack

Build this as a native Android application using:

- Kotlin
- Jetpack Compose
- Material 3
- Kotlin coroutines and Flow
- Android `AudioRecord` for real-time PCM microphone access
- Room for locally stored session history
- AndroidX libraries using stable releases compatible with the project's toolchain

Do not introduce a backend.

Do not introduce authentication.

Do not introduce analytics.

Do not introduce advertising SDKs.

Do not introduce cloud storage.

Do not introduce networking.

The release application should not require the `INTERNET` permission.

The application should function entirely offline.

Use a reasonable Android minimum SDK such as API 26 while targeting the current stable Android SDK supported by the installed toolchain.

Architect the code cleanly enough that audio detection can be tested independently of the Android microphone and independently of the Compose UI.

At minimum, separate:

1. Audio input
2. Signal processing
3. Pop-event detection
4. Popcorn-phase/doneness classification
5. Session state
6. Persistence
7. UI

Do not put the DSP algorithm directly inside a Compose screen or ViewModel.

---

# 3. Core User Experience

The application must be intentionally minimal.

The expected workflow is:

1. User opens Kernel Panic.
2. User places their phone on a nearby countertop outside the microwave.
3. User starts the microwave.
4. User taps the central Start Listening button at approximately the same time.
5. Kernel Panic requests microphone permission if necessary.
6. Kernel Panic begins analyzing microphone audio.
7. The UI shows that it is listening and provides a minimal visualization of the incoming audio.
8. Kernel Panic detects individual popcorn-pop events.
9. The UI progressively changes as the popcorn transitions from initial heating to active popping to declining popping.
10. Kernel Panic determines that the popcorn has reached the end of its popping cycle.
11. The application immediately alerts the user visually, audibly, and with haptic feedback.
12. If the microwave continues operating after doneness has been detected, the warning becomes progressively more urgent.
13. When the microwave operation appears to stop or the user manually presses Stop, the session ends.
14. The application displays a summary.
15. The completed session is stored locally in History.

The user should not need to configure microwave wattage, popcorn brand, bag size, kernel quantity, or a manual timer.

The detection algorithm should adapt to the observed popping pattern.

---

# 4. Safety and Product Positioning

Kernel Panic is a convenience aid, not a cooking safety system.

Never imply that the app can guarantee that popcorn will not burn or that it is safe to leave a microwave unattended.

Include concise messaging in the first-run instructions and About/How It Works screen explaining:

- Stay near the microwave while cooking.
- Follow the popcorn manufacturer's instructions.
- Microwave behavior varies.
- Kernel Panic estimates doneness from sound and can make mistakes.
- Do not put the phone inside the microwave.
- Place the phone nearby with its microphone unobstructed.

Keep this unobtrusive. The home screen itself should remain simple.

---

# 5. Microphone and Privacy Requirements

The only sensitive runtime permission the application requires is:

`RECORD_AUDIO`

Do not request:

- Location
- Contacts
- Camera
- Photos
- Bluetooth
- Nearby devices
- Phone
- Calendar

Request microphone permission contextually when the user first taps Start Listening rather than immediately when the application launches.

If permission is denied:

- explain in one sentence why microphone access is required;
- allow the user to try again where appropriate;
- if Android no longer allows the permission prompt to be shown, provide an action that opens the application's system settings page.

### Release privacy behavior

Raw microphone audio must:

- be processed locally;
- be processed in real time;
- not be uploaded;
- not be transmitted;
- not be saved to disk;
- not be retained after processing.

Only derived session statistics may be persisted.

Create an in-app Privacy screen and a `PRIVACY.md` file suitable for adapting into a hosted privacy policy later.

The privacy language should clearly state that microphone audio is analyzed on-device and is not stored or transmitted.

Debug/test builds may contain explicit developer-only audio capture or WAV testing functionality. Such functionality must not be accessible in release builds.

---

# 6. Foreground-Only Session Model

For this version, microphone monitoring should occur only while the Kernel Panic listening experience is actively visible.

While listening:

- keep the screen awake;
- clearly show that the microphone is active;
- prevent accidental navigation from silently leaving the microphone running.

Do not implement continuous hidden/background microphone surveillance.

If the activity loses the foreground in a way that prevents reliable capture, safely stop or interrupt the active session and preserve whatever summary information is appropriate.

This avoids unnecessary background-service complexity and is consistent with the intended use case: the user starts the microwave, places the phone nearby, and leaves the Kernel Panic screen open.

---

# 7. Audio Capture

Use `AudioRecord`.

Recommended starting configuration:

- mono
- PCM 16-bit
- 48 kHz where supported
- otherwise select an appropriate device-supported sample rate

Prefer an unprocessed/raw microphone source when Android and the device support it because aggressive speech-oriented noise suppression or automatic gain processing could reduce short popcorn transients.

Fall back safely to a normal microphone source when unprocessed recording is unavailable.

Audio processing must happen continuously without blocking the UI thread.

Use buffered processing with predictable frame sizes.

A reasonable starting point is approximately:

- 20–30 ms analysis windows
- overlapping frames
- a window function such as Hann before FFT analysis

Keep all important DSP thresholds and tuning parameters in a centralized `DetectorConfig` rather than scattering magic numbers throughout the code.

---

# 8. Detection Architecture

Do NOT implement doneness as:

`if timeSinceLastPop >= 2 seconds -> done`

That is specifically prohibited.

A single unusually long interval can occur early or in the middle of cooking.

Instead use two distinct detection layers.

## Layer A — Acoustic Pop Event Detector

Determine whether an incoming acoustic transient is likely to represent a popcorn pop.

## Layer B — Popcorn Session State Detector

Use the sequence and timing of accepted pop events to determine whether the bag has progressed through the expected popping lifecycle.

Doneness is only possible after the application has positively observed an active popping phase.

This distinction is central to the implementation.

---

# 9. Pop Acoustic Detection

Popcorn pops are short, impulsive, broadband acoustic events occurring over a relatively stable microwave/fan background.

Do not detect pops using microphone amplitude alone.

A slammed cabinet, speech syllable, footsteps, microwave beep, crinkling bag, car, train, or other loud sound should not automatically increment the counter simply because it is loud.

Implement a lightweight real-time transient classifier using an appropriate combination of audio features.

At minimum evaluate features such as:

- RMS/short-term energy
- energy relative to adaptive background noise
- onset strength
- spectral flux
- spectral distribution
- high-frequency or mid/high-frequency energy ratio
- spectral flatness where useful
- peak-to-RMS ratio / crest factor
- transient attack time
- transient decay behavior

An FFT-based approach is appropriate, but FFT magnitude alone is not sufficient.

### Filtering

Suppress the steady low-frequency microwave/fan drone before transient scoring.

A practical starting strategy is:

- high-pass or strongly de-emphasize low-frequency energy;
- analyze useful mid/high-frequency transient content;
- maintain a separate low-frequency measurement for detecting microwave operation.

Do not assume every microwave produces an identical 50/60 Hz signature.

The algorithm must adapt to the observed baseline.

### Adaptive background model

During the beginning of the session, establish the acoustic background produced by:

- the microwave motor;
- transformer/inverter behavior;
- fan;
- room ambience.

Do not include obvious transient events in the noise estimate.

Continue updating the noise estimate slowly during the session using frames that are not classified as transient events.

Pop thresholds should therefore be expressed substantially in relation to the measured noise floor rather than fixed microphone amplitudes.

This is necessary because microphone gain, phone distance, kitchens, microwave loudness, and acoustic reflections vary.

---

# 10. Pop Event Debouncing

One actual popcorn pop may produce several high-energy analysis frames.

Those frames must represent one pop event, not several pops.

Implement onset/local-maximum detection and merge contiguous transient frames into a single acoustic event.

At the same time, popcorn can produce legitimate rapid successive pops.

Do not use such a long refractory period that rapid popping is collapsed into one event.

Use a short configurable minimum-event-separation value and test rapid double-pop scenarios.

The displayed counter must be labeled:

**Detected Pops**

or

**Pop Events**

Do NOT label it “Kernels Popped.”

It is not possible to guarantee a one-to-one relationship between acoustic events and physical kernels.

---

# 11. Pop Confidence

Each acoustic event should internally have a confidence or transient score.

Only sufficiently strong events should enter the pop sequence used for doneness detection.

Debug builds should make it possible to inspect information such as:

- timestamp
- transient score
- RMS
- noise floor
- spectral flux
- dominant/summary spectral characteristics
- accepted/rejected status

This debug information should not clutter the release UI.

---

# 12. Popcorn Lifecycle State Machine

Implement an explicit session state machine.

Use states equivalent to:

### CALIBRATING
Collecting initial acoustic information.

### WAITING
Microwave appears to be operating, but meaningful popping has not begun.

### RAMPING_UP
Pop activity has started and is increasing.

### ACTIVE
Sustained popcorn activity has positively established that the bag entered its primary popping phase.

### DECLINING
Pop frequency has fallen substantially relative to the observed peak.

### DONE
Doneness confidence has crossed the required threshold.

### STOPPED
Microwave operation appears to have stopped or the user manually ended the session.

### INTERRUPTED / ERROR
Microphone access, audio input, lifecycle behavior, or another condition made the session unreliable.

The exact internal enum names may differ, but retain these semantics.

---

# 13. Preventing Early False “Done” Decisions

This is a non-negotiable requirement.

Kernel Panic must never decide that popcorn is done simply because little or no popping has occurred.

Before ACTIVE has been positively observed:

`DONE = impossible`

Therefore:

- 30 seconds of silence must not mean done.
- 60 seconds of silence must not mean done.
- 90 seconds of microwave drone must not mean done.
- one random early pop followed by silence must not mean done.
- one 2-second gap must not mean done.
- one 3-second gap must not mean done.

The application should first observe the characteristic rising portion of the popcorn cycle and a sustained active popping period.

This is substantially more reliable than using an arbitrary minimum elapsed time.

Elapsed time and total detected events may be used as secondary sanity checks, but they should not be the primary definition of the popcorn lifecycle.

---

# 14. Establishing the Active Phase

Maintain rolling measurements including:

- pop events per second;
- pop events over approximately 5-second and 10-second windows;
- inter-pop intervals;
- smoothed inter-pop interval;
- peak observed popping rate.

Transition into ACTIVE only after sustained pop activity indicates that real popping is underway.

Use both cumulative evidence and recent activity so that a few isolated false positives cannot establish ACTIVE.

Centralize the initial thresholds in `DetectorConfig`.

Reasonable initial parameters should be chosen and then validated against the automated fixtures described later.

The implementation should favor requiring convincing evidence of active popping over prematurely entering ACTIVE.

---

# 15. Detecting the Declining Phase

Once ACTIVE has occurred, track the relationship between current pop rate and the peak pop rate observed during the session.

The system should recognize the characteristic curve:

quiet  
→ occasional pops  
→ rapidly increasing pop rate  
→ sustained high pop rate  
→ decreasing pop rate  
→ increasingly large intervals  
→ done

DECLINING should be inferred using multiple signals rather than one gap.

Useful signals include:

- recent pop rate is substantially below peak pop rate;
- recent pop rate has a negative trend;
- rolling inter-pop interval is increasing;
- several recent intervals are long;
- pop activity remains sparse for several seconds.

---

# 16. Doneness Decision

The application's primary doneness heuristic should combine:

1. ACTIVE phase has definitely occurred.
2. Pop activity has subsequently declined materially from its observed peak.
3. Recent inter-pop intervals have become consistently large.
4. Sparse popping persists long enough to reject a single outlier interval.
5. The input signal still appears valid.

A single interval must never trigger doneness.

Use a robust recent interval statistic such as:

- median;
- trimmed mean;
- rolling mean with outlier handling.

A good initial implementation can use approximately the last 4–5 accepted inter-pop intervals.

A reasonable initial completion region is around the commonly recommended approximately 1.5–2.0 second spacing between pops, but tune this as part of testing rather than treating exactly 2.000 seconds as a magical boundary.

For example, a completion decision may require a combination such as:

- ACTIVE was previously established;
- current rolling pop rate is far below the session peak;
- recent interval median is approximately 1.75–2 seconds or greater;
- multiple recent intervals individually indicate sparse popping;
- the sparse state persists rather than resolving immediately back into rapid popping.

Also support the case where active popping has clearly occurred and then essentially stops completely.

Do not require another pop to occur merely so that a long interval can be measured indefinitely.

After ACTIVE, an extended absence of pop events can itself become strong evidence of completion, provided the audio input remains healthy.

Implement this as a confidence/state decision rather than a fragile single condition.

---

# 17. Detection Confidence and Hysteresis

Avoid state oscillation.

For example, do not rapidly alternate:

ACTIVE → DECLINING → ACTIVE → DECLINING

because of individual pops.

Use:

- rolling windows;
- hysteresis;
- minimum state durations where appropriate;
- smoothed values.

Similarly, once DONE is confidently reached, do not revert from DONE because another late kernel pops.

Late isolated pops after DONE may increment the detected-pop count but should not cancel the alert.

---

# 18. Signal Health

Detect situations in which microphone input becomes unreliable.

Examples:

- microphone unexpectedly returns silence;
- another application takes control of audio input;
- microphone permission disappears;
- the user covers or disconnects the relevant microphone;
- Android audio recording fails.

Do not interpret technical microphone failure as popcorn being done.

If the detector cannot confidently hear the environment, show a concise warning such as:

**Having trouble hearing the microwave**

and do not manufacture a doneness decision from missing audio.

---

# 19. Microwave Operation / Stop Detection

The application cannot actually access the microwave's door sensor.

Therefore do NOT claim internally or in the UI that Kernel Panic literally detects the door opening.

Instead, infer whether the microwave appears to have stopped operating acoustically.

Track the longer-duration acoustic signature of the microwave separately from the short transient pop detector.

After a stable operating baseline has been established, a sustained significant drop in the microwave/fan noise signature can indicate that the microwave has stopped.

This may happen because:

- the user opened the door;
- the user pressed Stop;
- the microwave timer expired.

All are acceptable indications that the cooking session has stopped.

Use language such as:

**Microwave stopped**

rather than:

**Door opened**

Manual Stop must always remain available because acoustic microwave-stop detection cannot be guaranteed across every appliance.

---

# 20. Behavior After Doneness

Do not immediately terminate microphone monitoring when DONE is detected.

The application needs to determine whether the microwave continues running.

At DONE:

- change the primary visual state to green;
- display large text:

**POPCORN IS DONE!**

- trigger strong haptic feedback;
- speak “Popcorn is done” using Android text-to-speech where available;
- provide an audible fallback alarm/tone if speech is unavailable.

If the microwave appears to continue operating after DONE, progressively escalate the warning.

Example progression:

### DONE
Green.

**POPCORN IS DONE!**

### WARNING
After a short configurable grace period while microwave operation continues:

Yellow.

**TAKE IT OUT!**

Repeat appropriate haptic/audio feedback.

### CRITICAL
If microwave operation continues substantially longer:

Red.

**STOP THE MICROWAVE!**

Repeat the alert periodically without creating an uncontrollable audio loop.

Use reasonable configurable timing values.

If microwave operation ceases, stop escalating and finalize the session.

Do not rely on color alone. Every state must also have text and, where appropriate, audio/haptic feedback.

---

# 21. Main UI

The visual design should be extremely simple.

Do not turn this into a dashboard.

## Home screen

Include:

- title: **Kernel Panic**
- animated popcorn character
- one large central microphone/listening button
- minimal helper text
- a small menu button

The primary call to action should dominate the screen.

Suggested button state:

microphone icon  
**START LISTENING**

The home screen should not display settings, forms, accounts, tabs, or configuration.

---

# 22. Popcorn Character

Create a simple original popcorn mascot.

Do not use copyrighted character artwork.

Prefer drawing it directly using Compose Canvas/vector shapes so the application does not depend on externally licensed imagery.

The character should subtly reflect the current state:

### Idle
Neutral/happy.

### Waiting
Waiting/sleepy/curious.

### Ramping up
Beginning to shake.

### Active
Energetic bouncing/shaking.

### Declining
Excited/anticipatory.

### Done
Happy/celebratory.

### Warning
Concerned.

### Critical
Panicked.

This should remain charming and minimal rather than becoming a complicated animation system.

Respect Android animation accessibility settings where applicable.

---

# 23. Listening Screen

Once listening begins, the same primary screen should transition into live monitoring.

Show:

- popcorn mascot;
- session timer;
- current state, e.g. “Listening…”, “Popping!”, or “Almost done…”;
- **Detected Pops** count;
- smoothed recent interval once enough pops exist;
- live audio visualization;
- Stop button.

Do not show a fake interval during the initial phase.

Before enough accepted pops exist, show something like:

**Interval —**

rather than a meaningless number.

---

# 24. Audio Visualization

Create a lightweight real-time vertical-bar/waveform visualization driven by actual incoming audio amplitude.

The steady microwave should create relatively modest activity.

Pop transients should visibly spike.

Keep animation smooth but efficient.

This visualization is cosmetic and must not affect the DSP detector.

Do not feed heavily smoothed visualization data back into detection logic.

---

# 25. Pop History Graph

During the session, optionally display a subtle background graph showing popping activity over time.

Prefer a rate/activity curve rather than only a cumulative count because the rate curve visually communicates the popcorn lifecycle.

For example:

X-axis:
elapsed time

Y-axis:
smoothed detected pop rate

The graph should visually produce the expected hill-shaped pattern as activity increases and then decreases.

Keep it understated so the interface remains minimalist.

Do not add chart libraries unless necessary; a small Compose Canvas implementation is preferable.

---

# 26. Theme

Support Android system theme preference.

### Light mode

Use an off-white/warm neutral background rather than pure white.

### Dark mode

Use dark gray rather than pure black.

Use restrained typography and spacing.

The DONE/WARNING/CRITICAL state colors may override portions of the normal palette.

Ensure appropriate text contrast and accessibility.

---

# 27. Menu

Use a minimal top-right menu.

It should contain only:

- History
- How It Works
- Privacy
- About

Do not create a large Settings screen.

There should be essentially nothing for a normal user to configure.

---

# 28. First-Run Instructions

On first launch, provide a very brief onboarding explanation.

It should communicate approximately:

1. Place your phone on a nearby counter with the microphone unobstructed.
2. Start Kernel Panic when you start the microwave.
3. Stay nearby and follow your popcorn package instructions.

Then allow the user to continue immediately.

Do not create a multi-page onboarding carousel.

Remember that the central value proposition is simplicity.

---

# 29. Manual Stop

The listening UI must always include a clear Stop button.

Pressing Stop should immediately:

- stop microphone capture;
- stop DSP processing;
- stop alerts;
- release audio resources;
- allow the screen to sleep again;
- finalize the session.

If Stop occurs before DONE, record the session as:

**Stopped manually**

rather than pretending doneness was detected.

---

# 30. Session Summary

At session completion show a clean results screen containing:

- total elapsed time;
- detected pop events;
- time until first detected pop;
- peak popping rate;
- final smoothed/median inter-pop interval where available;
- result reason:
  - Done detected
  - Microwave stopped
  - Stopped manually
  - Interrupted

Make the main numbers playful but do not imply scientific accuracy.

Provide:

- Done / return-home action;
- History access.

---

# 31. History

Completed sessions should be saved automatically on-device.

Use Room.

Store only derived statistics, not audio.

A session model should include fields such as:

- ID
- timestamp
- total duration
- detected pop-event count
- time to first detected pop
- peak pop rate
- final interval statistic
- whether doneness was detected
- completion reason

The History screen should show previous sessions in reverse chronological order.

Allow the user to:

- view a session;
- delete an individual session;
- delete all history.

Optionally highlight simple personal records such as:

- most detected pops;
- longest session;
- fastest session that reached DONE.

Keep this playful and clearly derived from acoustic events rather than claiming exact kernel counts.

No account or synchronization is required.

---

# 32. Debug/Test Architecture

Make the detector testable without a microphone.

Define an abstraction such as:

`AudioSource`

with implementations conceptually equivalent to:

- `MicrophoneAudioSource`
- `FileAudioSource`

Both must feed the exact same downstream processing pipeline.

The core detector should accept PCM/audio frames and timestamps without knowing whether they came from:

- AudioRecord;
- a WAV file;
- a synthetic test generator.

This is essential.

---

# 33. Debug Audio Lab

Provide a DEBUG-BUILD-ONLY testing interface that is inaccessible from release builds.

This can provide:

- bundled test scenario selection;
- WAV-file detector playback;
- current detector state;
- accepted/rejected pop markers;
- detector feature values;
- pop-rate graph;
- threshold information;
- state transitions;
- completion timestamp.

Do not expose this screen in the release application.

This tooling should make DSP tuning possible without repeatedly cooking popcorn.

---

# 34. Synthetic Popcorn Test Generator

Create deterministic synthetic audio fixtures for automated testing.

Generate signals containing a combination of:

### Microwave background

A realistic steady broadband/tonal noise bed with low-frequency components and fan-like noise.

### Pop transient

Generate randomized short broadband impulses with:

- fast attacks;
- short decays;
- randomized amplitude;
- randomized spectral characteristics.

Do not make every synthetic pop identical.

### Environmental distractors

Generate or construct events approximating:

- speech-like energy;
- knocks;
- footsteps;
- crinkling;
- microwave beeps;
- distant traffic;
- sudden broadband noise;
- changing room noise.

The synthetic generator does not need to acoustically recreate a kitchen perfectly. Its purpose is deterministic stress testing of the algorithm.

---

# 35. Required Automated Audio Scenarios

At minimum create tests for these cases.

### Scenario A — Microwave only

Microwave/fan noise for several minutes.

Expected:

- essentially zero accepted popcorn events in the deterministic fixture;
- ACTIVE never reached;
- DONE never reached.

### Scenario B — Normal popcorn lifecycle

Silence/background → occasional pops → ramp-up → rapid active popping → gradual decline → sparse final popping.

Expected:

- active phase detected;
- no early DONE;
- DONE detected only during final sparse phase.

### Scenario C — Early long gap

Begin popping, insert one approximately 2–3 second gap, then resume fast popping.

Expected:

- absolutely no DONE decision from the isolated gap.

### Scenario D — Several early isolated pops

A few random pop-like transients occur long before real popping begins.

Expected:

- ACTIVE is not falsely established;
- DONE remains impossible.

### Scenario E — Fast successive pops

Generate closely spaced legitimate pop events.

Expected:

- events are not all merged into one count;
- individual transient frames from one physical synthetic event are not counted multiple times.

### Scenario F — Loud external knocks

Introduce several high-amplitude impact sounds.

Expected:

- they should usually be rejected by the pop classifier;
- they must not produce a false DONE sequence.

### Scenario G — Speech

Overlay speech-like synthetic or licensed fixture audio.

Expected:

- speech does not continuously increment the pop counter;
- speech does not trigger DONE.

### Scenario H — Low signal-to-noise ratio

Pop amplitude only moderately exceeds microwave background.

Expected:

- detector remains reasonably sensitive;
- ACTIVE can still be established.

### Scenario I — Microphone/input failure

Audio becomes zero or invalid during the session.

Expected:

- do not declare popcorn done;
- transition to an interrupted/signal-health state.

### Scenario J — Microwave stops early

Microwave signature disappears before an active cycle completes.

Expected:

- stop/finalize appropriately;
- do not claim the popcorn was done.

### Scenario K — Microwave stops after DONE

Expected:

- DONE alert occurs;
- stop detection subsequently finalizes the session.

### Scenario L — Continued microwave operation after DONE

Expected:

- DONE;
- warning;
- critical warning if operation continues.

---

# 36. Detector Regression Tests

The detector must be deterministic when supplied deterministic PCM fixtures.

Unit tests should verify:

- exact or bounded pop-event counts;
- expected state transitions;
- absence of premature DONE;
- approximate expected DONE timestamp;
- correct behavior when no popcorn exists;
- correct behavior after interrupted audio.

Any future change to DSP thresholds should therefore reveal regressions immediately.

---

# 37. Real Audio Testing

Synthetic audio is necessary but not sufficient.

Provide a mechanism in debug builds for processing real WAV recordings through the exact same detector.

For initial development:

- use synthesized fixtures first;
- optionally use legitimately licensed/public-domain popcorn recordings if available;
- do not copy or redistribute copyrighted YouTube audio.

For manual experimentation, playing a popcorn video or recording through a separate speaker can be used as a rough smoke test, but do not treat speaker playback as equivalent to a real microwave environment.

Ultimately the detector should be verified using several actual popcorn cooking sessions because:

- microwave acoustic signatures differ;
- phone placement differs;
- kitchens differ;
- popcorn brands differ;
- microphone processing differs.

The architecture should make threshold tuning from these recordings straightforward.

A debug-only explicit audio-recording utility may be created to capture real test sessions locally for development. It must not exist in the release build.

---

# 38. Initial Detector Performance Goals

Optimize for avoiding premature doneness above everything else.

A late alert by a second or two is substantially preferable to announcing DONE in the middle of active popping.

For deterministic automated fixtures:

- microwave-only fixture: zero DONE decisions;
- early-gap fixture: zero premature DONE decisions;
- isolated-transient fixture: zero DONE decisions;
- speech/distractor fixture: zero DONE decisions;
- normal popcorn fixture: DONE only after the active and declining phases;
- low-SNR popcorn fixture: active cycle still detected;
- microphone-failure fixture: zero false DONE decisions.

The system should ideally announce completion within a few seconds of the point where sustained sparse popping satisfies the configured doneness criteria.

Do not manipulate synthetic fixtures merely to make a weak detector pass.

---

# 39. Tuning Configuration

Create a centralized structure such as `DetectorConfig`.

Include values for concepts such as:

- analysis frame size;
- overlap/hop size;
- frequency bands;
- adaptive-noise time constants;
- transient-score threshold;
- event merge duration;
- event minimum separation;
- rolling rate windows;
- minimum evidence required for ACTIVE;
- decline ratio relative to peak rate;
- interval smoothing window;
- target sparse-pop interval;
- completion confidence threshold;
- no-pop completion duration;
- microwave-stop energy drop;
- microwave-stop persistence duration;
- warning delay;
- critical-warning delay.

Document each parameter.

Do not expose these as consumer-facing Settings.

Debug builds may expose them for tuning.

---

# 40. UI State Accuracy

The UI must render actual detector state.

Do not make the mascot or status text advance based only on elapsed time.

For example:

**Heating up…**
should correspond to WAITING/RAMPING.

**Popping!**
should correspond to ACTIVE.

**Almost done…**
should correspond to DECLINING.

**POPCORN IS DONE!**
must correspond to DONE.

The UI should therefore visibly demonstrate the detector functioning during a YouTube recording of the project.

---

# 41. Resource Management

Handle AudioRecord and application lifecycle correctly.

Requirements:

- release microphone resources when session ends;
- stop processing coroutines;
- prevent multiple simultaneous recording sessions;
- handle configuration changes safely;
- avoid memory leaks;
- avoid continuously allocating large buffers in the audio loop;
- avoid performing FFT/DSP work on the main thread;
- restore keep-screen-on behavior after the session ends;
- stop TTS/alarm playback when appropriate;
- release text-to-speech resources.

The application should remain responsive during active audio processing.

---

# 42. Accessibility

Provide:

- meaningful TalkBack labels;
- adequate touch targets;
- appropriate color contrast;
- state text in addition to color;
- haptic/audio/visual feedback for DONE;
- reduced-motion-friendly behavior where Android accessibility settings indicate animations should be reduced.

Do not rely exclusively on the animated character or graph to communicate state.

---

# 43. Error Handling

Gracefully handle:

- microphone permission denied;
- microphone unavailable;
- AudioRecord initialization failure;
- unsupported sample rate;
- interrupted recording;
- inability to initialize TTS;
- Room/persistence errors;
- device rotation/configuration changes;
- user leaving the application;
- empty history;
- detector receiving invalid PCM frames.

No ordinary error should crash the application.

---

# 44. About / How It Works

Provide a concise How It Works screen explaining in plain language:

Kernel Panic does not simply run a timer. It listens for short sounds consistent with popcorn pops, watches the popping rate increase and then decrease, and alerts the user after the popping has slowed consistently.

Do not claim AI or machine learning unless the implementation actually contains such a model.

If this implementation uses deterministic digital signal processing and statistical heuristics, describe it accurately.

Avoid technical jargon on the consumer-facing screen.

---

# 45. Privacy Screen

Explain clearly:

- microphone access is required only while listening;
- audio is analyzed on the device;
- release builds do not save microphone recordings;
- audio is not uploaded;
- there is no account;
- session statistics are stored locally;
- history can be deleted by the user.

---

# 46. Branding

Use:

**Kernel Panic**

as the working app name.

Create a simple original icon featuring a popcorn kernel/popcorn character or similarly appropriate imagery.

Avoid:

- Shazam branding;
- Shazam visual motifs;
- copyrighted characters;
- trademarked third-party assets.

Do not spend excessive engineering effort on branding before the core detector works.

---

# 47. Code Quality

The project should be understandable to another Android engineer.

Use:

- sensible package organization;
- small cohesive classes;
- comments for DSP reasoning where necessary;
- descriptive names;
- immutable models where reasonable;
- proper coroutine lifecycle management;
- StateFlow/Flow for detector/session state.

Avoid unnecessary abstractions and enterprise architecture.

This is a small application.

The architecture should serve correctness and testability rather than complexity.

---

# 48. Documentation

Add a README containing:

## What Kernel Panic Does

Short product explanation.

## Architecture

Explain:

AudioRecord  
→ PCM frames  
→ DSP feature extraction  
→ transient classifier  
→ accepted pop events  
→ rolling statistics  
→ popcorn lifecycle state machine  
→ doneness decision  
→ UI state

## Running the App

Build/install instructions.

## Testing

Explain unit tests and synthetic fixtures.

## Debug Audio Lab

Explain how to run WAV and synthetic fixtures.

## Detector Tuning

Explain `DetectorConfig`.

## Privacy

Explain the offline/no-recording design.

## Limitations

Explicitly mention environmental variability and that Kernel Panic should not be treated as a safety device.

Also include `PRIVACY.md`.

---

# 49. Definition of Done

Do not consider the task complete until all of the following are true:

### Application

- Android project builds successfully.
- App launches successfully.
- Kernel Panic home screen renders correctly.
- System light/dark theme works.
- Start button functions.
- Microphone permission flow functions.
- Real microphone PCM is captured.
- Audio visualization uses real microphone input.
- Actual DSP logic processes live audio.
- Pop events are detected from PCM rather than mocked.
- Microwave drone does not continuously increment the pop counter.
- Early silence cannot produce DONE.
- One isolated long inter-pop interval cannot produce DONE.
- ACTIVE must occur before DONE.
- Decline/sparse popping produces DONE.
- DONE produces visual alert.
- DONE produces haptic alert.
- DONE produces spoken or audible alert.
- Continued microwave operation escalates the warning.
- Manual Stop works.
- Audio resources are released.
- Session summary works.
- History persists locally.
- History deletion works.
- No account exists.
- No backend exists.
- Release app does not store raw microphone audio.
- Release app does not transmit microphone audio.
- No unnecessary sensitive permissions exist.

### Testing

- Detector can run independently of AudioRecord.
- Synthetic audio generator exists.
- Required synthetic scenarios exist.
- Unit tests cover false-positive scenarios.
- Unit tests cover normal doneness.
- Unit tests cover early 2–3 second outlier gaps.
- Unit tests cover rapid pops.
- Unit tests cover microwave-only noise.
- Unit tests cover signal loss.
- Debug WAV/file playback uses the production detection pipeline.
- Tests pass.

### Quality

- No required TODOs remain.
- No placeholder functionality remains.
- No mocked production behavior remains.
- Lint/static checks are addressed.
- README is complete.
- Privacy documentation is complete.

---

# 50. Final Validation Procedure

When implementation is complete:

1. Run the full unit-test suite.
2. Run lint/static analysis.
3. Build the debug APK.
4. Build the release variant far enough to identify release-specific compile/resource problems.
5. Verify the release manifest contains only required permissions.
6. Verify no `INTERNET` permission is present unless an unavoidable dependency demonstrably requires it; remove such dependencies instead wherever practical.
7. Verify raw audio is never persisted in release builds.
8. Run synthetic normal-popcorn playback through the detector.
9. Run microwave-only playback through the detector.
10. Run the early-long-gap regression case.
11. Confirm DONE cannot occur before ACTIVE.
12. Confirm the Debug Audio Lab is excluded from the release UI/build behavior.
13. If an Android device is connected, install the application and verify real microphone permission and live AudioRecord capture.
14. Fix any discovered issues.

Then provide a concise implementation report describing:

- architecture created;
- pop-detection approach;
- doneness algorithm;
- important thresholds;
- tests implemented;
- test results;
- files added or modified;
- known limitations;
- exact steps for running the application on a Pixel 8.

Do not simply tell me what should be implemented.

Implement it.