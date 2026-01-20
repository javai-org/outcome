plugins {
    id("java")
}

group = "org.javai"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Optional: Log4j2 support for Log4jOpReporter
    compileOnly("org.apache.logging.log4j:log4j-api:2.24.3")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.apache.logging.log4j:log4j-api:2.24.3")
    testImplementation("org.apache.logging.log4j:log4j-core:2.24.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}