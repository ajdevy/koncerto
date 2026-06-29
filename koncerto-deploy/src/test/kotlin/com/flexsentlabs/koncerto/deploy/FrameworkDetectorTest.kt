package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FrameworkDetectorTest {

    private val detector = FrameworkDetector()
    private val generator = DockerfileGenerator()

    @Test
    fun `python pyproject uses pip install dot for build`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("pyproject.toml"),
            """
            [build-system]
            requires = ["hatchling"]
            build-backend = "hatchling.build"
            """.trimIndent()
        )
        Files.createDirectories(tmpDir.resolve("app"))
        Files.writeString(tmpDir.resolve("app/main.py"), "from fastapi import FastAPI\napp = FastAPI()\n")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.name).isEqualTo("python")
        assertThat(framework?.buildCmd).isEqualTo("pip install --default-timeout 60 --retries 10 .")
        assertThat(generator.generate(framework!!)).contains("pypi.tuna.tsinghua.edu.cn")
        assertThat(generator.generate(framework!!)).contains("RUN pip install")
    }

    @Test
    fun `detects spring boot from gradle kts`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("build.gradle.kts"),
            """
            plugins { id("org.springframework.boot") }
            """.trimIndent()
        )

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.name).isEqualTo("spring-boot")
        assertThat(framework?.ports).isEqualTo(listOf(8080))
    }

    @Test
    fun `detects node from package json`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"node index.js"}}""")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.name).isEqualTo("node")
        assertThat(framework?.runCmd).isEqualTo("npm start")
    }

    @Test
    fun `detects node dev script when start missing`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"dev":"vite"}}""")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.runCmd).isEqualTo("npm run dev")
    }

    @Test
    fun `node defaults to npm start when scripts missing`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("package.json"), """{"name":"demo"}""")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.name).isEqualTo("node")
        assertThat(framework?.runCmd).isEqualTo("npm start")
    }

    @Test
    fun `detects go from go mod`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("go.mod"), "module example.com/app\ngo 1.22\n")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.name).isEqualTo("go")
        assertThat(framework?.buildCmd).isEqualTo("go build -o app .")
    }

    @Test
    fun `detects python from requirements txt`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("requirements.txt"), "fastapi\n")
        Files.writeString(tmpDir.resolve("app.py"), "print('hi')")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.name).isEqualTo("python")
        assertThat(framework?.buildCmd).isEqualTo("pip install --default-timeout 60 --retries 10 -r requirements.txt")
        assertThat(framework?.runCmd).isEqualTo("python app.py")
    }

    @Test
    fun `detects uvicorn run command from pyproject`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("pyproject.toml"), "dependencies = [\"uvicorn\"]\n")
        Files.createDirectories(tmpDir.resolve("app"))
        Files.writeString(tmpDir.resolve("app/main.py"), "from fastapi import FastAPI\napi = FastAPI()\n")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.runCmd).isEqualTo("uvicorn app.main:api --host 0.0.0.0 --port 5000")
    }

    @Test
    fun `uvicorn falls back to app symbol when variable not declared`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("pyproject.toml"), "dependencies = [\"uvicorn\"]\n")
        Files.createDirectories(tmpDir.resolve("app"))
        Files.writeString(tmpDir.resolve("app/main.py"), "from fastapi import FastAPI\n")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.runCmd).isEqualTo("uvicorn app.main:app --host 0.0.0.0 --port 5000")
    }

    @Test
    fun `python pyproject without uvicorn falls back to python module command`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("pyproject.toml"), "[project]\nname = \"demo\"\n")
        Files.createDirectories(tmpDir.resolve("app"))
        Files.writeString(tmpDir.resolve("app/main.py"), "print('ok')\n")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.name).isEqualTo("python")
        assertThat(framework?.buildCmd).isEqualTo("pip install --default-timeout 60 --retries 10 .")
        assertThat(framework?.runCmd).isEqualTo("python -m app.main")
    }

    @Test
    fun `returns null for unknown project`(@TempDir tmpDir: Path) {
        assertThat(detector.detectFramework(tmpDir)).isEqualTo(null)
    }

    @Test
    fun `spring boot uses gradlew when present`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("build.gradle.kts"), "plugins { id(\"spring-boot\") }")
        Files.writeString(tmpDir.resolve("gradlew"), "#!/bin/sh\n")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.buildCmd).isEqualTo("./gradlew build")
    }

    @Test
    fun `detects python main py run command when uvicorn absent`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("requirements.txt"), "flask\n")
        Files.writeString(tmpDir.resolve("app.py"), "app = object()\n")

        val framework = detector.detectFramework(tmpDir)

        assertThat(framework?.runCmd).isEqualTo("python app.py")
    }

    @Test
    fun `buildCmdFor returns gradle wrapper command`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("build.gradle.kts"), "plugins { id(\"spring-boot\") }")
        Files.writeString(tmpDir.resolve("gradlew"), "#!/bin/sh\n")
        val method = FrameworkDetector::class.java.getDeclaredMethod(
            "buildCmdFor", Path::class.java, String::class.java
        )
        method.isAccessible = true
        val cmd = method.invoke(detector, tmpDir, "build") as String?
        assertThat(cmd).isEqualTo("./gradlew build")
    }

    @Test
    fun `detects spring boot from pom xml`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("pom.xml"),
            "<project><artifactId>demo</artifactId><dependency>springframework.boot</dependency></project>"
        )
        val framework = detector.detectFramework(tmpDir)
        assertThat(framework?.name).isEqualTo("spring-boot")
    }

    @Test
    fun `python run command falls back when pyproject read fails`(@TempDir tmpDir: Path) {
        Files.createDirectory(tmpDir.resolve("pyproject.toml"))
        Files.createDirectories(tmpDir.resolve("app"))
        val framework = detector.detectFramework(tmpDir)
        assertThat(framework?.name).isEqualTo("python")
        assertThat(framework?.runCmd).isEqualTo("python -m app.main")
    }

    @Test
    fun `detects spring boot from build gradle fallback`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("build.gradle"), "plugins { id 'spring-boot' }")
        val framework = detector.detectFramework(tmpDir)
        assertThat(framework?.name).isEqualTo("spring-boot")
    }
}
