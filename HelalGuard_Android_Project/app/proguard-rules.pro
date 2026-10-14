# Proguard rules for HelalGuard Android
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes JavascriptInterface
-keepclassmembers class com.helalguard.app.MainActivity$WebAppInterface {
    <methods>;
}

-dontwarn okio.**
-dontwarn javax.annotation.**
