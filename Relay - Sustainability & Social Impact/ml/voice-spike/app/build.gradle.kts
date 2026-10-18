plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.relay.voicespike"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "org.relay.voicespike"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // eval/guided_prompts.json is the single source of truth; it is copied into the APK's assets.
            assets.directories.add(layout.buildDirectory.dir("generated/guidedAssets").get().asFile.path)
            // Read-only reuse of the pure-Kotlin RuleExtractor + payload enums (not copied, not edited).
            kotlin.directories.add("../../../android/core-protocol/src/main/kotlin")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
}

// Copy eval/guided_prompts.json -> build/generated/guidedAssets/ and make every asset merge wait for it.
val syncGuidedAssets = tasks.register<Copy>("syncGuidedAssets") {
    from(rootProject.file("eval/guided_prompts.json"))
    into(layout.buildDirectory.dir("generated/guidedAssets"))
}
tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach { dependsOn(syncGuidedAssets) }
