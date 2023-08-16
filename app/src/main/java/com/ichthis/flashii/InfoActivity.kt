package com.ichthis.flashii

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class InfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Flashii)
        setContentView(R.layout.dialog_info)

        val closeButton = findViewById<ImageButton>(R.id.infoGoBackArrow)
        closeButton.setOnClickListener {
            finish()
        }

        // instantiate arrows
        val infoFlashlightExpandArrow = findViewById<ImageButton>(R.id.infoFlashlightExpandArrow)
        val infoTiltExpandArrow = findViewById<ImageButton>(R.id.infoTiltExpandArrow)
        val infoSOSExpandArrow = findViewById<ImageButton>(R.id.infoSOSExpandArrow)
        val infoSMSExpandArrow = findViewById<ImageButton>(R.id.infoSMSExpandArrow)
        val infoFlickerExpandArrow = findViewById<ImageButton>(R.id.infoFlickerExpandArrow)
        val infoNetworkExpandArrow = findViewById<ImageButton>(R.id.infoNetworkExpandArrow)
        val infoAltitudeExpandArrow = findViewById<ImageButton>(R.id.infoAltitudeExpandArrow)
        val infoBatteryExpandArrow = findViewById<ImageButton>(R.id.infoBatteryExpandArrow)
        val infoSoundExpandArrow = findViewById<ImageButton>(R.id.infoSoundExpandArrow)
        val infoCallExpandArrow = findViewById<ImageButton>(R.id.infoCallExpandArrow)
        val infoTimeExpandArrow = findViewById<ImageButton>(R.id.infoTimeExpandArrow)

        // instantiate expandable views
        val infoHiddenFlashlight = findViewById<LinearLayout>(R.id.infoHiddenFlashlight)
        val infoHiddenTilt = findViewById<LinearLayout>(R.id.infoHiddenTilt)
        val infoHiddenSOS = findViewById<LinearLayout>(R.id.infoHiddenSOS)
        val infoHiddenSMS = findViewById<LinearLayout>(R.id.infoHiddenSMS)
        val infoHiddenFlicker = findViewById<LinearLayout>(R.id.infoHiddenFlicker)
        val infoHiddenNetwork = findViewById<LinearLayout>(R.id.infoHiddenNetwork)
        val infoHiddenAltitude = findViewById<LinearLayout>(R.id.infoHiddenAltitude)
        val infoHiddenBattery = findViewById<LinearLayout>(R.id.infoHiddenBattery)
        val infoHiddenSound = findViewById<LinearLayout>(R.id.infoHiddenSound)
        val infoHiddenCall = findViewById<LinearLayout>(R.id.infoHiddenCall)
        val infoHiddenTimer = findViewById<LinearLayout>(R.id.infoHiddenTimer)

        // toggle visibility on clicks
        infoFlashlightExpandArrow.setOnClickListener {
            toggleView(infoHiddenFlashlight, infoFlashlightExpandArrow)
        }

        infoTiltExpandArrow.setOnClickListener {
            toggleView(infoHiddenTilt, infoTiltExpandArrow)
        }

        infoSOSExpandArrow.setOnClickListener {
            toggleView(infoHiddenSOS, infoSOSExpandArrow)
        }

        infoSMSExpandArrow.setOnClickListener {
            toggleView(infoHiddenSMS, infoSMSExpandArrow)
        }

        infoFlickerExpandArrow.setOnClickListener {
            toggleView(infoHiddenFlicker, infoFlickerExpandArrow)
        }

        infoNetworkExpandArrow.setOnClickListener {
            toggleView(infoHiddenNetwork, infoNetworkExpandArrow)
        }

        infoAltitudeExpandArrow.setOnClickListener {
            toggleView(infoHiddenAltitude, infoAltitudeExpandArrow)
        }

        infoBatteryExpandArrow.setOnClickListener {
            toggleView(infoHiddenBattery, infoBatteryExpandArrow)
        }

        infoSoundExpandArrow.setOnClickListener {
            toggleView(infoHiddenSound, infoSoundExpandArrow)
        }

        infoCallExpandArrow.setOnClickListener {
            toggleView(infoHiddenCall, infoCallExpandArrow)
        }

        infoTimeExpandArrow.setOnClickListener {
            toggleView(infoHiddenTimer, infoTimeExpandArrow)
        }
    }

    private fun toggleView (linearLayout: LinearLayout, imageButton: ImageButton) {
        if (linearLayout.visibility == View.VISIBLE) {
            linearLayout.visibility = View.GONE
            imageButton.setImageResource(R.drawable.arrow_down)
        } else {
            linearLayout.visibility = View.VISIBLE
            imageButton.setImageResource(R.drawable.arrow_up)
        }
    }
}