// Baseline-profile generator. Not part of the app: a com.android.test module that
// drives the release build on an emulator and records which methods hot startup
// actually runs; the result is checked in at app/src/release/generated/baselineProfiles/.
//
// Regenerate (after major startup-path changes) with an emulator running, API 33+:
//   ANDROID_SERIAL=emulator-XXXX ./gradlew :app:generateReleaseBaselineProfile
// (ANDROID_SERIAL matters: useConnectedDevices would otherwise also grab any
// attached physical phone.)
plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.baselineprofile)
}

android {
  namespace = "games.couchpad.controller.baselineprofile"
  compileSdk = 37
  compileSdkMinor = 1
  defaultConfig {
    // Macrobenchmark's floor, not the app's (24): profile generation only ever
    // runs on a modern emulator, and installs nowhere else.
    minSdk = 28
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  targetProjectPath = ":app"
}

kotlin {
  jvmToolchain(17)
}

baselineProfile {
  // Generate on whatever emulator is connected rather than a Gradle-managed
  // device — CI and dev machines already keep AVDs around for the screenshot jobs.
  useConnectedDevices = true
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
