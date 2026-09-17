plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.desent"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.desent"
        minSdk = 26
        targetSdk = 36
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    testOptions {
        unitTests {
            // android.util.Log and friends return defaults (instead of throwing
            // "not mocked") in pure-JVM unit tests.
            isReturnDefaultValues = true
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
    sourceSets {
        getByName("main") {
            // No Java source files - everything is Kotlin
            java {
                srcDirs()
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Bouncy Castle jars (bcpg → bcutil/bcprov) ship identical
            // module-info/OSGi metadata that collides at merge time.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{versions/9,versions/11}/module-info.class"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
    }
    
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.generateKotlin", "true")
    }
}

// Disable Java compilation - we're pure Kotlin
afterEvaluate {
    tasks.findByName("compileDebugJavaWithJavac")?.enabled = false
    tasks.findByName("compileReleaseJavaWithJavac")?.enabled = false
}

dependencies {
    // Wear OS status-sync codec shared with the wear companion
    implementation(project(":core:wearsync"))

    // Android Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Markdown rendering (note view, DM bubbles)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil2)

    // Reorderable LazyColumn (summary dashboard card customization)
    implementation(libs.reorderable)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.okhttp)

    // Nostr (crypto & events only - custom OkHttp relay client)
    implementation(libs.nostr.java.core)
    implementation(libs.nostr.java.event)
    implementation(libs.nostr.java.identity)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // Camera & QR Scanning
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    // Security & Biometrics
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pgp)
    implementation(libs.mime4j.dom)

    // Background Work
    implementation(libs.androidx.work.runtime.ktx)

    // Wear OS Data Layer (push email/calendar/bunker snapshots to the watch)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Data Storage
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

