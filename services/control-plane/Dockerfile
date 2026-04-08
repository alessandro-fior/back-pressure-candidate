FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /workspace
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY services/control-plane services/control-plane
RUN ./gradlew --no-daemon :services:control-plane:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/services/control-plane/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
