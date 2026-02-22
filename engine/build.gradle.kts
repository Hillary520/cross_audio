import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "com.crossaudio.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        aarMetadata {
            minCompileSdk = 35
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = (findProperty("POM_ARTIFACT_ID") as? String) ?: "crossaudio-engine"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set((findProperty("POM_NAME") as? String) ?: "CrossAudio Engine")
                description.set(
                    (findProperty("POM_DESCRIPTION") as? String)
                        ?: "Android audio playback engine with crossfade, queueing, cache, and streaming/DRM primitives.",
                )
                url.set((findProperty("POM_URL") as? String) ?: "https://github.com/example/cross_audio")

                licenses {
                    license {
                        name.set((findProperty("POM_LICENSE_NAME") as? String) ?: "The Apache License, Version 2.0")
                        url.set((findProperty("POM_LICENSE_URL") as? String) ?: "https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                scm {
                    url.set((findProperty("POM_SCM_URL") as? String) ?: "https://github.com/example/cross_audio")
                    connection.set((findProperty("POM_SCM_CONNECTION") as? String) ?: "scm:git:https://github.com/example/cross_audio.git")
                    developerConnection.set(
                        (findProperty("POM_SCM_DEV_CONNECTION") as? String)
                            ?: "scm:git:ssh://git@github.com/example/cross_audio.git",
                    )
                }
            }
        }
    }
}
