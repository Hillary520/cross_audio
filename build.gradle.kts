// Root build file. Keep it thin; module build files own configuration.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

allprojects {
    group = findProperty("GROUP") as? String ?: "com.crossaudio"
    version = findProperty("VERSION_NAME") as? String ?: "0.1.0-SNAPSHOT"
}
