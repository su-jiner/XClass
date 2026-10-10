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

# PdfBox-Android：可选的 JPEG2000（JPX）解码器未打包，仅在遇到 JPX 图像时才会用到
-dontwarn com.gemalto.jp2.**

# ML Kit / PdfBox 的可选参考
-dontwarn org.bouncycastle.**
-dontwarn org.apache.pdfbox.**
