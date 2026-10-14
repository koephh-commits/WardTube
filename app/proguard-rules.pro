# yt-dlp wrapper: parses yt-dlp's JSON with Jackson (reflection), so these must survive minification.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**

# Extra safety in case minification is turned back on.
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers enum * { *; }
-dontwarn org.apache.commons.**
