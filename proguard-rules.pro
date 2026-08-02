# Add project-specific ProGuard rules here.
# The default rules from getDefaultProguardFile already cover standard
# Android/Kotlin cases; add exceptions below only if release-build
# crashes point to something being stripped that shouldn't be.

# ZXing and secp256k1-kmp both use JNI/reflection internally in places;
# keep their classes intact rather than risk minification breaking them.
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class fr.acinq.secp256k1.** { *; }
