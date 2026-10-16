import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from local.properties or environment variables, never from git.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(name: String): String? = localProps.getProperty(name) ?: System.getenv(name)
val releaseStorePassword = signingValue("SHIELD_STORE_PASSWORD")

android {
    namespace = "com.shieldchild.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.shieldchild.app"
        minSdk = 26
        targetSdk = 36
        // Release tags are "v<versionName>-<versionCode>" (e.g. v1.0.1-2); the in-app updater
        // reads the number after the dash. Bump both for every GitHub release.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 2
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStorePassword != null) {
            create("release") {
                storeFile = file(signingValue("SHIELD_STORE_FILE") ?: "shieldchild-release.jks")
                storePassword = releaseStorePassword
                keyAlias = signingValue("SHIELD_KEY_ALIAS") ?: "shieldchild"
                keyPassword = signingValue("SHIELD_KEY_PASSWORD") ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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

    // Release lint ("lintVital") is the slowest part of assembleRelease; run `./gradlew lint` on demand instead.
    lint {
        checkReleaseBuilds = false
    }

    // Lets JVM unit tests touch android.jar stubs (e.g. ContextWrapper) without a device.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.zxing.core) // QR code for the remote-unlock setup

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // android.jar only has stubs of org.json
}
