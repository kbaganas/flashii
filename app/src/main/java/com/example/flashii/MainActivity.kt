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
import java.lang.Exception


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
    private val maxFlickerDurationAltitude : Long = 15000 // 30 seconds
    private val applicationName : String = "Flashii"
    private val initRotationAngle : Float = -1000f
    private val snackbarDelay : Long = 3000

    enum class ACTION {
        CREATE,
        RESUME
    }

    enum class TypeOfEvent {
        INCOMING_CALL,
        SMS,
        PHONE_SHAKE,
        SPEAK,
        OUT_OF_SERVICE,
        IN_SERVICE
    }

    enum class NetworkState {
        LOST,
        AVAILABLE,
        UNAVAILABLE,
        ASIS
    }

    enum class RequestKey (val value: Int) {
        CALL(1),
        SMS(2),
        AUDIO(3),
        ALTITUDE(4),
        LOW_ALTITUDE (-5),
        HIGH_ALTITUDE (1800)
    }

    private val permissionsKeys = mutableMapOf (
        "CALL" to false,
        "SMS" to false,
        "AUDIO" to false,
        "ALTITUDE" to false
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
    private var isFlickeringOnDemand : Boolean = false
    private var isSendSOS : Boolean = false
    private var incomingCall : Boolean = false
    private var incomingSMS : Boolean = false
    private var isPhoneOutOfNetwork : Boolean = false
    private var isPhoneInNetwork : Boolean = false
    private var isPhoneShaken : Boolean = false
    private var isSoundIncoming : Boolean = false
    private var networkConnectivityCbIsSet : Boolean = false
    private var isAltitudeOn : Boolean = false
    private var isBatteryOn : Boolean = false
    private var isTimerOn : Boolean = false

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
    private lateinit var altitudeBtn : ImageButton
    private lateinit var batteryBtn : ImageButton
    private lateinit var timerBtn : ImageButton

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
                        Log.i("MainActivity","flashlightBtn is OFF")
                        turnOffFlashlight(true)
                    } else {
                        Log.i("MainActivity","flashlightBtn is ON")
                        resetAllActivities(true, disableSensorListeners = true)
                        touchStartTime = System.currentTimeMillis()
                        turnOnFlashlight(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If touch duration > 750ms, then its a press-and-hold action and we need to turn-off flashlight.
                    // If otherwise, user just clicked to enable or disable the flashlight.
                    if (System.currentTimeMillis() - touchStartTime > 350) {
                        Log.i("MainActivity","flashlightBtn is OFF")
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
                resetAllActivities(disableSensorListeners = true)
                Log.i("MainActivity","sosBtn is ON")
                repeatSOS()
                setSOSBtn()
                showSnackbar("SOS message transmission")
            }
            else {
                Log.i("MainActivity","sosBtn is OFF")
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
                Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                startFlickering()
            }
        })

        // flicker flashlight button handler
        flickerFlashlightBtn = findViewById(R.id.flickerFlashLightId)
        flickerFlashlightBtn.setOnClickListener {
            if (!isFlickeringOnDemand) {
                Log.i("MainActivity","flickerFlashlightBtn is ON with ${flickerFlashlightHz}Hz")
                resetAllActivities(disableSOS = true, disableSensorListeners = true)
                startFlickering()
                setFlickeringFlashlightBtn()
            }
            else {
                Log.i("MainActivity","flickerFlashlightBtn is OFF")
                stopFlickering()
                resetFlickeringFlashlightBtn()
                setFlickeringHz(minFlickerHz.toLong())
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming call handler
        incomingCallBtn = findViewById(R.id.switchFlickerIncomingCallsId)
        incomingCallBtn.setOnClickListener {
            // Check first if permissions are granted
            if (permissionsKeys["CALL"] == true) {
                if (!incomingCall) {
                    Log.i("MainActivity","incomingCallBtn is ON")
                    registerIncomingEvents(TypeOfEvent.INCOMING_CALL)
                    setIncomingCallFlickeringBtn()
                } else {
                    Log.i("MainActivity", "incomingCallBtn is OFF")
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

                if (isSoundIncoming) {
                    Log.i("MainActivity","incomingSoundBtn is ON")
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
                    Log.i("MainActivity","incomingSoundBtn is OFF")
                    resetAllActivities(disableSOS = true, disableSensorListeners = true)
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
                            if (isAboveThreshold(buffer, bytesRead)) {
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
                    resetAllActivities(disableSOS = true, disableSensorListeners = true)
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
                    Log.i("MainActivity","incomingShakeBtn is ON ($sensorEventListener)")
                    sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    setShakeBtn()
                    isPhoneShaken = true
                    showSnackbar("Flashlight will turn ON/OFF on 90 degrees angle phone tilts")
                }
                else {
                    // we have to disable the btn now since rotation sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    showSnackbar("Device's rotation sensor is not available; feature is not feasible")
                    incomingShakeBtn.setImageResource(R.drawable.rotate_no_permission)
                }
            } else {
                Log.i("MainActivity","incomingShakeBtn is OFF ($sensorEventListener)")
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
                    Log.i("MainActivity","incomingSMSBtn is ON")
                    registerIncomingEvents(TypeOfEvent.SMS)
                    setIncomingSMSBtn()
                } else {
                    Log.i("MainActivity", "incomingSMSBtn is OFF")
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
                Log.i("MainActivity","outInNetworkBtn is OFF")
                networkConnectivityCbIsSet = false
                dismissSnackbar()
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
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
                    registerIncomingEvents(TypeOfEvent.IN_SERVICE)
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
                            registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                        }
                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Log.i("MainActivity", "NETWORK is currently LOST")
                            isPhoneOutOfNetwork = true
                            setNetworkBtn()
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                        }
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            Log.i("MainActivity", "NETWORK is currently AVAILABLE")
                            isPhoneInNetwork = true
                            setNetworkBtn()
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.OUT_OF_SERVICE)
                        }
                    }
                    Log.i("MainActivity", "Register CB $connectivityCallback")
                    connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                }
                Log.i("MainActivity","outInNetworkBtn is ON")
            }
        }


        ///////////////////////////////////////////////////////////////////////////////////////
        // battery handler
        batteryBtn = findViewById(R.id.batteryBtnId)
        batteryBtn.setOnClickListener {
            if (!isBatteryOn) {
                resetAllActivities(disableSensorListeners = true)
                Log.i("MainActivity","batteryBtn is ON")
                isBatteryOn = true
                setBatteryBtn()
            }
            else {
                Log.i("MainActivity","batteryBtn is OFF")
                isBatteryOn = false
                dismissSnackbar()
                resetBatteryBtn()
            }
        }
        ///////////////////////////////////////////////////////////////////////////////////////
        // timer handler
        timerBtn = findViewById(R.id.timerBtnId)
        timerBtn.setOnClickListener {
            if (!isTimerOn) {
                resetAllActivities(disableSensorListeners = true)
                Log.i("MainActivity","timerBtn is ON")
                isTimerOn = true
                setTimerBtn()
            }
            else {
                Log.i("MainActivity","timerBtn is OFF")
                isTimerOn = false
                dismissSnackbar()
                resetTimerBtn()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // altitude handler
        altitudeBtn = findViewById(R.id.altitudeBtnId)
        altitudeBtn.setOnClickListener {
            if (permissionsKeys["ALTITUDE"] == true) {
                if (!isAltitudeOn) {
                    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    val altitudeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
                    if (altitudeSensor != null) {
                        resetAllActivities(disableSOS = true, disableSensorListeners = true)
                        sensorEventListener = object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                if (event.sensor?.type == Sensor.TYPE_PRESSURE) {
                                    val pressureValue = event.values[0] // Get the pressure value in hPa
                                    val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureValue) // altitude in meters
                                    if (altitude > RequestKey.LOW_ALTITUDE.value && altitude < RequestKey.HIGH_ALTITUDE.value) {
                                        // these are the acceptable altitude limits
                                    }
                                    else {
                                        if (!isFlickering) {
                                            Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                                            startFlickering()
                                            loopHandler.postDelayed({ showSnackbar("WARNING: altitude reached is $altitude meters high") }, snackbarDelay)
                                            stopFlickeringAfterTimeout(maxFlickerDurationAltitude)
                                            sensorManager.unregisterListener(sensorEventListener)
                                        }
                                    }
                                }
                            }
                            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                                // Handle accuracy changes if needed
                            }
                        }
                        Log.i("MainActivity","altitudeBtn is ON ($sensorEventListener)")
                        sensorManager.registerListener(sensorEventListener, altitudeSensor, SensorManager.SENSOR_DELAY_NORMAL)
                        setAltitudeBtn()
                        isAltitudeOn = true
                        showSnackbar("Flashlight will turn ON/OFF based on the altitude")
                    }
                    else {
                        // we have to disable the btn now since sensor is not available on the device
                        Log.i("MainActivity","Barometer not available")
                        showSnackbar("Device's barometer sensor is not available; feature is not feasible")
                        altitudeBtn.setImageResource(R.drawable.altitude_btn_no_permission)
                    }
                } else {
                    Log.i("MainActivity","altitudeBtn is OFF ($sensorEventListener)")
                    turnOffFlashlight()
                    dismissSnackbar()
                    try {
                        sensorManager.unregisterListener(sensorEventListener)
                    }
                    catch (e : Exception) {
                        // do nothing
                    }
                    resetAltitudeBtn()
                    isAltitudeOn = false
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for ALTITUDE")
                showSnackbar("To use the feature, manually provide LOCATION permissions to $applicationName in your phone's Settings", Snackbar.LENGTH_LONG)
            }
        }


        ///////////////////////////////////////////////////////////////////////////////////////
        // gesture handler

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

                // ALTITUDE
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), RequestKey.ALTITUDE.value)
                }
                else {
                    permissionsKeys["ALTITUDE"] = true
                }
            }
            ACTION.RESUME -> {
                // User may have changed the permissions in Settings/App/Flashii/Licenses, so we have to align with that
                Log.i("MainActivity", "Ask for permissions again RESUME")

                // CALL
                if (permissionsKeys["CALL"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "Ask for CALL permissions again RESUME ")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), RequestKey.CALL.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                        setBtnImage(incomingCallBtn, R.drawable.incoming_call_no_permission)
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
                        setBtnImage(incomingSMSBtn, R.drawable.sms_icon_no_permission)
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
                        setBtnImage(incomingSoundBtn, R.drawable.sound_no_permission)
                        permissionsKeys["AUDIO"] = false
                    }
                }

                // ALTITUDE
                if (permissionsKeys["ALTITUDE"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "Ask for ALTITUDE permissions again RESUME ")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), RequestKey.ALTITUDE.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        setBtnImage(altitudeBtn, R.drawable.altitude_btn_no_permission)
                        permissionsKeys["ALTITUDE"] = false
                    }
                }
            }
        }
    }

    private fun isAboveThreshold(buffer: ShortArray, bytesRead: Int): Boolean {
        val threshold = 20000
        for (i in 0 until bytesRead) {
            if (buffer[i] > threshold || buffer[i] < -threshold) {
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

    private fun registerIncomingEvents (eventType : TypeOfEvent) {
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
                                Log.d("MainActivity", "EXTRA_STATE_RINGING - Flickering ON with ${flickerFlashlightHz}Hz")
                                resetAllActivities(disableSOS = true, disableSensorListeners = true)
                                startFlickering()
                            }
                            else if (state == TelephonyManager.EXTRA_STATE_IDLE || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                                Log.i("MainActivity", "IDLE/OFF-HOOK - Phone stops flickering")
                                stopFlickering()
                            }
                        }
                    }
                }
                val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                registerReceiver(incomingCallReceiver, intentFilter)
                showSnackbar("Flashlight will flicker on incoming calls")
            }
            TypeOfEvent.OUT_OF_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "NETWORK is LOST")
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        resetAllActivities(disableSOS = true, disableSensorListeners = true)
                        startFlickering()
                        setNetworkBtn(NetworkState.LOST)
                        stopFlickeringAfterTimeout(maxFlickerDurationNetwork)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }
                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.i("MainActivity", "NETWORK is UNAVAILABLE")
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        resetAllActivities(disableSOS = true, disableSensorListeners = true)
                        setNetworkBtn(NetworkState.UNAVAILABLE)
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDurationNetwork)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }}
                Log.i("MainActivity", "Register CB for OUT_OF_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                showSnackbar("Flashlight will flicker if WiFi or Network signal gets lost")
            }
            TypeOfEvent.IN_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i("MainActivity", "NETWORK is AVAILABLE")
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        resetAllActivities(disableSOS = true, disableSensorListeners = true)
                        setNetworkBtn(NetworkState.AVAILABLE)
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDurationNetwork)
                        isPhoneOutOfNetwork = false
                        isPhoneInNetwork = true
                    }}
                Log.i("MainActivity", "Register CB for IN_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                showSnackbar("Flashlight will flicker if WiFi or Network signal is found")
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
                            Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                            resetAllActivities(disableSOS = true, disableSensorListeners = true)
                            startFlickering()
                            stopFlickeringAfterTimeout(maxFlickerDurationIncomingSMS)
                        }
                    }
                }
                val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                registerReceiver(incomingSMSReceiver, intentFilter)
                showSnackbar("Flashlight will flicker on incoming SMSes")
            }
        }
    }


    private fun setAltitudeBtn () {
        altitudeBtn.setImageResource(R.drawable.altitude_btn_on)
    }


    private fun setTimerBtn () {
        timerBtn.setImageResource(R.drawable.timer_on)
    }

    private fun resetTimerBtn () {
        timerBtn.setImageResource(R.drawable.timer_off)
    }

    private fun setBatteryBtn () {
        batteryBtn.setImageResource(R.drawable.battery_on)
    }

    private fun resetBatteryBtn () {
        batteryBtn.setImageResource(R.drawable.battery_off)
    }

    private fun resetAltitudeBtn () {
        altitudeBtn.setImageResource(R.drawable.altitude_btn_off)
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

    private fun setNetworkBtn (networkState : NetworkState = NetworkState.ASIS) {
        if (isPhoneInNetwork) {
            when (networkState) {
                NetworkState.LOST -> {
                    outInNetworkBtn.setImageResource(R.drawable.wifi_lost)
                }
                NetworkState.UNAVAILABLE -> {
                    outInNetworkBtn.setImageResource(R.drawable.wifi_lost)
                }
                NetworkState.ASIS -> {
                    outInNetworkBtn.setImageResource(R.drawable.wifi_off_enabled)
                }

                else -> {}
            }
        }
        else if (isPhoneOutOfNetwork) {
            when (networkState) {
                NetworkState.AVAILABLE -> {
                    outInNetworkBtn.setImageResource(R.drawable.wifi_on_found)
                }
                NetworkState.ASIS -> {
                    outInNetworkBtn.setImageResource(R.drawable.wifi_on_enabled)
                }
                else -> {}
            }
        }
    }

    private fun disableIncomingSMSHandler (showSnack: Boolean = false) {
        incomingSMS = false
        if (!isFlickeringOnDemand) {
            stopFlickering()
        }
        unregisterReceiver(incomingSMSReceiver)
        if (showSnack) {
            showSnackbar("Feature dismissed")
        }
    }

    private fun disableIncomingCallFlickering (showSnack: Boolean = false) {
        incomingCall = false
        if (!isFlickeringOnDemand) {
            stopFlickering()
        }
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
        isFlickering = true
        val periodOfFlashLightInMilliseconds =  1000 / flickerFlashlightHz
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

    private fun setBtnImage (btn : ImageButton, icon : Int) {
        btn.setImageResource(icon)
    }


    private fun setFlickeringFlashlightBtn () {
        flickeringBar.visibility = View.VISIBLE
        flickerText.visibility = View.VISIBLE
        val displayText =  "$flickerFlashlightHz" + "Hz"
        flickerText.text = displayText
        flickerFlashlightBtn.setImageResource(R.drawable.flicker_on3)
        thumbInitialPosition = flickeringBar.thumb.bounds.right
        hzInitialPosition = flickerText.x.toInt()
        isFlickeringOnDemand = true
    }

    private fun resetFlickeringFlashlightBtn () {
        flickeringBar.visibility = View.INVISIBLE
        flickeringBar.progress = flickeringBar.min
        flickerText.visibility = View.INVISIBLE
        flickerFlashlightBtn.setImageResource(R.drawable.flicker_off3)
        isFlickeringOnDemand = false
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
                RequestKey.ALTITUDE.value -> {
                    permissionsKeys["ALTITUDE"] = true
                    setBtnImage(altitudeBtn, R.drawable.altitude_btn_off)
                }
            }
        }
        else {
            when (requestCode) {
                RequestKey.CALL.value -> {
                    Log.i("MainActivity", "Request NOT granted for CALL")
                    setBtnImage(incomingCallBtn, R.drawable.incoming_call_no_permission)
                }
                RequestKey.SMS.value -> {
                    Log.i("MainActivity", "Request NOT granted for SMS")
                    setBtnImage(incomingSMSBtn, R.drawable.sms_icon_no_permission)
                }
                RequestKey.AUDIO.value -> {
                    Log.i("MainActivity", "Request NOT granted for AUDIO")
                    setBtnImage(incomingSoundBtn, R.drawable.sound_no_permission)
                }
                RequestKey.ALTITUDE.value -> {
                    Log.i("MainActivity", "Request NOT granted for LOCATION")
                    setBtnImage(altitudeBtn, R.drawable.altitude_btn_no_permission)
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

    private fun resetAllActivities (disableSOS : Boolean = false, disableIncomingEvents : Boolean = false, disableSensorListeners : Boolean = false) {
        Log.i("MainActivity", "Reset all activities")
        if (isFlashLightOn) {
            turnOffFlashlight(true)
        }
        else if (isFlickering && isFlickeringOnDemand) {
            stopFlickering()
            resetFlickeringFlashlightBtn()
            setFlickeringHz(minFlickerHz.toLong())
        }

        if (disableSOS) {
            stopSOS()
        }

        if (disableSensorListeners) {
            if (isPhoneShaken) {
                turnOffFlashlight()
                dismissSnackbar()
                sensorManager.unregisterListener(sensorEventListener)
                resetShakeBtn()
                isPhoneShaken = false
            }

            if (isSoundIncoming) {
                isSoundIncoming = false
                turnOffFlashlight()
                resetIncomingSoundBtn()
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

        if (disableIncomingEvents) {
            if (incomingCall) {
                dismissSnackbar()
                disableIncomingCallFlickering()
                resetIncomingCallFlickeringBtn()
            }

            if (networkConnectivityCbIsSet) {
                networkConnectivityCbIsSet = false
                dismissSnackbar()
                stopFlickering()
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                resetNetworkBtn()
            }
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

