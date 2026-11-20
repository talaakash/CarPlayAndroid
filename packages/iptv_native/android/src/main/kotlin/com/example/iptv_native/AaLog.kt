package com.example.iptv_native

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Structured diagnostics for the Android Auto side, all under ONE tag:
 *
 *     adb logcat -s ANDROID_AUTO_DEBUG
 *
 * Every line is prefixed with the process id and a per-process "boot" id, so a log can tell apart
 * "same process, new session" from "new process", and [Build] identity is printed once per process
 * so it is obvious which APK produced the output.
 */
object AaLog {
    const val TAG = "ANDROID_AUTO_DEBUG"

    /** Bumped whenever the car UI behaviour changes; printed so stale installs are recognisable. */
    const val CAR_UI_REVISION = "car-ui-r3-web-shell"

    private val bootId = java.lang.Long.toHexString(System.nanoTime()).takeLast(6)
    private var identityLogged = false

    fun i(area: String, msg: String) { emit('I', area, msg, null) }
    fun w(area: String, msg: String) { emit('W', area, msg, null) }
    fun e(area: String, msg: String, t: Throwable? = null) { emit('E', area, msg, t) }

    // ---- persistent file log (exportable from the app) --------------------------------
    //
    // Everything above is mirrored into files/aa_debug/aa_log.txt so a log taken in a real car
    // (no adb) can be exported from Settings > Diagnostics. Two rotating files, identical
    // consecutive lines collapsed, so a long drive cannot grow it without bound.

    private const val MAX_FILE_BYTES = 1_500_000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aa-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var dir: File? = null
    private var out: java.io.Writer? = null
    private var lastLine: String? = null
    private var repeat = 0

    /** Call from every entry point (service/plugin onCreate); cheap and idempotent. */
    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir == null) dir = File(context.applicationContext.filesDir, "aa_debug").apply { mkdirs() }
        }
    }

    private fun emit(level: Char, area: String, msg: String, t: Throwable?) {
        val line = "[$bootId/${android.os.Process.myPid()}] $area: $msg"
        when (level) {
            'W' -> Log.w(TAG, line)
            'E' -> Log.e(TAG, line, t)
            else -> Log.i(TAG, line)
        }
        val d = dir ?: return
        val time = synchronized(stamp) { stamp.format(Date()) }
        val full = if (t == null) line else line + "\n" + Log.getStackTraceString(t)
        io.execute { try { write(d, time, level, line, full) } catch (_: Throwable) {} }
    }

    private fun write(d: File, time: String, level: Char, key: String, full: String) {
        if (key == lastLine) { repeat++; return }
        val w = out ?: java.io.OutputStreamWriter(java.io.FileOutputStream(File(d, "aa_log.txt"), true), Charsets.UTF_8).also { out = it }
        if (repeat > 0) { w.write("$time   (previous line repeated $repeat more times)\n"); repeat = 0 }
        lastLine = key
        w.write("$time $level $full\n")
        w.flush()
        val f = File(d, "aa_log.txt")
        if (f.length() > MAX_FILE_BYTES) {
            w.close(); out = null
            File(d, "aa_log.1.txt").delete()
            f.renameTo(File(d, "aa_log.1.txt"))
        }
    }

    /** Last lines of this app's own system log (EGL / Surface / host errors are only there). */
    fun snapshotSystemLog(reason: String) {
        val d = dir ?: return
        io.execute {
            try {
                val p = ProcessBuilder("logcat", "-d", "-t", "300", "-v", "threadtime").redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                val w = out ?: java.io.OutputStreamWriter(java.io.FileOutputStream(File(d, "aa_log.txt"), true), Charsets.UTF_8).also { out = it }
                w.write("---- system log snapshot ($reason) ----\n$text---- end snapshot ----\n")
                w.flush(); lastLine = null; repeat = 0
            } catch (t: Throwable) { Log.w(TAG, "snapshot failed: $t") }
        }
    }

    /** Whole log as one file for sharing: header + rotated + current + fresh system-log tail. */
    fun buildExport(context: Context): File {
        val d = File(context.filesDir, "aa_debug").apply { mkdirs() }
        val outDir = File(context.cacheDir, "aa_share").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val f = File(outDir, "android_auto_debug_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
        // Flush pending writes first (the single-thread executor is FIFO).
        try { io.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
        f.bufferedWriter().use { w ->
            w.write("=== Android Auto debug export ===\n")
            w.write("exported=${Date()}\n${header(context)}\n\n")
            for (n in listOf("aa_log.1.txt", "aa_log.txt")) {
                val x = File(d, n); if (x.exists()) { w.write("=== $n ===\n"); x.reader().use { it.copyTo(w) }; w.write("\n") }
            }
            try {
                val p = ProcessBuilder("logcat", "-d", "-t", "500", "-v", "threadtime").redirectErrorStream(true).start()
                w.write("=== system log tail (this process) ===\n"); w.write(p.inputStream.bufferedReader().readText()); p.waitFor()
            } catch (_: Throwable) {}
        }
        return f
    }

    fun clear() {
        val d = dir ?: return
        io.execute {
            try { out?.close() } catch (_: Throwable) {}
            out = null; lastLine = null; repeat = 0
            File(d, "aa_log.txt").delete(); File(d, "aa_log.1.txt").delete()
        }
    }

    fun sizeBytes(): Long { val d = dir ?: return 0; return (File(d, "aa_log.txt").length() + File(d, "aa_log.1.txt").length()) }

    private fun header(context: Context): String = try {
        val pm = context.packageManager
        fun ver(pkg: String) = try { pm.getPackageInfo(pkg, 0).let { "${it.versionName} (${it.lastUpdateTime})" } } catch (_: Throwable) { "not installed" }
        "app=${context.packageName} ${ver(context.packageName)} uiRevision=$CAR_UI_REVISION\n" +
            "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}\n" +
            "androidAutoHost=${ver("com.google.android.projection.gearhead")}"
    } catch (t: Throwable) { "header failed: $t" }

    /** Package id, version, build type, install time and signing cert hash - once per process. */
    @Synchronized
    fun logIdentity(context: Context, origin: String) {
        init(context)
        if (identityLogged) { i("identity", "(again from $origin)"); return }
        identityLogged = true
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, 0)
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            i("identity", "origin=$origin package=${context.packageName} version=${info.versionName}+$code " +
                "buildType=${if (debuggable) "debug" else "release"} debuggable=$debuggable " +
                "lastUpdate=${info.lastUpdateTime} carUiRevision=$CAR_UI_REVISION " +
                "sdk=${Build.VERSION.SDK_INT} device=${Build.MODEL}")
            i("identity", "apk=${context.applicationInfo.sourceDir}")
            i("identity", header(context).replace("\n", " | "))
        } catch (t: Throwable) {
            e("identity", "failed", t)
        }
    }
}
