FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    bash \
    wget \
    docker-cli \
    git

WORKDIR /app

COPY koncerto-app/build/libs/koncerto-app-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
