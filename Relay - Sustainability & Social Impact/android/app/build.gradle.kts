import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Machine-local, git-ignored values: relay.deviceKey and the optional relay.signing.* release keystore.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun relayProp(name: String): String? =
    providers.gradleProperty(name).orNull ?: providers.environmentVariable(name.replace('.', '_').uppercase()).orNull
        ?: localProps.getProperty(name)

android {
    namespace = "org.relay.app"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "org.relay.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Default backend for gateway sync on real phones (see gradle.properties). Emulators use 10.0.2.2.
        val backendUrl = providers.gradleProperty("relay.backendUrl").orElse("http://10.0.2.2:8080").get()
        buildConfigField("String", "DEFAULT_BACKEND", "\"${backendUrl.replace("\"", "")}\"")

        // Shared gateway key (X-Relay-Key). Never committed: set relay.deviceKey in local.properties,
        // RELAY_DEVICEKEY in the environment, or -Prelay.deviceKey. Falls back to the local-dev key.
        val deviceKey = relayProp("relay.deviceKey")?.trim().orEmpty().ifEmpty { "dev-relay-key" }
        buildConfigField("String", "DEFAULT_KEY", "\"${deviceKey.replace("\"", "")}\"")
        buildConfigField("boolean", "LOCK_CONNECTION", "false")

        // First-run value of the "Help locate neighbours" setting (mesh-assisted location, SPEC 5.5). true for demo
        // builds; village builds pass -Prelay.helpLocateDefault=false so position sharing is strictly opt-in.
        val helpLocate = providers.gradleProperty("relay.helpLocateDefault").orElse("true").get().trim().lowercase() != "false"
        buildConfigField("boolean", "HELP_LOCATE_DEFAULT", helpLocate.toString())

        // Languages this build can offer (contract v2 section 1). The picker shows a language only when it is also enabled in the
        // backend's GET /v1/languages. English and Hindi by default; Odia, Filipino and Waray keep their strings but stay hidden
        // until a community reviewer signs them off (then: -Prelay.enabledLangs=en,hi,or and the backend's RELAY_LANGS).
        val enabledLangs = providers.gradleProperty("relay.enabledLangs").orElse("en,hi").get().filter { it.isLetter() || it == ',' }
        buildConfigField("String", "ENABLED_LANGS", "\"$enabledLangs\"")
    }

    // Release uses a real keystore when relay.signing.storeFile is set; otherwise the debug key, so a
    // release build installs over an existing debug install without wiping app data.
    signingConfigs {
        relayProp("relay.signing.storeFile")?.let { path ->
            create("relay") {
                storeFile = file(path)
                storePassword = relayProp("relay.signing.storePassword")
                keyAlias = relayProp("relay.signing.keyAlias")
                keyPassword = relayProp("relay.signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Backend URL and device key are fixed to the build-time values and hidden in Settings.
            buildConfigField("boolean", "LOCK_CONNECTION", "true")
            signingConfig = signingConfigs.findByName("relay") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Errors fail the build; warnings are reported but do not.
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        // LiteRT-LM loads its native libraries from the extracted lib dir.
        jniLibs { useLegacyPackaging = true }
    }

    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

dependencies {
    implementation(project(":core-protocol"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // On-device Gemma (LiteRT-LM). Needs API 31+; the manifest overrides minSdk for it and GemmaRunner guards at runtime.
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    // The android.jar used for JVM unit tests only has stubs for org.json; use the real one.
    testImplementation(libs.org.json)
}
