package dev.otgformat.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the details of a crash so they can be sent without a logcat capture.
 *
 * An app that dies takes its stack trace with it, and asking someone to attach
 * a debugger or read a log is asking them to become a developer. The trace is
 * written to a file instead, shown on the next launch, and leaves as a single
 * block of text — the same bargain as the self-test.
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"

    /**
     * Installs the handler.
     *
     * The previously installed handler is called afterwards, so Android still
     * does whatever it would normally do — the process still dies, and nothing
     * here tries to keep a broken app running.
     */
    fun install(context: Context) {
        val application = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(application, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(context: Context, thread: Thread, error: Throwable) {
        val text = buildString {
            appendLine("OTGFormat ${BuildConfig.VERSION_NAME} crash report")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
            appendLine(
                "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            appendLine("Thread: ${thread.name}")
            appendLine()
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            append(writer.toString())
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** The last saved crash, or null if the app has not crashed since it was cleared. */
    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
