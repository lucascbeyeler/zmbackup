dependencies {
    implementation(project(":core"))
    implementation(libs.unboundid.ldapsdk)
    testImplementation(libs.wiremock)
}
