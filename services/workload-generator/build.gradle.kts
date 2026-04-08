plugins {
    scala
    application
}

group = "com.kynetics.backpressure"
version = "0.1.0-phase1b"
description = "Phase 1B workload-generator service."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

application {
    mainClass.set("com.kynetics.backpressure.generator.WorkloadGeneratorMain")
}

dependencies {
    implementation(libs.scala3.library)
    implementation(libs.pekko.actor.typed)
    implementation("com.lihaoyi:upickle_3:4.0.2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
