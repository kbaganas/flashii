package com.example.flashii

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import com.google.android.material.snackbar.Snackbar
import com.example.flashii.databinding.ActivityMainBinding
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import android.content.DialogInterface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.provider.Telephony


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

    enum class TypeOfEvent {
        INCOMING_CALL,
        SMS,
        PHONE_SHAKE,
        SPEAK,
        OUT_OF_SERVICE,
        IN_SERVICE
    }

    enum class REQUEST_KEY(val value: Int) {
        CALL(1),
        SMS(2)
    }

    // Handlers, Managers, Receivers
    private var loopHandler : Handler = Handler(Looper.getMainLooper())
    private lateinit var cameraManager : CameraManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sensorManager : SensorManager
    private lateinit var sensorEventListener : SensorEventListener
    private lateinit var connectivityCallback : ConnectivityManager.NetworkCallback
    private lateinit var incomingCallReceiver : BroadcastReceiver
    private lateinit var incomingSMSReceiver : BroadcastReceiver
    private lateinit var incomingNetworkReceiver : BroadcastReceiver
    private lateinit var incomingShakeReceiver : BroadcastReceiver
    private lateinit var incomingSpeakReceiver : BroadcastReceiver

    // Booleans
    private var isFlashLightOn = false
    private var isFlickering : Boolean = false
    private var isSendSOS : Boolean = false
    private var incomingCall : Boolean = false
    private var incomingSMS : Boolean = false
    private var isPhoneOutOfNetwork : Boolean = false
    private var isPhoneInNetwork : Boolean = false
    private var isPhoneShaked : Boolean = false
    private var isSoundIncoming : Boolean = false


    // Buttons & Ids
    private var flashlightId : String = "0"
    private lateinit var sosBtn : ImageButton
    private lateinit var flickerFlashlightBtn : ImageButton
    private lateinit var flashlightBtn : ImageButton
    private lateinit var incomingCallBtn : ImageButton
    private lateinit var outInNetworkBtn : ImageButton
    private lateinit var incomingSMSBtn : ImageButton
    private lateinit var incomingShakeBtn : ImageButton
    private lateinit var incomingSoundBtn : ImageButton

    // variables
    private var flickerFlashlightHz : Long = 1
    private var touchStartTime : Long = 0
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var flickeringBar : SeekBar
    private lateinit var flickerText : TextView
    var thumbInitialPosition = 0
    var hzInitialPosition = 0


    @SuppressLint("SetTextI18n")
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
        setflashlightId()
        flashlightBtn = findViewById(R.id.flashLightBtnId)
        flashlightBtn.setOnTouchListener { _, event ->
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
            if (!isSendSOS) {
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
                flickerText.text = "$flickerFlashlightHz" + "Hz"
                //val delta = flickeringBar.thumb.bounds.left - thumbInitialPosition
                //flickerText.x = hzInitialPosition.toFloat() + delta * 1.25.toFloat()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                loopHandler.removeCallbacksAndMessages(null)
                atomicFlashLightOff()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                startFlickering()
            }
        })

        // flicker flashlight button handler
        flickerFlashlightBtn = findViewById(R.id.flickerFlashLightId)
        flickerFlashlightBtn.setOnClickListener {
            if (!isFlickering) {
                Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                resetAllActivities()
                startFlickering()
                setFlickeringFlashlightBtn()
            }
            else {
                stopFlickering()
                resetFlickeringFlashlightBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming call handler
        incomingCallBtn = findViewById(R.id.switchFlickerIncomingCallsId)
        incomingCallBtn.visibility = ImageButton.INVISIBLE

        incomingCallBtn.setOnClickListener {
            if (!incomingCall) {
                Log.i("MainActivity","incomingCallFlickerToBeEnabled is ON")
                registerIncomingEvents(TypeOfEvent.INCOMING_CALL, true)
                setIncomingCallFlickeringBtn()
            } else {
                Log.i("MainActivity", "incomingCallFlickerSwitch is OFF")
                disableIncomingCallFlickering()
                resetIncomingCallFlickeringBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming sound handler
        incomingSoundBtn = findViewById(R.id.incomingSoundBtnId)
//        incomingSoundBtn.visibility = ImageButton.INVISIBLE

        incomingSoundBtn.setOnClickListener {
            if (!isSoundIncoming) {
                Log.i("MainActivity","isSoundIncoming is ON")
                isSoundIncoming = true
                setIncomingSoundBtn()
            } else {
                Log.i("MainActivity", "isSoundIncoming is OFF")
                isSoundIncoming = false
                resetIncomingSoundBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // shake phone handler
        incomingShakeBtn = findViewById(R.id.incomingShakeBtnId)
        incomingShakeBtn.setOnClickListener {
            if (!isPhoneShaked) {
                Log.i("MainActivity","isPhoneShaked is ON")
                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                var accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accelerometerSensor != null) {
                    sensorEventListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            // Handle accelerometer sensor changes here
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            // Process the sensor data as needed
                            Log.i("MainActivity","onSensorChanged EVENT received")
                        }

                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                            // Handle accuracy changes if needed
                            Log.i("MainActivity","onAccuracyChanged EVENT received")
                        }
                    }
                    sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    setShakeBtn()
                    isPhoneShaked = true
                }
                else {
                    // we have to disable the btn now since accelerometer sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    incomingShakeBtn.visibility = ImageButton.INVISIBLE
                }
            } else {
                Log.i("MainActivity", "isPhoneShaked is OFF")
                sensorManager.unregisterListener(sensorEventListener)
                resetShakeBtn()
                isPhoneShaked = false
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming SMS handler
        incomingSMSBtn = findViewById(R.id.smsBtnId)
        incomingSMSBtn.visibility = ImageButton.INVISIBLE

        incomingSMSBtn.setOnClickListener {
            if (!incomingSMS) {
                Log.i("MainActivity","SMS incoming handler is ON")
                registerIncomingEvents(TypeOfEvent.SMS, true)
                setIncomingSMSBtn()
            } else {
                Log.i("MainActivity", "SMS incoming handler is OFF")
                disableIncomingSMSHandler()
                resetIncomingSMSBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // phone out/in network handler
        outInNetworkBtn = findViewById(R.id.networkConnectionBtn)
        outInNetworkBtn.visibility = ImageButton.INVISIBLE

        outInNetworkBtn.setOnClickListener {
            if (isPhoneOutOfNetwork || isPhoneInNetwork) {
                // User wants to disable the feature
                Log.i("MainActivity","Disable In/Out of Network feature")
                isPhoneInNetwork = false
                isPhoneOutOfNetwork = false
                stopFlickering()
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                resetNetworkBtn()
            }
            else {
                // initialize telephonyManager
                connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                // User about to enable the feature. He has to provide the option he wants
                val alertDialogBuilder = AlertDialog.Builder(this)
                alertDialogBuilder.setTitle("WiFi/Data Connection Flickering")
                alertDialogBuilder.setMessage("Flashlight will start flickering when:")
                alertDialogBuilder.setPositiveButton("Phone has lost WiFi/Data connection") { dialog: DialogInterface, _: Int ->
                    dialog.dismiss()
                    isPhoneOutOfNetwork = true
                    Log.i("MainActivity","Out of Network is ON")
                    registerIncomingEvents(TypeOfEvent.OUT_OF_SERVICE)
                    setNetworkBtn()
                }
                alertDialogBuilder.setNegativeButton("Phone has found a WiFi/Data connection") { dialog: DialogInterface, _: Int ->
                    dialog.dismiss()
                    isPhoneInNetwork = true
                    Log.i("MainActivity","In Network is ON")
                    registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                    setNetworkBtn()
                }
                alertDialogBuilder.setCancelable(false)
                alertDialogBuilder.show()
            }
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // Permissions handling
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_KEY.CALL.value)
        } else {
            incomingCallBtn.visibility = ImageButton.VISIBLE
            outInNetworkBtn.visibility = ImageButton.VISIBLE
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_KEY.SMS.value)
        }
        else {
            incomingSMSBtn.visibility = ImageButton.VISIBLE
        }
    }

    private fun registerIncomingEvents (eventType : TypeOfEvent, showSnack: Boolean = false) {
        when (eventType) {
            TypeOfEvent.INCOMING_CALL -> {
                incomingCall = true
                incomingCallReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.i("MainActivity", "EVENT INCOMING")
                        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                            Log.i("MainActivity", "ACTION_PHONE_STATE_CHANGED EVENT")
                            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                                Log.i("MainActivity", "Phone starts flickering")
                                startFlickering()
                            }
                            else if (state == TelephonyManager.EXTRA_STATE_IDLE || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                                Log.i("MainActivity", "Phone stopped flickering")
                                stopFlickering()
                            }
                        }
                    }
                }
                val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                registerReceiver(incomingCallReceiver, intentFilter)
                if (showSnack) {
                    Snackbar.make(rootView, "Incoming calls flickering ON", Snackbar.LENGTH_SHORT).show()
                }
            }
            TypeOfEvent.OUT_OF_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "NETWORK is LOST")
                        startFlickering()
                    }}
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                if (showSnack) {
                    Snackbar.make(rootView, "Phone OUT of service/network flickering ON", Snackbar.LENGTH_SHORT).show()
                }
            }
            TypeOfEvent.IN_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i("MainActivity", "NETWORK is AVAILABLE")
                        startFlickering()
                    }}
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                if (showSnack) {
                    Snackbar.make(rootView, "Phone OUT of service/network flickering ON", Snackbar.LENGTH_SHORT).show()
                }
            }
            TypeOfEvent.PHONE_SHAKE -> {

            }
            TypeOfEvent.SPEAK -> {

            }
            TypeOfEvent.SMS -> {
                Log.i("MainActivity", "SMS_RECEIVED_ACTION registered")
                incomingSMS = true
                incomingSMSReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.i("MainActivity", "EVENT INCOMING")
                        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                            Log.i("MainActivity", "SMS_RECEIVED_ACTION EVENT")
                            startFlickering()
                        }
                    }
                }
                val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                registerReceiver(incomingSMSReceiver, intentFilter)
                if (showSnack) {
                    Snackbar.make(rootView, "Incoming SMS flickering ON", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setIncomingSoundBtn () {
        incomingSoundBtn.setImageResource(R.drawable.sound_on)
    }

    private fun resetIncomingSoundBtn () {
        incomingSoundBtn.setImageResource(R.drawable.sound_off)
    }

    private fun setShakeBtn () {
        incomingShakeBtn.setImageResource(R.drawable.shake_enabled)
    }

    private fun resetShakeBtn () {
        incomingShakeBtn.setImageResource(R.drawable.shake_icon)
    }

    private fun resetIncomingSMSBtn () {
        incomingSMSBtn.setImageResource(R.drawable.sms_icon)
    }

    private fun setIncomingSMSBtn () {
        incomingSMSBtn.setImageResource(R.drawable.sms_enabled)
    }

    private fun resetNetworkBtn () {
        outInNetworkBtn.setImageResource(R.drawable.wifi_icon)
    }

    private fun setNetworkBtn () {
        if (isPhoneInNetwork) {
            outInNetworkBtn.setImageResource(R.drawable.wifi_on_enabled)
        }
        else if (isPhoneOutOfNetwork) {
            outInNetworkBtn.setImageResource(R.drawable.wifi_off_enabled)
        }
    }

    private fun disableIncomingSMSHandler (showSnack: Boolean = false) {
        incomingSMS = false
        stopFlickering()
        unregisterReceiver(incomingSMSReceiver)
        if (showSnack) {
            Snackbar.make(rootView, "Incoming SMS flickering OFF", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun disableIncomingCallFlickering (showSnack: Boolean = false) {
        incomingCall = false
        stopFlickering()
        unregisterReceiver(incomingCallReceiver)
        if (showSnack) {
            Snackbar.make(rootView, "Incoming calls flickering OFF", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setIncomingCallFlickeringBtn () {
        incomingCallBtn.setImageResource(R.drawable.incoming_call_icon_enabled)
    }

    private fun resetIncomingCallFlickeringBtn () {
        incomingCallBtn.setImageResource(R.drawable.incoming_call_icon)
    }

    fun setFlickeringHz(hz : Long) {
        flickerFlashlightHz = hz
    }

    fun stopFlickering() {
        if (isFlickering) {
            Log.d("MainActivity", "Flickering OFF")
            isFlickering = false
            loopHandler.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
        }
    }

    fun startFlickering() {
        // flicker as flickerFlashlightHz
        isFlickering = true
        val periodOfFlashLightInMilliseconds : Long =  1000 / flickerFlashlightHz
        atomicFlashLightOn()
        loopHandler.postDelayed({ atomicFlashLightOff() }, (periodOfFlashLightInMilliseconds / 2))
        loopHandler.postDelayed({ startFlickering() }, periodOfFlashLightInMilliseconds)
    }


    private fun atomicFlashLightOn () {
        cameraManager.setTorchMode(flashlightId, true)
    }

    private fun atomicFlashLightOff () {
        cameraManager.setTorchMode(flashlightId, false)
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
        flashlightBtn.setImageResource(R.drawable.on_btn2)
    }

    private fun resetFlashLightBtn () {
        flashlightBtn.setImageResource(R.drawable.off_btn2)
    }

    private fun setSOSBtn () {
        sosBtn.setImageResource(R.drawable.sosbtn_enabled)
    }

    private fun resetSOSBtn () {
        sosBtn.setImageResource(R.drawable.sosbtn)
    }

    private fun setFlickeringFlashlightBtn () {
        flickeringBar.visibility = View.VISIBLE
        flickerText.visibility = View.VISIBLE
        flickerText.text = "$flickerFlashlightHz" + "Hz"
        flickerFlashlightBtn.setImageResource(R.drawable.flicker_on3)
        thumbInitialPosition = flickeringBar.thumb.bounds.right
        hzInitialPosition = flickerText.x.toInt()
    }

    private fun resetFlickeringFlashlightBtn () {
        flickeringBar.visibility = View.INVISIBLE
        flickerText.visibility = View.INVISIBLE
        flickerFlashlightBtn.setImageResource(R.drawable.flicker_off3)
    }

    private fun setflashlightId () {
        // Iterate over the available camera devices
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            // Check if the camera is the rear camera
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                // Rear camera found. Now check if the rear camera has a flashlight
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    flashlightId = id
                    break
                }
            }
        }
        Log.d("MainActivity", "setFlashLightAndCameraIds - flashlightId = $flashlightId")
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
        isSendSOS = true
        val durationOfWord = S(O(S()))
        loopHandler.postDelayed({repeatSOS()}, durationOfWord + SPACE_WORDS_DURATION)
    }

    private fun stopSOS (showSnack : Boolean = false) {
        if (isSendSOS) {
            isSendSOS = false
            Log.i("MainActivity", "STOP SOS")
            loopHandler.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
            resetSOSBtn()
            if (showSnack) {
                Snackbar.make(rootView, "SOS message stopped", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // Handle the permission request result
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission has been granted, so register the TelephonyCallback
            when (requestCode) {
                REQUEST_KEY.CALL.value -> {
                    incomingCallBtn.visibility = ImageButton.VISIBLE
                }
                REQUEST_KEY.SMS.value -> {
                    incomingSMSBtn.visibility = ImageButton.VISIBLE
                }
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
        else if (isSendSOS) {
            stopSOS()
        }
        else if (isFlickering) {
            stopFlickering()
        }
        else if (incomingCall) {
            unregisterReceiver(incomingCallReceiver)
        }
    }

    private fun resetAllActivities () {
        Log.i("MainActivity", "Reset all activities")
        if (isFlashLightOn) {
            turnOffFlashlight()
        }
        else if (isFlickering) {
            stopFlickering()
            resetFlickeringFlashlightBtn()
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


