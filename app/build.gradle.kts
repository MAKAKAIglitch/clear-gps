plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.cleargps"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.cleargps.nav"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions run numbers keep sideloaded APK updates monotonic.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 5).coerceAtLeast(5)
        versionName = "0.4.0"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads Android framework jars at test runtime.
            // Use Maven Central's canonical hostname instead of the legacy repo1 alias.
            it.systemProperty("robolectric.dependency.repo.id", "mavenCentral")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // CI supplies one persistent keystore through GitHub Actions secrets.
    // Local builds keep using Android's normal per-user debug keystore.
    val stableDebugKeystorePath = System.getenv("CLEARGPS_DEBUG_KEYSTORE_PATH")
    signingConfigs {
        if (!stableDebugKeystorePath.isNullOrBlank()) {
            create("stableDebug") {
                storeFile = file(stableDebugKeystorePath)
                storePassword = System.getenv("CLEARGPS_DEBUG_STORE_PASSWORD")
                keyAlias = System.getenv("CLEARGPS_DEBUG_KEY_ALIAS")
                keyPassword = System.getenv("CLEARGPS_DEBUG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (!stableDebugKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core-detect"))
    implementation(project(":core-fusion"))
    implementation(project(":core-cell"))
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // MapLibre exposes an OkHttp Call.Factory hook; keep this on its matching runtime version.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("androidx.work:work-runtime:2.12.0")
    // Debug-only memory leak detector; must never be included in release APKs.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
