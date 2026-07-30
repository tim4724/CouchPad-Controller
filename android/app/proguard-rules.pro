# App-specific R8 rules. The defaults already cover what this app needs — notably
# @android.webkit.JavascriptInterface members (the CouchPadHost bridge) are kept
# by proguard-android-optimize.txt, and kotlinx-serialization ships its own rules.
