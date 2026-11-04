import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
}

dependencies {
    implementation("io.micrometer:micrometer-core:1.14.3")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Werror"))
    options.errorprone {
        isEnabled.set(true)
    }
}
