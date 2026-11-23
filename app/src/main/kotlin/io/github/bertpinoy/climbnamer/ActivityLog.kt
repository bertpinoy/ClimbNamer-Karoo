package io.github.bertpinoy.climbnamer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    /** Enough for a long ride: route load, lookups, position and alerts for every climb. */
    private const val MAX_ENTRIES: Int = 300
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

    /**
     * Writes the whole log to a text file that can be copied off the Karoo over USB:
     * Download/ on Android 10 and later, otherwise Android/data/<app>/files/Download/.
     * Returns where the file was saved.
     */
    fun export(context: Context): String {
        val entries: List<String> = _entries.value
        val stamp: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName: String = "ClimbNamer-activity-$stamp.txt"
        val text: String = (listOf("ClimbNamer ${BuildConfig.VERSION_NAME} activity, newest first", "") + entries)
            .joinToString(separator = "\n", postfix = "\n")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values: ContentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the file")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("Could not write the file")
            "Download/$fileName"
        } else {
            val dir: File = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException("No storage available")
            val f: File = File(dir, fileName)
            f.writeText(text)
            f.absolutePath.substringAfter("/0/").substringAfter("/sdcard/")
        }
    }

    fun clear(): Unit = synchronized(lock) {
        _entries.value = emptyList()
        runCatching { file?.delete() }
        Unit
    }
}
