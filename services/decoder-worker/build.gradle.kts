plugins {
    scala
    application
}

group = "com.kynetics.backpressure"
version = "0.1.0-phase1b"
description = "Phase 1B decoder-worker service."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

application {
    mainClass.set("com.kynetics.backpressure.decoder.DecoderWorkerMain")
}

dependencies {
    implementation(libs.scala3.library)
    implementation(libs.pekko.actor.typed)
    implementation(libs.pekko.stream)
    implementation("com.lihaoyi:upickle_3:3.3.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
