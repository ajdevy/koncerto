FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    bash \
    wget \
    docker-cli \
    git \
    nodejs \
    npm \
    github-cli

RUN npm install -g \
    @openai/codex \
    @anthropic-ai/claude-code

WORKDIR /app

COPY koncerto-app/build/libs/koncerto-app-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
