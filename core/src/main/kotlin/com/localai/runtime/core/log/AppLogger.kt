package com.localai.runtime.core.log

import android.util.Log
import com.localai.runtime.core.model.LogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * In-memory ring buffer + rotating file logger.
 *
 * - Ring buffer of the last [MAX_LINES] lines exposed as [lines]
 *   (StateFlow of an immutable snapshot list).
 * - Every accepted line is also mirrored to android.util.Log.
 * - File output happens on the injected [scope] with [Dispatchers.IO]:
 *   `log.txt` in [logDir], rotated to `log.1.txt` at [MAX_LOG_BYTES]
 *   (one previous generation is kept).
 * - [d] output (ring, file and logcat) is gated by [verbose]; w/e always log.
 *
 * [exportTo] and [clear] are fire-and-forget on the scope (non-suspend by
 * contract); the write is a snapshot taken synchronously.
 */
class AppLogger(
    private val scope: CoroutineScope,
    private val logDir: File,
    private val verbose: () -> Boolean = { false },
) {

    private val bufferLock = Any()
    private val buffer = ArrayDeque<LogLine>()

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())

    /** Last [MAX_LINES] log lines, oldest first (newest last). */
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private val fileMutex = Mutex()

    init {
        try {
            logDir.mkdirs()
        } catch (_: Throwable) {
            // best-effort
        }
    }

    fun d(tag: String, msg: String) {
        if (verbose()) append("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        append("I", tag, msg, null)
    }

    fun w(tag: String, msg: String) {
        append("W", tag, msg, null)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        append("E", tag, msg, t)
    }

    /** Clears the ring buffer and the on-disk log files. */
    fun clear() {
        synchronized(bufferLock) {
            buffer.clear()
            _lines.value = emptyList()
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    try {
                        currentLogFile().delete()
                        File(logDir, ROTATED_FILE_NAME).delete()
                    } catch (_: Throwable) {
                        // best-effort
                    }
                }
            }
        }
    }

    /**
     * Writes the current ring buffer as plain text (newest last) to [file].
     * Asynchronous on the injected scope; the content snapshot is taken now.
     */
    fun exportTo(file: File) {
        val snapshot = synchronized(bufferLock) { buffer.toList() }
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    file.parentFile?.mkdirs()
                    val builder = StringBuilder()
                    for (line in snapshot) {
                        builder.append(formatLine(line))
                        builder.append('\n')
                    }
                    file.writeText(builder.toString())
                } catch (_: Throwable) {
                    // best-effort
                }
            }
        }
    }

    // ------------------------------------------------------------------ //

    private fun append(level: String, tag: String, msg: String, t: Throwable?) {
        try {
            when (level) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> Log.w(tag, msg)
                else -> Log.e(tag, msg, t)
            }
        } catch (_: Throwable) {
            // logcat unavailable (e.g. stripped runtime)
        }
        val text = if (t == null) msg else msg + "\n" + t.stackTraceToString()
        val line = LogLine(System.currentTimeMillis(), level, tag, text)
        synchronized(bufferLock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeFirst()
            }
            _lines.value = ArrayList(buffer)
        }
        scope.launch { appendToFile(line) }
    }

    private suspend fun appendToFile(line: LogLine) {
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                try {
                    val active = currentLogFile()
                    if (active.length() >= MAX_LOG_BYTES) {
                        val rotated = File(logDir, ROTATED_FILE_NAME)
                        try {
                            rotated.delete()
                        } catch (_: Throwable) {
                            // ignore
                        }
                        try {
                            active.renameTo(rotated)
                        } catch (_: Throwable) {
                            // ignore; new file will be appended anyway
                        }
                    }
                    currentLogFile().appendText(formatLine(line) + "\n")
                } catch (_: Throwable) {
                    // disk full etc. — never crash the app for a log write
                }
            }
        }
    }

    private fun currentLogFile(): File = File(logDir, ACTIVE_FILE_NAME)

    private fun formatLine(line: LogLine): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = line.ts
        val stamp = String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
        )
        return "$stamp ${line.level}/${line.tag}: ${line.message}"
    }

    private companion object {
        const val MAX_LINES = 2000
        const val MAX_LOG_BYTES = 2L * 1024 * 1024 // 2 MB
        const val ACTIVE_FILE_NAME = "log.txt"
        const val ROTATED_FILE_NAME = "log.1.txt"
    }
}
