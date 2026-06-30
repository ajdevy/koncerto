#!/usr/bin/env bash
# scripts/bootstrap-gradle-plugins.sh — Pre-download Gradle plugin markers into a local Maven repo.
# Use when plugins.gradle.org is unreachable but Maven Central (or Aliyun) works, or to enable offline builds.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_REPO="${PROJECT_ROOT}/gradle/local-plugin-repo"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.0.21}"
SPRING_BOOT_VERSION="${SPRING_BOOT_VERSION:-3.3.5}"
SPRING_DM_VERSION="${SPRING_DM_VERSION:-1.1.6}"

if [[ "${KONCERTO_USE_ALIYUN_MIRRORS:-}" == "true" ]]; then
  BASE_URL="https://maven.aliyun.com/repository/public"
else
  BASE_URL="https://repo.maven.apache.org/maven2"
fi

download() {
  local path="$1"
  local dest="${LOCAL_REPO}/${path}"
  mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" ]]; then
    return 0
  fi
  echo "[bootstrap] $path"
  curl -fsSL "${BASE_URL}/${path}" -o "$dest"
}

echo "[bootstrap] Populating ${LOCAL_REPO} from ${BASE_URL}"

# Kotlin JVM + serialization plugin markers and implementation JARs
for artifact in \
  "org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin/${KOTLIN_VERSION}/org.jetbrains.kotlin.jvm.gradle.plugin-${KOTLIN_VERSION}.pom" \
  "org/jetbrains/kotlin/kotlin-gradle-plugin/${KOTLIN_VERSION}/kotlin-gradle-plugin-${KOTLIN_VERSION}.pom" \
  "org/jetbrains/kotlin/kotlin-gradle-plugin/${KOTLIN_VERSION}/kotlin-gradle-plugin-${KOTLIN_VERSION}.jar" \
  "org/jetbrains/kotlin/plugin/serialization/org.jetbrains.kotlin.plugin.serialization.gradle.plugin/${KOTLIN_VERSION}/org.jetbrains.kotlin.plugin.serialization.gradle.plugin-${KOTLIN_VERSION}.pom" \
  "org/jetbrains/kotlin/kotlin-serialization/${KOTLIN_VERSION}/kotlin-serialization-${KOTLIN_VERSION}.pom" \
  "org/jetbrains/kotlin/kotlin-serialization/${KOTLIN_VERSION}/kotlin-serialization-${KOTLIN_VERSION}.jar" \
  "org/springframework/boot/org.springframework.boot.gradle.plugin/${SPRING_BOOT_VERSION}/org.springframework.boot.gradle.plugin-${SPRING_BOOT_VERSION}.pom" \
  "org/springframework/boot/spring-boot-gradle-plugin/${SPRING_BOOT_VERSION}/spring-boot-gradle-plugin-${SPRING_BOOT_VERSION}.pom" \
  "org/springframework/boot/spring-boot-gradle-plugin/${SPRING_BOOT_VERSION}/spring-boot-gradle-plugin-${SPRING_BOOT_VERSION}.jar" \
  "io/spring/gradle/dependency-management/io.spring.dependency-management.gradle.plugin/${SPRING_DM_VERSION}/io.spring.dependency-management.gradle.plugin-${SPRING_DM_VERSION}.pom" \
  "io/spring/gradle/dependency-management-plugin/${SPRING_DM_VERSION}/dependency-management-plugin-${SPRING_DM_VERSION}.pom" \
  "io/spring/gradle/dependency-management-plugin/${SPRING_DM_VERSION}/dependency-management-plugin-${SPRING_DM_VERSION}.jar"
do
  download "$artifact"
done

echo "[bootstrap] Done. Add to gradle.properties:"
echo "  koncerto.useLocalPluginRepo=true"
echo "Then run: ./gradlew help"
