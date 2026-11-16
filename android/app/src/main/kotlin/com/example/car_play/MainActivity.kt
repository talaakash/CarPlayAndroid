package com.example.car_play

import android.content.res.Configuration
import com.example.iptv_native.IptvNativePlugin
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity() {
    // Forwarded to the IPTV plugin so Flutter can hide its controls in PiP and
    // enter PiP automatically on Android < 12.
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        IptvNativePlugin.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        IptvNativePlugin.onUserLeaveHint()
    }
}
