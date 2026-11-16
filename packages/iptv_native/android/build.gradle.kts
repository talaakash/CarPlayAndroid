group = "com.example.iptv_native"
version = "1.0-SNAPSHOT"

buildscript {
    val kotlinVersion = "2.4.20"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.3.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.iptv_native"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()

                it.outputs.upToDateWhen { false }

                it.testLogging {
                    events("passed", "skipped", "failed", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Media3: one ExoPlayer instance owned by a MediaLibraryService, shared by the
    // Flutter UI (through a MediaController) and Android Auto.
    val media3 = "1.11.1"
    api("androidx.media3:media3-exoplayer:$media3")
    api("androidx.media3:media3-exoplayer-hls:$media3")
    // IPTV playlists contain DASH / SmoothStreaming / RTSP entries too. Without these
    // modules DefaultMediaSourceFactory throws while building the queue, which made the
    // entire play request fail silently.
    api("androidx.media3:media3-exoplayer-dash:$media3")
    api("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    api("androidx.media3:media3-exoplayer-rtsp:$media3")
    api("androidx.media3:media3-session:$media3")
    // Presentation effect: letterbox (Fit) / crop (Fill) on the car Surface, which cannot scale by itself.
    implementation("androidx.media3:media3-effect:$media3")
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("androidx.core:core:1.16.0")
    // CarConnection: platform-confirmed Android Auto / Automotive OS connection state.
    implementation("androidx.car.app:app:1.7.0")
}
