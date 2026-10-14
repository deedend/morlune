// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.morlune.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.morlune.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    buildFeatures { buildConfig = true }

    // Optional local/CI signing. F-Droid can build without these environment variables
    // and apply its own repository signature to the release APK.
    val releaseKeystore = System.getenv("MORLUNE_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("MORLUNE_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("MORLUNE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("MORLUNE_KEY_PASSWORD")
    val releaseSigningReady = listOf(
        releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    if (releaseSigningReady) {
        signingConfigs {
            create("morluneRelease") {
                storeFile = file(requireNotNull(releaseKeystore))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("morluneRelease")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // No signing/dependency blobs in the APK: friendlier to F-Droid reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
