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
import androidx.core.app.ActivityCompat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.provider.Telephony
import kotlin.math.sqrt


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

    enum class ACTION {
        CREATE,
        RESUME,
    }

    enum class TypeOfEvent {
        INCOMING_CALL,
        SMS,
        PHONE_SHAKE,
        SPEAK,
        OUT_OF_SERVICE,
        IN_SERVICE
    }

    enum class REQUEST_KEY (val value: Int) {
        CALL(1),
        SMS(2)
    }

    val PERMISSIONS = mutableMapOf (
        "CALL" to false,
        "SMS" to false,
    )


    // Handlers, Managers, Receivers
    private var loopHandler : Handler = Handler(Looper.getMainLooper())
    private lateinit var cameraManager : CameraManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sensorManager : SensorManager
    private lateinit var sensorEventListener : SensorEventListener
    private lateinit var connectivityCallback: ConnectivityManager.NetworkCallback
    private lateinit var incomingCallReceiver : BroadcastReceiver
    private lateinit var incomingSMSReceiver : BroadcastReceiver

    // Booleans
    private var isFlashLightOn = false
    private var isFlickering : Boolean = false
    private var isSendSOS : Boolean = false
    private var incomingCall : Boolean = false
    private var incomingSMS : Boolean = false
    private var isPhoneOutOfNetwork : Boolean = false
    private var isPhoneInNetwork : Boolean = false
    private var isPhoneShaken : Boolean = false
    private var isSoundIncoming : Boolean = false
    private var networkConnectivityCbIsSet : Boolean = false

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
    private var thumbInitialPosition = 0
    private var hzInitialPosition = 0
    private lateinit var snackbar: Snackbar


    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootView = findViewById(android.R.id.content)
        snackbar = Snackbar.make(rootView, "", Snackbar.LENGTH_SHORT)

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
                showSnackbar("SOS message is transmitted")
            }
            else {
                dismissSnackbar()
                stopSOS()
                resetSOSBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flicker seekbar and textview handler
        flickeringBar = findViewById(R.id.flickeringBarId)
        flickerText = findViewById(R.id.flickerTextViewId)
        flickeringBar.min = MIN_FLICKER_HZ
        flickeringBar.max = MAX_FLICKER_HZ
        flickeringBar.visibility = View.INVISIBLE
        flickerText.visibility = View.INVISIBLE

        flickeringBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                setFlickeringHz(progress.toLong())
                flickerText.text = "$flickerFlashlightHz" + "Hz"
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
        incomingCallBtn.setOnClickListener {
            // Check first if permissions are granted
            if (PERMISSIONS["CALL"] == true) {
                if (!incomingCall) {
                    Log.i("MainActivity","incomingCallFlickerToBeEnabled is ON")
                    registerIncomingEvents(TypeOfEvent.INCOMING_CALL, true)
                    setIncomingCallFlickeringBtn()
                } else {
                    Log.i("MainActivity", "incomingCallFlickerSwitch is OFF")
                    dismissSnackbar()
                    disableIncomingCallFlickering()
                    resetIncomingCallFlickeringBtn()
                }
            }
            else {
                // user should be asked for permissions again
                showSnackbar("To use the feature, provide manually CALL permissions to Flashii in your phone's Settings", Snackbar.LENGTH_LONG)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming sound handler
        incomingSoundBtn = findViewById(R.id.incomingSoundBtnId)
        incomingSoundBtn.setOnClickListener {
            if (!isSoundIncoming) {
                Log.i("MainActivity","isSoundIncoming is ON")
                isSoundIncoming = true
                setIncomingSoundBtn()
            } else {
                Log.i("MainActivity", "isSoundIncoming is OFF")
                dismissSnackbar()
                isSoundIncoming = false
                resetIncomingSoundBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // shake phone handler
        incomingShakeBtn = findViewById(R.id.incomingShakeBtnId)
        incomingShakeBtn.setOnClickListener {
            if (!isPhoneShaken) {
                Log.i("MainActivity","isPhoneShaken is ON")
                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accelerometerSensor != null) {
                    var acceleration = 10f
                    var currentAcceleration = SensorManager.GRAVITY_EARTH
                    var lastAcceleration: Float
                    var delta : Float
                    sensorEventListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            lastAcceleration = currentAcceleration
                            currentAcceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                            delta = currentAcceleration - lastAcceleration
                            acceleration = acceleration * 0.9f + delta
                            if (acceleration > 12) {
                                Log.i("MainActivity", "Shake event detected")
                                if (isFlashLightOn) {
                                    turnOffFlashlight()
                                }
                                else {
                                    turnOnFlashlight()
                                }
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                            // Handle accuracy changes if needed
                        }
                    }
                    sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    setShakeBtn()
                    isPhoneShaken = true
                }
                else {
                    // we have to disable the btn now since accelerometer sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    incomingShakeBtn.setImageResource(R.drawable.shake_icon_not_available)
                }
            } else {
                Log.i("MainActivity", "isPhoneShaken is OFF")
                dismissSnackbar()
                sensorManager.unregisterListener(sensorEventListener)
                resetShakeBtn()
                isPhoneShaken = false
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming SMS handler
        incomingSMSBtn = findViewById(R.id.smsBtnId)

        incomingSMSBtn.setOnClickListener {
            // Check first if permissions are granted
            if (PERMISSIONS["SMS"] == true) {
                if (!incomingSMS) {
                    Log.i("MainActivity","SMS incoming handler is ON")
                    registerIncomingEvents(TypeOfEvent.SMS, true)
                    setIncomingSMSBtn()
                } else {
                    Log.i("MainActivity", "SMS incoming handler is OFF")
                    dismissSnackbar()
                    disableIncomingSMSHandler()
                    resetIncomingSMSBtn()
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for SMS")
                showSnackbar("To use the feature, provide manually SMS permissions to Flashii in your phone's Settings", Snackbar.LENGTH_LONG)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // phone out/in network handler
        outInNetworkBtn = findViewById(R.id.networkConnectionBtn)
        outInNetworkBtn.setOnClickListener {
            if (networkConnectivityCbIsSet) {
                // User wants to disable the feature
                Log.i("MainActivity", "Disable In/Out of Network feature")
                networkConnectivityCbIsSet = false
                dismissSnackbar()
                stopFlickering()
                Log.i("MainActivity", "Unregister running CB")
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                resetNetworkBtn()
            }
            else {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "NETWORK is currently LOST")
                        isPhoneOutOfNetwork = true
                        setNetworkBtn()
                        Log.i("MainActivity", "Unregister status CB")
                        connectivityManager.unregisterNetworkCallback(connectivityCallback)
                        registerIncomingEvents(TypeOfEvent.IN_SERVICE, true)
                    }
                    override fun onAvailable(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "NETWORK is currently AVAILABLE")
                        isPhoneInNetwork = true
                        setNetworkBtn()
                        Log.i("MainActivity", "Unregister status CB")
                        connectivityManager.unregisterNetworkCallback(connectivityCallback)
                        registerIncomingEvents(TypeOfEvent.OUT_OF_SERVICE, true)
                    }
                }
                networkConnectivityCbIsSet = true
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
            }
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // Permissions handling
        checkPermissions(ACTION.CREATE)
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////




    private fun checkPermissions (activity: ACTION) {
        when (activity) {
            ACTION.CREATE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_KEY.CALL.value)
                }
                else {
                    PERMISSIONS["CALL"] = true
                }

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_KEY.SMS.value)
                }
                else {
                    PERMISSIONS["SMS"] = true
                }
            }
            ACTION.RESUME -> {
                // User may have changed the permissions in Settings/App/Flashii/Licenses, so we have to align with that
                Log.i("MainActivity", "Ask for CALL permissions again RESUME")
                if (PERMISSIONS["CALL"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_KEY.CALL.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                        setBtnUnavailable(incomingCallBtn, R.drawable.incoming_call_no_permission)
                        PERMISSIONS["CALL"] = false
                    }
                }

                if (PERMISSIONS["SMS"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "Ask for SMS permissions again RESUME ")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_KEY.SMS.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                        setBtnUnavailable(incomingSMSBtn, R.drawable.sms_icon_no_permission)
                        PERMISSIONS["SMS"] = false
                    }
                }
            }
            else -> {}
        }
    }

    private fun showSnackbar (text : String, length : Int = Snackbar.LENGTH_SHORT) {
        snackbar.dismiss()
        snackbar = Snackbar.make(rootView, text, length)
        snackbar.show()
    }

    private fun dismissSnackbar () {
        snackbar.dismiss()
    }

    private fun
            registerIncomingEvents (eventType : TypeOfEvent, showSnack: Boolean = false) {
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
                    showSnackbar("Flashlight will flicker on incoming calls")
                }
            }
            TypeOfEvent.OUT_OF_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "NETWORK is LOST")
                        startFlickering()
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }}
                Log.i("MainActivity", "Register CB for OUT_OF_SERVICE")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback
                )
                if (showSnack) {
                    showSnackbar("Flashlight will flicker if WiFi/Data signal gets lost")
                }
            }
            TypeOfEvent.IN_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i("MainActivity", "NETWORK is AVAILABLE")
                        startFlickering()
                        isPhoneOutOfNetwork = false
                        isPhoneInNetwork = true
                    }}
                Log.i("MainActivity", "Register CB for IN_SERVICE")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback
                )
                if (showSnack) {
                    showSnackbar("Flashlight will flicker if WiFi/Data signal is found")
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
                            Log.i("MainActivity", "Phone starts flickering")
                            startFlickering()
                        }
                    }
                }
                val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                registerReceiver(incomingSMSReceiver, intentFilter)
                if (showSnack) {
                    showSnackbar("Flashlight will flicker on incoming SMSes")
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
            outInNetworkBtn.setImageResource(R.drawable.wifi_off_enabled)
        }
        else if (isPhoneOutOfNetwork) {
            outInNetworkBtn.setImageResource(R.drawable.wifi_on_enabled)
        }
    }

    private fun disableIncomingSMSHandler (showSnack: Boolean = false) {
        incomingSMS = false
        stopFlickering()
        unregisterReceiver(incomingSMSReceiver)
        if (showSnack) {
            showSnackbar("Feature dismissed")
        }
    }

    private fun disableIncomingCallFlickering (showSnack: Boolean = false) {
        incomingCall = false
        stopFlickering()
        unregisterReceiver(incomingCallReceiver)
        if (showSnack) {
            showSnackbar("Feature dismissed")
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

    private fun setBtnUnavailable (btn : ImageButton, icon : Int) {
        btn.setImageResource(icon)
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
                showSnackbar("Feature dismissed")
            }
        }
    }

    // Handle the permission request result
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission has been granted, so register the TelephonyCallback
            Log.i("MainActivity", "Request granted")
            when (requestCode) {
                REQUEST_KEY.CALL.value -> {
                    PERMISSIONS["CALL"] = true
                    resetIncomingCallFlickeringBtn()
                }
                REQUEST_KEY.SMS.value -> {
                    PERMISSIONS["SMS"] = true
                    resetSOSBtn()
                }
            }
        }
        else {
            when (requestCode) {
                REQUEST_KEY.CALL.value -> {
                    Log.i("MainActivity", "Request NOT granted for CALL")
                    setBtnUnavailable(incomingCallBtn, R.drawable.incoming_call_no_permission)
                }
                REQUEST_KEY.SMS.value -> {
                    Log.i("MainActivity", "Request NOT granted for SMS")
                    setBtnUnavailable(incomingSMSBtn, R.drawable.sms_icon_no_permission)
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
        checkPermissions(ACTION.RESUME)
    }

}

