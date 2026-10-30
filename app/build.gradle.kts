plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.hapanel"
    compileSdk = 34
    defaultConfig { applicationId = "dev.hapanel"; minSdk = 26; targetSdk = 33; versionCode = 10; versionName = "2.8" }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Stały klucz wydań: plik podaje GitHub Actions z sekretu HAPANEL_KEYSTORE (nigdy w repozytorium).
        create("release") {
            val ks = System.getenv("HAPANEL_KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storeType = "pkcs12"
                storePassword = System.getenv("HAPANEL_KEYSTORE_PASS") ?: "hapanel"
                keyAlias = "hapanel"
                keyPassword = System.getenv("HAPANEL_KEYSTORE_PASS") ?: "hapanel"
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("HAPANEL_KEYSTORE_FILE") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    // rozpoznawanie słowa „Aurora” na tablecie (offline)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
