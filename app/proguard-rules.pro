# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.flowzapret.core.ByeDpiProxy { *; }

-keep,allowoptimization class com.flowzapret.core.TProxyService { *; }
-keep,allowoptimization class com.flowzapret.activities.** { *; }
-keep,allowoptimization class com.flowzapret.services.** { *; }
-keep,allowoptimization class com.flowzapret.receiver.** { *; }

-keep class com.flowzapret.fragments.** {
    <init>();
}

-keep,allowoptimization class com.flowzapret.data.** {
    <fields>;
}

-keepattributes Signature
-keepattributes *Annotation*

-repackageclasses 'com.flowzapret'
-renamesourcefileattribute ''
-keepattributes SourceFile,InnerClasses,EnclosingMethod,Signature,RuntimeVisibleAnnotations,*Annotation*,*Parcelable*
-allowaccessmodification
-overloadaggressively
-optimizationpasses 5
-verbose
-dontusemixedcaseclassnames
-adaptclassstrings
-adaptresourcefilecontents **.xml,**.json
-adaptresourcefilenames **.xml,**.json
# --- JNA / libtgwsproxy ---
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep interface com.flowzapret.tgproxy.TgProxyLibrary { *; }
-keep class com.flowzapret.tgproxy.TgProxyNative { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
