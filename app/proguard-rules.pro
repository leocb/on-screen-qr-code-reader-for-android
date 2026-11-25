# Keep ML Kit and ZXing classes, they are referenced reflectively by the scanner.
-keep class com.google.mlkit.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.mlkit.**
