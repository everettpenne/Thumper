package com.everettpenne.thumper

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.everettpenne.thumper.playback.PiPState
import com.everettpenne.thumper.ui.ThumperRoot

class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<Uri?>(null)
    /** True while this activity is a floating picture-in-picture window (a video episode); the UI shows only the video then. */
    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (savedInstanceState == null) deepLink = intent?.data
        setContent { ThumperRoot(deepLink, inPictureInPicture) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); deepLink = intent.data }

    /** Leaving the app (Home, recents, another app) floats a playing video episode; audio keeps going in its notification as before. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Only a playing video floats, and only once: the leave hint can fire again from inside the floating window, where re-entering would throw.
        if (PiPState.videoPlaying && !isInPictureInPictureMode) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }
}
