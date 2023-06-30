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
import android.graphics.Color
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
import android.os.BatteryManager
import android.provider.Telephony
//import android.view.GestureDetector
//import java.lang.Exception
import kotlin.Exception
import kotlin.time.Duration.Companion.minutes


class MainActivity : AppCompatActivity() {

    // elements
    private lateinit var  rootView : View
    private val applicationName : String = "Flashii"
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var flickeringBar : SeekBar
    private lateinit var flickerText : TextView

    // constants, variables
    private val minFlickerHz : Int = 1
    private val maxFlickerHz : Int = 10
    private var flickerFlashlightHz : Long = 1
    private val minBattery : Int = 1
    private val maxBattery : Int = 100
    private val minAltitude : Int = 1   // sea level
    private val maxAltitude : Int = 7000
    private val minTimerMinutes = 1.minutes
    private val maxTimerMinutes = 240.minutes
    private val ditDuration : Long = 250
    private val spaceDuration : Long = ditDuration
    private val dahDuration : Long = 3 * ditDuration
    private val spaceCharsDuration : Long = 3 * spaceDuration
    private val spaceWordsDuration : Long = 4 * spaceDuration // results to 7*ditDuration, considering that we add spaceCharsDuration after each letter
    private val maxFlickerDuration15 : Long = 15000 // 15 seconds
    private val maxFlickerDuration30 : Long = 30000 // 30 seconds
    private val initRotationAngle : Float = -1000f
    private val snackbarDelay : Long = 3000
    private var touchStartTime : Long = 0
    private var thumbInitialPosition = 0
    private var hzInitialPosition = 0
    private var timerSetAfter = 0.minutes
    private var batteryThreshold : Int = minBattery // 1%
    private var altitudeThreshold : Int = minAltitude // sea level
    private val hideSeekBarAfterDelay3 : Long = 3000
    private var initBatteryLevel : Int = minBattery
    private var initAltitudeLevel : Int = minAltitude
    private val checkInterval : Long = 5000 // 5 seconds
    private lateinit var token : Token

    enum class ACTION {
        CREATE,
        RESUME,
        INIT,
        PROGRESS,
        STOP,
        SET,
        RESET,
        SUCCESS,
        NO_PERMISSION
    }

    enum class TypeOfEvent {
        INCOMING_CALL,
        SMS,
        PHONE_TILT,
        AUDIO,
        OUT_OF_SERVICE,
        IN_SERVICE
    }

    enum class NetworkState {
        LOST,
        AVAILABLE,
        UNAVAILABLE,
        ASIS
    }

    enum class SeekBarMode {
        HZ,
        PERCENTAGE,
        METERS,
        HOURS
    }

    enum class RequestKey (val value: Int) {
        CALL(1),
        SMS(2),
        AUDIO(3),
        ALTITUDE(4)
    }

    enum class Token {
        FLICKER,
        TIMER,
        BATTERY,
        ALTITUDE
    }

    private val permissionsKeys = mutableMapOf (
        "CALL" to false,
        "SMS" to false,
        "AUDIO" to false,
        "ALTITUDE" to false
    )

    // Handlers, Threads, Managers, Receivers, Detectors
    private var loopHandler : Handler = Handler(Looper.getMainLooper())
    private lateinit var snackbarHandler: Snackbar
    private lateinit var audioRecordHandler : AudioRecord
    private var recordingThread: Thread? = null
    private lateinit var cameraManager : CameraManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sensorManager : SensorManager
    private lateinit var sensorEventListener : SensorEventListener
    private lateinit var connectivityCallback: ConnectivityManager.NetworkCallback
    private lateinit var incomingCallReceiver : BroadcastReceiver
    private lateinit var incomingSMSReceiver : BroadcastReceiver
    private lateinit var batteryReceiver : BroadcastReceiver
//    private lateinit var gestureDetector: GestureDetector

    // Booleans
    private var isFlashLightOn = false
    private var isFlickering : Boolean = false
    private var isFlickeringOnDemand : Boolean = false
    private var isSendSOS : Boolean = false
    private var isIncomingCall : Boolean = false
    private var isIncomingSMS : Boolean = false
    private var isPhoneOutOfNetwork : Boolean = false
    private var isPhoneInNetwork : Boolean = false
    private var isPhoneTilt : Boolean = false
    private var isAudioIncoming : Boolean = false
    private var networkConnectivityCbIsSet : Boolean = false
    private var isAltitudeOn : Boolean = false
    private var isAltitudeThresholdSet : Boolean = false
    private var isBatteryOn : Boolean = false
    private var isBatteryThresholdSet : Boolean = false
    private var isTimerOn : Boolean = false
    private var isTimerThresholdSet : Boolean = false
//    private var isGestureOn : Boolean = false

    // Buttons & Ids
    private var flashlightId : String = "0"
    private lateinit var sosBtn : ImageButton
    private lateinit var flickerFlashlightBtn : ImageButton
    private lateinit var flashlightBtn : ImageButton
    private lateinit var incomingCallBtn : ImageButton
    private lateinit var outInNetworkBtn : ImageButton
    private lateinit var incomingSMSBtn : ImageButton
    private lateinit var incomingTiltBtn : ImageButton
    private lateinit var incomingSoundBtn : ImageButton
    private lateinit var altitudeBtn : ImageButton
    private lateinit var batteryBtn : ImageButton
    private lateinit var timerBtn : ImageButton
//    private lateinit var gestureBtn : ImageButton

    @SuppressLint("SetTextI18n", "MissingPermission", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootView = findViewById(android.R.id.content)
        snackbarHandler = Snackbar.make(rootView, "", Snackbar.LENGTH_SHORT)

        // setup cameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        ///////////////////////////////////////////////////////////////////////////////////////
        // flashLightBtn handler
        setFlashlightId()
        flashlightBtn = findViewById(R.id.flashLightBtnId)
        turnOnFlashlight(true)
        flashlightBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isFlashLightOn) {
                        Log.i("MainActivity","flashlightBtn is OFF")
                        turnOffFlashlight(true)
                    } else {
                        Log.i("MainActivity","flashlightBtn is ON")
                        resetAllActivities(disableFlickeringOnDemand = true, disableSOS = true)
                        touchStartTime = System.currentTimeMillis()
                        turnOnFlashlight(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If touch duration > 350ms, then its a press-and-hold action and we need to turn-off flashlight.
                    // If otherwise, user just clicked to enable/disable the flashlight.
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
                resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableTilt = true, disableAudioIncoming = true)
                Log.i("MainActivity","sosBtn is ON")
                repeatSOS(true)
                showSnackbar("SOS message transmission")
            }
            else {
                Log.i("MainActivity","sosBtn is OFF")
                dismissSnackbar()
                stopSOS(true)
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
                if (token == Token.FLICKER) {
                    setFlickeringHz(progress.toLong())
                    Log.i("MainActivity","onProgressChanged is ON with ${flickerFlashlightHz}Hz")
                    setSeekBarDisplayText(progress.toString(), SeekBarMode.HZ, ACTION.PROGRESS)
                }
                else if (token == Token.BATTERY && isBatteryOn && !isBatteryThresholdSet) {
                    batteryThreshold = progress
                    setSeekBarDisplayText(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.PROGRESS)
                }
                else if (token == Token.ALTITUDE && isAltitudeOn && !isAltitudeThresholdSet) {
                    altitudeThreshold = progress
                    setSeekBarDisplayText(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.PROGRESS)
                }
                else if (token == Token.TIMER && isTimerOn && !isTimerThresholdSet) {
                    timerSetAfter = progress.minutes
                    setSeekBarDisplayText(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.PROGRESS)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dismissSnackbar()
                loopHandler.removeCallbacksAndMessages(null)
                atomicFlashLightOff()

                if (token == Token.BATTERY && isBatteryOn && isBatteryThresholdSet) {
                    isBatteryThresholdSet = false
                    setPowerLevelDisplayText(ACTION.RESET)
                }
                else if (token == Token.ALTITUDE && isAltitudeOn && isAltitudeThresholdSet) {
                    isAltitudeThresholdSet = false
                    setAltitudeLevelDisplayText(ACTION.RESET)
                }
                else if (token == Token.TIMER && isTimerOn && isTimerThresholdSet) {
                    isTimerThresholdSet = false
                    setTimerThresholdDisplayText(ACTION.RESET)
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isFlickeringOnDemand) {
                    startFlickering()
                }
                else if (token == Token.BATTERY && isBatteryOn && !isBatteryThresholdSet) {
                    isBatteryThresholdSet = true
                    setSeekBarDisplayText(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.STOP)
                    Log.d("MainActivity", "Battery power level set to ${batteryThreshold}%")
//                    showSnackbar("Battery power level set to $batteryThreshold %", Snackbar.LENGTH_LONG)
                    setPowerLevelDisplayText(ACTION.SET)
                    loopHandler.postDelayed({ resetSeekBar(true) }, hideSeekBarAfterDelay3)
                }
                else if (token == Token.ALTITUDE && isAltitudeOn && !isAltitudeThresholdSet) {
                    isAltitudeThresholdSet = true
                    setSeekBarDisplayText(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.STOP)
                    Log.d("MainActivity", "Altitude point set to ${altitudeThreshold}m")
//                    showSnackbar("Altitude point set to {$altitudeThreshold}m high", Snackbar.LENGTH_LONG)
                    setAltitudeLevelDisplayText(ACTION.SET)
                    loopHandler.postDelayed({ resetSeekBar(true) }, hideSeekBarAfterDelay3)
                }
                else if (token == Token.TIMER && isTimerOn && !isTimerThresholdSet) {
                    isTimerThresholdSet = true
                    setSeekBarDisplayText(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.STOP)
                    Log.d("MainActivity", "Timer set to $timerSetAfter")
                    loopHandler.postDelayed({ startFlickering() }, timerSetAfter.inWholeMilliseconds)
                    stopFlickeringAfterTimeout(timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
//                    showSnackbar("Timer set to $isTimerThresholdSet minutes", Snackbar.LENGTH_LONG)
                    setTimerThresholdDisplayText(ACTION.SET)
                    loopHandler.postDelayed({ resetSeekBar(true) }, hideSeekBarAfterDelay3)
                }
            }
        })

        // flicker flashlight button handler
        flickerFlashlightBtn = findViewById(R.id.flickerFlashLightId)
        flickerFlashlightBtn.setOnClickListener {
            if (!isFlickeringOnDemand) {
                token = Token.FLICKER
                resetAllActivities(disableFlashLight = true, disableSOS = true, disableTilt = true, disableAudioIncoming = true)
                setFlickeringHz(minFlickerHz.toLong())
                Log.i("MainActivity","flickerFlashlightBtn is ON with ${flickerFlashlightHz}Hz")
                isFlickeringOnDemand = true
                setSeekBar(SeekBarMode.HZ)
                startFlickering()
                setFlickeringFlashlightBtn()
            }
            else {
                Log.i("MainActivity","flickerFlashlightBtn is OFF")
                isFlickeringOnDemand = false
                stopFlickering()
                resetFlickeringFlashlightBtn()
                resetSeekBar()
                setFlickeringHz(minFlickerHz.toLong())
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming call handler
        incomingCallBtn = findViewById(R.id.switchFlickerIncomingCallsId)
        incomingCallBtn.setOnClickListener {
            // Check first if permissions are granted
            if (permissionsKeys["CALL"] == true) {
                if (!isIncomingCall) {
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
            Log.i("MainActivity","isAudioIncoming CLICKED")
            if (permissionsKeys["AUDIO"] == true) {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

                if (isAudioIncoming) {
                    Log.i("MainActivity","incomingSoundBtn is OFF")
                    isAudioIncoming = false
                    audioRecordHandler.stop()
                    audioRecordHandler.release()
                    dismissSnackbar()
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
                    Log.i("MainActivity","incomingSoundBtn is ON")
                    resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableSOS = true, disableTilt = true)
                    isAudioIncoming = true
                    setIncomingSoundBtn()
                    audioRecordHandler = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )

                    val buffer = ShortArray(bufferSize)
                    audioRecordHandler.startRecording()

                    recordingThread = Thread {
                        while (isAudioIncoming) {
                            val bytesRead = audioRecordHandler.read(buffer, 0, bufferSize)
                            if (isAboveThreshold(buffer, bytesRead)) {
                                if (isFlashLightOn) {
                                    Log.i("MainActivity","LOOP ABOVE THRESHOLD - TURN OFF Flashlight")
                                    turnOffFlashlight()
                                }
                                else {
                                    Log.i("MainActivity","LOOP ABOVE THRESHOLD - TURN ON Flashlight")
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
        // tilt phone handler
        incomingTiltBtn = findViewById(R.id.incomingShakeBtnId)
        incomingTiltBtn.setOnClickListener {
            if (!isPhoneTilt) {
                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (accelerometerSensor != null) {
                    resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableSOS = true, disableAudioIncoming = true)
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
                                        Log.i("MainActivity","TILT REACHED ANGLE - TURN OFF Flashlight")
                                        turnOffFlashlight()
                                    }
                                    else {
                                        Log.i("MainActivity","TILT REACHED ANGLE - TURN ON Flashlight")
                                        turnOnFlashlight()
                                    }
                                }
                            }
                        }
                        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                            // Handle accuracy changes if needed
                        }
                    }
                    Log.i("MainActivity","incomingTiltBtn is ON ($sensorEventListener)")
                    sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    setShakeBtn()
                    isPhoneTilt = true
                    showSnackbar("Flashlight will turn ON/OFF on 90 degrees angle phone tilts")
                }
                else {
                    // we have to disable the btn now since rotation sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    showSnackbar("Device's rotation sensor is not available; feature is not feasible")
                    incomingTiltBtn.setImageResource(R.drawable.tilt_no_permission_r)
                }
            } else {
                Log.i("MainActivity","incomingTiltBtn is OFF ($sensorEventListener)")
                turnOffFlashlight()
                dismissSnackbar()
                sensorManager.unregisterListener(sensorEventListener)
                resetShakeBtn()
                isPhoneTilt = false
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming SMS handler
        incomingSMSBtn = findViewById(R.id.smsBtnId)
        incomingSMSBtn.setOnClickListener {
            // Check first if permissions are granted
            if (permissionsKeys["SMS"] == true) {
                if (!isIncomingSMS) {
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
        setPowerLevelDisplayText(ACTION.RESET)
        batteryBtn.setOnClickListener {
            if (!isBatteryOn) {
                Log.i("MainActivity","batteryBtn is ON")
                token = Token.BATTERY
                resetAllActivities(disableFlickeringOnDemand = true, disableTimer = true, disableAltitudeSensor = true)
                isBatteryOn = true
                setBatteryBtn(ACTION.SET)
                setSeekBar(SeekBarMode.PERCENTAGE)

                batteryReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (initBatteryLevel == minBattery) {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                            initBatteryLevel = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                            Log.i("MainActivity", "Battery initial level is ${initBatteryLevel}%")
                            setSeekBar(SeekBarMode.PERCENTAGE)
                        }

                        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                            val batteryPercentage = (level.toFloat() / scale.toFloat()) * 100

                            if (batteryThreshold > initBatteryLevel) {
                                // So the user is charging his phone and wants an optical indication when threshold is reached
                                if (batteryPercentage.toInt() >= batteryThreshold) {
                                    Log.i("MainActivity", "Battery has been charged up to ${batteryPercentage}%")
                                    showSnackbar("Battery has been charged up to ${batteryPercentage}%", Snackbar.LENGTH_LONG)
                                    Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                    stopFlickeringAfterTimeout(maxFlickerDuration15)
                                    // Should unregister
                                    unregisterReceiver(batteryReceiver)
                                    // Should changed Btn to SUCCESS
                                    setBatteryBtn(ACTION.SUCCESS)
                                }
                            }
                            else {
                                // So the phone is discharged and user wants an optical indication when threshold is reached
                                if (batteryPercentage.toInt() < batteryThreshold) {
                                    Log.i("MainActivity", "Battery is discharged to ${batteryPercentage}%")
                                    showSnackbar("Battery is discharged ${batteryPercentage}%", Snackbar.LENGTH_LONG)
                                    Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                    stopFlickeringAfterTimeout(maxFlickerDuration15)
                                    // Should unregister
                                    unregisterReceiver(batteryReceiver)
                                    // Should changed Btn to SUCCESS
                                    setBatteryBtn(ACTION.SUCCESS)
                                }
                            }
                        }
                    }
                }
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                loopHandler.postDelayed({ checkStatus(Token.BATTERY) }, checkInterval)
            }
            else {
                Log.i("MainActivity","batteryBtn is OFF")
                try {
                    unregisterReceiver(batteryReceiver)
                }
                catch (e : Exception) {
                    // We are OK, receiver is already unregistered
                }
                isBatteryOn = false
                batteryThreshold = minBattery
                dismissSnackbar()
                setBatteryBtn(ACTION.RESET)
                resetSeekBar()
                setPowerLevelDisplayText(ACTION.RESET)
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                initBatteryLevel = minBattery
            }
        }
        ///////////////////////////////////////////////////////////////////////////////////////
        // timer handler
        timerBtn = findViewById(R.id.timerBtnId)
        setTimerThresholdDisplayText(ACTION.RESET)
        timerBtn.setOnClickListener {
            if (!isTimerOn) {
                Log.i("MainActivity","timerBtn is ON")
                token = Token.TIMER
                resetAllActivities(disableFlickeringOnDemand = true, disableBatterySensor = true, disableAltitudeSensor = true)
                isTimerOn = true
                setSeekBar(SeekBarMode.HOURS)
                setTimerBtn()
                loopHandler.postDelayed({ checkStatus(Token.TIMER) }, checkInterval)
            }
            else {
                Log.i("MainActivity","timerBtn is OFF")
                isTimerOn = false
                isTimerThresholdSet = false
                timerSetAfter = minTimerMinutes
                dismissSnackbar()
                resetSeekBar()
                resetTimerBtn()
                setTimerThresholdDisplayText(ACTION.RESET)
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // altitude handler
        altitudeBtn = findViewById(R.id.altitudeBtnId)
        setAltitudeLevelDisplayText(ACTION.RESET)
        altitudeBtn.setOnClickListener {
            if (permissionsKeys["ALTITUDE"] == true) {
                if (!isAltitudeOn) {
                    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    val altitudeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
                    if (altitudeSensor != null) {
                        sensorEventListener = object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                if (event.sensor?.type == Sensor.TYPE_PRESSURE) {
                                    val pressureValue = event.values[0] // Get the pressure value in hPa
                                    val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureValue) // altitude in meters
                                    if (altitude > minAltitude) {
                                        if (initAltitudeLevel == minAltitude) {
                                            initAltitudeLevel = altitude.toInt()
                                            setSeekBar(SeekBarMode.METERS)
                                            Log.d("MainActivity", "initAltitudeLevel set to ${initAltitudeLevel}m")
                                        }
                                        if (altitudeThreshold > initAltitudeLevel) {
                                            // User is ascending in height
                                            if (altitude > altitudeThreshold) {
                                                if (!isFlickering) {
                                                    turnOffFlashlight(true)
                                                    stopFlickering()
                                                    Log.d("MainActivity", "Flickering ON while ascending to altitude of ${flickerFlashlightHz}m")
                                                    startFlickering()
                                                    loopHandler.postDelayed({ showSnackbar("Altitude reached: ${altitude.toInt()}m high") }, snackbarDelay)
                                                    stopFlickeringAfterTimeout(maxFlickerDuration15)
                                                    sensorManager.unregisterListener(sensorEventListener)
                                                    setAltitudeBtn(ACTION.SUCCESS)
                                                    setAltitudeLevelDisplayText(ACTION.SET)
                                                }
                                            }
                                        }
                                        else {
                                            // User is descending in height
                                            if (altitude < altitudeThreshold) { if (!isFlickering) {
                                                turnOffFlashlight(true)
                                                stopFlickering()
                                                Log.d("MainActivity", "Flickering ON while descending to altitude of ${flickerFlashlightHz}m")
                                                startFlickering()
                                                loopHandler.postDelayed({ showSnackbar("Altitude reached: ${altitude.toInt()}m low")}, snackbarDelay)
                                                stopFlickeringAfterTimeout(maxFlickerDuration15)
                                                sensorManager.unregisterListener(sensorEventListener)
                                                setAltitudeBtn(ACTION.SUCCESS)
                                                setAltitudeLevelDisplayText(ACTION.SET)
                                            }
                                            }
                                        }
                                    }
                                }
                            }
                            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                                // Handle accuracy changes if needed
                            }
                        }
                        Log.i("MainActivity","altitudeBtn is ON ($sensorEventListener)")
                        token = Token.ALTITUDE
                        resetAllActivities(disableFlickeringOnDemand = true, disableTimer = true, disableBatterySensor = true)
                        sensorManager.registerListener(sensorEventListener, altitudeSensor, SensorManager.SENSOR_DELAY_NORMAL)
                        setSeekBar(SeekBarMode.METERS)
                        setAltitudeBtn(ACTION.SET)
                        isAltitudeOn = true
                        loopHandler.postDelayed({ checkStatus(Token.ALTITUDE) }, checkInterval)
//                        showSnackbar("Flashlight will turn ON/OFF based on the altitude")
                    }
                    else {
                        // we have to disable the btn now since sensor is not available on the device
                        Log.i("MainActivity","Barometer not available")
                        showSnackbar("Device's barometer sensor is not available; feature is not feasible")
                        setAltitudeBtn(ACTION.NO_PERMISSION)
                        setAltitudeLevelDisplayText(ACTION.NO_PERMISSION)
                    }
                } else {
                    Log.i("MainActivity","altitudeBtn is OFF ($sensorEventListener)")
                    dismissSnackbar()
                    resetSeekBar()
                    sensorManager.unregisterListener(sensorEventListener)
                    setAltitudeBtn(ACTION.RESET)
                    isAltitudeOn = false
                    altitudeThreshold = minAltitude
                    setAltitudeLevelDisplayText(ACTION.RESET)
                    if (!isFlickeringOnDemand) {
                        stopFlickering()
                    }
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
        // Create the GestureDetector with an anonymous GestureListener
//        gestureBtn = findViewById(R.id.gestureBtnId)
//        gestureBtn.setOnClickListener {
//            if (!isGestureOn) {
//                Log.i("MainActivity","isGestureOn is ON")
//                isGestureOn = true
//                setGestureBtn(ACTION.SET)
//                gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
//                    override fun onDoubleTap(e: MotionEvent): Boolean {
//                        // Perform action for double tap gesture
//                        Log.i("MainActivity", "Double tap MotionEvent")
//                        if (!isFlickering) {
//                            startFlickering()
//                        }
//                        else {
//                            stopFlickering()
//                        }
//                        return true
//                    }
//                })
//                rootView.setOnTouchListener { _, event ->
//                    gestureDetector.onTouchEvent(event)
//                    true
//                }
//            }
//            else {
//                Log.i("MainActivity","isGestureOn is OFF")
//                isGestureOn = false
//                setGestureBtn(ACTION.RESET)
//                stopFlickering()
//                rootView.setOnTouchListener(null)
//            }
//        }

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
                        setBtnImage(incomingCallBtn, R.drawable.incoming_call_no_permission_r)
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
                        setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_no_permission_r)
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
                        setBtnImage(incomingSoundBtn, R.drawable.sound_no_permission_r)
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
                        setAltitudeBtn(ACTION.NO_PERMISSION)
                        setAltitudeLevelDisplayText(ACTION.NO_PERMISSION)
                        permissionsKeys["ALTITUDE"] = false
                    }
                }
            }
            else -> {}
        }
    }

    private fun registerIncomingEvents (eventType : TypeOfEvent) {
        when (eventType) {
            TypeOfEvent.INCOMING_CALL -> {
                isIncomingCall = true
                incomingCallReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.i("MainActivity", "EVENT INCOMING")
                        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                            Log.i("MainActivity", "ACTION_PHONE_STATE_CHANGED EVENT")
                            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                                resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableSOS = true, disableAudioIncoming = true, disableTilt = true)
                                Log.d("MainActivity", "EXTRA_STATE_RINGING - Flickering ON with ${flickerFlashlightHz}Hz")
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
                showSnackbar("Flashlight will flicker on incoming phone calls")
            }
            TypeOfEvent.OUT_OF_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "NETWORK is LOST")
                        resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableSOS = true)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering()
                        setNetworkBtn(NetworkState.LOST)
                        stopFlickeringAfterTimeout(maxFlickerDuration30)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }
                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.i("MainActivity", "NETWORK is UNAVAILABLE")
                        resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableSOS = true)
                        setNetworkBtn(NetworkState.UNAVAILABLE)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDuration30)
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
                        resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableSOS = true)
                        setNetworkBtn(NetworkState.AVAILABLE)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDuration30)
                        isPhoneOutOfNetwork = false
                        isPhoneInNetwork = true
                    }}
                Log.i("MainActivity", "Register CB for IN_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                showSnackbar("Flashlight will flicker if WiFi or Network signal is found")
            }
            TypeOfEvent.PHONE_TILT -> {

            }
            TypeOfEvent.AUDIO -> {

            }
            TypeOfEvent.SMS -> {
                Log.i("MainActivity", "SMS_RECEIVED_ACTION registered")
                isIncomingSMS = true
                incomingSMSReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.i("MainActivity", "EVENT INCOMING")
                        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                            Log.i("MainActivity", "SMS_RECEIVED_ACTION EVENT")
                            resetAllActivities(disableFlashLight = true, disableFlickeringOnDemand = true, disableSOS = true)
                            Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                            startFlickering()
                            stopFlickeringAfterTimeout(maxFlickerDuration15)
                        }
                    }
                }
                val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                registerReceiver(incomingSMSReceiver, intentFilter)
                showSnackbar("Flashlight will flicker on incoming SMSes")
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////

//    private fun setGestureBtn (action : ACTION) {
//        when (action) {
//            ACTION.SET -> {
//                gestureBtn.setImageResource(R.drawable.dtap_on)
//            }
//            ACTION.RESET -> {
//                gestureBtn.setImageResource(R.drawable.dtap_off)
//            }
//            else -> {}
//        }
//    }

    private fun checkStatus (token : Token) {
        when (token) {
            Token.TIMER -> {
                if (isTimerOn && !isTimerThresholdSet) {
                    Log.i("MainActivity", "TURN OFF isTimerOn after inactivity")
                    // we have to reset timer key due to user inactivity
                    isTimerOn = false
                    timerSetAfter = minTimerMinutes
                    resetSeekBar()
                    resetTimerBtn()
                    setTimerThresholdDisplayText(ACTION.RESET)
                }
            }
            Token.BATTERY -> {
                 if (isBatteryOn && !isBatteryThresholdSet) {
                     Log.i("MainActivity", "TURN OFF isBatteryOn after inactivity")
                     // we have to reset timer key due to user inactivity
                     try {
                         unregisterReceiver(batteryReceiver)
                     }
                     catch (e : Exception) {
                         // We are OK, receiver is already unregistered
                     }
                     setBatteryBtn(ACTION.RESET)
                     isBatteryOn = false
                     resetSeekBar()
                     batteryThreshold = minBattery
                     initBatteryLevel = minBattery
                     setPowerLevelDisplayText(ACTION.RESET)
                     loopHandler.removeCallbacksAndMessages(null)
                 }
            }
            Token.ALTITUDE -> {
                if (isAltitudeOn && !isAltitudeThresholdSet) {
                    // we have to reset timer key due to user inactivity
                    Log.i("MainActivity", "TURN OFF isAltitudeOn after inactivity")
                    try {
                        resetSeekBar()
                        sensorManager.unregisterListener(sensorEventListener)
                        setAltitudeBtn(ACTION.RESET)
                        isAltitudeOn = false
                        altitudeThreshold = minAltitude
                        setAltitudeLevelDisplayText(ACTION.RESET)
                        loopHandler.removeCallbacksAndMessages(null)
                    }
                    catch (e : Exception) {
                        // We are OK, receiver is already unregistered
                    }
                }
            }
            else -> {}
        }
    }

    private fun setPowerLevelDisplayText (action : ACTION) {
        val textView = findViewById<TextView>(R.id.powerLevelId)
        var text = "$batteryThreshold%"
        when (action) {
            ACTION.SET -> {
                textView.setTextColor(Color.parseColor("#4ECAF6"))
                textView.text = text
            }
            ACTION.RESET -> {
                textView.setTextColor(Color.parseColor("#5a85a0"))
                text = "Power %"
                textView.text = text
            }
            ACTION.NO_PERMISSION -> {
                textView.setTextColor(Color.parseColor("#383838"))
                text = "Power %"
                textView.text = text
            }
            else -> {}
        }
    }

    private fun setTimerThresholdDisplayText (action : ACTION) {
        val textView = findViewById<TextView>(R.id.timerThresholdId)
        var text = timerSetAfter.toString()
        when (action) {
            ACTION.SET -> {
                textView.text = text
                textView.setTextColor(Color.parseColor("#4ECAF6"))
            }
            ACTION.RESET -> {
                text = "Timer -"
                textView.text = text
                textView.setTextColor(Color.parseColor("#5a85a0"))
            }
            else -> {}
        }
    }

    private fun setAltitudeLevelDisplayText(action : ACTION) {
        // set/init textView
//        var textView = findViewById<TextView>(R.id.initAltitudeLevelId)
//        var text = "${initAltitudeLevel}m"
//        textView.text = text
//        textView.setTextColor(Color.parseColor("#FF5A849F"))
//        textView.visibility = when (action) {
//            ACTION.SET -> {
//                TextView.VISIBLE
//            }
//            ACTION.RESET -> {
//                TextView.INVISIBLE
//            }
//            else -> {TextView.INVISIBLE}
//        }

        // target textView
        val textView = findViewById<TextView>(R.id.targetAltitudeLevelId)
        var text = "${altitudeThreshold}m"
        textView.visibility = TextView.VISIBLE
        when (action) {
            ACTION.SET -> {
                textView.text = text
                textView.setTextColor(Color.parseColor("#4ECAF6"))
            }
            ACTION.RESET -> {
                text = "Height -"
                textView.text = text
                textView.setTextColor(Color.parseColor("#5a85a0"))
            }
            ACTION.NO_PERMISSION -> {
                text = "Height -"
                textView.text = text
                textView.setTextColor(Color.parseColor("#383838"))
            }
            else -> {}
        }
    }

    private fun setSeekBarDisplayText (displayValue: String, mode: SeekBarMode, action : ACTION) {
        val displayText : String
        when (mode) {
            SeekBarMode.HOURS -> {
                displayText = when (action) {
                    ACTION.INIT -> {
                        "Set Timer"
                    }
                    ACTION.PROGRESS -> {
                        "Timer set to $displayValue"
                    }
                    ACTION.STOP -> {
                        "Phone will start flickering after $displayValue"
                    }
                    else -> {
                        "Timer set to $displayValue"
                    }
                }
                flickerText.text = displayText
            }
            SeekBarMode.HZ -> {
                displayText = when (action) {
                    ACTION.INIT -> {
                        "Set flickering frequency"
                    }
                    ACTION.PROGRESS -> {
                        "Flickering frequency set to ${displayValue.toInt()} Hz"
                    }
                    ACTION.STOP -> {
                        "Flickering frequency set to ${displayValue.toInt()} Hz"
                    }
                    else -> {
                        "Flickering frequency set to ${displayValue.toInt()} Hz"
                    }
                }
                flickerText.text = displayText
            }
            SeekBarMode.METERS -> {
                displayText = when (action) {
                    ACTION.INIT -> {
                        "Set altitude (currently ${initAltitudeLevel}m)"
                    }
                    ACTION.PROGRESS -> {
                        "High altitude set to ${displayValue.toInt()}m"
                    }
                    ACTION.STOP -> {
                        "Phone will start flickering at the altitude of ${displayValue.toInt()}m"
                    }
                    else -> {
                        "High altitude set to ${displayValue.toInt()}m"
                    }
                }
                flickerText.text = displayText
            }
            SeekBarMode.PERCENTAGE -> {
                displayText = when (action) {
                    ACTION.INIT -> {
                        if (initBatteryLevel != minBattery) {
                            "Set battery level (currently $initBatteryLevel%):"
                        }
                        else {
                            "Set battery level"
                        }
                    }
                    ACTION.PROGRESS -> {
                        "Battery level set to ${displayValue.toInt()}%"
                    }
                    ACTION.STOP -> {
                        "Phone will start flickering at battery level ${displayValue.toInt()}%"
                    }
                    else -> {
                        "Battery level set to ${displayValue.toInt()}%"
                    }
                }
                flickerText.text = displayText
            }
        }
    }

    private fun setSeekBar(mode : SeekBarMode) {
        flickeringBar.visibility = View.VISIBLE
        flickerText.visibility = View.VISIBLE
        thumbInitialPosition = flickeringBar.thumb.bounds.right
        hzInitialPosition = flickerText.x.toInt()
        when (mode) {
            SeekBarMode.HOURS -> {
                flickeringBar.min = minTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.max = maxTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.progress = minTimerMinutes.inWholeMinutes.toInt()
                setSeekBarDisplayText(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.INIT)
            }
            SeekBarMode.HZ -> {
                flickeringBar.min = minFlickerHz
                flickeringBar.max = maxFlickerHz
                flickeringBar.progress = minFlickerHz
                setSeekBarDisplayText(flickerFlashlightHz.toString(), SeekBarMode.HZ, ACTION.INIT)
            }
            SeekBarMode.METERS -> {
                flickeringBar.min = minAltitude
                flickeringBar.max = maxAltitude
                if (initAltitudeLevel != minAltitude) {
                    flickeringBar.progress = initAltitudeLevel
                }
                setSeekBarDisplayText(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.INIT)
            }
            SeekBarMode.PERCENTAGE -> {
                flickeringBar.min = minBattery
                flickeringBar.max = maxBattery
                if (initBatteryLevel != minBattery) {
                    flickeringBar.progress = initBatteryLevel
                }
                setSeekBarDisplayText(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.INIT)
            }
        }
    }

    private fun resetSeekBar (hideBarOnly : Boolean = false) {
        flickeringBar.visibility = View.INVISIBLE
        flickerText.visibility = View.INVISIBLE
        if (!hideBarOnly) {
            flickeringBar.progress = flickeringBar.min
        }
        flickeringBar.min = when (token) {
            Token.FLICKER -> {minFlickerHz}
            Token.TIMER -> {minTimerMinutes.inWholeMinutes.toInt()}
            Token.BATTERY -> {minBattery}
            Token.ALTITUDE -> {minAltitude}
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
        snackbarHandler.dismiss()
        snackbarHandler = Snackbar.make(rootView, text, length)
        snackbarHandler.show()
    }

    private fun dismissSnackbar () {
        snackbarHandler.dismiss()
    }

    private fun setTimerBtn () {
        timerBtn.setImageResource(R.drawable.timer_on_r)
    }

    private fun resetTimerBtn () {
        timerBtn.setImageResource(R.drawable.timer_off_r)
    }

    private fun setBatteryBtn (action : ACTION) {
        when (action) {
            ACTION.SET -> {
                batteryBtn.setImageResource(R.drawable.battery_full_on_r)
            }
            ACTION.RESET -> {
                batteryBtn.setImageResource(R.drawable.battery_full_off_r)
            }
            ACTION.SUCCESS -> {
                batteryBtn.setImageResource(R.drawable.battery_full_on_r)
            }
            else -> {
                batteryBtn.setImageResource(R.drawable.battery_full_off_r)
            }
        }
    }

    private fun setAltitudeBtn (action : ACTION) {
        when (action) {
            ACTION.SET -> {
                altitudeBtn.setImageResource(R.drawable.altitude_on_r)
            }
            ACTION.RESET -> {
                altitudeBtn.setImageResource(R.drawable.altitude_off_r)
            }
            ACTION.SUCCESS -> {
                altitudeBtn.setImageResource(R.drawable.altitude_on_r)
            }
            ACTION.NO_PERMISSION -> {
                altitudeBtn.setImageResource(R.drawable.altitude_no_permission_r)
            }
            else -> {
                altitudeBtn.setImageResource(R.drawable.altitude_off_r)
            }
        }
    }

    private fun setIncomingSoundBtn () {
        incomingSoundBtn.setImageResource(R.drawable.sound_on_r)
    }

    private fun resetIncomingSoundBtn () {
        incomingSoundBtn.setImageResource(R.drawable.sound_off_r)
    }

    private fun setShakeBtn () {
        incomingTiltBtn.setImageResource(R.drawable.tilt_on_r)
    }

    private fun resetShakeBtn () {
        incomingTiltBtn.setImageResource(R.drawable.tilt_off_r)
    }

    private fun resetIncomingSMSBtn () {
        incomingSMSBtn.setImageResource(R.drawable.incoming_sms_off_r)
    }

    private fun setIncomingSMSBtn () {
        incomingSMSBtn.setImageResource(R.drawable.incoming_sms_on_r)
    }

    private fun resetNetworkBtn () {
        outInNetworkBtn.setImageResource(R.drawable.network_off_r)
    }

    private fun setNetworkBtn (networkState : NetworkState = NetworkState.ASIS) {
        if (isPhoneInNetwork) {
            when (networkState) {
                NetworkState.LOST -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_off_r) // wifi_lost_r1
                }
                NetworkState.UNAVAILABLE -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_off_r) // wifi_lost_r1
                }
                NetworkState.ASIS -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_off_r) //wifi_off_enabled_r1
                }

                else -> {}
            }
        }
        else if (isPhoneOutOfNetwork) {
            when (networkState) {
                NetworkState.AVAILABLE -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_on_r) //wifi_on_found_r1
                }
                NetworkState.ASIS -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_on_r) //wifi_on_enabled_r1
                }
                else -> {}
            }
        }
    }

    private fun disableIncomingSMSHandler (showSnack: Boolean = false) {
        isIncomingSMS = false
        if (!isFlickeringOnDemand) {
            stopFlickering()
        }
        unregisterReceiver(incomingSMSReceiver)
        if (showSnack) {
            showSnackbar("Feature dismissed")
        }
    }

    private fun disableIncomingCallFlickering (showSnack: Boolean = false) {
        isIncomingCall = false
        if (!isFlickeringOnDemand) {
            stopFlickering()
        }
        unregisterReceiver(incomingCallReceiver)
        if (showSnack) {
            showSnackbar("Feature dismissed")
        }
    }

    private fun setIncomingCallFlickeringBtn () {
        incomingCallBtn.setImageResource(R.drawable.incoming_call_on_r)
    }

    private fun resetIncomingCallFlickeringBtn () {
        incomingCallBtn.setImageResource(R.drawable.incoming_call_off_r)
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
//        Log.d("MainActivity", "startFlickering ${flickerFlashlightHz}Hz")
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
        flashlightBtn.setImageResource(R.drawable.turn_on_r2)
    }

    private fun resetFlashLightBtn () {
        flashlightBtn.setImageResource(R.drawable.turn_off_r2)
    }

    private fun setSOSBtn () {
        sosBtn.setImageResource(R.drawable.sos_on_r)
    }

    private fun resetSOSBtn () {
        sosBtn.setImageResource(R.drawable.sos_off_r)
    }

    private fun setBtnImage (btn : ImageButton, icon : Int) {
        btn.setImageResource(icon)
    }


    private fun setFlickeringFlashlightBtn () {
        flickerFlashlightBtn.setImageResource(R.drawable.flickering_on_r)
    }

    private fun resetFlickeringFlashlightBtn () {
        flickerFlashlightBtn.setImageResource(R.drawable.flickering_off_r)
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

    private fun repeatSOS(setSOSBtn : Boolean = false) {
        if (!isSendSOS) {
            val durationOfWord = s(o(s()))
            loopHandler.postDelayed({ repeatSOS() }, durationOfWord + spaceWordsDuration)
            if (setSOSBtn) {
                setSOSBtn()
            }
            isSendSOS = true
        }
    }

    private fun stopSOS (resetSOSBtn : Boolean = false) {
        if (isSendSOS) {
            Log.i("MainActivity", "STOP SOS")
            loopHandler.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
            if (resetSOSBtn) {
                resetSOSBtn()
            }
            isSendSOS = false
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
                    setBtnImage(altitudeBtn, R.drawable.altitude_off_r)
                }
            }
        }
        else {
            when (requestCode) {
                RequestKey.CALL.value -> {
                    Log.i("MainActivity", "Request NOT granted for CALL")
                    setBtnImage(incomingCallBtn, R.drawable.incoming_call_no_permission_r)
                }
                RequestKey.SMS.value -> {
                    Log.i("MainActivity", "Request NOT granted for SMS")
                    setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_no_permission_r)
                }
                RequestKey.AUDIO.value -> {
                    Log.i("MainActivity", "Request NOT granted for AUDIO")
                    setBtnImage(incomingSoundBtn, R.drawable.sound_no_permission_r)
                }
                RequestKey.ALTITUDE.value -> {
                    Log.i("MainActivity", "Request NOT granted for LOCATION")
                    setAltitudeBtn(ACTION.NO_PERMISSION)
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

        if (isIncomingCall) {
            unregisterReceiver(incomingCallReceiver)
        }

        if (isPhoneTilt) {
            sensorManager.unregisterListener(sensorEventListener)
        }

        if (networkConnectivityCbIsSet) {
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
        }

        if (isAudioIncoming) {
            audioRecordHandler.stop()
            audioRecordHandler.release()
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

    private fun resetAllActivities (disableFlashLight : Boolean = false, disableFlickeringOnDemand : Boolean = false, disableSOS : Boolean = false, disableIncomingEvents : Boolean = false,
                                    disableTilt : Boolean = false, disableAudioIncoming : Boolean = false, disableNw : Boolean = false, disableBatterySensor : Boolean = false,
                                    disableAltitudeSensor : Boolean = false, disableTimer : Boolean = false) {
        Log.i("MainActivity", " --------- Reset all activities --------- ")
        if (disableFlashLight && isFlashLightOn) {
            Log.i("MainActivity", "RAA - TURN OFF Flashlight")
            turnOffFlashlight(true)
        }

        if (disableFlickeringOnDemand && isFlickering && isFlickeringOnDemand) {
            Log.i("MainActivity", "RAA - STOP FLICKERING on demand")
            isFlickeringOnDemand = false
            stopFlickering()
            resetFlickeringFlashlightBtn()
            resetSeekBar()
            setFlickeringHz(minFlickerHz.toLong())
        }

        if (disableSOS and isSendSOS) {
            Log.i("MainActivity", "RAA - DISABLE SOS")
            stopSOS(true)
        }

        if (disableIncomingEvents) {
            if (isIncomingCall) {
                Log.i("MainActivity", "RAA - TURN OFF isIncomingCall")
                dismissSnackbar()
                disableIncomingCallFlickering()
                resetIncomingCallFlickeringBtn()
            }
            if (isIncomingSMS) {
                Log.i("MainActivity", "RAA - TURN OFF isIncomingSMS")
                dismissSnackbar()
                disableIncomingSMSHandler()
                resetIncomingSMSBtn()
            }
        }

        if (disableTilt && isPhoneTilt) {
            Log.i("MainActivity", "RAA - TURN OFF isPhoneTilt")
            turnOffFlashlight()
            dismissSnackbar()
            sensorManager.unregisterListener(sensorEventListener)
            resetShakeBtn()
            isPhoneTilt = false
            loopHandler.removeCallbacksAndMessages(null)
        }

        if (disableAudioIncoming && isAudioIncoming) {
            Log.i("MainActivity", "RAA - TURN OFF isAudioIncoming")
            isAudioIncoming = false
            turnOffFlashlight()
            resetIncomingSoundBtn()
            audioRecordHandler.stop()
            audioRecordHandler.release()
            try {
                recordingThread?.interrupt()
            }
            catch (e : SecurityException) {
                Log.e("MainActivity", "THREAD SecurityException $e")
            }
            recordingThread?.join()
            recordingThread = null
            loopHandler.removeCallbacksAndMessages(null)
        }

        if (disableNw && networkConnectivityCbIsSet) {
            Log.i("MainActivity", "RAA - TURN OFF networkConnectivityCbIsSet")
            networkConnectivityCbIsSet = false
            dismissSnackbar()
            stopFlickering()
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
            resetNetworkBtn()
        }

        if (disableBatterySensor && isBatteryOn && !isBatteryThresholdSet) {
            Log.i("MainActivity", "RAA - TURN OFF isBatteryOn")
            turnOffFlashlight()
            dismissSnackbar()
            try {
                unregisterReceiver(batteryReceiver)
            }
            catch (e : Exception) {
                // We are OK, receiver is already unregistered
            }
            setBatteryBtn(ACTION.RESET)
            isBatteryOn = false
            resetSeekBar()
            batteryThreshold = minBattery
            initBatteryLevel = minBattery
            setPowerLevelDisplayText(ACTION.RESET)
            loopHandler.removeCallbacksAndMessages(null)
        }

        if (disableAltitudeSensor && isAltitudeOn && !isAltitudeThresholdSet) {
            Log.i("MainActivity", "RAA - TURN OFF isAltitudeOn")
            turnOffFlashlight()
            dismissSnackbar()
            resetSeekBar()
            sensorManager.unregisterListener(sensorEventListener)
            setAltitudeBtn(ACTION.RESET)
            isAltitudeOn = false
            altitudeThreshold = minAltitude
            setAltitudeLevelDisplayText(ACTION.RESET)
            loopHandler.removeCallbacksAndMessages(null)
        }

        if (disableTimer && isTimerOn && !isTimerThresholdSet) {
            Log.i("MainActivity", "RAA - TURN OFF isTimerOn")
            isTimerOn = false
            isTimerThresholdSet = false
            timerSetAfter = minTimerMinutes
            dismissSnackbar()
            resetSeekBar()
            resetTimerBtn()
            setTimerThresholdDisplayText(ACTION.RESET)
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

