# Keep readable crash reports: the app is open source, so hiding names gains nothing.
-dontobfuscate

# kotlinx.serialization: keep generated serializers of the app's own models.
-keepclassmembers class com.canis.hejje.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.canis.hejje.**$$serializer { *; }

# Play services location and MapLibre ship their own rules.
-dontwarn org.maplibre.**

# Replay video: the page calls these through the JavaScript bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
