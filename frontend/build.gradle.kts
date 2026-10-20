import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":backend"))
    implementation(project(":estructuras"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // Math en el chat: render de LaTeX a imagen, offline y sin servicios (F5).
    implementation("org.scilab.forge:jlatexmath:1.0.7")

    // Tests: JUnit 5. Ver docs/TESTING.md.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// En desarrollo, todo (config, modelos, docs internas) vive en <raíz>/app-home
tasks.withType<JavaExec>().configureEach {
    systemProperty("app.home", rootProject.layout.projectDirectory.dir("app-home").asFile.absolutePath)
}

tasks.test {
    useJUnitPlatform()
    // AWT sin escritorio: el render de math funciona igual.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DocsChatbot"
            packageVersion = "1.0.0"
        }
    }
}
