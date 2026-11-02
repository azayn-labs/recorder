# The recorder daemon runs in its own app_process JVM, entered by class name
# (com.azayn.recorder.daemon.Main / SelfTest), so R8 must neither strip nor rename it.
-keep class com.azayn.recorder.daemon.** { *; }

# libadb-android and its crypto: reached via reflection and the platform's Conscrypt.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
