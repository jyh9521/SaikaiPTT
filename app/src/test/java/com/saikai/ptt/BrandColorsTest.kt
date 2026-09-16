package com.saikai.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The brand colours exist twice and must not drift.
 *
 * `res/values/colors.xml` is what the floating indicator reads: it is a
 * platform View, it needs an int, and `app.overlay` is forbidden to import the
 * UI layer at all (`ArchitectureRulesTest`). `ui/theme/Color.kt` is what
 * Compose reads. Neither can be derived from the other without dragging a
 * resource lookup into a plain `val` or a Kotlin constant into an XML file.
 *
 * So they are two copies, and this is the thing that stops them diverging. A
 * drift here is not cosmetic: green and red carry operational meaning
 * (`docs/04_UI_UX.md` section 3), and an indicator in a slightly different
 * green than the app it belongs to looks like a different app.
 */
class BrandColorsTest {

    private fun projectFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.isFile }
            ?: error(
                "Cannot locate the file. Working directory is " +
                    File(".").absolutePath + ". Tried: " + candidates.joinToString()
            )

    private val colorsXml: File = projectFile(
        "src/main/res/values/colors.xml",
        "app/src/main/res/values/colors.xml",
    )

    private val colorKt: File = projectFile(
        "src/main/java/com/saikai/ptt/ui/theme/Color.kt",
        "app/src/main/java/com/saikai/ptt/ui/theme/Color.kt",
    )

    /** `saikai_green` -> `FF2E7D32`, upper case, alpha first. */
    private fun resourceColors(): Map<String, String> =
        Regex("""<color name="(\w+)">#([0-9A-Fa-f]{8})</color>""")
            .findAll(colorsXml.readText())
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }

    /** `SaikaiGreen` -> `FF2E7D32`. */
    private fun composeColors(): Map<String, String> =
        Regex("""val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
            .findAll(colorKt.readText())
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }

    @Test
    fun `the four operational colours agree in both places`() {
        val resources = resourceColors()
        val compose = composeColors()

        val pairs = mapOf(
            "saikai_green" to "SaikaiGreen",
            "saikai_red" to "SaikaiRed",
            "saikai_amber" to "SaikaiAmber",
            "saikai_grey" to "SaikaiGrey",
        )

        pairs.forEach { (resourceName, composeName) ->
            val fromResources = resources[resourceName]
            val fromCompose = compose[composeName]

            assertTrue(
                "$resourceName is missing from ${colorsXml.path}",
                fromResources != null,
            )
            assertTrue(
                "$composeName is missing from ${colorKt.path}",
                fromCompose != null,
            )
            assertEquals(
                "$resourceName and $composeName have drifted apart. They are the same " +
                    "colour seen by the overlay and by Compose; change both together.",
                fromCompose,
                fromResources,
            )
        }
    }
}
