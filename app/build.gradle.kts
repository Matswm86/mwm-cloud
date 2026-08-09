plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "no.mwmai.mwmcloud"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.mwmai.mwmcloud"
        minSdk = 26
        targetSdk = 35
        // CI derives these from git (commit count + short sha) so an installed
        // APK can say which build it is; a plain local build stays at 1/dev.
        versionCode = System.getenv("MWMCLOUD_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("MWMCLOUD_VERSION_NAME") ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Real release signing, fed by CI secrets. Nothing is hardcoded: the
    // keystore path and passwords arrive as environment variables, and a local
    // build without them still assembles an unsigned release rather than
    // failing, so `assembleRelease` stays runnable everywhere.
    val releaseKeystore: String? = System.getenv("MWMCLOUD_KEYSTORE")
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MWMCLOUD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MWMCLOUD_KEY_ALIAS") ?: "mwmcloud"
                keyPassword = System.getenv("MWMCLOUD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            // The published APK. Debuggable would let run-as/JDWP read the box
            // password out of the running process on a compromised device.
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            // Gradle prints nothing on success, which makes a green CI log
            // indistinguishable from one where no test ran at all.
            it.testLogging {
                events("passed", "skipped", "failed")
            }
            it.afterSuite(
                KotlinClosure2<org.gradle.api.tasks.testing.TestDescriptor, org.gradle.api.tasks.testing.TestResult, Unit>(
                    { desc, result ->
                        if (desc.parent == null) {
                            println(
                                "TEST SUMMARY: ${result.testCount} tests, " +
                                    "${result.successfulTestCount} passed, " +
                                    "${result.failedTestCount} failed, " +
                                    "${result.skippedTestCount} skipped",
                            )
                        }
                    },
                ),
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
    implementation(libs.androidx.lifecycle.runtime.compose.livedata)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)

    debugImplementation(libs.androidx.ui.tooling)
}
