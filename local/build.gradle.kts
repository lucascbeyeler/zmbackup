dependencies {
    implementation(project(":core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zstd.jni)
}
