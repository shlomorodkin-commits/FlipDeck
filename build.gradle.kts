plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flipdeck.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flipdeck.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("flipdeck.keystore")
            storePassword = "flipdeck123"
            keyAlias = "flipdeck"
            keyPassword = "flipdeck123"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
