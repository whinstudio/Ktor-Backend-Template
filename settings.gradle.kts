rootProject.name = "cmpose-app"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download the JDK that gradle/gradle-daemon-jvm.properties asks for when it is missing
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
