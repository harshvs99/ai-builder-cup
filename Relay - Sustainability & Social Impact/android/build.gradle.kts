// Top-level build. Plugins are declared (apply false) so that every module resolves the same
// plugin classpath; a later `:app` Android module just applies them via the version catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---------------------------------------------------------------------------------------------
// Relay helper tasks (group "relay"): run from the Gradle tool window or via the shared run
// configurations in .run/. They wrap the repo scripts; scripts live at <relay root>/scripts.
// Configuration-cache safe: all inputs are resolved through providers at configuration time.
// ---------------------------------------------------------------------------------------------
val relayScripts = layout.projectDirectory.dir("../scripts")
// Prefer the caller's JAVA_HOME; otherwise the JVM running this build (Android Studio's JBR).
val relayJavaHome = providers.environmentVariable("JAVA_HOME").orElse(providers.systemProperty("java.home"))

fun TaskContainer.relayScript(
    name: String,
    description: String,
    script: String,
    vararg args: String,
) = register<Exec>(name) {
    group = "relay"
    this.description = description
    workingDir = relayScripts.asFile.parentFile
    environment("JAVA_HOME", relayJavaHome.get())
    commandLine(listOf("bash", relayScripts.file(script).asFile.path) + args)
}

tasks.relayScript(
    "relayBackendStart",
    "Start the local backend + dashboard in the background (http://localhost:8080/); keeps the database.",
    "run-local-poc.sh", "--no-e2e", "--keep-db",
)
tasks.relayScript(
    "relayBackendStop",
    "Stop the local backend started by relayBackendStart / scripts/run-local-poc.sh.",
    "stop-local-poc.sh",
)
tasks.relayScript(
    "relayBleDemo",
    "Run the automated two-emulator BLE mesh demo (emulator-5554 + emulator-5556 must be running).",
    "emulator-ble-demo.sh", "--no-build",
).configure {
    // The script must not call gradlew from inside a Gradle build, so build the APK here.
    dependsOn(":app:assembleDebug")
}
tasks.register("relayAllTests") {
    group = "relay"
    description = "Run all JVM unit tests: core-protocol, simulator and app."
    dependsOn(":core-protocol:test", ":simulator:test", ":app:testDebugUnitTest")
}
