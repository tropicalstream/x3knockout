plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.x3knockout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.x3knockout"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // The JVM tests exist to police the two-clock split and the model's forward axis, both of
        // which are pure arithmetic. Nothing under test should touch the framework — this only
        // stops an incidental android.jar stub from throwing instead of returning a default.
        unitTests.isReturnDefaultValues = true
    }
}

// Zero dependencies IN THE APK: OpenGL ES 3.0 via the framework, SFX synthesised at runtime,
// voice lines pre-rendered into assets/voice, one bundled music track. JUnit is test-only and
// never ships; ClockTest and StrokeModelTest are Phase 1's definition of done.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
