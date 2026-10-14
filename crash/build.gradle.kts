plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.postings.crash.CrashHarnessMain")
}
