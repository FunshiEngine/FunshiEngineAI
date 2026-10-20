plugins {
    kotlin("jvm")
}

dependencies {
    // Tests: JUnit 5. Ver docs/TESTING.md.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Librería autocontenida de estructuras de datos dinámicas (sin dependencias
// externas). Réplica en Kotlin del diseño de FunshiEngineOpenGL: interfaces
// genéricas, posiciones ([Position]) y nodos específicos por estructura.
