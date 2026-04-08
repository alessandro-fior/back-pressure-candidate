 import java.io.ByteArrayOutputStream
import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    base
}

description = "Phase 0 Gradle bootstrap for the back-pressure repository."

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun requiredVersion(alias: String): String =
    libsCatalog.findVersion(alias).orElseThrow {
        GradleException("Missing version alias '$alias' in gradle/libs.versions.toml.")
    }.requiredVersion

val javaBaseline = requiredVersion("java")
val gradleWrapperVersion = requiredVersion("gradle")
val springBootVersion = requiredVersion("spring-boot")
val kotlinVersion = requiredVersion("kotlin")
val scalaVersion = requiredVersion("scala")
val pekkoVersion = requiredVersion("pekko")
val nodeVersion = requiredVersion("node")
val typeScriptVersion = requiredVersion("typescript")
val pulumiVersion = requiredVersion("pulumi")

fun Project.captureCommand(vararg command: String): String? {
    val output = ByteArrayOutputStream()

    return try {
        val result = exec {
            commandLine(command.toList())
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }

        if (result.exitValue == 0) {
            output.toString().trim().ifBlank { null }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

fun normalizeToolVersion(raw: String): String =
    raw.lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .removePrefix("v")
        .substringBefore(' ')

fun parseJavaMajor(version: String): Int? =
    Regex("""^(\d+)""")
        .find(version)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

tasks.named<Wrapper>("wrapper") {
    gradleVersion = gradleWrapperVersion
    distributionType = Wrapper.DistributionType.BIN
}

tasks.register("toolchainVersions") {
    group = "help"
    description = "Shows the pinned Phase 0 toolchain versions and the locally detected tools."

    doLast {
        logger.lifecycle("Pinned Phase 0 toolchain versions:")
        listOf(
            "Java" to javaBaseline,
            "Gradle wrapper" to gradleWrapperVersion,
            "Spring Boot" to springBootVersion,
            "Kotlin" to kotlinVersion,
            "Scala" to scalaVersion,
            "Apache Pekko" to pekkoVersion,
            "Node.js" to nodeVersion,
            "TypeScript" to typeScriptVersion,
            "Pulumi CLI" to pulumiVersion,
        ).forEach { (label, version) ->
            logger.lifecycle(" - $label: $version")
        }

        logger.lifecycle("")
        logger.lifecycle("Detected local tools:")
        logger.lifecycle(" - JVM running Gradle: ${System.getProperty("java.version")}")
        logger.lifecycle(
            " - Node.js on PATH: ${project.captureCommand("node", "--version")?.let(::normalizeToolVersion) ?: "missing"}",
        )
        logger.lifecycle(
            " - Pulumi CLI on PATH: ${project.captureCommand("pulumi", "version")?.let(::normalizeToolVersion) ?: "missing"}",
        )
    }
}

tasks.register("toolchainCheck") {
    group = "verification"
    description = "Verifies the local JVM baseline and PATH availability for Node.js and Pulumi CLI."

    doLast {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val currentJavaVersion = System.getProperty("java.version")
        val requiredJava = javaBaseline.toInt()
        val currentJavaMajor = parseJavaMajor(currentJavaVersion)

        when {
            currentJavaMajor == null -> {
                failures += "Could not parse the current JVM version '$currentJavaVersion'. Run Gradle with Java $javaBaseline LTS."
            }
            currentJavaMajor < requiredJava -> {
                failures += "Gradle is running on Java $currentJavaVersion. Set JAVA_HOME to Java $javaBaseline LTS or newer and retry."
            }
            currentJavaMajor != requiredJava -> {
                warnings += "Gradle is running on Java $currentJavaVersion. Java $javaBaseline LTS remains the repository baseline."
            }
        }

        val detectedNode = project.captureCommand("node", "--version")?.let(::normalizeToolVersion)
        if (detectedNode == null) {
            failures += "Node.js is not available on PATH. Install Node.js $nodeVersion and retry."
        } else if (detectedNode != nodeVersion) {
            warnings += "Detected Node.js $detectedNode. The pinned baseline is $nodeVersion."
        }

        val detectedPulumi = project.captureCommand("pulumi", "version")?.let(::normalizeToolVersion)
        if (detectedPulumi == null) {
            failures += "Pulumi CLI is not available on PATH. Install Pulumi CLI $pulumiVersion and retry."
        } else if (detectedPulumi != pulumiVersion) {
            warnings += "Detected Pulumi CLI $detectedPulumi. The pinned baseline is $pulumiVersion."
        }

        warnings.forEach { logger.lifecycle("WARNING: $it") }

        if (failures.isNotEmpty()) {
            failures.forEach { logger.error("ERROR: $it") }
            throw GradleException("Toolchain check failed. Run './gradlew toolchainVersions' for the pinned repository versions.")
        }

        logger.lifecycle("Toolchain check passed.")
    }
}

fun registerInfraTask(taskName: String, targetTask: String, fallbackCommand: String) {
    tasks.register(taskName) {
        group = "infrastructure"
        description = "Delegates to :infra:pulumi-docker:$targetTask when the Pulumi Docker module is available."

        if (findProject(":infra:pulumi-docker") != null) {
            dependsOn(":infra:pulumi-docker:$targetTask")
        } else {
            doLast {
                logger.lifecycle("$taskName cannot run because ':infra:pulumi-docker' is missing.")
                logger.lifecycle("Create the Pulumi TypeScript module first, then delegate to '$fallbackCommand'.")
                logger.lifecycle("Expected tooling baseline: Node.js $nodeVersion, TypeScript $typeScriptVersion, Pulumi CLI $pulumiVersion.")
            }
        }
    }
}

registerInfraTask("infraPreview", "pulumiPreview", "pulumi preview")
registerInfraTask("infraUp", "pulumiUp", "pulumi up")
registerInfraTask("infraDown", "pulumiDestroy", "pulumi destroy")

tasks.register<Exec>("candidateBundle") {
    group = "distribution"
    description = "Creates a candidate-facing repository bundle without the internal maintainer docs."
    workingDir = rootDir
    commandLine("sh", "${rootDir}/tooling/scripts/create-candidate-bundle")
}
