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
}

// En desarrollo, todo (config, modelos, docs internas) vive en <raíz>/app-home
tasks.withType<JavaExec>().configureEach {
    systemProperty("app.home", rootProject.layout.projectDirectory.dir("app-home").asFile.absolutePath)
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
