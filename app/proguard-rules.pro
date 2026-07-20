# ProGuard rules for Simple Call App

# 1. Keep WebRTC classes intact
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# 2. Keep Firebase Firestore data model classes for reflection/serialization
-keep class com.simplecallapp.data.model.** { *; }
-keepclassmembers class com.simplecallapp.data.model.** {
    <fields>;
    <init>(...);
    *** get*();
    *** set*(...);
}

# 3. Allow reflection for general Firebase operations if needed
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 4. Keep standard Android and Kotlin metadata if needed
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
