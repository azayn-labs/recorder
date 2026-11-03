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
        versionName = releaseVersion ?: "1.2.2-dev"
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

    // The recorder daemon is compiled into the APK too: the app launches it with
    // app_process straight from its own base.apk over on-device ADB (see Adb.kt).
    sourceSets["main"].java.directories.add("../../daemon/src")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // On-device ADB over Wireless debugging (pairing, TLS connect, shell), as used by App Manager.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81") // the self-signed cert libadb pairs with
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}
