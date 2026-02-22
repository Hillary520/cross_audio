# Getting Started

## Requirements
- Android `minSdk 23`
- Kotlin + Coroutines
- App has a foreground-service strategy for playback

## Dependency
```kotlin
dependencies {
    implementation("com.crossaudio:crossaudio-engine:0.2.0")
}
```

## Quick setup
```kotlin
val engine = PlatformPlayerEngine(
    context = applicationContext,
    delegate = CrossAudioEngine(applicationContext),
)
```

## Queue and play
```kotlin
val items = listOf(
    MediaItem(uri = Uri.parse("https://cdn.example.com/track1.mp3"), title = "Track 1"),
    MediaItem(uri = Uri.parse("https://cdn.example.com/track2.mp3"), title = "Track 2"),
)

engine.setQueue(items, startIndex = 0)
engine.setCrossfadeDurationMs(8_000)
engine.play()
```

## Lifecycle
- Call `pause()`/`play()` from UI lifecycle events as needed.
- Call `release()` when the owner is permanently destroyed.

## Recommended production setup
- Host the engine in a foreground `Service` and expose transport controls via `MediaSession`.
- Use `CrossAudioPlaybackService` if you want the included service integration path.
- Use `CacheEngine` and `DrmEngine` only when your app has a clear offline-content policy.
