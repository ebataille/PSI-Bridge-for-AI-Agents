import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
    // Resolves the JDK the toolchain asks for, downloading it when the machine has none.
    // Without this the build depends on a JDK 21 happening to be installed, which stops being
    // true the moment an IDE upgrade replaces its bundled JBR with a newer one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "idebridge"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
