plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.4.21"
}

group = "dev.plex"
version = "1.0"

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("plexUpdater") {
            id = "dev.plex.updater"
            implementationClass = "dev.plex.gradle.updater.PlexUpdaterPlugin"
            displayName = "Plex Updater Gradle Plugin"
            description = "Writes the Plex updater metadata of a Jenkins build of Plex or a Plex module."
        }
    }
}

kotlin {
    jvmToolchain(25)
}
