plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.protobuf.java)
    implementation(platform(libs.okhttpBom))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    testImplementation(libs.mockwebserver3)
    testImplementation("com.squareup.okhttp3:okhttp-tls")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufLite.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                maybeCreate("java").apply { option("lite") }
            }
        }
    }
}

// Explicit opt-in: normal unit tests never require Steam or account credentials.
tasks.register<Test>("cmSelectionBenchmark") {
    group = "verification"
    description = "Compare serial CM connection with bounded parallel selection on the real network"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*SteamCmSelectionBenchmarkTest") }
    environment("STS_CM_BENCHMARK", "1")
    environment(
        "STS_CM_BENCHMARK_OUT",
        System.getenv("STS_CM_BENCHMARK_OUT") ?: rootProject.file("agent-tmp/cm-selection-benchmark.csv").absolutePath,
    )
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}

tasks.register<Test>("cmNetworkSimulation") {
    group = "verification"
    description = "Reproducible player-network CM simulation matrix (no network or credentials)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("*SteamCmNetworkSimulationTest")
        includeTestsMatching("*SteamCmTransportFaultTest")
    }
    systemProperty("cm.simulation.rounds", providers.gradleProperty("cmSimulationRounds").getOrElse("12"))
    systemProperty("cm.simulation.out", rootProject.file("agent-tmp/cm-network-simulation.csv").absolutePath)
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
