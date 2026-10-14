plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mwmods.wardtube"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mwmods.wardtube"
        minSdk = 24
        targetSdk = 35
        versionCode = 73
        versionName = "2.6.27"

        // The app is translated into English and Arabic only; the libraries (Material, AppCompat...) carry strings for
        // dozens of other languages that would never be shown. Other phone languages fall back to the default (English).
        // (AGP 8.7 uses resourceConfigurations; the newer localeFilters name does not exist in this version.)
        resourceConfigurations += listOf("ar", "en")
    }

    // Release signing. The keystore never lives in the repository: it is given through environment variables
    // (GitHub Actions secrets) or ~/.gradle/gradle.properties. Without it the release APK is simply unsigned.
    val keystoreFile = System.getenv("KEYSTORE_FILE") ?: (findProperty("keystoreFile") as String?)
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: (findProperty("keystorePassword") as String?)
                keyAlias = System.getenv("KEY_ALIAS") ?: (findProperty("keyAlias") as String?)
                keyPassword = System.getenv("KEY_PASSWORD") ?: (findProperty("keyPassword") as String?)
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // yt-dlp / ffmpeg ship as native libraries that must be extracted to disk to be executable.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // The bundled Python + ffmpeg are large; per-ABI APKs keep each one smaller. Only the two ABIs real phones use
    // are built (x86 / x86_64 are emulators and rare devices), which also halves the universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    lint {
        // Only a few of the bundled translations exist for the newer strings.
        disable += "MissingTranslation"
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Self-contained downloader: yt-dlp + ffmpeg run inside the app (no external website involved).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
