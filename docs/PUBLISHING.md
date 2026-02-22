# Publishing

## Local publish (for app integration testing)
```bash
./gradlew :engine:publishReleasePublicationToMavenLocal
```

Then consume from `mavenLocal()`:
```kotlin
repositories {
    mavenLocal()
    google()
    mavenCentral()
}
```

## Artifact metadata
- Group: from `GROUP` in `gradle.properties`
- Version: from `VERSION_NAME` in `gradle.properties`
- Artifact: `crossaudio-engine` by default

## Configure project metadata before public release
Set these Gradle properties (for CI or `~/.gradle/gradle.properties`):
- `POM_URL`
- `POM_SCM_URL`
- `POM_SCM_CONNECTION`
- `POM_SCM_DEV_CONNECTION`
- `POM_LICENSE_NAME`
- `POM_LICENSE_URL`

Defaults are placeholders and should be replaced with your real repository details before publishing to a shared registry.

## Typical CI release flow
1. Set `VERSION_NAME` to the release version.
2. Run `./gradlew :engine:test`.
3. Publish to your target repository with your repository-specific plugin/credentials.
4. Tag release in git.
