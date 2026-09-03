/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.batch

import app.morphe.manager.patcher.patch.PatchInfo
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.InstallerType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule the queue merges a saved selection with by, which decides what a plan patches with
 * without ever asking. It has to answer exactly as the expert dialog does, or the same app comes
 * out of the queue patched differently than it would have been from the home screen.
 */
class BatchSelectionMergeTest {
    private val installer = InstallerType.STANDARD
    private val architecture = ApkArchitecture.UNIVERSAL

    private fun patch(name: String, include: Boolean) = PatchInfo(
        name = name,
        description = null,
        include = include,
        compatiblePackages = null,
        options = null
    )

    private val patches = listOf(
        patch("Old default", include = true),
        patch("Old opt-in", include = false),
        patch("New default", include = true),
        patch("New opt-in", include = false)
    )

    private val known = setOf("Old default", "Old opt-in")

    @Test
    fun `a patch added since the configuration was saved follows its own default`() {
        assertEquals(
            setOf("New default"),
            newlyAddedDefaults(patches, known, installer, architecture)
        )
    }

    @Test
    fun `a patch the user deselected is not enabled again`() {
        val seen = patches.mapTo(mutableSetOf()) { it.name }

        assertEquals(
            emptySet(),
            newlyAddedDefaults(patches, seen, installer, architecture)
        )
    }

    @Test
    fun `a source added after the app was configured contributes nothing`() {
        assertEquals(
            emptySet(),
            newlyAddedDefaults(patches, null, installer, architecture)
        )
    }
}
