plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":llm-api"))
    // Voz: audio, dictado y locución (módulo hermano de llm-api).
    api(project(":voice-api"))

    // Tests: JUnit 5. Ver docs/TESTING.md.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // Los tests tocan AWT (render de math) y Swing: nunca un escritorio.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
