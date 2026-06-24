package com.flexsentlabs.koncerto.deploy

import java.nio.file.Files
import java.nio.file.Path

data class FrameworkInfo(
    val name: String,
    val ports: List<Int>,
    val buildCmd: String?,
    val runCmd: String?
)

class FrameworkDetector {

    fun detectFramework(projectPath: Path): FrameworkInfo? {
        return when {
            hasSpringBoot(projectPath) -> FrameworkInfo(
                "spring-boot", listOf(8080),
                buildCmdFor(projectPath, "build"),
                "java -jar build/libs/*.jar"
            )
            hasPackageJson(projectPath) -> FrameworkInfo(
                "node", listOf(3000),
                "npm install",
                startCommandFromPackageJson(projectPath) ?: "npm start"
            )
            hasPython(projectPath) -> {
                val hasReqTxt = Files.exists(projectPath.resolve("requirements.txt"))
                val hasUv = Files.exists(projectPath.resolve("uv.lock"))
                val hasPyproject = Files.exists(projectPath.resolve("pyproject.toml"))
                val buildCmd = when {
                    hasReqTxt -> "pip install -r requirements.txt"
                    hasPyproject -> "pip install -e ."
                    else -> "pip install -e ."
                }
                val runCmd = detectPythonRunCmd(projectPath)
                FrameworkInfo("python", listOf(5000), buildCmd, runCmd)
            }
            hasGo(projectPath) -> FrameworkInfo(
                "go", listOf(8080),
                "go build -o app .",
                "./app"
            )
            else -> null
        }
    }

    private fun detectPythonRunCmd(projectPath: Path): String {
        val pyproject = projectPath.resolve("pyproject.toml")
        if (Files.exists(pyproject)) {
            try {
                val content = Files.readString(pyproject)
                if (content.contains("uvicorn", ignoreCase = true)) {
                    // Look for the app entry point
                    val mainPy = projectPath.resolve("app/main.py")
                    if (Files.exists(mainPy)) {
                        val mainContent = Files.readString(mainPy)
                        val appMatch = Regex("""(\w+)\s*=\s*FastAPI\(\)""").find(mainContent)
                        if (appMatch != null) {
                            val appVar = appMatch.groupValues[1]
                            return "uvicorn app.main:$appVar --host 0.0.0.0 --port 5000"
                        }
                    }
                    return "uvicorn app.main:app --host 0.0.0.0 --port 5000"
                }
            } catch (_: Exception) {}
        }
        val mainPy = projectPath.resolve("app.py")
        if (Files.exists(mainPy)) return "python app.py"
        val mainDir = projectPath.resolve("app")
        if (Files.exists(mainDir)) return "python -m app.main"
        return "python app.py"
    }

    private fun hasSpringBoot(path: Path): Boolean {
        val gradle = path.resolve("build.gradle.kts").takeIf { Files.exists(it) }
            ?: path.resolve("build.gradle").takeIf { Files.exists(it) }
            ?: path.resolve("pom.xml").takeIf { Files.exists(it) }
            ?: return false
        val content = Files.readString(gradle)
        return content.contains("spring-boot", ignoreCase = true) ||
               content.contains("spring.boot", ignoreCase = true)
    }

    private fun hasPackageJson(path: Path): Boolean =
        Files.exists(path.resolve("package.json"))

    private fun hasPython(path: Path): Boolean =
        Files.exists(path.resolve("requirements.txt")) ||
        Files.exists(path.resolve("Pipfile")) ||
        Files.exists(path.resolve("setup.py")) ||
        Files.exists(path.resolve("pyproject.toml"))

    private fun hasGo(path: Path): Boolean =
        Files.exists(path.resolve("go.mod"))

    private fun buildCmdFor(path: Path, stage: String): String? {
        if (Files.exists(path.resolve("gradlew")) || Files.exists(path.resolve("gradlew.bat"))) {
            return "./gradlew $stage"
        }
        if (Files.exists(path.resolve("mvnw")) || Files.exists(path.resolve("mvnw.cmd"))) {
            return "./mvnw $stage"
        }
        return null
    }

    private fun startCommandFromPackageJson(path: Path): String? {
        val pj = path.resolve("package.json")
        if (!Files.exists(pj)) return null
        return try {
            val text = Files.readString(pj)
            if ("\"start\"" in text) "npm start"
            else if ("\"dev\"" in text) "npm run dev"
            else null
        } catch (_: Exception) { null }
    }
}
