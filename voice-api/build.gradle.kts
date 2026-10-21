plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Sin ktor: la voz habla HTTP con el HttpClient del JDK (como
    // `rag/retrieval/Sources.kt` en el backend). Menos dependencias y menos
    // toolchain que mover (mismo criterio que el cliente MCP propio).
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Tests: JUnit 5. Ver docs/TESTING.md.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // Los tests tocan audio (AWT/Swing): nunca un escritorio.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
