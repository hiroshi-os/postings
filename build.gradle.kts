plugins {
    id("com.diffplug.spotless") version "6.25.0"
    id("net.ltgt.errorprone") version "4.1.0" apply false
    id("me.champeau.jmh") version "0.7.2" apply false
}

allprojects {
    group = "com.postings"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "net.ltgt.errorprone")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.24.0")
            target("src/**/*.java")
        }
    }

    dependencies {
        add("errorprone", "com.google.errorprone:error_prone_core:2.36.0")
        add("testImplementation", platform("org.junit:junit-bom:5.11.4"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core:3.27.3")
        add("testImplementation", "net.jqwik:jqwik:1.9.2")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            includeEngines("junit-jupiter", "jqwik")
        }
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
