package com.example.iptv_native

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Browser history shared by the phone and the car: one file, one owner (native), so both sides always
 * see the same list. The phone (Dart) reads and writes through the plugin; the car browser writes
 * directly. Every change fires [addListener] callbacks (any thread) so the phone list and the car
 * list refresh without a restart.
 */
object BrowserHistory {
    data class Entry(val url: String, val title: String, val time: Long)

    const val MAX = 100
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { for (l in listeners) l() }

    private fun file(ctx: Context) = File(File(ctx.applicationContext.filesDir, "iptv").also { it.mkdirs() }, "browser_history.json")

    fun list(ctx: Context): List<Entry> = synchronized(lock) { read(file(ctx)) }

    /** Adds [url] at the top (an older entry for the same page is replaced). Blank [title] keeps the old one. */
    fun record(ctx: Context, rawUrl: String, title: String, time: Long = System.currentTimeMillis()) {
        val url = WebUrls.normalize(rawUrl) ?: return
        var changed = false
        synchronized(lock) {
            val f = file(ctx)
            val all = read(f).toMutableList()
            val i = all.indexOfFirst { WebUrls.same(it.url, url) }
            val old = if (i >= 0) all.removeAt(i) else null
            val t = title.ifBlank { old?.title.orEmpty() }
            // Same page, same title, seen moments ago (several load callbacks per page): nothing to do.
            if (old != null && i == 0 && old.title == t && time - old.time < 2_000) { all.add(0, old); return@synchronized }
            all.add(0, Entry(url, t, time))
            write(f, all.take(MAX))
            changed = true
        }
        if (changed) fire()
    }

    fun remove(ctx: Context, url: String) {
        synchronized(lock) {
            val f = file(ctx)
            val all = read(f)
            val left = all.filterNot { WebUrls.same(it.url, url) }
            if (left.size == all.size) return
            write(f, left)
        }
        fire()
    }

    fun clear(ctx: Context) {
        synchronized(lock) { write(file(ctx), emptyList()) }
        fire()
    }

    /** One-time import of history that older builds kept on the Dart side; existing entries win. */
    fun import(ctx: Context, items: List<Entry>) {
        synchronized(lock) {
            val f = file(ctx)
            val have = read(f)
            val extra = items.filter { n -> have.none { WebUrls.same(it.url, n.url) } }
            if (extra.isEmpty()) return
            write(f, (have + extra).sortedByDescending { it.time }.take(MAX))
        }
        fire()
    }

    private fun read(f: File): List<Entry> = try {
        if (!f.exists()) emptyList() else {
            val a = JSONObject(f.readText()).getJSONArray("items")
            (0 until a.length()).mapNotNull {
                val o = a.optJSONObject(it) ?: return@mapNotNull null
                val u = o.optString("u")
                if (u.isEmpty()) null else Entry(u, o.optString("t"), o.optLong("at"))
            }
        }
    } catch (_: Throwable) { emptyList() }

    private fun write(f: File, items: List<Entry>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("at", it.time)) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONObject().put("items", a).toString())
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
}
