import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The backend base URL is never hardcoded in tracked source. Put
//   mwmcloud.backendUrl=https://your-host.example.com
// in local.properties (untracked) to point a build at a real deployment.
val backendUrl: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    props.getProperty("mwmcloud.backendUrl") ?: "https://cloud.example.com"
}

android {
    namespace = "no.mwmai.mwmcloud"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.mwmai.mwmcloud"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
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
