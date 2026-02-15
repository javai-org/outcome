plugins {
    id("java")
    id("signing")
    id("com.vanniktech.maven.publish") version "0.36.0"
}

signing {
    useGpgCmd()
}

group = "org.javai"
version = property("outcomeVersion") as String

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Optional: SLF4J support for Log4jOpReporter and MetricsOpReporter
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.14.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.21.0")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("org.javai", "outcome", version.toString())

    pom {
        name.set("Outcome")
        description.set("A framework for building action plans based on natural language inputs")
        url.set("https://github.com/javai-org/outcome")

        licenses {
            license {
                name.set("Attribution Required License (ARL-1.0)")
                url.set("https://github.com/javai-org/outcome/blob/main/LICENSE")
            }
        }

        developers {
            developer {
                id.set("mikemannion")
                name.set("Michael Franz Mannion")
                email.set("michaelmannion@me.com")
            }
        }

        scm {
            url.set("https://github.com/javai-org/outcome")
            connection.set("scm:git:git://github.com/javai-org/outcome.git")
            developerConnection.set("scm:git:ssh://github.com/javai-org/outcome.git")
        }
    }
}

// ========== Release Lifecycle ==========

fun runCommand(vararg args: String) {
    val process = ProcessBuilder(*args)
        .directory(projectDir)
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException("Command failed (exit $exitCode): ${args.joinToString(" ")}")
    }
}

fun runCommandAndCapture(vararg args: String): String {
    val process = ProcessBuilder(*args)
        .directory(projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output.trim()
}

tasks.register("release") {
    description = "Validates, publishes to Maven Central, tags the release, and bumps to next SNAPSHOT"
    group = "publishing"

    doLast {
        val ver = project.property("outcomeVersion") as String

        // 1. Validate not a SNAPSHOT
        if (ver.endsWith("-SNAPSHOT")) {
            throw GradleException(
                "Cannot release a SNAPSHOT version ($ver). " +
                "Set the release version in gradle.properties first, e.g. outcomeVersion=0.2.0"
            )
        }

        // 2. Validate clean git state
        val statusOutput = runCommandAndCapture("git", "status", "--porcelain")
        if (statusOutput.isNotEmpty()) {
            throw GradleException(
                "Cannot release with uncommitted changes. Commit or stash them first.\n$statusOutput"
            )
        }

        // 3. Create annotated tag locally (before publish, so a successful publish always has a tag)
        val tag = "v$ver"
        logger.lifecycle("Creating tag $tag...")
        runCommand("git", "tag", "-a", tag, "-m", "Release $ver")

        // 4. Publish to Maven Central (delete local tag if this fails)
        logger.lifecycle("Publishing $ver to Maven Central...")
        try {
            runCommand("./gradlew", "publishAndReleaseToMavenCentral")
        } catch (e: Exception) {
            logger.lifecycle("Publishing failed — removing local tag $tag")
            runCommand("git", "tag", "-d", tag)
            throw e
        }

        // 5. Push tag (artifact is published, so the tag must reach the remote)
        logger.lifecycle("Pushing tag $tag to origin...")
        runCommand("git", "push", "origin", tag)

        // 6. Bump to next SNAPSHOT
        val parts = ver.split(".")
        val nextPatch = parts[2].toInt() + 1
        val nextVersion = "${parts[0]}.${parts[1]}.$nextPatch-SNAPSHOT"
        logger.lifecycle("Bumping version to $nextVersion...")

        val propsFile = file("gradle.properties")
        propsFile.writeText(propsFile.readText().replace("outcomeVersion=$ver", "outcomeVersion=$nextVersion"))

        runCommand("git", "add", "gradle.properties")
        runCommand("git", "commit", "-m", "Bump version to $nextVersion")
        runCommand("git", "push")

        logger.lifecycle("Release $ver complete. Version bumped to $nextVersion.")
    }
}

tasks.register("tagRelease") {
    description = "Creates and pushes a release tag for a given version (e.g. -PreleaseVersion=0.1.0)"
    group = "publishing"

    doLast {
        val ver = project.findProperty("releaseVersion") as String?
            ?: throw GradleException("Specify -PreleaseVersion=<version>, e.g. ./gradlew tagRelease -PreleaseVersion=0.1.0")

        val tag = "v$ver"
        val commitish = (project.findProperty("commitish") as String?) ?: "HEAD"

        logger.lifecycle("Creating tag $tag at $commitish...")
        runCommand("git", "tag", "-a", tag, commitish, "-m", "Release $ver")

        logger.lifecycle("Pushing tag $tag to origin...")
        runCommand("git", "push", "origin", tag)

        logger.lifecycle("Tag $tag created and pushed.")
    }
}

tasks.register("publishLocal") {
    description = "Publishes to the local Maven repository"
    group = "publishing"
    dependsOn(tasks.publishToMavenLocal)
}
