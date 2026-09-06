package com.saikai.ptt.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Makes the mandatory architectural constraints executable instead of merely
 * documented.
 *
 * `.claude/CLAUDE.md` and `docs/02_Architecture.md` section 41 list constraints
 * that "must" hold. A rule nobody can check is a rule that erodes: the first
 * time someone needs a Context in a hurry, the boundary quietly moves. These
 * tests fail the build instead.
 */
class ArchitectureRulesTest {

    private val sourceRoot: File = resolveSourceRoot()

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin"),
            File("core/src/main/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Cannot locate core sources. Working directory is " +
                    File(".").absolutePath + ". Tried: " + candidates.joinToString()
            )
    }

    private fun kotlinSources(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `core must not depend on Android`() {
        val offenders = kotlinSources().flatMap { file ->
            file.readLines()
                .map { it.trim() }
                .filter { it.startsWith("import android.") || it.startsWith("import androidx.") }
                .map { "${file.path}: $it" }
        }

        assertTrue(
            "core is a pure Kotlin/JVM module (docs/02_Architecture.md 5.1). It holds the " +
                "protocol codec, the session state machine and the domain model precisely so " +
                "they can be tested on the JVM without a device. An Android import here means " +
                "either the dependency belongs in :app, or an Android plugin was added to " +
                "core's build script.\nOffending imports:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `core must not depend on the app module`() {
        val offenders = kotlinSources().flatMap { file ->
            file.readLines()
                .map { it.trim() }
                .filter { line ->
                    line.startsWith("import com.saikai.ptt.") &&
                        !line.startsWith("import com.saikai.ptt.core.")
                }
                .map { "${file.path}: $it" }
        }

        assertTrue(
            "Dependencies point app -> core and never back (docs/02_Architecture.md 5.2). " +
                "core must not know that an Android application exists.\nOffending imports:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every core package documents its responsibility`() {
        // Each package directory carries a README stating what it owns and which
        // task fills it. This is what keeps the boundaries meaningful while the
        // packages are still empty.
        val packageDirs = sourceRoot.resolve("com/saikai/ptt/core")
            .listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()

        assertTrue("Expected core packages to exist under $sourceRoot", packageDirs.isNotEmpty())

        val undocumented = packageDirs.filterNot { File(it, "README.md").isFile }
        assertTrue(
            "These core packages have no README describing their responsibility: " +
                undocumented.joinToString { it.name },
            undocumented.isEmpty(),
        )
    }
}
