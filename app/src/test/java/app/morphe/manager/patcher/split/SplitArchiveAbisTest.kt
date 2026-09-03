/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.split

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ABIs a split archive is read to carry. They stand in for the lib/ directories of the APK
 * the modules are merged into, which is what patches declare their architecture against.
 */
class SplitArchiveAbisTest {
    private val workspace = createTempDirectory("split-abis").toFile()

    @AfterTest
    fun cleanup() {
        workspace.deleteRecursively()
    }

    private var archives = 0

    private fun archive(vararg modules: String): File {
        val file = File(workspace, "bundle-${archives++}.apks")
        ZipOutputStream(file.outputStream()).use { zip ->
            modules.forEach { module ->
                zip.putNextEntry(ZipEntry(module))
                zip.write(module.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `config splits are read by the ABI they are named after`() {
        val file = archive("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk")

        assertEquals(listOf("arm64-v8a"), SplitApkPreparer.splitArchiveAbis(file))
    }

    @Test
    fun `an ABI is not claimed by the shorter name it contains`() {
        val file = archive("base.apk", "split_config.armeabi_v7a.apk", "split_config.x86_64.apk")

        assertEquals(listOf("armeabi-v7a", "x86_64"), SplitApkPreparer.splitArchiveAbis(file))
    }

    @Test
    fun `an archive without ABI splits carries no architecture`() {
        val file = archive("base.apk", "split_config.en.apk")

        assertEquals(emptyList(), SplitApkPreparer.splitArchiveAbis(file))
    }

    @Test
    fun `nested APKs are not split modules`() {
        val file = archive("base.apk", "res/raw/config.arm64-v8a.apk")

        assertEquals(emptyList(), SplitApkPreparer.splitArchiveAbis(file))
    }
}
