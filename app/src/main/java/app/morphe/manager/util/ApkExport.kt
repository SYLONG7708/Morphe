/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "Morphe ApkExport"

/**
 * Copies a patched APK to a location the user picked through the system document picker.
 *
 * @return true when the file was written in full.
 */
suspend fun Context.exportApkTo(file: File, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        contentResolver.openOutputStream(uri)
            ?.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            ?: throw IOException("Could not open output stream for export")
    }.onFailure {
        Log.e(TAG, "Failed to export ${file.name}", it)
    }.isSuccess
}

/** Creates a new Downloads entry without depending on an OEM document picker. */
@RequiresApi(Build.VERSION_CODES.Q)
suspend fun Context.exportApkToDownloads(file: File, fileName: String): Boolean =
    withContext(Dispatchers.IO) {
        var inserted: Uri? = null
        try {
            require(file.isFile && file.length() > 0) { "No completed APK to export" }
            currentCoroutineContext().ensureActive()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, File(fileName).name)
                put(MediaStore.MediaColumns.MIME_TYPE, APK_MIMETYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SyMorphe")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destination = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create Downloads entry")
            inserted = destination
            val copied = contentResolver.openOutputStream(destination, "w")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open Downloads output stream")
            check(copied == file.length()) { "Incomplete APK export" }
            currentCoroutineContext().ensureActive()
            check(contentResolver.update(destination, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Could not publish completed APK" }
            true
        } catch (error: Exception) {
            // The URI belongs only to the new entry created by this attempt.
            inserted?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed to export APK to Downloads", error)
            false
        }
    }
