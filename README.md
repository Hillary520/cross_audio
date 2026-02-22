# cross_audio

Android audio playback library focused on PCM crossfade, queue control, caching, and streaming/DRM primitives.

## Package coordinates
- Group: `com.crossaudio`
- Artifact: `crossaudio-engine`
- Version: `0.2.0`

```kotlin
dependencies {
    implementation("com.crossaudio:crossaudio-engine:0.2.0")
}
```

## Modules
- `:engine`: Android library consumed by apps.
- `:sample`: Sample app for manual validation.

## Key capabilities
- PCM decode/playback (`MediaExtractor` + `MediaCodec` + `AudioTrack`).
- Gapless and configurable real crossfade.
- Queue mutation, repeat, and stable shuffle.
- Foreground playback service + MediaSession + notifications.
- HTTP/HTTPS playback with request headers.
- Read-through cache with pin/unpin and grouped eviction.
- HLS/DASH source detection and quality-cap selection.
- Widevine playback/offline license scaffolding.
- Optional loudness normalization and limiter.
- Playback/cache/DRM telemetry events.

## Security defaults
- DRM license requests are HTTPS-only.
- DRM request retries honor `DrmGlobalConfig.retryCount`.
- Offline license records are stored in SQLite (legacy SharedPreferences data is migrated and cleared).

## Documentation
- `docs/GETTING_STARTED.md`
- `docs/API_GUIDE.md`
- `docs/PUBLISHING.md`

## Build and test
```bash
./gradlew :engine:test :sample:assembleDebug
```
