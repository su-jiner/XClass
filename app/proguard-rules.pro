# kotlinx-serialization：保留生成的 serializer 与 Companion
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cn.xiao.xyaax.**$$serializer { *; }
-keepclassmembers class cn.xiao.xyaax.** {
    *** Companion;
}
-keepclasseswithmembers class cn.xiao.xyaax.** {
    kotlinx.serialization.KSerializer serializer(...);
}
