plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation("io.micrometer:micrometer-core:1.14.3")
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.3")
}

application {
    mainClass.set("com.postings.server.PostingsServer")
}
