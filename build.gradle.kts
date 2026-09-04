import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.spotbugs) apply false
}

allprojects {
    group = "io.zmbackup"
    version = file(rootDir.resolve("VERSION")).readText().trim()
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.spotbugs")

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
        "compileOnly"(libs.findLibrary("spotbugs-annotations").get())
        "testCompileOnly"(libs.findLibrary("spotbugs-annotations").get())
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("jacocoTestReport"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.named("jacocoTestCoverageVerification"))
    }

    configure<SpotBugsExtension> {
        effort.set(Effort.MAX)
        reportLevel.set(Confidence.MEDIUM)
        excludeFilter.set(rootProject.file("gradle/spotbugs-exclude.xml"))
    }

    tasks.withType<SpotBugsTask>().configureEach {
        reports.create("html") { required.set(true) }
        reports.create("xml") { required.set(true) }
    }

    tasks.named("spotbugsTest") { enabled = false }
}
