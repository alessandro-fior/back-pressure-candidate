import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "back-pressure"

fun File.isGradleProject(): Boolean =
    resolve("build.gradle.kts").isFile || resolve("build.gradle").isFile

fun Settings.includeBoundProject(projectPath: String, projectDir: File) {
    include(projectPath)
    project(projectPath).projectDir = projectDir
}

rootDir.listFiles()
    ?.asSequence()
    ?.filter(File::isDirectory)
    ?.filter { it.name !in setOf(".git", ".github", ".gradle", "docs", "gradle") }
    ?.filter { it.isGradleProject() }
    ?.sortedBy(File::getName)
    ?.forEach { includeBoundProject(":${it.name}", it) }

listOf("apps", "services", "libraries", "infra")
    .map(::file)
    .filter(File::isDirectory)
    .forEach { containerDir ->
        containerDir.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.filter { it.isGradleProject() }
            ?.sortedBy(File::getName)
            ?.forEach { moduleDir ->
                includeBoundProject(":${containerDir.name}:${moduleDir.name}", moduleDir)
            }
    }
