# API Guide

## Core interface
`PlayerEngine` is the base API:
- `setQueue(items, startIndex)`
- `play()`, `pause()`, `stop()`
- `seekTo(positionMs)`
- `skipNext()`, `skipPrevious()`
- `setCrossfadeDurationMs(durationMs)`
- `setRepeatMode(mode)`
- `setVolume(volume)`
- `release()`

## Optional capabilities
Cast `PlayerEngine` to capability interfaces when needed:
- `QueueMutableEngine`
- `ShuffleEngine`
- `ObservableEngine`
- `CacheEngine`
- `AdaptiveStreamingEngine`
- `DrmEngine`
- `AudioProcessingEngine`
- `TelemetryEngine`

## MediaItem fields
`MediaItem` supports:
- `uri`
- `title`, `artist`
- `headers` (per-item HTTP headers)
- `cacheKey`, `cacheGroupKey`
- `sourceType` (`AUTO`, `PROGRESSIVE`, `HLS`, `DASH`)
- `drm` (`DrmRequest`)
- `qualityHint`

## Streaming and quality
- `setStreamingConfig(config)` configures segment pipeline and ABR bounds.
- `setQualityCap(cap)` limits maximum quality (`LOW`, `MEDIUM`, `HIGH`, `AUTO`).
- `currentQuality()` returns active quality info.

## DRM
- Set global DRM policy with `setDrmConfig`.
- Acquire/release offline licenses via `DrmEngine`.
- License URL must be `https://`.
- `retryCount` and `requestTimeoutMs` are honored for license requests.

## Events and telemetry
- `ObservableEngine.events` emits queue/focus/cache/playback events.
- `TelemetryEngine.telemetry` emits ABR/DRM/crossfade/cache telemetry events.
- `TelemetryEngine.telemetrySnapshot()` returns a current aggregate snapshot.
