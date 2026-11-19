package com.example.iptv_native

import android.net.Uri
import android.os.SystemClock

/**
 * Phone <-> car browser link. A process-wide object because the phone UI (Flutter plugin) and the
 * car session ([CarVideoService]) live in the same app process but have unrelated lifecycles.
 * Everything runs on the main thread.
 *
 *  * Phone -> car: [send] validates the URL, removes duplicates and either hands it to the attached
 *    car session or, when a car is connected but its app is not open yet, keeps it as [pending]
 *    (delivered when the session attaches; expires after [PENDING_TTL_MS]).
 *  * Car -> phone: the car session publishes the browser's state with [publish]; the plugin forwards it
 *    to Flutter through [onState].
 */
object CarBrowserBridge {
    /** Implemented by the car session. */
    interface CarSide {
        /** Show [url] and bring the browser screen to the front (explicit "Send to Car"). */
        fun openUrl(url: String)
        /** Make [url] the car browser's page without leaving the current car screen (background sync). */
        fun syncUrl(url: String)
    }

    data class State(val open: Boolean = false, val url: String = "", val title: String = "", val loading: Boolean = false)

    private const val DUPLICATE_WINDOW_MS = 3_000L
    private const val PENDING_TTL_MS = 5 * 60_000L

    private var side: CarSide? = null
    private var pending: Pair<String, Long>? = null
    private var lastSent = ""
    private var lastSentAt = 0L

    @Volatile var state = State(); private set
    var onState: ((State) -> Unit)? = null

    const val UNCONFIRMED = "unconfirmed"
    const val SENT = "sent"
    const val QUEUED = "queued"
    const val DUPLICATE = "duplicate"
    const val NO_CAR = "noCar"
    const val INVALID = "invalid"

    /** @param carConnected platform-confirmed Android Auto / Automotive connection. */
    fun send(rawUrl: String?, carConnected: Boolean): String {
        val url = WebUrls.normalize(rawUrl) ?: return INVALID
        if (!carConnected && side == null) return NO_CAR
        val now = SystemClock.elapsedRealtime()
        if (url == lastSent && now - lastSentAt < DUPLICATE_WINDOW_MS) return DUPLICATE
        val s = side
        if (s != null && state.open && WebUrls.same(state.url, url) && !state.loading) {
            lastSent = url; lastSentAt = now
            return DUPLICATE // the car already shows exactly this page
        }
        lastSent = url; lastSentAt = now
        return if (s != null) { pending = null; s.openUrl(url); SENT } else { pending = url to now; QUEUED }
    }

    /**
     * Phone -> car, background sync: the car's browser follows the phone's page but the car screen is not
     * touched (no interruption of video). The URL is remembered in [lastPage] so a car session that
     * connects (or reconnects) later starts on the same page.
     */
    fun sync(rawUrl: String?, carConnected: Boolean): String {
        val url = WebUrls.normalize(rawUrl) ?: return INVALID
        lastPage = url
        val s = side ?: return if (carConnected) QUEUED else NO_CAR
        if (WebUrls.same(state.url, url)) return DUPLICATE
        s.syncUrl(url)
        return SENT
    }

    /** The page the browser was last on (phone or car); survives a car session ending. */
    @Volatile var lastPage: String? = null; private set

    /** The car session started: deliver a URL that was waiting for it, or restore the last page. */
    fun attach(car: CarSide) {
        side = car
        val p = pending
        pending = null
        val last = lastPage
        if (p != null && SystemClock.elapsedRealtime() - p.second < PENDING_TTL_MS) car.openUrl(p.first)
        else {
            publish(State()) // fresh session: nothing open yet
            if (last != null) car.syncUrl(last)
        }
    }

    fun detach(car: CarSide) {
        if (side === car) { side = null; publish(State()) }
    }

    private var waiting: ((String) -> Unit)? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable { finishWait(UNCONFIRMED) }

    /**
     * After [send] returned [SENT]: call [cb] with [SENT] once the car really shows the browser
     * ([State.open] = web screen visible AND page surface attached), or [UNCONFIRMED] after [timeoutMs]
     * (car showing something else, app not in front, no surface). A newer wait replaces an older one.
     */
    fun awaitConfirm(timeoutMs: Long, cb: (String) -> Unit) {
        finishWait(UNCONFIRMED)
        if (state.open && !state.loading) { cb(SENT); return }
        waiting = cb
        handler.postDelayed(timeout, timeoutMs)
    }

    private fun finishWait(result: String) {
        val cb = waiting ?: return
        waiting = null
        handler.removeCallbacks(timeout)
        cb(result)
    }

    fun publish(s: State) {
        if (s == state) return
        state = s
        if (s.url.isNotEmpty()) lastPage = s.url
        onState?.invoke(s)
        if (s.open) finishWait(SENT)
    }
}

/** URL helpers shared by the phone bridge and the car browser. */
object WebUrls {
    /** http(s) URL with a host, trimmed; null otherwise. */
    fun normalize(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val u = try { Uri.parse(t) } catch (_: Throwable) { return null }
        val scheme = u.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || u.host.isNullOrEmpty()) return null
        return t
    }

    /** Same page ignoring a trailing slash and the fragment. */
    fun same(a: String, b: String): Boolean = key(a) == key(b)
    private fun key(u: String) = u.substringBefore('#').trimEnd('/').lowercase()

    private val host = Regex(
        "^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63})(:\\d{1,5})?([/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )

    /** URL typed on the car: a web address is opened, anything else is searched (Google). */
    fun resolve(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val scheme = Regex("^([a-z][a-z0-9+.-]*):", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.lowercase()
        if (scheme == "http" || scheme == "https") return normalize(t) ?: search(t)
        if (t.none { it.isWhitespace() } && host.matches(t)) return "https://$t"
        return search(t)
    }

    fun search(q: String) = "https://www.google.com/search?q=" + Uri.encode(q.trim())
}

/** Popular sites on the car's Browser tab. Keep in sync with `lib/browser/browser_sites.dart`. */
object PopularSites {
    data class Site(val name: String, val url: String, val domain: String, val color: Int = 0xFF37474F.toInt())

    val all = listOf(
        Site("Google", "https://www.google.com", "google.com", 0xFF4285F4.toInt()),
        Site("YouTube", "https://m.youtube.com", "youtube.com", 0xFFFF0000.toInt()),
        Site("Facebook", "https://m.facebook.com", "facebook.com", 0xFF1877F2.toInt()),
        Site("Instagram", "https://www.instagram.com", "instagram.com", 0xFFE4405F.toInt()),
        Site("X", "https://x.com", "x.com", 0xFF111111.toInt()),
        Site("Wikipedia", "https://en.m.wikipedia.org", "wikipedia.org", 0xFF636466.toInt()),
        Site("Reddit", "https://www.reddit.com", "reddit.com", 0xFFFF4500.toInt()),
        Site("Amazon", "https://www.amazon.com", "amazon.com", 0xFFFF9900.toInt()),
    )

    fun favicon(domain: String) = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
}
