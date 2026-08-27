dependencies {
    implementation(project(":core"))
    implementation("com.unboundid:unboundid-ldapsdk:7.0.5")
    testImplementation("org.wiremock:wiremock:3.13.1")
}
