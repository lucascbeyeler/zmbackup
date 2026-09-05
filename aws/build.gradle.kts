dependencies {
    implementation(project(":core"))
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.dynamodb)
    testImplementation(libs.wiremock)
}
