package com.wangzan101.mika.task

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** A clock sample records the boot identity: elapsed time cannot be compared across reboots. */
internal data class DeadlineTime(val wallMs: Long, val elapsedMs: Long, val boot: String)

@Serializable
internal data class TaskDeadlineEntry(
    val runKey: String,
    val budgetMs: Long,
    val wallDueMs: Long,
    val elapsedDueMs: Long,
    val boot: String,
    val remoteId: String? = null,
    val triggered: Boolean = false,
) {
    fun remaining(now: DeadlineTime): Long = if (boot == now.boot)
        (elapsedDueMs - now.elapsedMs).coerceAtLeast(0L)
    else (wallDueMs - now.wallMs).coerceAtLeast(0L)

    companion object {
        fun start(key: String, budgetMs: Long, now: DeadlineTime, remoteId: String? = null, triggered: Boolean = false): TaskDeadlineEntry {
            require(key.isNotBlank() && budgetMs > 0)
            return TaskDeadlineEntry(key, budgetMs, Math.addExact(now.wallMs, budgetMs),
                Math.addExact(now.elapsedMs, budgetMs), now.boot, remoteId, triggered)
        }
    }
}

/** No goal, credentials or screen contents. fsync + atomic replacement precede alarm registration. */
internal class TaskDeadlineStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val entries: LinkedHashMap<String, TaskDeadlineEntry> = if (file.isFile) {
        try { json.decodeFromString<List<TaskDeadlineEntry>>(file.readText()).associateByTo(linkedMapOf()) { it.runKey } }
        catch (e: kotlinx.serialization.SerializationException) {
            // Preserve evidence. Generic run recovery still interrupts all old runs; none resume.
            file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            linkedMapOf()
        }
    } else linkedMapOf()

    @Synchronized fun all(): List<TaskDeadlineEntry> = entries.values.toList()
    @Synchronized fun get(key: String) = entries[key]
    @Synchronized fun put(entry: TaskDeadlineEntry) {
        save(entries + (entry.runKey to entry))
    }
    @Synchronized fun remove(key: String) {
        if (key in entries) save(entries - key)
    }

    private fun save(next: Map<String, TaskDeadlineEntry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out -> out.write(json.encodeToString(next.values.toList()).toByteArray()); out.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        entries.clear()
        entries.putAll(next)
    }
}