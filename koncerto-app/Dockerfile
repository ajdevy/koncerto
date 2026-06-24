FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    bash \
    wget \
    docker-cli \
    docker-compose \
    git \
    nodejs \
    npm \
    github-cli \
    chromium \
    nss \
    freetype \
    harfbuzz \
    ca-certificates \
    ttf-freefont \
    ffmpeg \
    xvfb

RUN npm install -g \
    @openai/codex \
    @anthropic-ai/claude-code \
    playwright && \
    npx playwright install ffmpeg

ENV PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium-browser
ENV NODE_PATH=/usr/local/lib/node_modules

WORKDIR /app

COPY koncerto-app/build/libs/koncerto-app-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
