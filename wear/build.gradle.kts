plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "xyz.desent.wear"
    compileSdk = 36

    defaultConfig {
        // MUST match the phone app's applicationId: the Wear OS Data Layer
        // API only routes DataItems/Messages between identical package names
        // on phone and watch. The distinct namespace below keeps code/R refs.
        applicationId = "xyz.desent"
        minSdk = 26
        targetSdk = 36
        // Version-synced with the phone app — the pair ships together.
        versionCode = 9
        versionName = "1.0.9"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val releaseStoreFile = providers.gradleProperty("DESENT_STORE_FILE").orNull
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("DESENT_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("DESENT_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("DESENT_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Shared status-sync codec (Wear OS Data Layer payloads)
    implementation(project(":core:wearsync"))

    // Wear OS
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.navigation)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    // RemoteIntent — "Open on phone" deep-link handoff
    implementation(libs.androidx.wear.remote.interactions)
    // Android core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM pins versions compatible with wear-compose 1.3.x)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)


    // Coroutines / serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
