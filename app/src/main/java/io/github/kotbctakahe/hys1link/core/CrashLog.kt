package io.github.kotbctakahe.hys1link.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last few crashes / background failures in a file the user can copy from Settings. */
object CrashLog {
    private const val MAX = 48 * 1024

    private fun file(ctx: Context) = File(ctx.filesDir, "crash.log")

    @Synchronized
    fun record(ctx: Context, where: String, t: Throwable) {
        runCatching {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = "=== $stamp [$where] Android ${Build.VERSION.RELEASE} ${Build.MODEL}\n$sw\n"
            val f = file(ctx)
            val old = if (f.exists()) f.readText() else ""
            f.writeText((entry + old).take(MAX))
        }
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    fun clear(ctx: Context) { file(ctx).delete() }

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            record(ctx, "crash in ${th.name}", e)
            prev?.uncaughtException(th, e)
        }
    }
}
