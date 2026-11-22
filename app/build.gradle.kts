plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.homepad"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.homepad"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin") }

    lint {
        // Only the API-level check is fatal here. minSdk is 30 while compileSdk is 36, so a call
        // above minSdk is a NoSuchMethodError waiting for the one code path nobody tested -- that
        // is exactly how Display.getRoundedCorner() took the launcher down when an icon was
        // tapped, and how setPendingIntentBackgroundActivityStartMode() would have taken down the
        // notification shade. Run `gradlew lintRelease` after touching platform APIs.
        checkOnly += setOf("NewApi")
        abortOnError = true
    }
}

kotlin { jvmToolchain(17) }
