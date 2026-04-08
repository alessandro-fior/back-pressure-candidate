FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /workspace
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY services/decoder-worker services/decoder-worker
RUN ./gradlew --no-daemon :services:decoder-worker:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/services/decoder-worker/build/install/decoder-worker /app
EXPOSE 8082
ENTRYPOINT ["/app/bin/decoder-worker"]
