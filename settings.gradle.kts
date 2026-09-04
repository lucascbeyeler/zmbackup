plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// gradle/libs.versions.toml is picked up automatically as the "libs" version catalog by convention.
rootProject.name = "zmbackup"

include("core", "zimbra", "local", "app")
