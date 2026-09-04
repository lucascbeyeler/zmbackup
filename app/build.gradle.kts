plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":zimbra"))
    implementation(project(":local"))
    implementation(libs.snakeyaml)
    implementation(libs.picocli)
    // The standard SLF4J/Logback logging stack, fed by core's java.util.logging calls through the
    // jul-to-slf4j bridge (core stays dependency-free; see core/build.gradle.kts).
    implementation(libs.logback.classic)
    implementation(libs.slf4j.jul.to.slf4j)
    testImplementation(libs.unboundid.ldapsdk)
    testImplementation(libs.wiremock)
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.junit.platform.suite)
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
