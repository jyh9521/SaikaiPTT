package com.saikai.ptt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the layering rules inside the :app module.
 *
 * Within one Gradle module nothing but a test stops a package from reaching
 * into another, so the boundaries described in `docs/02_Architecture.md`
 * section 5.2 are checked here.
 */
class ArchitectureRulesTest {

    private val sourceRoot: File = resolveSourceRoot()

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Cannot locate app sources. Working directory is " +
                    File(".").absolutePath + ". Tried: " + candidates.joinToString()
            )
    }

    private fun kotlinSources(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Files whose package is `com.saikai.ptt.ui` or deeper. */
    private fun File.isUiLayer(): Boolean =
        invariantSeparatorsPath.contains("/com/saikai/ptt/ui/")

    /** `di` is the one place allowed to see every layer, in order to assemble them. */
    private fun File.isDependencyInjection(): Boolean =
        invariantSeparatorsPath.contains("/com/saikai/ptt/di/")

    @Test
    fun `nothing outside the UI layer depends on the UI layer`() {
        val offenders = kotlinSources()
            .filterNot { it.isUiLayer() || it.isDependencyInjection() }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.saikai.ptt.ui.") }
                    .map { "${file.path}: $it" }
            }

        assertTrue(
            "Lower layers must never depend on the UI (docs/02_Architecture.md 5.2, " +
                "CLAUDE.md section 41). Network, audio, storage and service code has to stay " +
                "usable with no Activity alive at all -- that is what makes background receive " +
                "work. Only app.di may see every layer, because assembling them is its job." +
                "\nOffending imports:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `Compose stays in the UI layer`() {
        val offenders = kotlinSources()
            .filterNot { it.isUiLayer() }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import androidx.compose.") }
                    .map { "${file.path}: $it" }
            }

        assertTrue(
            "Compose is a presentation concern. A Compose import outside the ui package " +
                "usually means business state is being held in, or shaped by, the UI framework " +
                "-- which breaks when the Activity dies and the Foreground Service keeps " +
                "running.\nOffending imports:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every app package documents its responsibility`() {
        val packageDirs = sourceRoot.resolve("com/saikai/ptt")
            .listFiles()
            ?.filter { it.isDirectory && it.name != "ui" }
            .orEmpty()

        assertTrue("Expected app packages to exist under $sourceRoot", packageDirs.isNotEmpty())

        val undocumented = packageDirs.filterNot { File(it, "README.md").isFile }
        assertTrue(
            "These app packages have no README describing their responsibility: " +
                undocumented.joinToString { it.name },
            undocumented.isEmpty(),
        )
    }
}
