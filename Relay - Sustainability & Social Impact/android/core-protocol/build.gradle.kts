import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnit()
    // Golden vectors live in the repo-level protocol/ directory (android/ is a sibling of protocol/).
    systemProperty("relay.vectors", rootProject.file("../protocol/vectors.json").absolutePath)
    // Labelled prompts for the RuleExtractor per-field report (RuleScoreTest).
    systemProperty("relay.labels", rootProject.file("../ml/voice-spike/eval").absolutePath)
    systemProperty("relay.reportdir", layout.buildDirectory.dir("reports").get().asFile.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
