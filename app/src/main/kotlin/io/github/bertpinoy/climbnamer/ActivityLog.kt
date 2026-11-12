package io.github.bertpinoy.climbnamer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What ClimbNamer did and when (route loaded, lookups, alerts, notifications), shown in the app so
 * problems during a ride can be understood afterwards. Kept on the Karoo, newest first, limited size.
 */
object ActivityLog {
    private const val MAX_ENTRIES: Int = 60
    private val lock: Any = Any()
    private var file: File? = null
    private val _entries: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /** Loads the stored log; safe to call more than once. */
    fun init(context: Context): Unit = synchronized(lock) {
        if (file != null) return
        val f: File = File(context.applicationContext.filesDir, "activity_log.txt")
        file = f
        val stored: List<String> = runCatching { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() }
            .getOrDefault(emptyList())
        _entries.value = stored.take(MAX_ENTRIES)
    }

    fun add(message: String): Unit = synchronized(lock) {
        Timber.i("Activity: $message")
        val time: String = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
        val updated: List<String> = (listOf("$time  $message") + _entries.value).take(MAX_ENTRIES)
        _entries.value = updated
        runCatching { file?.writeText(updated.joinToString("\n")) }
        Unit
    }

    fun clear(): Unit = synchronized(lock) {
        _entries.value = emptyList()
        runCatching { file?.delete() }
        Unit
    }
}
