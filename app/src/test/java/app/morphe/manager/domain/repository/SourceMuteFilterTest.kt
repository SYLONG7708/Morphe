/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule that narrows an app to the sources it is allowed to be patched from, which is what the
 * source list a run is built from reads.
 *
 * The last source standing is the case worth pinning down: leaving an app with none reads as an
 * app no source has patches for, and there is no screen from which to undo that.
 */
class SourceMuteFilterTest {
    private data class Source(val uid: Int)

    private val sources = listOf(Source(1), Source(2), Source(3))

    private fun List<Source>.filtered(muted: Set<Int>) =
        withoutMutedSources(muted) { it.uid }

    @Test
    fun `a source the app is kept from is dropped`() {
        assertEquals(listOf(Source(1), Source(3)), sources.filtered(setOf(2)))
    }

    @Test
    fun `an app that rules out nothing keeps every source`() {
        assertEquals(sources, sources.filtered(emptySet()))
    }

    @Test
    fun `ruling out every source leaves them all, rather than nothing to patch from`() {
        assertEquals(sources, sources.filtered(setOf(1, 2, 3)))
    }

    @Test
    fun `a rule naming a source that is gone changes nothing`() {
        assertEquals(sources, sources.filtered(setOf(99)))
    }

    @Test
    fun `the sources that survive keep the order they arrived in`() {
        assertEquals(listOf(Source(3), Source(1)), listOf(Source(3), Source(2), Source(1)).filtered(setOf(2)))
    }
}

/**
 * Which apps a "stop offering this source" is actually recorded against, asked of a selection of
 * them at once. The last source standing is the case that decides it: an app with none left reads
 * as an app nothing has patches for, so it keeps the one it has.
 */
class AppsToKeepFromTest {
    private val apps = setOf("youtube", "reddit")

    private fun keptFrom(
        bundleUid: Int,
        coveredBy: Map<String, Set<Int>>,
        keptFrom: Map<String, Set<Int>> = emptyMap()
    ) = appsToKeepFrom(bundleUid, apps, coveredBy, keptFrom)

    @Test
    fun `an app with another source left is kept from this one`() {
        assertEquals(
            setOf("youtube", "reddit"),
            keptFrom(1, mapOf("youtube" to setOf(1, 2), "reddit" to setOf(1, 2)))
        )
    }

    @Test
    fun `an app down to its last source keeps it`() {
        assertEquals(
            setOf("youtube"),
            keptFrom(1, mapOf("youtube" to setOf(1, 2), "reddit" to setOf(1)))
        )
    }

    @Test
    fun `a source with nothing for an app is not recorded against it`() {
        assertEquals(
            setOf("youtube"),
            keptFrom(1, mapOf("youtube" to setOf(1, 2), "reddit" to setOf(2, 3)))
        )
    }

    @Test
    fun `an app already kept from the source is left alone`() {
        assertEquals(
            setOf("reddit"),
            keptFrom(
                bundleUid = 1,
                coveredBy = mapOf("youtube" to setOf(1, 2), "reddit" to setOf(1, 2)),
                keptFrom = mapOf("youtube" to setOf(1))
            )
        )
    }

    @Test
    fun `sources the app is already kept from do not count as the ones left to it`() {
        // Two sources cover it, but one is already ruled out, so this is the last one it has
        assertEquals(
            emptySet(),
            keptFrom(
                bundleUid = 1,
                coveredBy = mapOf("youtube" to setOf(1, 2), "reddit" to setOf(1, 2)),
                keptFrom = mapOf("youtube" to setOf(2), "reddit" to setOf(2))
            )
        )
    }
}
