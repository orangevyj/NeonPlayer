import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.orangestudio.neonplayer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.orangestudio.neonplayer"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two signing keys, on purpose: one per distribution channel. Both property files sit at the
    // project root and neither is part of the sources (see .gitignore).
    //
    //  - keystore.properties        -> the Google Play upload key. Personal; stays on this machine.
    //  - github-keystore.properties -> a separate, non-personal key, so the build downloaded from
    //                                  GitHub is signed by an identity of its own and carries
    //                                  nothing of the Play signing identity at all.
    //
    // A missing file is not an error: that channel then simply builds unsigned.
    signingConfigs {
        create("play") {
            val keyProperties = rootProject.file("keystore.properties")
            if (keyProperties.exists()) {
                val properties = Properties().apply {
                    keyProperties.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
        create("github") {
            val keyProperties = rootProject.file("github-keystore.properties")
            if (keyProperties.exists()) {
                val properties = Properties().apply {
                    keyProperties.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    // Two channels of one app - deliberately the same applicationId, so a user switching between
    // the GitHub download and the Play listing keeps the same app and its settings.
    flavorDimensions += "channel"
    productFlavors {
        create("play") {
            dimension = "channel"
            signingConfigs.findByName("play")?.takeIf { it.storeFile != null }?.let { signingConfig = it }
        }
        create("github") {
            dimension = "channel"
            signingConfigs.findByName("github")?.takeIf { it.storeFile != null }?.let { signingConfig = it }
        }
    }

    buildTypes {
        release {
            // R8: shrink, optimise and obfuscate the release build. Keep the mapping file it
            // writes at app/build/outputs/mapping/release/mapping.txt for every release -
            // it is the only thing that turns a store's crash reports back into stack traces.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}