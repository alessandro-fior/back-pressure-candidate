plugins {
    base
}

group = "com.kynetics.backpressure"
version = "0.1.0-phase1b"
description = "Phase 1B Pulumi Docker local stack."

val stackName = "dev"
val pulumiBackendDir = layout.buildDirectory.dir("pulumi-backend")
val pulumiHomePath = System.getenv("PULUMI_HOME")
    ?.takeIf { it.isNotBlank() }
    ?: rootProject.layout.projectDirectory.dir(".pulumi-home").asFile.absolutePath

fun Exec.configurePulumiEnvironment() {
    environment("PULUMI_BACKEND_URL", "file://${pulumiBackendDir.get().asFile.absolutePath}")
    environment("PULUMI_CONFIG_PASSPHRASE", "")
    environment("PULUMI_SKIP_UPDATE_CHECK", "true")
    environment("PULUMI_HOME", pulumiHomePath)
    environment("PULUMI_STACK", stackName)
    workingDir = projectDir
}

val npmInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Install Pulumi TypeScript dependencies."
    commandLine("npm", "ci", "--quiet")
    workingDir = projectDir
    inputs.files(file("package.json"), file("package-lock.json"))
    outputs.dir(file("node_modules"))
}

val npmBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Type-check the Pulumi TypeScript program."
    dependsOn(npmInstall)
    commandLine("npm", "run", "build")
    workingDir = projectDir
    inputs.files(file("package.json"), file("tsconfig.json"), fileTree("src"))
}

val pulumiStackSelect by tasks.registering(Exec::class) {
    group = "infrastructure"
    description = "Select or create the local Pulumi stack."
    dependsOn(npmInstall)
    doFirst {
        pulumiBackendDir.get().asFile.mkdirs()
        file(pulumiHomePath).mkdirs()
    }
    commandLine("pulumi", "stack", "select", stackName, "--create", "--non-interactive")
    configurePulumiEnvironment()
}

val pulumiPreview by tasks.registering(Exec::class) {
    group = "infrastructure"
    description = "Preview the local Docker stack via Pulumi."
    dependsOn(npmBuild, pulumiStackSelect)
    commandLine("pulumi", "preview", "--stack", stackName, "--non-interactive")
    configurePulumiEnvironment()
}

val pulumiUp by tasks.registering(Exec::class) {
    group = "infrastructure"
    description = "Create or update the local Docker stack via Pulumi."
    dependsOn(npmBuild, pulumiStackSelect)
    commandLine("pulumi", "up", "--stack", stackName, "--yes", "--non-interactive", "--skip-preview")
    configurePulumiEnvironment()
}

val pulumiDestroy by tasks.registering(Exec::class) {
    group = "infrastructure"
    description = "Destroy the local Docker stack via Pulumi."
    dependsOn(pulumiStackSelect)
    commandLine("pulumi", "destroy", "--stack", stackName, "--yes", "--non-interactive", "--skip-preview")
    configurePulumiEnvironment()
}

tasks.named("build") {
    dependsOn(npmBuild)
}

tasks.register("describePhase1b") {
    group = "help"
    description = "Describe the Phase 1B Pulumi Docker stack."

    doLast {
        logger.lifecycle("Phase 1B Pulumi Docker stack builds 3 local service images and runs them on one Docker network.")
        logger.lifecycle("Use :infra:pulumi-docker:pulumiPreview or the root infraPreview/infraUp/infraDown tasks.")
    }
}
