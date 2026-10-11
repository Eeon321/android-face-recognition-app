# Project R8/ProGuard rules.
#
# Context: release builds previously ran with minifyEnabled false, so the shipped
# APK carried unshrunk, unobfuscated libraries for all four ABIs. R8 is enabled in
# app/build.gradle; these rules cover what R8 must NOT touch.

# ML Kit's face detector is delivered through Play services / a bundled dynamite
# module and reaches its native code via reflection and JNI. Keep its public API,
# the callback/generic signatures used from Kotlin, and its internal bundled classes.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face_bundled.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# CameraX uses annotation-annotated (ExperimentalGetImage) and reflection-based
# surface/lifecycle integration paths.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# JNI: native code resolves these by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ViewBinding inflate() is called reflectively by generated code paths.
-keep class com.example.facerecognition.databinding.** { *; }

# Keep enums used by ML Kit option builders (values() is reflective).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
