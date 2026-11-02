import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: CI passes the key through environment variables (GitHub
// secrets); locally it can come from keystore.properties. Neither is committed.
// Without either, release builds fall back to the debug key (personal builds).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val releaseKeystore = signingValue("RECORDER_KEYSTORE_FILE", "storeFile")

// Version from the release tag in CI (e.g. v1.2.0), else the defaults below.
val releaseVersion = System.getenv("RECORDER_VERSION_NAME")
val releaseVersionCode = System.getenv("RECORDER_VERSION_CODE")?.toIntOrNull()

android {
    namespace = "com.azayn.recorder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.azayn.recorder"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersion ?: "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("RECORDER_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("RECORDER_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("RECORDER_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
}
