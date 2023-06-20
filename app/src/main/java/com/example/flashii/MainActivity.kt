package com.example.flashii

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import com.google.android.material.snackbar.Snackbar
import com.example.flashii.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    // constants
    private val MIN_FLICKER_HZ : Int = 1
    private val MAX_FLICKER_HZ : Int = 10
    private val DIT_DURATION : Long = 250
    private val SPACE_DURATION : Long = DIT_DURATION
    private val DAH_DURATION : Long = 3 * DIT_DURATION
    private val SPACE_CHARS_DURATION : Long = 3 * SPACE_DURATION
    private val SPACE_WORDS_DURATION : Long = 4 * SPACE_DURATION // results to 7*DIT_DURATION, considering that we add SPACE_CHARS_DURATION after each letter
    private lateinit var  rootView : View

    // variables
    private lateinit var cameraManager : CameraManager
    private var flashLightId : String = "0"
    private var isFlashLightOn = false
    private var flickerFlashLightHz : Long = 1
    private var isFlickering : Boolean = false
    private var loopHandler : Handler = Handler(Looper.getMainLooper())
    private var receiverIsRegistered : Boolean = false
    private var incomingCallReceiver : IncomingCallReceiver? = null
    private var sendSOS : Boolean = false
    private var touchStartTime : Long = 0
    private lateinit var viewBinding: ActivityMainBinding
    private var incomingCallFlickerToBeEnabled : Boolean = true
    private lateinit var  sosBtn : ImageButton
    private lateinit var flickeringBar : SeekBar
    private lateinit var flickerText : TextView
    private lateinit var flickerFlashLightBtn : ImageButton
    var thumbInitialPosition = 0
    var hzInitialPosition = 0
    private lateinit var flashlightButton : ImageButton

    @SuppressLint("SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootView = findViewById(android.R.id.content)

        // setup cameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        ///////////////////////////////////////////////////////////////////////////////////////
        // flashLightBtn handler
        setFlashLightId()
        flashlightButton = findViewById(R.id.flashLightBtnId)
        flashlightButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isFlashLightOn) {
                        turnOffFlashlight()
                    } else {
                        resetAllActivities()
                        touchStartTime = System.currentTimeMillis()
                        turnOnFlashlight()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If touch duration > 750ms, then its a press-and-hold action and we need to turn-off flashlight.
                    // If otherwise, user just clicked to enable or disable the flashlight.
                    if (System.currentTimeMillis() - touchStartTime > 350) {
                        turnOffFlashlight()
                    }
                    false
                }
                else -> {false}
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // sosBtn handler
        sosBtn = findViewById(R.id.sosBtn)
        sosBtn.setOnClickListener {
            if (!sendSOS) {
                resetAllActivities()
                repeatSOS()
                setSOSBtn()
                Snackbar.make(rootView, "SOS message started (in Morse code)", Snackbar.LENGTH_SHORT).show()
            }
            else {
                stopSOS()
                resetSOSBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flicker seekbar and textview handler
        flickeringBar = findViewById<SeekBar>(R.id.flickeringBarId)
        flickerText = findViewById<TextView>(R.id.flickerTextViewId)
        flickeringBar.min = MIN_FLICKER_HZ
        flickeringBar.max = MAX_FLICKER_HZ
        flickeringBar.visibility = View.INVISIBLE
        flickerText.visibility = View.INVISIBLE

        flickeringBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                setFlickeringHz(progress.toLong())
                flickerText.text = "$flickerFlashLightHz" + "Hz"
                val delta = flickeringBar.thumb.bounds.right - thumbInitialPosition
                flickerText.y = hzInitialPosition.toFloat() - delta * 1.5.toFloat()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                loopHandler.removeCallbacksAndMessages(null)
                atomicFlashLightOff()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Log.d("MainActivity", "Flickering ON with ${flickerFlashLightHz}Hz")
                startFlickering()
            }
        })

        // flicker flashlight button handler
        flickerFlashLightBtn = findViewById(R.id.flickerFlashLightId)
        flickerFlashLightBtn.setOnClickListener {
            if (!isFlickering) {
                Log.d("MainActivity", "Flickering ON with ${flickerFlashLightHz}Hz")
                resetAllActivities()
                startFlickering()
                setFlickeringFlashlightBtn()
            }
            else {
                stopFlickering()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        incomingCallReceiver = IncomingCallReceiver()
        Log.i("MainActivity","incomingCallReceiver = $incomingCallReceiver, intentFilter = $intentFilter")
        registerReceiver(incomingCallReceiver, intentFilter)


        val incomingCallFlickerSwitch = findViewById<ImageButton>(R.id.switchFlickerIncomingCallsId)
        incomingCallFlickerSwitch.setOnClickListener {
            if (incomingCallFlickerToBeEnabled) {
                Log.i("MainActivity","incomingCallFlickerToBeEnabled is ON")
                if (receiverIsRegistered) {
                    Log.i("MainActivity","receiverIsRegistered is already true")
                }
                else {
                    Log.i("MainActivity","intent and registration is ON")
                    val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    registerReceiver(incomingCallReceiver, intentFilter)
                    receiverIsRegistered = true
                    incomingCallFlickerToBeEnabled = !incomingCallFlickerToBeEnabled
                    incomingCallFlickerSwitch.setImageResource(R.drawable.phoneringingon)
                    Snackbar.make(rootView, "Flashlight ON for incoming calls", Snackbar.LENGTH_SHORT).show()
                }
            } else {
                Log.i("MainActivity", "incomingCallFlickerSwitch is OFF")
                unregisterReceiver(incomingCallReceiver)
                receiverIsRegistered = false
                incomingCallFlickerToBeEnabled = !incomingCallFlickerToBeEnabled
                incomingCallFlickerSwitch.setImageResource(R.drawable.phoneriningoff)
                Snackbar.make(rootView, "Feature disabled", Snackbar.LENGTH_SHORT).show()
            }
        }



    }


    fun setFlickeringHz(hz : Long) {
        flickerFlashLightHz = hz
    }

    fun stopFlickering() {
        if (isFlickering) {
            Log.d("MainActivity", "Flickering OFF")
            isFlickering = false
            loopHandler.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
            resetFlickeringFlashlightBtn()
        }
    }

    fun startFlickering() {
        if (!isFlickering) {
            // flicker as flickerFlashLightHz
            isFlickering = true
            val delayedMilliseconds : Long =  1000 / (2 * flickerFlashLightHz)
            atomicFlashLightOn()
            loopHandler.postDelayed({ atomicFlashLightOff() }, delayedMilliseconds)
            loopHandler.postDelayed({ startFlickering() }, delayedMilliseconds * 2)
        }
    }


    private fun atomicFlashLightOn () {
        cameraManager.setTorchMode(flashLightId, true)
    }

    private fun atomicFlashLightOff () {
        cameraManager.setTorchMode(flashLightId, false)
    }

    private fun turnOnFlashlight() {
        if (!isFlashLightOn) {
            try {
                isFlashLightOn = true
                atomicFlashLightOn()
                setFlashLightBtn()
                Log.d("MainActivity", "FlashLight ON")
            } catch (e: CameraAccessException) {
                Log.d("MainActivity", "FlashLight ON - ERROR: $e")
            }
        }
    }

    private fun turnOffFlashlight() {
        if (isFlashLightOn) {
            try {
                isFlashLightOn = false
                atomicFlashLightOff()
                resetFlashLightBtn()
                Log.d("MainActivity", "FlashLight OFF")
            } catch (e: CameraAccessException) {
                Log.d("MainActivity", "FlashLight OFF - ERROR: $e")
            }
        }
    }

    private fun setFlashLightBtn () {
        flashlightButton.setImageResource(R.drawable.on)
    }

    private fun resetFlashLightBtn () {
        flashlightButton.setImageResource(R.drawable.off)
    }

    private fun setSOSBtn () {
        sosBtn.setImageResource(R.drawable.soson)
    }

    private fun resetSOSBtn () {
        sosBtn.setImageResource(R.drawable.sos)
    }

    private fun setFlickeringFlashlightBtn () {
        flickeringBar.visibility = View.VISIBLE
        flickerText.visibility = View.VISIBLE
        flickerText.text = "$flickerFlashLightHz" + "Hz"
        flickerFlashLightBtn.setImageResource(R.drawable.on_flicker)
        thumbInitialPosition = flickeringBar.thumb.bounds.right
        hzInitialPosition = flickerText.y.toInt()
    }

    private fun resetFlickeringFlashlightBtn () {
        flickeringBar.visibility = View.INVISIBLE
        flickerText.visibility = View.INVISIBLE
        flickerFlashLightBtn.setImageResource(R.drawable.off_flicker)
    }

    private fun setFlashLightId () {
        // Iterate over the available camera devices
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            // Check if the camera is the rear camera
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                // Rear camera found. Now check if the rear camera has a flashlight
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    flashLightId = id
                    break
                }
            }
        }
        Log.d("MainActivity", "setFlashLightAndCameraIds - flashLightId = $flashLightId")
    }


    // dit flashing per Morse code
    private fun dit () {
        atomicFlashLightOn()
        loopHandler.postDelayed({ atomicFlashLightOff() }, DIT_DURATION)
    }

    // dah flashing per Morse code
    private fun dah () {
        atomicFlashLightOn()
        loopHandler.postDelayed({ atomicFlashLightOff() }, DAH_DURATION)
    }

    // S = ...
    // Function return the duration of S in milliseconds
    private fun S (initialPauseByMilliseconds : Long = 0) : Long {
        loopHandler.postDelayed({ dit() }, initialPauseByMilliseconds)
        loopHandler.postDelayed({ dit() }, initialPauseByMilliseconds + DIT_DURATION + SPACE_DURATION)
        loopHandler.postDelayed({ dit() }, initialPauseByMilliseconds + 2 * DIT_DURATION + 2 * SPACE_DURATION)
        return initialPauseByMilliseconds + 3 * DIT_DURATION + 2 * SPACE_DURATION + SPACE_CHARS_DURATION
    }

    // O = - - -
    private fun O (initialPauseByMilliseconds : Long = 0) : Long {
        loopHandler.postDelayed({ dah() }, initialPauseByMilliseconds)
        loopHandler.postDelayed({ dah() }, initialPauseByMilliseconds + DAH_DURATION + SPACE_DURATION)
        loopHandler.postDelayed({ dah() }, initialPauseByMilliseconds + 2 * DAH_DURATION + 2 * SPACE_DURATION)
        return initialPauseByMilliseconds + 3 * DAH_DURATION + 2 * SPACE_DURATION + SPACE_CHARS_DURATION
    }

    private fun repeatSOS() {
        if (!sendSOS) {
            sendSOS = true
            Log.i("MainActivity", "SEND SOS")
            val durationOfWord = S(O(S()))
            loopHandler.postDelayed({repeatSOS()}, durationOfWord + SPACE_WORDS_DURATION)
        }
    }

    private fun stopSOS (showSnack : Boolean = false) {
        if (sendSOS) {
            sendSOS = false
            Log.i("MainActivity", "STOP SOS")
            loopHandler.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
            resetSOSBtn()
            if (showSnack) {
                Snackbar.make(rootView, "SOS message stopped", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        Log.i("MainActivity", "onPause is running")
        super.onPause()
    }

    override fun onRestart() {
        Log.i("MainActivity", "onRestart is running")
        super.onRestart()
    }

    override fun onDestroy() {
        Log.i("MainActivity", "onDestroy is running")
        super.onDestroy()
        if (isFlashLightOn) {
            turnOffFlashlight()
        }
        else if (sendSOS) {
            stopSOS()
        }
        else if (isFlickering) {
            stopFlickering()
        }
    }

    private fun resetAllActivities () {
//        if (receiverIsRegistered) {
//            unregisterReceiver(incomingCallReceiver)
//        }
        Log.i("MainActivity", "Reset all activities")
        if (isFlashLightOn) {
            turnOffFlashlight()
        }
        else if (sendSOS) {
            stopSOS()
        }
        else if (isFlickering) {
            stopFlickering()
        }
    }


    override fun onStop() {
        Log.i("MainActivity", "onStop is running")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Log.i("MainActivity", "onResume is running")
    }

}


class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("MainActivity", "Phone is ringing loc1")
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                Log.i("MainActivity", "Phone is ringing")
                // Perform your desired actions when a call is received
                // For example, you can toggle the flashlight here
                // or trigger any other functionality
            }
        }
    }
}

