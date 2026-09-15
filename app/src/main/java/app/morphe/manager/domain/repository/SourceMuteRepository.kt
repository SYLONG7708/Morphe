/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import app.morphe.manager.data.room.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Which sources each app is kept from being patched by.
 *
 * A source switched off in the source list is off for everything; this narrows one app instead.
 * Someone who patches YouTube from one source and Reddit from another stops being asked which to
 * use. Patches added to the source they turned down never join a selection behind their back.
 *
 * Apps are keyed by the package the user patches, not by the install a run produces: a copy is
 * built from the same app and is offered the same sources.
 */
class SourceMuteRepository(db: AppDatabase) {
    private val dao = db.sourceMuteDao()

    /** Muted source UIDs per package. Packages that rule out nothing are absent. */
    val mutedSources: Flow<Map<String, Set<Int>>> = dao.getAll()
        .map { mutes ->
            mutes.groupBy({ it.packageName }, { it.patchBundle })
                .mapValues { (_, uids) -> uids.toSet() }
        }
        .distinctUntilChanged()

    /**
     * The same exclusions read the other way round: apps kept from each source.
     *
     * The source list asks from this end, since a source is what the user is looking at there.
     * It is also the one place either mode can lift an exclusion.
     */
    val mutedApps: Flow<Map<Int, Set<String>>> = dao.getAll()
        .map { mutes ->
            mutes.groupBy({ it.patchBundle }, { it.packageName })
                .mapValues { (_, packages) -> packages.toSet() }
        }
        .distinctUntilChanged()

    /** The sources [packageName] rules out, as a flow of its own for the per-app call sites. */
    fun mutedFor(packageName: String): Flow<Set<Int>> =
        mutedSources.map { it[packageName].orEmpty() }.distinctUntilChanged()

    suspend fun getMutedFor(packageName: String): Set<Int> = dao.getForPackage(packageName).toSet()

    /** Rules every source out for [packageName] except [keepUid], out of [candidates]. */
    suspend fun keepOnly(packageName: String, keepUid: Int, candidates: Set<Int>) =
        dao.replaceForPackage(packageName, candidates - keepUid)

    suspend fun mute(packageName: String, bundleUid: Int) =
        dao.replaceForPackage(packageName, getMutedFor(packageName) + bundleUid)

    /** Offers every source to [packageName] again. */
    suspend fun unmuteAll(packageName: String) = dao.replaceForPackage(packageName, emptySet())

    suspend fun unmute(packageName: String, bundleUid: Int) =
        dao.clearForPackageAndBundle(packageName, bundleUid)

    suspend fun resetForBundle(bundleUid: Int) = dao.clearForBundle(bundleUid)

    /** Sources at least one app is kept from, the other half of what a backup has to cover. */
    suspend fun getAllBundleUids(): List<Int> = dao.getAllBundleUids()

    /** The apps kept from [bundleUid], which a backup carries alongside that source's selection. */
    suspend fun exportForBundle(bundleUid: Int): List<String> = dao.getForBundle(bundleUid)

    /** Replaces what a backup says about [bundleUid], for a restore that starts from a clean slate. */
    suspend fun importForBundle(bundleUid: Int, packageNames: Collection<String>) {
        dao.clearForBundle(bundleUid)
        dao.addForBundle(bundleUid, packageNames)
    }

    /** Adds what a backup says about [bundleUid] to what this device already rules out. */
    suspend fun mergeForBundle(bundleUid: Int, packageNames: Collection<String>) =
        dao.addForBundle(bundleUid, packageNames)

    suspend fun reset() = dao.clear()
}

/**
 * The apps a "stop offering this source" actually reaches, out of [apps].
 *
 * An app is left out when the source has no patches for it, or when it is already kept from it.
 * The case that matters is the third: this is the last source still offered to it. An app with
 * nothing left to patch from would read as one the manager has no patches for, so the exclusion is
 * never recorded rather than recorded and then ignored.
 */
fun appsToKeepFrom(
    bundleUid: Int,
    apps: Set<String>,
    coveredBy: Map<String, Set<Int>>,
    keptFrom: Map<String, Set<Int>>
): Set<String> = apps.filterTo(mutableSetOf()) { app ->
    val covering = coveredBy[app].orEmpty()
    val alreadyKeptFrom = keptFrom[app].orEmpty()

    bundleUid in covering &&
            bundleUid !in alreadyKeptFrom &&
            (covering - alreadyKeptFrom - bundleUid).isNotEmpty()
}

/**
 * Drops the sources [muted] rules out, unless that would leave nothing behind.
 *
 * Every source of an app can end up muted: the last one was deleted, or a stale row outlived the
 * patches it pointed at. Such an app reads as one the manager has no patches for at all. Ignoring
 * the exclusions is the recoverable half of that, since the user then sees more sources than they
 * asked for rather than a dead end they cannot open to fix.
 */
fun <T> List<T>.withoutMutedSources(muted: Set<Int>, uidOf: (T) -> Int): List<T> {
    if (muted.isEmpty()) return this
    return filterNot { uidOf(it) in muted }.ifEmpty { this }
}
