plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma: le chiavi arrivano da variabili d'ambiente (mai scritte nel repository).
val ksFile = System.getenv("HP_KEYSTORE")
val webUrl = "https://taipia925-sketch.github.io/HOME-POKER/"

android {
    namespace = "it.homepoker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.homepoker.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("HP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HP_VERSION_NAME") ?: "1.0"
        manifestPlaceholders["hostName"] = "taipia925-sketch.github.io"
        buildConfigField("String", "WEB_URL", "\"$webUrl\"")
        buildConfigField("String", "WIDGET_API", "\"https://nqlywzjhxykrfyrpkjdw.supabase.co/functions/v1/hpl-widget\"")
    }

    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = file(ksFile)
            storePassword = System.getenv("HP_KEYSTORE_PASS")
            keyAlias = System.getenv("HP_KEY_ALIAS") ?: "homepoker"
            keyPassword = System.getenv("HP_KEY_PASS") ?: System.getenv("HP_KEYSTORE_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
