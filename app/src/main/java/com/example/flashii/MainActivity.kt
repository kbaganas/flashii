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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Telephony


class MainActivity : AppCompatActivity() {

    // constants
    private val minFlickerHz : Int = 1
    private val maxFlickerHz : Int = 10
    private val ditDuration : Long = 250
    private val spaceDuration : Long = ditDuration
    private val dahDuration : Long = 3 * ditDuration
    private val spaceCharsDuration : Long = 3 * spaceDuration
    private val spaceWordsDuration : Long = 4 * spaceDuration // results to 7*ditDuration, considering that we add spaceCharsDuration after each letter
    private lateinit var  rootView : View
    private val maxFlickerDurationIncomingSMS : Long = 15000 // 15 seconds
    private val maxFlickerDurationNetwork : Long = 30000 // 30 seconds
    private val applicationName : String = "Flashii"
    private val initRotationAngle : Float = -1000f

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

    enum class RequestKey (val value: Int) {
        CALL(1),
        SMS(2),
        AUDIO(3)
    }

    private val permissionsKeys = mutableMapOf (
        "CALL" to false,
        "SMS" to false,
        "AUDIO" to false
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
    private lateinit var audioRecord : AudioRecord
    private var recordingThread: Thread? = null

    @SuppressLint("SetTextI18n", "MissingPermission", "ClickableViewAccessibility")
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
        setFlashlightId()
        flashlightBtn = findViewById(R.id.flashLightBtnId)
        flashlightBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isFlashLightOn) {
                        turnOffFlashlight(true)
                    } else {
                        resetAllActivities()
                        touchStartTime = System.currentTimeMillis()
                        turnOnFlashlight(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If touch duration > 750ms, then its a press-and-hold action and we need to turn-off flashlight.
                    // If otherwise, user just clicked to enable or disable the flashlight.
                    if (System.currentTimeMillis() - touchStartTime > 350) {
                        turnOffFlashlight(true)
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
                showSnackbar("SOS message transmission")
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
        flickeringBar.min = minFlickerHz
        flickeringBar.max = maxFlickerHz
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
            if (permissionsKeys["CALL"] == true) {
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
                showSnackbar("To use the feature, manually provide CALL permissions to $applicationName in your phone's Settings", Snackbar.LENGTH_LONG)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming sound handler
        incomingSoundBtn = findViewById(R.id.incomingSoundBtnId)
        incomingSoundBtn.setOnClickListener {
            Log.i("MainActivity","isSoundIncoming CLICKED")
            if (permissionsKeys["AUDIO"] == true) {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val threshold = 3500

                if (isSoundIncoming) {
                    Log.i("MainActivity", "isSoundIncoming is OFF")
                    isSoundIncoming = false
                    audioRecord.stop()
                    audioRecord.release()
                    dismissSnackbar()
                    isSoundIncoming = false
                    resetIncomingSoundBtn()
                    try {
                        recordingThread?.interrupt()
                    }
                    catch (e : SecurityException) {
                        Log.e("MainActivity", "THREAD SecurityException $e")
                    }

                    recordingThread?.join()
                    recordingThread = null
                }
                else {
                    Log.i("MainActivity","isSoundIncoming is ON")
                    isSoundIncoming = true
                    setIncomingSoundBtn()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )

                    val buffer = ShortArray(bufferSize)
                    audioRecord.startRecording()

                    recordingThread = Thread {
                        while (isSoundIncoming) {
                            val bytesRead = audioRecord.read(buffer, 0, bufferSize)
                            if (isAboveThreshold(buffer, bytesRead, threshold)) {
                                Log.i("MainActivity","LOOP ABOVE THRESHOLD")
                                if (isFlashLightOn) {
                                    turnOffFlashlight()
                                }
                                else {
                                    turnOnFlashlight()
                                }
                            }
                        }
                    }
                    recordingThread?.start()
                    showSnackbar("Flashlight will turn ON/OFF on short sounds")
                }
            }
            else {
                // user should be asked for permissions again
                showSnackbar("To use the feature, manually provide AUDIO permissions to $applicationName in your phone's Settings", Snackbar.LENGTH_LONG)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // shake phone handler
        incomingShakeBtn = findViewById(R.id.incomingShakeBtnId)
        incomingShakeBtn.setOnClickListener {
            if (!isPhoneShaken) {
                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (accelerometerSensor != null) {
                    var rotationAngle = initRotationAngle
                    sensorEventListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            if (event.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                                val rotationMatrix = FloatArray(9)
                                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                                val orientationAngles = FloatArray(3)
                                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                                val angleInDegrees = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                                if (angleInDegrees > -5f && rotationAngle == initRotationAngle) {
                                    // Phone rotated to the left ~ 90 degrees
                                    rotationAngle = angleInDegrees
                                } else if (angleInDegrees < -80f && rotationAngle > -5f) {
                                    // Phone returned to portrait orientation
                                    rotationAngle = initRotationAngle
                                    if (isFlashLightOn) {
                                        turnOffFlashlight()
                                    }
                                    else {
                                        turnOnFlashlight()
                                    }
                                }
                            }
                        }
                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                            // Handle accuracy changes if needed
                        }
                    }
                    Log.i("MainActivity","isPhoneShaken is ON $sensorEventListener")
                    sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    setShakeBtn()
                    isPhoneShaken = true
                    showSnackbar("Flashlight will turn ON/OFF on Phone short rotations")
                }
                else {
                    // we have to disable the btn now since accelerometer sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    incomingShakeBtn.setImageResource(R.drawable.rotate_no_permission)
                }
            } else {
                Log.i("MainActivity", "isPhoneShaken is OFF $sensorEventListener")
                turnOffFlashlight()
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
            if (permissionsKeys["SMS"] == true) {
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
                showSnackbar("To use the feature, manually provide SMS permissions to $applicationName in your phone's Settings", Snackbar.LENGTH_LONG)
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
                Log.i("MainActivity", "Unregister running CB $connectivityCallback")
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                resetNetworkBtn()
            }
            else {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                // Check if network is currently available first
                val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                networkConnectivityCbIsSet = true
                if (!isConnected) {
                    Log.i("MainActivity", "NETWORK is right now UNAVAILABLE")
                    isPhoneOutOfNetwork = true
                    setNetworkBtn()
                    registerIncomingEvents(TypeOfEvent.IN_SERVICE, true)
                }
                else {
                    Log.i("MainActivity", "NETWORK is right now AVAILABLE")
                    connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onUnavailable () {
                            super.onUnavailable()
                            Log.i("MainActivity", "NETWORK is currently UNAVAILABLE")
                            isPhoneOutOfNetwork = true
                            setNetworkBtn()
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.IN_SERVICE, true)
                        }
                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Log.i("MainActivity", "NETWORK is currently LOST")
                            isPhoneOutOfNetwork = true
                            setNetworkBtn()
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.IN_SERVICE, true)
                        }
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            Log.i("MainActivity", "NETWORK is currently AVAILABLE")
                            isPhoneInNetwork = true
                            setNetworkBtn()
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.OUT_OF_SERVICE, true)
                        }
                    }
                    Log.i("MainActivity", "Register CB $connectivityCallback")
                    connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                }
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
                // CALL
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), RequestKey.CALL.value)
                }
                else {
                    permissionsKeys["CALL"] = true
                }

                // SMS
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), RequestKey.SMS.value)
                }
                else {
                    permissionsKeys["SMS"] = true
                }

                // AUDIO
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RequestKey.AUDIO.value)
                }
                else {
                    permissionsKeys["AUDIO"] = true
                }
            }
            ACTION.RESUME -> {
                // User may have changed the permissions in Settings/App/Flashii/Licenses, so we have to align with that
                Log.i("MainActivity", "Ask for permissions again RESUME")

                // CALL
                if (permissionsKeys["CALL"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), RequestKey.CALL.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                        setBtnUnavailable(incomingCallBtn, R.drawable.incoming_call_no_permission)
                        permissionsKeys["CALL"] = false
                    }
                }

                // SMS
                if (permissionsKeys["SMS"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "Ask for SMS permissions again RESUME ")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), RequestKey.SMS.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                        setBtnUnavailable(incomingSMSBtn, R.drawable.sms_icon_no_permission)
                        permissionsKeys["SMS"] = false
                    }
                }

                // AUDIO
                if (permissionsKeys["AUDIO"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "Ask for AUDIO permissions again RESUME ")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RequestKey.AUDIO.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        setBtnUnavailable(incomingSoundBtn, R.drawable.sound_no_permission)
                        permissionsKeys["AUDIO"] = false
                    }
                }
            }
            else -> {}
        }
    }

    private fun isAboveThreshold(buffer: ShortArray, bytesRead: Int, THRESHOLD : Int): Boolean {
        for (i in 0 until bytesRead) {
            if (buffer[i] > THRESHOLD || buffer[i] < -THRESHOLD) {
                return true
            }
        }
        return false
    }

    private fun showSnackbar (text : String, length : Int = Snackbar.LENGTH_SHORT) {
        snackbar.dismiss()
        snackbar = Snackbar.make(rootView, text, length)
        snackbar.show()
    }

    private fun dismissSnackbar () {
        snackbar.dismiss()
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
                        stopFlickeringAfterTimeout(maxFlickerDurationNetwork)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }
                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.i("MainActivity", "NETWORK is UNAVAILABLE")
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDurationNetwork)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }}
                Log.i("MainActivity", "Register CB for OUT_OF_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                if (showSnack) {
                    showSnackbar("Flashlight will flicker if WiFi or Network signal gets lost")
                }
            }
            TypeOfEvent.IN_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i("MainActivity", "NETWORK is AVAILABLE")
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDurationNetwork)
                        isPhoneOutOfNetwork = false
                        isPhoneInNetwork = true
                    }}
                Log.i("MainActivity", "Register CB for IN_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                if (showSnack) {
                    showSnackbar("Flashlight will flicker if WiFi or Network signal is found")
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
                            stopFlickeringAfterTimeout(maxFlickerDurationIncomingSMS)
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
        incomingShakeBtn.setImageResource(R.drawable.rotate_on)
    }

    private fun resetShakeBtn () {
        incomingShakeBtn.setImageResource(R.drawable.rotate_off)
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

    fun stopFlickeringAfterTimeout (timeout : Long) {
        Log.d("MainActivity", "Flickering TIMEOUT set after ${timeout / 1000} seconds")
        loopHandler.postDelayed({ stopFlickering() }, timeout)
    }

    private fun atomicFlashLightOn () {
        cameraManager.setTorchMode(flashlightId, true)
    }

    private fun atomicFlashLightOff () {
        cameraManager.setTorchMode(flashlightId, false)
    }

    private fun turnOnFlashlight(setFlashlightBtn : Boolean = false) {
        if (!isFlashLightOn) {
            try {
                isFlashLightOn = true
                atomicFlashLightOn()
                if (setFlashlightBtn) {
                    setFlashLightBtn()
                }
                Log.d("MainActivity", "FlashLight ON")
            } catch (e: CameraAccessException) {
                Log.d("MainActivity", "FlashLight ON - ERROR: $e")
            }
        }
    }

    private fun turnOffFlashlight (resetFlashlightBtn : Boolean = false) {
        if (isFlashLightOn) {
            try {
                isFlashLightOn = false
                atomicFlashLightOff()
                if (resetFlashlightBtn) {
                    resetFlashLightBtn()
                }
                Log.d("MainActivity", "FlashLight OFF")
            } catch (e: CameraAccessException) {
                Log.d("MainActivity", "FlashLight OFF - ERROR: $e")
            }
        }
    }

    private fun setFlashLightBtn () {
        flashlightBtn.setImageResource(R.drawable.turn_on)
    }

    private fun resetFlashLightBtn () {
        flashlightBtn.setImageResource(R.drawable.turn_off)
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

    private fun setFlashlightId () {
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
        loopHandler.postDelayed({ atomicFlashLightOff() }, ditDuration)
    }

    // dah flashing per Morse code
    private fun dah () {
        atomicFlashLightOn()
        loopHandler.postDelayed({ atomicFlashLightOff() }, dahDuration)
    }

    // S = ...
    // Function return the duration of S in milliseconds
    private fun s (initialPauseByMilliseconds : Long = 0) : Long {
        loopHandler.postDelayed({ dit() }, initialPauseByMilliseconds)
        loopHandler.postDelayed({ dit() }, initialPauseByMilliseconds + ditDuration + spaceDuration)
        loopHandler.postDelayed({ dit() }, initialPauseByMilliseconds + 2 * ditDuration + 2 * spaceDuration)
        return initialPauseByMilliseconds + 3 * ditDuration + 2 * spaceDuration + spaceCharsDuration
    }

    // O = - - -
    private fun o (initialPauseByMilliseconds : Long = 0) : Long {
        loopHandler.postDelayed({ dah() }, initialPauseByMilliseconds)
        loopHandler.postDelayed({ dah() }, initialPauseByMilliseconds + dahDuration + spaceDuration)
        loopHandler.postDelayed({ dah() }, initialPauseByMilliseconds + 2 * dahDuration + 2 * spaceDuration)
        return initialPauseByMilliseconds + 3 * dahDuration + 2 * spaceDuration + spaceCharsDuration
    }

    private fun repeatSOS() {
        isSendSOS = true
        val durationOfWord = s(o(s()))
        loopHandler.postDelayed({repeatSOS()}, durationOfWord + spaceWordsDuration)
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
                RequestKey.CALL.value -> {
                    permissionsKeys["CALL"] = true
                    resetIncomingCallFlickeringBtn()
                }
                RequestKey.SMS.value -> {
                    permissionsKeys["SMS"] = true
                    resetSOSBtn()
                }
                RequestKey.AUDIO.value -> {
                    permissionsKeys["AUDIO"] = true
                    resetIncomingSoundBtn()
                }
            }
        }
        else {
            when (requestCode) {
                RequestKey.CALL.value -> {
                    Log.i("MainActivity", "Request NOT granted for CALL")
                    setBtnUnavailable(incomingCallBtn, R.drawable.incoming_call_no_permission)
                }
                RequestKey.SMS.value -> {
                    Log.i("MainActivity", "Request NOT granted for SMS")
                    setBtnUnavailable(incomingSMSBtn, R.drawable.sms_icon_no_permission)
                }
                RequestKey.AUDIO.value -> {
                    Log.i("MainActivity", "Request NOT granted for AUDIO")
                    setBtnUnavailable(incomingSoundBtn, R.drawable.sound_no_permission)
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

        if (incomingCall) {
            unregisterReceiver(incomingCallReceiver)
        }

        if (isPhoneShaken) {
            sensorManager.unregisterListener(sensorEventListener)
        }

        if (networkConnectivityCbIsSet) {
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
        }

        if (isSoundIncoming) {
            audioRecord.stop()
            audioRecord.release()
            try {
                recordingThread?.interrupt()
            }
            catch (e : SecurityException) {
                Log.e("MainActivity", "THREAD SecurityException $e")
            }
            recordingThread?.join()
            recordingThread = null
        }
    }

    private fun resetAllActivities () {
        Log.i("MainActivity", "Reset all activities")
        if (isFlashLightOn) {
            turnOffFlashlight(true)
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

