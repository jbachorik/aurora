plugins {
    // Lets Gradle download the pinned JDK (gradle.properties: javaLanguageVersion)
    // when the machine has none — CI runners and development containers alike.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "media-center"
