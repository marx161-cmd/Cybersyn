-keep class com.termux.cybersyn.core.model.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }

# Keep manifest-declared entry points
-keep class com.termux.cybersyn.app.OpenTaskerApp_NoHilt
-keep class com.termux.cybersyn.app.MainActivity

# Room generated code
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao class *
-keep @androidx.room.Entity class *

# Shizuku AIDL stubs and IPC reflection
-keep class dev.rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# RE2J internals (uses sun.misc.Unsafe fallback)
-dontwarn com.google.re2j.**
-keep class com.google.re2j.** { *; }

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Cybersyn evdev native bridge — EvdevRootHelper.main() is called via app_process,
# not from within the app, so R8 would strip them as unreachable code.
-keep class com.termux.cybersyn.core.evdev.EvdevRootHelper { *; }
-keep class com.termux.cybersyn.core.evdev.CybersynEvdevBridge { *; }
-keep class com.termux.cybersyn.core.evdev.KeyHijackController { *; }
-keep class com.termux.cybersyn.core.evdev.EvdevDeviceInfo { *; }
-keep class com.termux.cybersyn.core.evdev.GrabTargetKeyCode { *; }
-keep class com.termux.cybersyn.core.evdev.GrabbedDeviceHandle { *; }
-keep class com.termux.cybersyn.core.evdev.ShellResult { *; }
