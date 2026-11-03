// Test-only stand-in for a VoIP app (WhatsApp): takes communication mode and records the mic with
// VOICE_COMMUNICATION, logging whether Android silences it. Not part of any release.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.azayn.voiptest"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.azayn.voiptest"
        minSdk = 31
        targetSdk = 37
    }
}
