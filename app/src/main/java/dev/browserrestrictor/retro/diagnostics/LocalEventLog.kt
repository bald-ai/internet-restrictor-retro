package dev.browserrestrictor.retro.diagnostics

import android.content.Context
import java.io.File
import java.time.Instant

class LocalEventLog(context: Context) {
    private val file = File(context.filesDir, "restrictor_diagnostics.log")
    private val lock = Any()

    fun record(event: String) {
        val safe = event.replace('\n', ' ').take(240)
        synchronized(lock) {
            val existing = if (file.exists()) file.readLines().takeLast(MAX_EVENTS - 1) else emptyList()
            file.bufferedWriter().use { writer ->
                existing.forEach { writer.appendLine(it) }
                writer.appendLine("${Instant.now()} $safe")
            }
        }
    }

    fun exportText(): String = synchronized(lock) {
        buildString {
            appendLine("Internet Restrictor diagnostics")
            appendLine("Generated ${Instant.now()}")
            appendLine("No URLs, page content, typed text, or unrelated package names are recorded.")
            appendLine()
            if (file.exists()) append(file.readText()) else appendLine("No events recorded.")
        }
    }

    private companion object {
        const val MAX_EVENTS = 500
    }
}
