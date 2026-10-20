package com.example.frnchatkotlin

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowCompat

// Layar 1: sambutan. Tombol "Mulai" membuka layar chat (MainActivity).
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Latar gelap, jadi ikon status bar dibuat terang
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        findViewById<android.view.View>(R.id.btnStart).setOnClickListener {
            val options = ActivityOptionsCompat.makeCustomAnimation(
                this, R.anim.zoom_in_enter, R.anim.zoom_in_exit
            )
            startActivity(Intent(this, MainActivity::class.java), options.toBundle())
        }
    }
}