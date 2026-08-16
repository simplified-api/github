plugins {
    id("java-library")
    idea
}

group = "dev.sbs"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven(url = "https://central.sonatype.com/repository/maven-snapshots")
    maven(url = "https://jitpack.io")
}

dependencies {
    // Simplified Annotations
    compileOnly(libs.simplified.annotations)
    annotationProcessor(libs.simplified.annotations)
    testCompileOnly(libs.simplified.annotations)
    testAnnotationProcessor(libs.simplified.annotations)

    // Tests
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)

    // Simplified Libraries (github.com/simplified-dev)
    api("com.github.simplified-dev:client") { version { strictly("1ca9934") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("f143dc1") } }

    // Gson - DTO bindings + the Gson-bound exception body parsing
    api(libs.gson)
}

tasks {
    test {
        useJUnitPlatform()
    }
}
