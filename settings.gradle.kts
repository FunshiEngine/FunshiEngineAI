pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "docs-chatbot"
include(":llm-api", ":voice-api", ":backend", ":frontend", ":estructuras")
