plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.suguna.admin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.suguna.admin"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0"
    }

    signingConfigs {
        create("stable") {
            storeFile = file("suguna.keystore")
            storePassword = "sugunahome"
            keyAlias = "suguna"
            keyPassword = "sugunahome"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
