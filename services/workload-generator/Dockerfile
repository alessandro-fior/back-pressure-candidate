FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /workspace
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY services/workload-generator services/workload-generator
RUN ./gradlew --no-daemon :services:workload-generator:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/services/workload-generator/build/install/workload-generator /app
EXPOSE 8081
ENTRYPOINT ["/app/bin/workload-generator"]
