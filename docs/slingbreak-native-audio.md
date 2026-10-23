# SlingBreak optional native audio

## Why

The affected device reports 9,620 PCM frames as its minimum output buffer at
48 kHz (200.4167 ms). This matches the Web Audio `baseLatency` in the supplied
logs; changing `latencyHint` to `interactive` did not change it. This is evidence
about that WebView path, not a hardware latency floor or a speaker measurement.

## Integration

`SlingNativeAudioBridge` exposes a v2 capability to the bundled game in
both the standalone activity and boot overlay, with system WebView or Gecko. The web code checks the capability,
not the UA, model, or manufacturer. Without it, the existing Web Audio graph is used.

`sling_audio.cpp` opens a float-stereo AAudio callback stream with low-latency
preference, first requesting exclusive sharing and then shared if opening fails.
It lets the device select the sample rate, requests two bursts, and rejects a
reported application buffer of 50 ms or more. The negotiated settings are logged;
buffer duration is **not** end-to-end output latency.

The first gesture starts asynchronous preparation. `audio-recipes.js` is the
single source of sound recipes for both backends. `audio-native.js` renders each
unique mono voice with `OfflineAudioContext`, at the native stream's rate, and
uploads bounded float PCM samples once. Existing Web Audio remains available
during preparation. Native playback begins only after the entire bank is ready.
The one-time switch stops existing Web Audio voices, so a sound already playing
can be cut short at that transition.

The callback mixes cached voices, equal-power stereo positioning, delayed notes,
the 105 ms / 18% feedback / 16% wet echo, and the existing limiter curve. Noise is
randomly generated once per session, as in the web path. DSP interpolation and
buffer boundaries differ; waveform-identical output is not guaranteed.

Sound triggers transfer only small commands. The callback performs no file I/O,
JNI, allocations or locking. A bounded queue, voice limits, and priority stealing
prevent unbounded backlog. Request throttling still uses `performance.now()`.

Both host entry points pause/flush on backgrounding and close on destruction.
Audio focus loss mutes and clears pending sounds. Page reloads reset the sample
bank. Disconnections are closed on the control thread; the next web request falls
back to Web Audio. Underruns increase the application buffer by one burst, capped
below 50 ms; if no further bounded increase is possible, playback falls back.
Native initialization/upload/start failures also fall back. A failed native
session is not retried until the page is reloaded.

## v2 compatibility fixes

- The web recipe catalogue owns sound names; native playback validates uploaded
  sample IDs instead of the obsolete 13-name list. Boss cues no longer invalidate
  the session. Banks support 512 samples / 32 MiB and 16 voices per request.
- `setState` applies continuous gain at every nonzero game volume. Separate
  effects/UI/music buses preserve dialog cues while paused, discarding gameplay
  voices, echoes and stale queued commands.
- Music supports bounded PCM caching, seven instrument buses, delayed notes,
  filtered mix, echo, fade and stop. Evicted buffers remain alive while queued or
  playing; final PCM reclamation stays on the control thread. Bus limits are
  64 effects, 16 UI and 48 music voices.
- `playBatch` accepts up to 96 independent requests and returns one result each.
- Gecko receives the same API through a per-view random-token loopback channel,
  also for standalone play. Only named audio methods are allowed; POST sizes,
  origin/Host and bridge headers are checked. The synchronous facade matches the
  game's host ABI. PCM uploads occur during preparation/warmup, not impacts;
  actual Gecko transport latency still needs device measurement.
- Host pause/resume, reset and destruction also govern Gecko native audio.
  Channel closure revokes the capability and releases the stream.
- The build-time adapter in `StsAndroidAppBuildPlugin.kt` preserves the host's
  reported AAudio backend and relinquishes v2 ownership after native failure,
  restoring Web Audio instead of leaving output silently native-only. Changes
  are applied when packaging, not to generated/downloaded files.

## Packaging local changes

The app now fetches web sources by default. To include uncommitted sibling web
changes, add this argument to the usual build command:

```text
-PslingBreak.sourceDir=/home/apricityx/workspaces/slingbreak
```

The override accepts the repository root or its `game/` directory. Current upstream
keeps `audio-recipes.js`, `audio-native.js`, and `audio.js` under `game/src/audio/`,
with their script entries in `game/index.html`. The build packages only the web
runtime into `assets/slingbreak/`, preserving relative paths. Older flat source
layouts remain supported. No manual asset copy is needed.

## Diagnostics and verification status

With the existing audio-debug option enabled, `webview_diagnostics.log` contains
`native_audio_opened`, `native_audio_ready`, lifecycle/failure events, and a final
snapshot at close. Web JSON contains `native-ready`, `native-sound-enqueued`,
`native-fallback`, and `backend: native-aaudio` snapshots including xruns and drops.
Enqueued means accepted for rendering, not confirmed speaker output.

Host-side regression commands:

```sh
python3 -m unittest scripts.tools.tests.test_sling_audio
# Run prepareCommonRuntimeAssets first; tests use the actual packaged scripts.
node --test scripts/tools/tests/sling_audio_protocol.test.cjs
```

The C++ tests exercise the production mixer with AddressSanitizer/UBSan: gain,
pause/UI isolation, mute/flush, music/stop and voice bounds. JS tests cover v2
preparation, Boss cues, partial gain, UI, music, fallback and Gecko forwarding.
Kotlin/Java and Android native compilation have been checked.
Device verification still needs audible latency, focus/pause/resume, fidelity,
Gecko IPC latency and route-change fallback checks. The connected device's ADB
shell timed out during investigation; host tests do not prove speaker output.
