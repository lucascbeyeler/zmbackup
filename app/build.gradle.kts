dependencies {
    implementation(project(":core"))
    implementation(project(":zimbra"))
    implementation(project(":local"))
    implementation("org.yaml:snakeyaml:2.6")
    implementation("info.picocli:picocli:4.7.6")
}

// Bundle the repo-root VERSION file into the jar so VersionProvider can read it at runtime,
// keeping it as the single source of truth (it also drives project.version, see root build.gradle.kts).
tasks.processResources {
    from(rootDir) { include("VERSION") }
}
