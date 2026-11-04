plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":zimbra"))
    implementation(project(":local"))
    implementation(project(":aws"))
    implementation(libs.snakeyaml)
    implementation(libs.picocli)
    implementation(libs.logback.classic)
    implementation(libs.slf4j.jul.to.slf4j)
    testImplementation(libs.unboundid.ldapsdk)
    testImplementation(libs.wiremock)
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.junit.platform.suite)
}

tasks.processResources {
    from(rootDir) { include("VERSION") }
}

tasks.shadowJar {
    archiveFileName.set("zmbackup.jar")
    manifest {
        attributes["Main-Class"] = "io.zmbackup.app.cli.Main"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("zmbackup.shadowJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}

val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
configurations[integrationTest.compileOnlyConfigurationName].extendsFrom(configurations.testCompileOnly.get())

tasks.named("spotbugsIntegrationTest") { enabled = false }

tasks.register<Test>("integrationTest") {
    description = "Runs the packaged zmbackup.jar as a real process against a local fake Zimbra."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    dependsOn(tasks.shadowJar)
    systemProperty("zmbackup.shadowJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    mustRunAfter(tasks.test)
}
