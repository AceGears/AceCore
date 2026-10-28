# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.acecore.app.**$$serializer { *; }
-keepclassmembers class com.acecore.app.** { *** Companion; }
-keepclasseswithmembers class com.acecore.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Native code (SDL2, mkxp-z) calls into these classes through JNI by name: never rename or strip them.
-keep class org.libsdl.app.** { *; }
-keep class com.acecore.app.runtime.rgss.RgssActivity { public static *; *; }
-keep class com.acecore.app.runtime.rgss.RgssActivity$Companion { *; }
