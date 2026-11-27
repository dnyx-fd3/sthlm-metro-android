import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "se.dnyx.sthlmmetro"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.dnyx.sthlmmetro"
        minSdk = 26
        targetSdk = 35
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (ciRun != null) 100 + ciRun else 10
        versionName = "2.2.0"

        buildConfigField("long", "BUILD_TIME_MS", "${System.currentTimeMillis()}L")
        buildConfigField("String", "GITHUB_REPO", "\"dnyx-fd3/sthlm-metro-android\"")
        buildConfigField("String", "RADAR_API_URL", "\"https://sthlm-radar-api.dnyashasvi18-99.workers.dev\"")
    }

    signingConfigs {
        getByName("debug") {
            val ciKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            if (ciKeystore != null && file(ciKeystore).exists()) {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASS") ?: "android"
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("SIGNING_KEY_PASS") ?: "android"
            } else {
                val repoKeystore = file("debug.keystore")
                if (repoKeystore.exists()) {
                    storeFile = repoKeystore
                    storeType = "PKCS12"
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-analytics")
}
