FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle/
COPY libs.versions.toml gradle/

RUN ./gradlew --no-daemon dependencies

COPY . .
RUN ./gradlew :koncerto-app:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget

WORKDIR /app

COPY --from=builder /app/koncerto-app/build/libs/koncerto-app-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
