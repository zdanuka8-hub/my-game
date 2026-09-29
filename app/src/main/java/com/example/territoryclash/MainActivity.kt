package com.example.territoryclash

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var music: BackgroundMusic
    private lateinit var gameView: GameView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        music = BackgroundMusic()
        gameView = GameView(this, music)
        setContentView(gameView)
    }

    override fun onResume() {
        super.onResume()
        if (::music.isInitialized) music.resume()
    }

    override fun onPause() {
        if (::music.isInitialized) music.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::music.isInitialized) music.release()
        super.onDestroy()
    }
}
