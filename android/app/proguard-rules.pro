# App-specific R8 rules. The defaults already cover what this app needs — notably
# @android.webkit.JavascriptInterface members (the CouchPadHost bridge) are kept
# by proguard-android-optimize.txt, and kotlinx-serialization ships its own rules.

# zxing-cpp's JNI resolves the Kotlin side by name — FindClass("zxingcpp/BarcodeReader$Result"),
# GetFieldID(.., "lastReadTime"), and every Options field — so R8 must not rename or remove any
# of it. The published AAR carried this as consumer-rules.pro; we compile the wrapper from
# source (see build.gradle.kts), which does not bring consumer rules with it. Without this the
# scanner still builds and runs in debug, and silently finds nothing in a minified release.
-keep class zxingcpp.** { *; }
