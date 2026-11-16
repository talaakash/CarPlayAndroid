package com.example.iptv_native

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.car.app.features.CarFeatures

/**
 * Decides whether video may be shown, and whether audio may continue, for a car.
 *
 * What is and is not known (see docs):
 *  - Android Auto parked video (beta, Google's early-access video category): the phone runs
 *    this app's Activity on the car's display and the system pauses it when the car moves.
 *    As on AAOS the app only sees its lifecycle. `onCar` is an INFERENCE: Android Auto is
 *    connected (CarConnection) and the Activity was last resumed on a non-default display.
 *    When it applies, video is hidden but audio keeps playing, because Android Auto's media
 *    interface (media session) is a supported driving surface for audio. Not verified on a
 *    real Android Auto video build: Google has not shipped the program publicly.
 *  - Android Automotive OS: a video app that is not distraction optimized is paused and
 *    hidden by the system while driving. The app is only told through its lifecycle
 *    (onPause), not through a "driving" flag. So `restricted` here is an INFERENCE:
 *    automotive device + our Activity was resumed and is no longer resumed.
 *    A real vehicle can also pause the Activity for reasons other than driving
 *    (user left the app); the effect is conservative: video hidden, audio kept when the
 *    platform supports background audio while driving, otherwise playback is paused.
 *  - `FEATURE_BACKGROUND_AUDIO_WHILE_DRIVING` is read from the platform (CarFeatures).
 */
object ParkedVideoPolicy {
    private var app: Application? = null
    private var resumed = 0
    private var everResumed = false
    private var projected = false
    private var lastResumedDisplay = 0

    /** Debug-build-only override: null = real signals, otherwise a simulated mode. */
    enum class Simulation { OFF, DRIVING_AUDIO_OK, DRIVING_NO_AUDIO }
    private var simulation = Simulation.OFF

    var restricted = false; private set
    var audioWhileDriving = false; private set
    var automotive = false; private set
    var simulated = false; private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    val videoAllowed get() = !restricted
    /** Playback must not be started or resumed by anyone while this is true. */
    val playbackBlocked get() = restricted && !audioWhileDriving

    /** Called by the plugin with the platform's CarConnection result. */
    fun setProjected(connected: Boolean) {
        if (projected == connected) return
        projected = connected
        recompute()
    }

    fun start(application: Application) {
        if (app != null) return
        app = application
        automotive = application.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                resumed++; everResumed = true
                lastResumedDisplay = a.window?.decorView?.display?.displayId ?: 0
                recompute()
            }
            override fun onActivityPaused(a: Activity) { resumed = (resumed - 1).coerceAtLeast(0); recompute() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        recompute()
    }

    /** Ignored unless the app is debuggable. Lets the real code path run without a car. */
    fun simulate(mode: Simulation): Boolean {
        val a = app ?: return false
        if (a.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        simulation = mode
        recompute()
        return true
    }

    private fun platformAudioWhileDriving(ctx: Context) = try {
        CarFeatures.isFeatureEnabled(ctx, CarFeatures.FEATURE_BACKGROUND_AUDIO_WHILE_DRIVING)
    } catch (_: Throwable) { false }

    private fun recompute() {
        val ctx = app ?: return
        val onCar = projected && lastResumedDisplay != 0
        val (r, audio, sim) = when (simulation) {
            Simulation.DRIVING_AUDIO_OK -> Triple(true, true, true)
            Simulation.DRIVING_NO_AUDIO -> Triple(true, false, true)
            Simulation.OFF -> Triple(
                (automotive || onCar) && everResumed && resumed == 0,
                if (automotive) platformAudioWhileDriving(ctx) else onCar,
                false,
            )
        }
        if (r == restricted && audio == audioWhileDriving && sim == simulated) return
        restricted = r; audioWhileDriving = audio; simulated = sim
        listeners.forEach { it() }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "type" to "parkedVideo",
        "automotive" to automotive,
        "videoAllowed" to videoAllowed,
        "audioWhileDriving" to audioWhileDriving,
        "playbackBlocked" to playbackBlocked,
        "simulated" to simulated,
    )
}
