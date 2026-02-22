# Lightweight Android Music Engine With Crossfade (Plan)

## Goal
Build a small Android library (not a full app) that provides “music player engine” essentials with first-class **crossfade** support, without pulling in ExoPlayer/Media3 as a dependency.

**Primary use case**: local files + simple HTTP progressive streams, reliable background playback, predictable behavior, easy embedding into apps.

## Non-Goals (initially)
- DRM (Widevine), DASH/HLS adaptive streaming, casting, video.
- A full “media framework replacement” (that is a multi-year scope).
- Bit-perfect audiophile features; prioritize correctness, stability, and battery-aware playback.

## Requirements (MVP)
- Queue/playlist: add, remove, reorder, skip, repeat, shuffle.
- Reliable playback: play/pause/seek, state machine, error recovery.
- Audio focus + noisy intents (headphones unplug) handling.
- MediaSession integration: lock screen controls, Bluetooth controls, notifications.
- Gapless playback (no audible gaps between tracks when crossfade is 0s).
- Crossfade: configurable duration (e.g., 0–12s), constant-power fade curves.
- Basic buffering for local and HTTP; smooth under normal jitter.
- Supported formats: whatever `MediaCodec` can decode on-device (AAC/MP3/Opus/FLAC depending on device).

## Stretch Goals (post-MVP)
- Loudness normalization (ReplayGain-like) and/or limiter to prevent clipping during crossfade.
- Equalizer, playback speed/pitch (time-stretch), silence skip.
- Download/cache for HTTP.
- Advanced preloading heuristics (smart crossfade timing based on track endings).
- Multi-output (wired/Bluetooth) quirks handling and device-specific workarounds catalog.

## Key Design Decision
Crossfade is fundamentally **mixing two PCM streams**. Most Android “player wrappers” don’t do this because they rely on a single decoded stream feeding a renderer.

So the engine must:
1. Decode Track A to PCM.
2. Decode Track B to PCM early (pre-roll).
3. Overlap the last N seconds of A with the first N seconds of B.
4. Mix with a fade curve and render to `AudioTrack`.

## Proposed Architecture (Library)
Name placeholder: `cross_audio_engine` (module name to be decided).

### Public API (Kotlin)
- `PlayerEngine`
  - `setQueue(list: List<MediaItem>)`
  - `play() / pause() / stop() / seekTo(positionMs)`
  - `skipNext() / skipPrevious()`
  - `setCrossfade(durationMs)`
  - `setRepeatMode(...) / setShuffle(enabled)`
  - `state: StateFlow<PlayerState>`
  - `events: Flow<PlayerEvent>` (errors, track transitions, audio focus changes)
- `MediaItem`
  - `uri`, optional `metadata` (title/artist/album/artUri), optional `durationHintMs`

### Internal Components
- `EngineStateMachine`
  - single owner of “what should be playing”, transitions, and recovery rules.
- `Decoder` (per-track)
  - based on `MediaExtractor` + `MediaCodec`
  - outputs uniform PCM format into a ring buffer.
- `PcmRingBuffer`
  - lock-free-ish (single producer/single consumer) buffer for PCM frames.
- `Mixer`
  - consumes PCM from current and next decoder during overlap window
  - applies fade curves and writes mixed PCM frames into the render buffer.
  - optional limiter (post-MVP).
- `Renderer`
  - `AudioTrack` in `MODE_STREAM`, driven by a high-priority audio thread.
  - handles underruns gracefully and surfaces metrics.
- `PreloadController`
  - decides when to start decoding next track:
    - immediately for gapless/crossfade, but bounded by memory and network type.
- `PlatformIntegration`
  - Audio focus, media button receiver, MediaSession, notification channel.

## Audio Pipeline Details (How Crossfade Works)
### PCM Format
Standardize internal PCM to one format to simplify mixing:
- Prefer `PCM_FLOAT` stereo at 48kHz (or device native sample rate if queried).
- If a decoded track differs in sample rate/channel count:
  - initial approach: request/accept `AudioTrack` resampling and downmix/upmix as needed.
  - post-MVP: add explicit resampler for consistent mixing quality.

### Fade Curves
Use constant-power curves to avoid “dip” in perceived loudness:
- `gainA = cos(t * pi/2)`
- `gainB = sin(t * pi/2)`
Where `t` goes 0→1 across the crossfade duration.

### Timing Model
Crossfade window starts at:
- `trackEndTime - crossfadeDuration` (bounded to >= 0)
The engine must know (or estimate) track duration. For local files, extract duration via metadata or extractor; for streams, crossfade may be disabled or based on heuristics.

### Avoiding Clicks and Gaps
- Always mix on frame boundaries.
- Apply a tiny ramp (e.g., 5–10ms) on abrupt transitions (pause/seek) to avoid clicks.
- Keep render buffer ahead (e.g., 100–300ms) but not so large that seeking feels laggy.

## Development Plan (Phases)

### Phase 0: Repo + Scaffolding
Deliverables:
- Gradle project with:
  - `:engine` Android library module (Kotlin)
  - `:sample` app for manual testing
- CI-ready scripts: `./gradlew test`, `./gradlew lint`
- Basic docs: `README.md` with usage snippet

Decisions:
- Minimum SDK: 23+ (adjust if you need lower)
- Target: latest stable Android SDK

### Phase 1: Minimal Single-Track Playback
Deliverables:
- `AudioTrack` renderer thread
- Single decoder feeding PCM into renderer
- Basic controls: play/pause/seek/stop
- Observability: logs + counters (underruns, decode time)

Acceptance:
- Plays common local MP3/AAC on a real device without glitches.

### Phase 2: Queue + MediaSession Integration
Deliverables:
- Queue model + state machine
- Track transitions without crossfade (hard cut)
- Audio focus + noisy intent
- MediaSession + notification controls

Acceptance:
- Bluetooth headset controls work (play/pause/next/prev).

### Phase 3: Gapless (Crossfade = 0)
Deliverables:
- Preload next decoder early
- Seamless transition at sample boundary

Acceptance:
- No audible gap between consecutive tracks (device-dependent tolerance).

### Phase 4: Crossfade (Core Feature)
Deliverables:
- Dual-decoder overlap
- Mixer with constant-power curve
- Configurable crossfade duration and enable/disable
- Clip prevention strategy:
  - MVP: conservative headroom (e.g., multiply mix output by 0.9)
  - Post-MVP: look-ahead limiter

Acceptance:
- Audible, smooth crossfade between tracks; no stutters during overlap.

### Phase 5: Robustness, Edge Cases, Performance
Deliverables:
- Handling:
  - seeks near end/start during crossfade
  - skipping while preloading
  - decoder failures fallback (disable crossfade, continue)
  - network jitter for HTTP progressive
- Battery/perf tuning:
  - thread priorities
  - buffer sizes
  - minimizing allocations in audio thread

Acceptance:
- 1+ hour playback without runaway memory/CPU, stable state transitions.

### Phase 6: Testing Strategy
Deliverables:
- Unit tests (pure Kotlin):
  - fade curve correctness
  - mixer math (known inputs -> expected outputs)
  - state machine transitions
- Instrumentation tests:
  - decode + render smoke tests on emulator/device
- Manual test checklist:
  - focus loss/gain scenarios
  - Bluetooth connect/disconnect
  - phone call interruption

## Technology Choices (Advice)
### Recommended (MVP)
- Kotlin + coroutines/Flow for control plane (not in audio thread).
- `MediaExtractor` + `MediaCodec` for decoding (platform-native, zero big deps).
- `AudioTrack` for output (stream mode), single dedicated audio thread.
- AndroidX MediaSession (or framework MediaSession) for integration.

### Consider Later (Only If Needed)
- NDK/Oboe:
  - helps with ultra-low latency and consistent callback timing
  - adds build complexity; not necessary for typical music playback
- FFmpeg:
  - widest codec support, but heavy and often not “lightweight”
  - licensing and binary size concerns

## Risks / Unknowns (Call These Out Up Front)
- Device codec quirks: `MediaCodec` behavior varies widely across vendors.
- Accurate duration for some files/streams can be unreliable.
- Mixing two streams increases CPU; must keep math simple and allocations near-zero.
- Sample rate / channel conversion: for best crossfade quality, you eventually want explicit conversion.

## Definition of Done (v1)
- Library + sample app.
- Queue + MediaSession + focus handling.
- Gapless transitions.
- Configurable crossfade implemented via PCM mixing.
- Tests for mixer and state machine.
- Basic documentation and a stable API surface.

## Next Step
If you want me to proceed beyond this plan, answer:
1. Minimum Android SDK you want to support (e.g., 21, 23, 26).
2. Local-only vs local + HTTP progressive for v1.
3. Any must-have features besides crossfade (EQ, speed, ReplayGain, caching).

