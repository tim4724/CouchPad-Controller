import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.aboutlibraries)
  alias(libs.plugins.baselineprofile)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Release signing is driven by a gitignored `android/keystore.properties` (see
// keystore.properties.example; release-build.yml materializes it from secrets).
// Where it is absent, `hasReleaseKeystore` is false and the release build falls
// back to debug signing — the project still builds and tests. A real Play Store
// upload needs the file present with the upload keystore it points at.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
// storeFile may be absolute (a keystore anywhere on the machine, outside the repo)
// or relative to android/ as keystore.properties.example documents — hence
// rootProject.file(), which `file()` would re-root under this module (android/app).
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseKeystore = releaseStoreFile != null

android {
    // Kotlin package / R+BuildConfig namespace — compile-time only, kept identical
    // to the applicationId below.
    namespace = "games.couchpad.controller"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        // Play Store identity, reverse-DNS of couchpad.games. Changed in the 2026-07
        // rebrand while the app was still unpublished — this is a one-way door once
        // a build is uploaded (a new applicationId is a new listing), and it must
        // match the package_name in the assetlinks.json served on every App Link
        // host, or link verification silently fails.
        applicationId = "games.couchpad.controller"
        minSdk = 24
        // 37 = Android 17. Local Network Protections are ENFORCED from this target:
        // mDNS room discovery (NearbyRooms.kt) needs ACCESS_LOCAL_NETWORK granted or
        // it is silently blocked — no exception, just no results.
        targetSdk = 37
        // CI overrides both via -P (release-build.yml): versionCode from the run
        // number — Play rejects a reused code — and versionName from the release tag.
        versionCode = (findProperty("cpVersionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("cpVersionName") as String? ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the real upload keystore when android/keystore.properties
            // is present; otherwise debug-signed so the release build stays
            // installable for testing on machines/CI without the keystore.
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Per-artifact copies of the Apache-2.0 text and NOTICE, one from every
        // dependency that ships them. Attribution and the license text itself already
        // ship in R.raw.aboutlibraries, which the About screen renders, so nothing
        // reads these.
        excludes += "/META-INF/**/LICENSE.txt"
        excludes += "/META-INF/NOTICE.md"
      }
    }

    testOptions {
      // Zero the device animation scales while instrumented tests run — sheets and
      // dialogs settle instantly instead of racing the screenshot capture.
      animationsDisabled = true
    }
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    // Reorder the release dex so the classes in the startup profile (the generator's
    // startup() journey) sit contiguously — fewer page faults on cold start.
    dexLayoutOptimization = true
}

// Attribution list generation. Only collect the release classpath (what actually
// ships — drops debug/test-only tooling like ui-tooling, ui-test-manifest), and
// merge duplicate artifacts that resolve to the same library.
aboutLibraries {
    collect {
        filterVariants.addAll("release")
    }
    library {
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
    }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Baseline profile: profileinstaller compiles the checked-in profile into ART on
  // install/first run; :baselineprofile is the generator (see that module's README
  // header comment for how to regenerate).
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":baselineprofile"))

  // QR scanning — in-app CameraX preview decoded by zxing-cpp. Fully on-device
  // and telemetry-free (unlike ML Kit), works without Google Play Services, and
  // opens instantly with no first-use module download; see ScanScreen.kt.
  // Deliberately no camera-view: PreviewView pulls AppCompat, Fragment, ViewPager
  // and camera-video into the APK, and CameraPreviewView.kt covers the one thing
  // this app wanted from it.
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.zxingcpp.android)

  // Open-source license list (About screen)
  implementation(libs.aboutlibraries.compose.m3)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)

  // Card-state previews (MainScreen.kt, fed by ui/preview/CardSamples.kt). @Preview is
  // declared on main because it annotates the private composables it renders; the
  // renderer behind it is debug-only and never ships. (ui-tooling-preview already
  // arrives transitively via aboutlibraries-compose-m3 — declared anyway, because
  // compiling against another library's transitive dependency breaks silently.)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)

  // WebView compat — document-start script injection for the legal viewer.
  implementation(libs.androidx.webkit)

  // Instrumented smoke test + store-screenshot capture (androidTest/StoreScreenshotTest.kt)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.espresso.core)
}
