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

# EasyRPG Player (RPG Maker 2000/2003): its native code calls the relocated SDL3 Java layer and the EasyRPG host
# activity through JNI by name.
-keep class org.libsdl3.app.** { *; }
-keep class org.easyrpg.player.** { *; }
# Godot: the engine library calls into its Java layer through JNI; keep it and AceArcade's Godot host as-is.
-keep class org.godotengine.** { *; }
-keep class com.acecore.app.runtime.godot.** { *; }
