package com.fujicam.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fujicam.app.ui.AppRoot
import com.fujicam.app.ui.FujiCamTheme

class MainActivity : ComponentActivity() {

    /** หน้ากล้องลงทะเบียนไว้ เพื่อให้กดปุ่มเสียงแล้วถ่ายรูปได้ */
    private var volumeKeyHandler: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FujiCamTheme {
                AppRoot(registerVolumeKey = { volumeKeyHandler = it })
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handler = volumeKeyHandler
        if (handler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if ((event?.repeatCount ?: 0) == 0) handler()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
