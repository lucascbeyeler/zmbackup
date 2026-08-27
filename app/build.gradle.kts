plugins {
    id("com.gradleup.shadow") version "8.3.6"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":zimbra"))
    implementation(project(":local"))
    implementation("org.yaml:snakeyaml:2.6")
    implementation("info.picocli:picocli:4.7.6")
    testImplementation("com.unboundid:unboundid-ldapsdk:7.0.5")
    testImplementation("org.wiremock:wiremock:3.13.1")
    testImplementation("io.cucumber:cucumber-java:7.34.7")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.34.7")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.4")
}

// Bundle the repo-root VERSION file into the jar so VersionProvider can read it at runtime,
// keeping it as the single source of truth (it also drives project.version, see root build.gradle.kts).
tasks.processResources {
    from(rootDir) { include("VERSION") }
}

// The thin bash launcher runs `java -jar zmbackup.jar`, so the jar must be self-contained
// (all dependencies bundled) and executable (Main-Class set).
tasks.shadowJar {
    archiveFileName.set("zmbackup.jar")
    manifest {
        attributes["Main-Class"] = "io.zmbackup.app.cli.Main"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Packaging smoke tests run the shaded jar as a subprocess, so it must exist first.
tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("zmbackup.shadowJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}
