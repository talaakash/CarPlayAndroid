package com.example.iptv_native

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Read-only view of the channel cache written by Dart's PlaylistService, plus
 * the favorites file (Dart is the only writer) and the recents file (native is
 * the only writer). Using one writer per file avoids cross-runtime races.
 */
object IptvStorage {
    data class Channel(
        val id: String,
        val name: String,
        val url: String,
        val logo: String,
        val group: String,
        val country: String,
    )

    const val MAX_RECENTS = 50

    /** What the phone (or the car) is currently browsing: all | favorites | recents | cat:<group>. */
    data class Selection(val scope: String, val label: String, val version: Long, val origin: String)

    @Volatile
    var onSelectionChanged: ((Selection) -> Unit)? = null

    /** Invoked (on any thread) after recents change so Flutter can refresh. */
    @Volatile
    var onUserDataChanged: (() -> Unit)? = null

    // ---- change hub: lets the car session (same process) react to any shared-data change ----

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** kind: "data" (favorites / recents / channels), "settings" (output, fit), "selection". */
    fun addChangeListener(l: (String) -> Unit) { changeListeners.addIfAbsent(l) }
    fun removeChangeListener(l: (String) -> Unit) { changeListeners.remove(l) }
    fun fireChange(kind: String) { for (l in changeListeners) l(kind) }

    // ---- player settings shared by phone and car (single source of truth) ----

    const val OUTPUT_PHONE = "phone"
    const val OUTPUT_CAR = "car"
    const val FIT = "fit"
    const val FILL = "fill"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("iptv_native_prefs", Context.MODE_PRIVATE)

    /** Where the video is rendered: "phone" or "car". */
    fun outputMode(context: Context): String =
        prefs(context).getString("output", OUTPUT_PHONE).takeIf { it == OUTPUT_CAR } ?: OUTPUT_PHONE

    /** "fit" (letterbox) or "fill" (crop), applied to both phone and car. */
    fun videoFit(context: Context): String =
        prefs(context).getString("fit", FIT).takeIf { it == FILL } ?: FIT

    fun setOutputMode(context: Context, mode: String): Boolean {
        val v = if (mode == OUTPUT_CAR) OUTPUT_CAR else OUTPUT_PHONE
        if (outputMode(context) == v) return false
        prefs(context).edit().putString("output", v).commit()
        fireChange("settings")
        return true
    }

    fun setVideoFit(context: Context, fit: String): Boolean {
        val v = if (fit == FILL) FILL else FIT
        if (videoFit(context) == v) return false
        prefs(context).edit().putString("fit", v).commit()
        fireChange("settings")
        return true
    }

    private val lock = Any()
    private var cachedStamp = -1L
    private var cachedChannels: List<Channel> = emptyList()
    private var cachedById: Map<String, Channel> = emptyMap()

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "iptv").also { it.mkdirs() }

    private fun file(context: Context, name: String) = File(dir(context), name)

    fun channels(context: Context): List<Channel> = synchronized(lock) {
        val f = file(context, "channels.json")
        val stamp = if (f.exists()) f.lastModified() xor f.length() else 0L
        if (stamp != cachedStamp) {
            cachedChannels = if (f.exists()) parseChannels(f) else emptyList()
            cachedById = cachedChannels.associateBy { it.id }
            cachedStamp = stamp
        }
        cachedChannels
    }

    /** Local videos picked on the phone (local_videos.json, written by Dart). url = content:// URI. */
    fun localVideos(context: Context): List<Channel> = synchronized(lock) {
        val f = file(context, "local_videos.json")
        if (f.exists()) parseChannels(f) else emptyList()
    }

    fun channelById(context: Context, id: String): Channel? {
        channels(context)
        return synchronized(lock) { cachedById[id] }
    }

    private fun parseChannels(f: File): List<Channel> = try {
        val arr = JSONArray(f.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Channel(
                o.getString("id"), o.getString("name"), o.getString("url"),
                o.optString("logo"), o.optString("group"), o.optString("country"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Group name -> channel count, sorted by name. */
    fun groups(context: Context): List<Pair<String, Int>> =
        channels(context).groupingBy { it.group }.eachCount().toList()
            .sortedBy { it.first.lowercase() }

    /** Country -> channel count (unknown countries last), sorted by name. */
    fun countries(context: Context): List<Pair<String, Int>> =
        channels(context).groupingBy { it.country.ifBlank { UNKNOWN_COUNTRY } }.eachCount().toList()
            .sortedWith(compareBy({ it.first == UNKNOWN_COUNTRY }, { it.first.lowercase() }))

    const val UNKNOWN_COUNTRY = "Unknown"

    /** "A".."Z", or "#" for names starting with anything else. */
    fun azBucket(name: String): String =
        name.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

    fun azBuckets(context: Context): List<Pair<String, Int>> =
        channels(context).groupingBy { azBucket(it.name) }.eachCount().toList()
            .sortedWith(compareBy({ it.first == "#" }, { it.first }))

    data class Playlist(val name: String, val url: String, val active: Boolean)

    /**
     * Playlists configured in the phone app. Flutter's shared_preferences keeps them in
     * FlutterSharedPreferences ("flutter." prefix); channels.json only ever holds the ACTIVE one.
     */
    fun playlists(context: Context): List<Playlist> = try {
        val prefs = context.applicationContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("flutter.iptv.playlists", null) ?: "[]")
        val active = prefs.getLong("flutter.iptv.activePlaylist", 0L).toInt()
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Playlist(o.getString("name"), o.getString("url"), i == active)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun isFavorite(context: Context, id: String): Boolean =
        idList(file(context, "favorites.json"), "favorites").contains(id)

    /**
     * Toggles a favorite from the car. Dart reloads favorites.json on the "userData" event and
     * re-reads the file before each of its own toggles, so the two writers never overwrite
     * each other. Returns the new state.
     */
    fun toggleFavorite(context: Context, id: String): Boolean {
        val now: Boolean
        synchronized(lock) {
            val f = file(context, "favorites.json")
            val ids = idList(f, "favorites").toMutableList()
            now = if (ids.remove(id)) false else { ids.add(0, id); true }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("favorites", JSONArray(ids)).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        run { onUserDataChanged?.invoke(); fireChange("data") }
        return now
    }

    fun favorites(context: Context): List<Channel> =
        idList(file(context, "favorites.json"), "favorites").mapNotNull { channelById(context, it) }

    fun recents(context: Context): List<Channel> =
        idList(file(context, "recents.json"), "recents").mapNotNull { channelById(context, it) }

    private fun idList(f: File, key: String): List<String> = try {
        if (!f.exists()) emptyList() else {
            val arr = JSONObject(f.readText()).getJSONArray(key)
            List(arr.length()) { arr.getString(it) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun addRecent(context: Context, id: String) {
        synchronized(lock) {
            val f = file(context, "recents.json")
            val ids = idList(f, "recents").toMutableList()
            ids.remove(id)
            ids.add(0, id)
            writeRecents(f, ids.take(MAX_RECENTS))
        }
        run { onUserDataChanged?.invoke(); fireChange("data") }
    }

    fun clearRecents(context: Context) {
        synchronized(lock) { writeRecents(file(context, "recents.json"), emptyList()) }
        run { onUserDataChanged?.invoke(); fireChange("data") }
    }

    private fun writeRecents(f: File, ids: List<String>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONObject().put("recents", JSONArray(ids)).toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}

/** Browsing selection shared by phone and car. selection.json has a single writer: native. */
fun IptvStorage.selection(context: Context): IptvStorage.Selection = synchronized(IptvStorage) {
    try {
        val f = java.io.File(dir(context), "selection.json")
        val o = JSONObject(f.readText())
        IptvStorage.Selection(o.getString("scope"), o.getString("label"), o.getLong("version"), o.optString("origin", "phone"))
    } catch (e: Exception) {
        IptvStorage.Selection("all", "All channels", 0, "phone")
    }
}

/** Returns true if the selection actually changed (so callers can skip echoes). */
fun IptvStorage.setSelection(context: Context, scope: String, label: String, origin: String): Boolean {
    val updated: IptvStorage.Selection
    synchronized(IptvStorage) {
        val cur = selection(context)
        if (cur.scope == scope) return false
        updated = IptvStorage.Selection(scope, label, cur.version + 1, origin)
        val f = java.io.File(dir(context), "selection.json")
        val tmp = java.io.File(f.parentFile, "selection.json.tmp")
        tmp.writeText(JSONObject().put("scope", scope).put("label", label)
            .put("version", updated.version).put("origin", origin).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
    onSelectionChanged?.invoke(updated)
    fireChange("selection")
    return true
}
