/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.host

import app.morphe.manager.patcher.PatchPipeline
import app.morphe.manager.patcher.PatchRequest
import app.morphe.manager.patcher.PatchingListener
import app.morphe.manager.patcher.logger.LogLevel
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.split.SplitPreparationEvent
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Entry point of the JVM the app embeds for patching, see [serveRun].
 *
 * @param args The read end of a pipe the app writes [HostCommand]s to, one per line, then the write
 *             end of a pipe the app reads [HostEvent]s from.
 */
fun main(args: Array<String>) {
    val (commandsFile, eventsFile) = args
    serveRun(commandsFile, eventsFile) { loadPatchesFromJar(setOf(it)) }
}

/**
 * Serves the single run of a process of its own, in whichever VM it runs, and ends the process
 * with it. The host is started before the run is known, so it can load ahead while the user still
 * picks what to patch.
 *
 * @param commandsFile What the app writes [HostCommand]s to, one per line.
 * @param eventsFile What the app reads [HostEvent]s from.
 * @param loadPatches Reads the patches of a bundle, which is the one step that depends on the VM.
 */
fun serveRun(
    commandsFile: String,
    eventsFile: String,
    loadPatches: (File) -> Collection<Patch<*>>
): Nothing {
    val code = try {
        HostEvents(FileOutputStream(eventsFile)).use { events ->
            // Patches print what they did, which on Android goes to /dev/null or at best logcat.
            // The log is the one place the user sees it
            System.setOut(events.printer())
            System.setErr(events.printer())

            var preloaded = emptyMap<String, PreloadedBundle>()
            File(commandsFile).bufferedReader().useLines { lines ->
                for (line in lines) when (val command = HostCommand.decode(line)) {
                    is HostCommand.Preload ->
                        preloaded = command.bundles.associateWith { PreloadedBundle(File(it), loadPatches) }

                    is HostCommand.Run -> {
                        run(command, preloaded, loadPatches, events)
                        break
                    }
                }
            }
        }
        0
    } catch (_: IOException) {
        // The app stopped reading, so there is nobody left to report to
        1
    }

    // Threads the patches left running must not keep the process, which exists for this run alone
    exitProcess(code)
}

private fun run(
    command: HostCommand.Run,
    preloaded: Map<String, PreloadedBundle>,
    loadPatches: (File) -> Collection<Patch<*>>,
    events: HostEvents
) {
    val failure = runCatching {
        val request = Json.decodeFromString(PatchRequest.serializer(), File(command.requestFile).readText())
        runBlocking {
            PatchPipeline(request, events.logger, events.listener) { bundle ->
                preloaded[bundle.patchesFile]?.take(events.logger)
                    ?: loadPatches(File(bundle.patchesFile))
            }.run()
        }
    }.exceptionOrNull()

    events.send(HostEvent.Finished(failure?.let(HostEvent::Failure)))
}

/** Hands each line written to it over to [onLine], without its line break. */
private class LineOutputStream(private val onLine: (String) -> Unit) : OutputStream() {
    private val line = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        if (b == '\n'.code) {
            onLine(line.toString(Charsets.UTF_8.name()).removeSuffix("\r"))
            line.reset()
        } else {
            line.write(b)
        }
    }
}

/**
 * The patches of a bundle, loaded on a thread of their own from the moment they are asked for. The
 * file may be replaced by an update before the run, so they are only used if it is unchanged.
 */
private class PreloadedBundle(private val file: File, loadPatches: (File) -> Collection<Patch<*>>) {
    private val stamp = stampOf(file)
    private val patches = FutureTask { loadPatches(file) }.also {
        thread(isDaemon = true, name = "PreloadPatches") { it.run() }
    }

    /** The loaded patches, or null if the file changed or loading failed, for them to be loaded again. */
    fun take(logger: Logger): Collection<Patch<*>>? {
        if (stampOf(file) != stamp) return null
        return try {
            patches.get()
        } catch (e: ExecutionException) {
            logger.warn("Loading ${file.name} ahead of the run failed, loading it again: ${e.cause}")
            null
        }
    }

    private companion object {
        fun stampOf(file: File) = file.lastModified() to file.length()
    }
}

/** Sends the run's events to the app, from whichever thread reports them. */
private class HostEvents(stream: OutputStream) : AutoCloseable {
    private val writer = stream.bufferedWriter()

    @Synchronized
    fun send(event: HostEvent) {
        writer.write(HostEvent.encode(event))
        writer.newLine()
        writer.flush()
    }

    val logger = object : Logger() {
        override fun log(level: LogLevel, message: String) = send(HostEvent.Log(level, message))
    }

    val listener = object : PatchingListener {
        override suspend fun stepCompleted() = send(HostEvent.StepCompleted)

        override suspend fun splitPreparation(event: SplitPreparationEvent) =
            send(HostEvent.SplitPreparation(event))

        override suspend fun patchesStarted() = send(HostEvent.PatchesStarted)

        override suspend fun patchSucceeded(name: String) = send(HostEvent.PatchSucceeded(name))

        override suspend fun patchFailed(name: String?, stackTrace: String) =
            send(HostEvent.PatchFailed(name, stackTrace))

        override suspend fun patchesFinished(count: Int) = send(HostEvent.PatchesFinished(count))
    }

    /** A stream whose lines go to the log as warnings, as nothing is meant to be printed. */
    fun printer() = PrintStream(
        LineOutputStream { line -> logger.warn("[STDIO]: $line") },
        true,
        Charsets.UTF_8.name()
    )

    override fun close() = writer.close()
}
