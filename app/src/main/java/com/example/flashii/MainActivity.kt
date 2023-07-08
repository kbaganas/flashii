package com.example.flashii

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flashii.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlin.time.Duration.Companion.minutes

class MainActivity : AppCompatActivity() {

    // elements
    private lateinit var  rootView : View
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var flickeringBar : SeekBar
    private lateinit var messageText : TextView
    private val applicationName : String = "Flashii"

    // constants, variables
    private val minFlickerHz : Int = 1
    private var maxFlickerHz : Int = 10
    private var flickerFlashlightHz : Long = 1
    private val minBattery : Int = 1
    private val maxBattery : Int = 100
    private var batteryThreshold : Int = minBattery
    private var initBatteryLevel : Int = minBattery
    private val minAltitude : Int = 1
    private val maxAltitude : Int = 7000
    private var altitudeThreshold : Int = minAltitude
    private var initAltitudeLevel : Int = minAltitude
    private val minTimerMinutes = 1.minutes
    private var maxTimerMinutes = 240.minutes
    private val ditDuration : Long = 250
    private val spaceDuration : Long = ditDuration
    private val dahDuration : Long = 3 * ditDuration
    private val spaceCharsDuration : Long = 3 * spaceDuration
    private val spaceWordsDuration : Long = 4 * spaceDuration // results to 7*ditDuration, considering that we add spaceCharsDuration after each letter
    private val maxFlickerDuration15 : Long = 15000 // 15 seconds
    private val maxFlickerDuration30 : Long = 30000 // 30 seconds
    private var maxFlickerDurationIncomingCall : Int = 15000
    private var maxFlickerDurationIncomingSMS : Int = 15000
    private var maxFlickerDurationBattery : Int = 15000
    private var maxFlickerDurationAltitude : Int = 15000
    private val initRotationAngle : Float = -1000f
    private var touchStartTime : Long = 0
    private var thumbInitialPosition = 0
    private var hzInitialPosition = 0
    private var timerSetAfter = 0.minutes
    private var sensitivityAngle = 80
    private var sensitivitySoundThreshold = 20000
    private val hideSeekBarAfterDelay35 : Long = 3500 // 3.5 seconds
    private val hideMessageTextAfter35 : Long = 3500
    //private val hideMessageTextAfter15 : Long = 1500 // 1.5 seconds
    private val checkInterval50 : Long = 5000 // checkStatus after interval
    private lateinit var token : Token // token regarding which key is pressed
    private var tokenMessageText : Token = Token.FLASHLIGHT // token regarding which feature is using the message text
    private lateinit var reviewInfo : ReviewInfo

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
        ALTITUDE,
        INCOMING_CALL,
        INCOMING_SMS,
        NETWORK,
        SOS,
        SOUND,
        TILT,
        FLASHLIGHT
    }

    private val permissionsKeys = mutableMapOf (
        "CALL" to false,
        "SMS" to false,
        "AUDIO" to false,
        "ALTITUDE" to false
    )

    // Handlers, Threads, Managers, Receivers, Detectors
    private var loopHandler : Handler = Handler(Looper.getMainLooper())
    private var messageLoopHandler : Handler = Handler(Looper.getMainLooper())
    private var timerLoopHandler : Handler = Handler(Looper.getMainLooper())
    private lateinit var audioRecordHandler : AudioRecord
    private var recordingThread: Thread? = null
    private lateinit var cameraManager : CameraManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sensorManager : SensorManager
    private lateinit var sensorEventListener : SensorEventListener
    private lateinit var connectivityCallback: ConnectivityManager.NetworkCallback
    private lateinit var reviewManager : ReviewManager
    private lateinit var incomingCallReceiver : BroadcastReceiver
    private lateinit var incomingSMSReceiver : BroadcastReceiver
    private lateinit var batteryReceiver : BroadcastReceiver

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
    private var isNetworkConnectivityCbIsSet : Boolean = false
    private var isAltitudeOn : Boolean = false
    private var isAltitudeThresholdSet : Boolean = false
    private var isBatteryOn : Boolean = false
    private var isBatteryThresholdSet : Boolean = false
    private var isTimerOn : Boolean = false
    private var isTimerThresholdSet : Boolean = false
    private var isStartTrackingTouched : Boolean = false

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
    private lateinit var infoBtn : ImageButton
    private lateinit var settingsBtn : ImageButton
    private lateinit var rateBtn : ImageButton
    private lateinit var donateBtn : ImageButton

    @SuppressLint("SetTextI18n", "MissingPermission", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootView = findViewById(android.R.id.content)

        // setup cameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        messageText = findViewById(R.id.messageId)

        ///////////////////////////////////////////////////////////////////////////////////////
        // flashLightBtn handler
        setFlashlightId()
        flashlightBtn = findViewById(R.id.flashLightBtnId)
        turnOnFlashlight(true)
        setMessageText("Flashlight is turned On", hideMessageTextAfter35, Token.FLASHLIGHT)
        flashlightBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartTime = System.currentTimeMillis()
                    if (isFlashLightOn) {
                        Log.i("MainActivity","flashlightBtn is OFF")
                        turnOffFlashlight(true)
                        setMessageText(token = Token.FLASHLIGHT)
                    } else {
                        Log.i("MainActivity","flashlightBtn is ON")
                        resetAllActivities(Token.FLASHLIGHT)
                        turnOnFlashlight(true)
                        setMessageToken(Token.FLASHLIGHT)
                        setMessageText("Flashlight is turned On", hideMessageTextAfter35, Token.FLASHLIGHT)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If touch duration > 350ms, then its a press-and-hold action and we need to turn-off flashlight.
                    // If otherwise, user just clicked to enable/disable the flashlight.
                    if (System.currentTimeMillis() - touchStartTime > 350) {
                        Log.i("MainActivity","flashlightBtn is OFF after press/hold")
                        turnOffFlashlight(true)
                        setMessageText(token = Token.FLASHLIGHT)
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
                resetAllActivities(Token.SOS)
                Log.i("MainActivity","sosBtn is ON")
                repeatSOS(true)
                setMessageToken(Token.SOS)
                setMessageText("SOS is transmitted", token = Token.SOS)
            }
            else {
                Log.i("MainActivity","sosBtn is OFF")
                stopSOS(true)
                setMessageText(token = Token.SOS)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flicker seekbar and textview handler
        flickeringBar = findViewById(R.id.flickeringBarId)
        flickeringBar.min = minFlickerHz
        flickeringBar.max = maxFlickerHz
        flickeringBar.visibility = View.INVISIBLE

        flickeringBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (token == Token.FLICKER && isFlickeringOnDemand) {
                    setFlickeringHz(progress.toLong())
//                    Log.i("MainActivity","onProgressChanged is ON with ${flickerFlashlightHz}Hz")
                    setMessageDisplayTextEnhanced(progress.toString(), SeekBarMode.HZ, ACTION.PROGRESS)
                }
                else if (token == Token.BATTERY && isBatteryOn && !isBatteryThresholdSet) {
                    batteryThreshold = progress
                    setMessageDisplayTextEnhanced(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.PROGRESS)
                }
                else if (token == Token.ALTITUDE && isAltitudeOn && !isAltitudeThresholdSet) {
                    altitudeThreshold = progress
                    setMessageDisplayTextEnhanced(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.PROGRESS)
                }
                else if (token == Token.TIMER && isTimerOn && !isTimerThresholdSet) {
                    timerSetAfter = progress.minutes
                    setMessageDisplayTextEnhanced(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.PROGRESS)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                loopHandler.removeCallbacksAndMessages(null)
                atomicFlashLightOff()
                isStartTrackingTouched = true

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
                    setMessageDisplayTextEnhanced(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.STOP)
                    Log.d("MainActivity", "Battery power level \nset to ${batteryThreshold}%")
                    setPowerLevelDisplayText(ACTION.SET)
                    loopHandler.postDelayed({ resetSeekBar(true) }, hideSeekBarAfterDelay35)
                }
                else if (token == Token.ALTITUDE && isAltitudeOn && !isAltitudeThresholdSet) {
                    isAltitudeThresholdSet = true
                    setMessageDisplayTextEnhanced(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.STOP)
                    Log.d("MainActivity", "Altitude point set to ${altitudeThreshold}m")
                    setAltitudeLevelDisplayText(ACTION.SET)
                    loopHandler.postDelayed({ resetSeekBar(true) }, hideSeekBarAfterDelay35)
                }
                else if (token == Token.TIMER && isTimerOn && !isTimerThresholdSet) {
                    isTimerThresholdSet = true
                    setMessageDisplayTextEnhanced(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.STOP)
                    Log.d("MainActivity", "Timer set to $timerSetAfter")
                    timerLoopHandler.postDelayed({ startFlickering() }, timerSetAfter.inWholeMilliseconds)
                    timerLoopHandler.postDelayed({ setBtnImage(timerBtn, R.drawable.timer_success) }, timerSetAfter.inWholeMilliseconds)
                    timerLoopHandler.postDelayed({ stopFlickering() }, timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
                    timerLoopHandler.postDelayed({ setBtnImage(timerBtn, R.drawable.timer_off_m3) }, timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
                    setTimerThresholdDisplayText(ACTION.SET)
                    loopHandler.postDelayed({ resetSeekBar(true) }, hideSeekBarAfterDelay35)
                }
                isStartTrackingTouched = false
            }
        })

        // flicker flashlight button handler
        flickerFlashlightBtn = findViewById(R.id.flickerFlashLightId)
        flickerFlashlightBtn.setOnClickListener {
            if (!isFlickeringOnDemand) {
                token = Token.FLICKER
                resetAllActivities(Token.FLICKER)
                setFlickeringHz(minFlickerHz.toLong())
                Log.i("MainActivity","flickerFlashlightBtn is ON with ${flickerFlashlightHz}Hz")
                isFlickeringOnDemand = true
                setSeekBar(SeekBarMode.HZ)
                startFlickering()
                setBtnImage(flickerFlashlightBtn, R.drawable.flickering_on_m3)
            }
            else {
                Log.i("MainActivity","flickerFlashlightBtn is OFF")
                isFlickeringOnDemand = false
                stopFlickering()
                setBtnImage(flickerFlashlightBtn, R.drawable.flickering_off_m3)
                resetSeekBar()
                setFlickeringHz(minFlickerHz.toLong())
                setMessageText(token = Token.FLICKER)
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
                    setBtnImage(incomingCallBtn, R.drawable.incoming_call_on_m3)
                    setMessageToken(Token.INCOMING_CALL)
                    setMessageText("Flashlight will be flickering\non incoming Calls", hideMessageTextAfter35, Token.INCOMING_CALL)
                } else {
                    Log.i("MainActivity", "incomingCallBtn is OFF")
                    disableIncomingCallFlickering()
                    setBtnImage(incomingCallBtn, R.drawable.incoming_call_off_m3)
                    setMessageText(token = Token.INCOMING_CALL)
                }
            }
            else {
                // user should be asked for permissions again
                setMessageToken(Token.INCOMING_CALL)
                setMessageText("To use the feature, manually provide\nCall access rights to $applicationName", hideMessageTextAfter35, Token.INCOMING_CALL)
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
                    setMessageText(token = Token.SOUND)
                    isAudioIncoming = false
                    audioRecordHandler.stop()
                    audioRecordHandler.release()
                    setBtnImage(incomingSoundBtn, R.drawable.sound_off_m3)
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
                    resetAllActivities(Token.SOUND)
                    isAudioIncoming = true
                    setBtnImage(incomingSoundBtn, R.drawable.sound_on_m3)
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
                                    setMessageText(token = Token.SOUND)
                                }
                                else {
                                    Log.i("MainActivity","LOOP ABOVE THRESHOLD - TURN ON Flashlight")
                                    turnOnFlashlight()
                                    setMessageToken(Token.SOUND)
                                    setMessageText("Flashlight is turned On", hideMessageTextAfter35, Token.SOUND)
                                }
                            }
                        }
                    }
                    recordingThread?.start()
                    setMessageToken(Token.SOUND)
                    setMessageText("Flashlight will turn On & Off\non short sounds", token = Token.SOUND)
                }
            }
            else {
                // user should be asked for permissions again
                setMessageToken(Token.SOUND)
                setMessageText("To use the feature, manually provide Audio access rights to $applicationName", hideMessageTextAfter35, Token.SOUND)
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
                    resetAllActivities(Token.TILT)
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
                                } else if (angleInDegrees < -sensitivityAngle.toFloat() && rotationAngle > -5f) {
                                    // Phone returned to portrait orientation
                                    rotationAngle = initRotationAngle
                                    if (isFlashLightOn) {
                                        Log.i("MainActivity","TILT REACHED ANGLE - TURN OFF Flashlight")
                                        turnOffFlashlight()
                                        setMessageText(token = Token.TILT)
                                    }
                                    else {
                                        Log.i("MainActivity","TILT REACHED ANGLE - TURN ON Flashlight")
                                        turnOnFlashlight()
                                        setMessageToken(Token.TILT)
                                        setMessageText("Flashlight is turned On", hideMessageTextAfter35, Token.TILT)
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
                    setBtnImage(incomingTiltBtn, R.drawable.tilt_on_m3)
                    isPhoneTilt = true
                    setMessageToken(Token.TILT)
                    setMessageText("Flashlight will turn On & /Off\non $sensitivityAngle degrees angle phone tilts", token = Token.TILT)
                }
                else {
                    // we have to disable the btn now since rotation sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    setMessageToken(Token.TILT)
                    setMessageText("Device's rotation sensor\nis not available", hideMessageTextAfter35, Token.TILT)
                    incomingTiltBtn.setImageResource(R.drawable.tilt_no_permission_m3)
                }
            } else {
                Log.i("MainActivity","incomingTiltBtn is OFF ($sensorEventListener)")
                setMessageText(token = Token.TILT)
                turnOffFlashlight()
                sensorManager.unregisterListener(sensorEventListener)
                setBtnImage(incomingTiltBtn, R.drawable.tilt_off_m3)
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
                    setMessageToken(Token.INCOMING_SMS)
                    setMessageText("Flashlight will be flickering\non incoming SMSes", hideMessageTextAfter35, Token.INCOMING_SMS)
                    registerIncomingEvents(TypeOfEvent.SMS)
                    setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_on_m3)
                } else {
                    Log.i("MainActivity", "incomingSMSBtn is OFF")
                    setMessageText(token = Token.INCOMING_SMS)
                    disableIncomingSMSHandler()
                    setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_off_m3)
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for SMS")
                setMessageToken(Token.INCOMING_SMS)
                setMessageText("To use the feature, manually provide\nSMS access rights to $applicationName", hideMessageTextAfter35, Token.INCOMING_SMS)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // phone out/in network handler
        outInNetworkBtn = findViewById(R.id.networkConnectionBtn)
        outInNetworkBtn.setOnClickListener {

            if (isNetworkConnectivityCbIsSet) {
                // User wants to disable the feature
                Log.i("MainActivity","outInNetworkBtn is OFF")
                setMessageText(token = Token.NETWORK)
                isNetworkConnectivityCbIsSet = false
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                Log.i("MainActivity", "Unregister running CB $connectivityCallback")
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                setBtnImage(outInNetworkBtn, R.drawable.network_off_m3)
            }
            else {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                // Check if network is currently available first
                val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                isNetworkConnectivityCbIsSet = true
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
                setMessageToken(Token.NETWORK)
                setMessageText("Flickering on Network connection changes is On", hideMessageTextAfter35, Token.NETWORK)
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
                resetAllActivities(Token.BATTERY)
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
                                    setMessageToken(Token.BATTERY)
                                    setMessageText("Battery has been charged up to ${batteryPercentage}%", hideMessageTextAfter35, Token.BATTERY)
                                    Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                    stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong())
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
                                    setMessageToken(Token.BATTERY)
                                    setMessageText("Battery is discharged ${batteryPercentage}%", hideMessageTextAfter35, Token.BATTERY)
                                    Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                    stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong())
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
                loopHandler.postDelayed({ checkStatus(Token.BATTERY) }, checkInterval50)
            }
            else {
                Log.i("MainActivity","batteryBtn is OFF")
                setMessageText(token = Token.BATTERY)
                try {
                    unregisterReceiver(batteryReceiver)
                }
                catch (e : Exception) {
                    // We are OK, receiver is already unregistered
                }
                isBatteryOn = false
                batteryThreshold = minBattery
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
                resetAllActivities(Token.TIMER)
                isTimerOn = true
                setSeekBar(SeekBarMode.HOURS)
                setTimerBtn(ACTION.SET)
                loopHandler.postDelayed({ checkStatus(Token.TIMER) }, checkInterval50)
                setMessageToken(Token.TIMER)
            }
            else {
                Log.i("MainActivity","timerBtn is OFF")
                setMessageText(token = Token.TIMER)
                isTimerOn = false
                isTimerThresholdSet = false
                timerSetAfter = minTimerMinutes
                resetSeekBar()
                setTimerBtn(ACTION.RESET)
                setTimerThresholdDisplayText(ACTION.RESET)
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                timerLoopHandler.removeCallbacksAndMessages(null)
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
                                                    Log.d("MainActivity", "Flickering ON while ascending \nto altitude of ${flickerFlashlightHz}m")
                                                    startFlickering()
                                                    setMessageToken(Token.ALTITUDE)
                                                    setMessageText("Altitude reached: ${altitude.toInt()}m high", hideMessageTextAfter35, Token.ALTITUDE)
                                                    stopFlickeringAfterTimeout(
                                                        maxFlickerDurationAltitude.toLong()
                                                    )
                                                    sensorManager.unregisterListener(sensorEventListener)
                                                    setAltitudeBtn(ACTION.SUCCESS)
                                                    setAltitudeLevelDisplayText(ACTION.SET)
                                                }
                                            }
                                        }
                                        else {
                                            // User is descending in height
                                            if (altitude < altitudeThreshold) {
                                                if (!isFlickering) {
                                                    turnOffFlashlight(true)
                                                    stopFlickering()
                                                    Log.d("MainActivity", "Flickering ON while descending \nto altitude of ${flickerFlashlightHz}m")
                                                    startFlickering()
                                                    setMessageToken(Token.ALTITUDE)
                                                    setMessageText("Altitude reached: ${altitude.toInt()}m low", hideMessageTextAfter35, Token.ALTITUDE)
                                                    stopFlickeringAfterTimeout(
                                                        maxFlickerDurationAltitude.toLong()
                                                    )
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
                        resetAllActivities(Token.ALTITUDE)
                        isAltitudeOn = true
                        setSeekBar(SeekBarMode.METERS)
                        setAltitudeBtn(ACTION.SET)
                        sensorManager.registerListener(sensorEventListener, altitudeSensor, SensorManager.SENSOR_DELAY_NORMAL)
                        loopHandler.postDelayed({ checkStatus(Token.ALTITUDE) }, checkInterval50)
                    }
                    else {
                        // we have to disable the btn now since sensor is not available on the device
                        Log.i("MainActivity","Barometer not available")
                        setMessageToken(Token.ALTITUDE)
                        setMessageText("Device's barometer sensor\nis not available", hideMessageTextAfter35, Token.ALTITUDE)
                        setAltitudeBtn(ACTION.NO_PERMISSION)
                        setAltitudeLevelDisplayText(ACTION.NO_PERMISSION)
                    }
                } else {
                    Log.i("MainActivity","altitudeBtn is OFF ($sensorEventListener)")
                    setMessageText(token = Token.ALTITUDE)
                    isAltitudeOn = false
                    altitudeThreshold = minAltitude
                    resetSeekBar()
                    setAltitudeBtn(ACTION.RESET)
                    setAltitudeLevelDisplayText(ACTION.RESET)
                    sensorManager.unregisterListener(sensorEventListener)
                    if (!isFlickeringOnDemand) {
                        stopFlickering()
                    }
                    loopHandler.removeCallbacksAndMessages(null)
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for ALTITUDE")
                setMessageToken(Token.ALTITUDE)
                setMessageText("To use the feature, manually provide\nLOCATION access rights to $applicationName", hideMessageTextAfter35, Token.ALTITUDE)
            }
        }



        ////////////////////////////////////////////////////////////////////////////////////////
        // info button
        infoBtn = findViewById(R.id.infoBtnId)
        infoBtn.setOnClickListener {
            val intent = Intent(this, InfoActivity::class.java)
            startActivity(intent)
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // settings button init
        settingsBtn = findViewById(R.id.settingsBtnId)

        // set intent for Settings activity
        val intentSettings = Intent(this, SettingsActivity::class.java)
        intentSettings.putExtra("maxFlickerHz", maxFlickerHz)
        intentSettings.putExtra("maxTimerMinutes", maxTimerMinutes.inWholeMinutes.toInt())
        intentSettings.putExtra("sensitivityAngle", sensitivityAngle)
        intentSettings.putExtra("sensitivitySoundThreshold", sensitivitySoundThreshold)
        intentSettings.putExtra("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall/1000)
        intentSettings.putExtra("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS/1000)
        intentSettings.putExtra("maxFlickerDurationBattery", maxFlickerDurationBattery/1000)
        intentSettings.putExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude/1000)

        val registerSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Handle the result from the SettingsActivity here
                val data = result.data
                maxFlickerHz = data?.getIntExtra("maxFlickerHz", maxFlickerHz) ?: maxFlickerHz
                sensitivityAngle = data?.getIntExtra("sensitivityAngle", sensitivityAngle) ?: sensitivityAngle
                sensitivitySoundThreshold = data?.getIntExtra("sensitivitySoundThreshold", sensitivitySoundThreshold) ?: sensitivitySoundThreshold
                maxFlickerDurationIncomingSMS = data?.getIntExtra("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS) ?: maxFlickerDurationIncomingSMS
                maxFlickerDurationBattery = data?.getIntExtra("maxFlickerDurationBattery", maxFlickerDurationBattery) ?: maxFlickerDurationBattery
                maxFlickerDurationAltitude = data?.getIntExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude) ?: maxFlickerDurationAltitude
                maxFlickerDurationIncomingCall = data?.getIntExtra("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall) ?: maxFlickerDurationIncomingCall
                val maxTimerMinutesFromSettings = data?.getIntExtra("maxTimerMinutes", maxTimerMinutes.inWholeMinutes.toInt()) ?: maxTimerMinutes.inWholeMinutes.toInt()
                maxTimerMinutes = maxTimerMinutesFromSettings.minutes
                Log.i("MainActivity", "Data from Settings are: $maxFlickerHz,$maxTimerMinutesFromSettings,${maxTimerMinutes.inWholeMinutes.toInt()},$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
            }
        }

        settingsBtn.setOnClickListener{
            registerSettings.launch(intentSettings)
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // rate button
        reviewManager = ReviewManagerFactory.create(this)
        val request = reviewManager.requestReviewFlow()
        rateBtn = findViewById(R.id.rateBtnId)

        request.addOnCompleteListener { requestInfo ->
            if (requestInfo.isSuccessful) {
                reviewInfo = requestInfo.result
                // Use the reviewInfo to launch the review flow
                Log.e("RateActivity", "request addOnCompleteListener: reviewInfo is ready}")

            } else {
                // Handle the error case
                setBtnImage(rateBtn, R.drawable.rate_no_bind)
                Log.e("RateActivity", "request addOnCompleteListener: reviewErrorCode = ${requestInfo.exception.toString()}")
            }
        }

        rateBtn.setOnClickListener{
            try {
                val flow = reviewManager.launchReviewFlow(this, reviewInfo)
                flow.addOnCompleteListener {
                    // The flow has finished. The API does not indicate whether the user
                    // reviewed or not, or even whether the review dialog was shown. Thus, no
                    // matter the result, we continue our app flow.
                    Log.e("RateActivity", "flow addOnCompleteListener: complete")
                }
            }
            catch (e : java.lang.Exception) {
                Log.e("RateActivity", "Probably no service bind with Google Play")
            }
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // donate button
        donateBtn = findViewById(R.id.donateBtnId)
        donateBtn.setOnClickListener {
            val intent = Intent(this, DonateActivity::class.java)
            startActivity(intent)
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // Permissions handling
        checkPermissions(ACTION.CREATE)
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////

    private fun getMessageToken () : Token {
        return tokenMessageText
    }

    private fun setMessageToken (token : Token) {
        tokenMessageText = token
    }

    private fun setMessageText (msg : String = "", hideAfter : Long = 0L, token : Token) {
        Log.i("MainActivity", "setMessageText $msg, $hideAfter, $token")
        if (hideAfter != 0L) {
            messageText.text = msg
            messageLoopHandler.postDelayed({setMessageText(token = token)}, hideAfter)
        }
        else {
            if (token == getMessageToken()) {
                messageText.text = msg
                messageLoopHandler.removeCallbacksAndMessages(null)
            }
        }
    }


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
                        setBtnImage(incomingCallBtn, R.drawable.incoming_call_no_permission_m3)
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
                        setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_no_permission_m3)
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
                        setBtnImage(incomingSoundBtn, R.drawable.sound_no_permission_m3)
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
                                resetAllActivities(Token.INCOMING_CALL)
                                Log.d("MainActivity", "EXTRA_STATE_RINGING - Flickering ON with ${flickerFlashlightHz}Hz")
                                startFlickering()
                                stopFlickeringAfterTimeout(maxFlickerDurationIncomingCall.toLong())
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
                setMessageText("Flashlight will be flickering\non incoming phone Calls", hideMessageTextAfter35, Token.INCOMING_CALL)
            }
            TypeOfEvent.OUT_OF_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "NETWORK is LOST")
                        resetAllActivities(Token.NETWORK)
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
                        resetAllActivities(Token.NETWORK)
                        setNetworkBtn(NetworkState.UNAVAILABLE)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDuration30)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }}
                Log.i("MainActivity", "Register CB for OUT_OF_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                setMessageText("Flashlight will be flickering if\nWiFi or Network signal gets lost", hideMessageTextAfter35, Token.NETWORK)
            }
            TypeOfEvent.IN_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i("MainActivity", "NETWORK is AVAILABLE")
                        resetAllActivities(Token.NETWORK)
                        setNetworkBtn(NetworkState.AVAILABLE)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering()
                        stopFlickeringAfterTimeout(maxFlickerDuration30)
                        isPhoneOutOfNetwork = false
                        isPhoneInNetwork = true
                    }}
                Log.i("MainActivity", "Register CB for IN_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                setMessageText("Flashlight will be flickering if\nWiFi or Network signal are found", hideMessageTextAfter35, Token.NETWORK)
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
                            resetAllActivities(Token.INCOMING_SMS)
                            Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                            startFlickering()
                            stopFlickeringAfterTimeout(maxFlickerDurationIncomingSMS.toLong())
                        }
                    }
                }
                val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                registerReceiver(incomingSMSReceiver, intentFilter)
                setMessageText("Flashlight will be flickering\non incoming SMSes", hideMessageTextAfter35, Token.INCOMING_SMS)
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////

    private fun checkStatus (token : Token) {
        when (token) {
            Token.TIMER -> {
                if (isTimerOn && !isTimerThresholdSet && !isStartTrackingTouched) {
                    Log.i("MainActivity", "TURN OFF isTimerOn after inactivity")
                    // we have to reset timer key due to user inactivity
                    isTimerOn = false
                    timerSetAfter = minTimerMinutes
                    resetSeekBar()
                    setTimerBtn(ACTION.RESET)
                    setTimerThresholdDisplayText(ACTION.RESET)
                    setMessageToken(Token.TIMER)
                    setMessageText(token = Token.TIMER)
                }
            }
            Token.BATTERY -> {
                if (isBatteryOn && !isBatteryThresholdSet && !isStartTrackingTouched) {
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
                    setMessageToken(Token.BATTERY)
                    setMessageText(token = Token.BATTERY)
                    batteryThreshold = minBattery
                    initBatteryLevel = minBattery
                    setPowerLevelDisplayText(ACTION.RESET)
                    loopHandler.removeCallbacksAndMessages(null)
                }
            }
            Token.ALTITUDE -> {
                if (isAltitudeOn && !isAltitudeThresholdSet && !isStartTrackingTouched) {
                    // we have to reset timer key due to user inactivity
                    Log.i("MainActivity", "TURN OFF isAltitudeOn after inactivity")
                    try {
                        sensorManager.unregisterListener(sensorEventListener)
                        setAltitudeBtn(ACTION.RESET)
                        isAltitudeOn = false
                        resetSeekBar()
                        setMessageToken(Token.ALTITUDE)
                        setMessageText(token = Token.ALTITUDE)
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
                text = "Power -"
                textView.text = text
            }
            ACTION.NO_PERMISSION -> {
                textView.setTextColor(Color.parseColor("#383838"))
                text = "Power -"
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

    private fun setMessageDisplayTextEnhanced (displayValue: String, mode: SeekBarMode, action : ACTION) {
//        Log.d("MainActivity", "setMessageDisplayTextEnhanced: $displayValue, $mode, $action")
        val displayText : String
        when (mode) {
            SeekBarMode.HOURS -> {
                setMessageToken(Token.TIMER)
                when (action) {
                    ACTION.INIT -> {
                        displayText = "Phone will be flickering after:"
                        setMessageText(displayText, token = Token.TIMER)
                    }
                    ACTION.PROGRESS -> {
                        displayText = "Phone will be flickering after: $displayValue"
                        setMessageText(displayText, hideMessageTextAfter35, Token.TIMER)
                    }
                    ACTION.STOP -> {
                        displayText = "Phone will be flickering after: $displayValue"
                        setMessageText(displayText, hideMessageTextAfter35, Token.TIMER)
                    }
                    else -> {
                        displayText = "Phone will be flickering after: $displayValue"
                        setMessageText(displayText, hideMessageTextAfter35, Token.TIMER)
                    }
                }
            }
            SeekBarMode.HZ -> {
                setMessageToken(Token.FLICKER)
                displayText = when (action) {
                    ACTION.INIT -> {
                        "Flashlight is flickering\nwith frequency ${displayValue.toInt()}Hz"
                    }

                    ACTION.PROGRESS -> {
                        "Flashlight is flickering\nwith frequency ${displayValue.toInt()}Hz"
                    }

                    ACTION.STOP -> {
                        "Flashlight is flickering\nwith frequency ${displayValue.toInt()}Hz"
                    }

                    else -> {
                        "Flashlight is flickering\nwith frequency ${displayValue.toInt()}Hz"
                    }
                }
                setMessageText(displayText, token = Token.FLICKER)
            }
            SeekBarMode.METERS -> {
                setMessageToken(Token.ALTITUDE)
                when (action) {
                    ACTION.INIT -> {
                        Log.i("INIT", "INIT ALTITUDE")
                        displayText = "Current Altitude level is ${initAltitudeLevel}m\nGet notified at the Altitude:"
                        setMessageText(displayText, token = Token.ALTITUDE)
                    }
                    ACTION.PROGRESS -> {
                        Log.i("PROGRESS", "PROGRESS ALTITUDE")
                        displayText = "Current Altitude level is ${initAltitudeLevel}m\nGet notified at the Altitude: ${displayValue.toInt()}m"
                        setMessageText(displayText, hideMessageTextAfter35, Token.ALTITUDE)
                    }
                    ACTION.STOP -> {
                        displayText = "Current Altitude level is ${initAltitudeLevel}m\nGet notified at the Altitude: ${displayValue.toInt()}m"
                        setMessageText(displayText, hideMessageTextAfter35, Token.ALTITUDE)
                    }
                    else -> {
                        displayText = "Current Altitude level is ${initAltitudeLevel}m\nGet notified at the Altitude: ${displayValue.toInt()}m"
                        setMessageText(displayText, hideMessageTextAfter35, Token.ALTITUDE)
                    }
                }
            }
            SeekBarMode.PERCENTAGE -> {
                setMessageToken(Token.BATTERY)
                when (action) {
                    ACTION.INIT -> {
                        displayText = "Current Battery power level is $initBatteryLevel%\nGet notified at Battery power:"
                        setMessageText(displayText, token = Token.BATTERY)
                    }
                    ACTION.PROGRESS -> {
                        displayText = "Current Battery power level is $initBatteryLevel%\nGet notified at Battery power: ${displayValue.toInt()}%"
                        setMessageText(displayText, hideMessageTextAfter35, Token.BATTERY)
                    }
                    ACTION.STOP -> {
                        displayText = "Current Battery power level is $initBatteryLevel%\nGet notified at Battery power: ${displayValue.toInt()}%"
                        setMessageText(displayText, hideMessageTextAfter35, Token.BATTERY)
                    }
                    else -> {
                        displayText = "Current Battery power level is $initBatteryLevel%\nGet notified at Battery power: ${displayValue.toInt()}%"
                        setMessageText(displayText, hideMessageTextAfter35, Token.BATTERY)
                    }
                }
                messageText.text = displayText
            }
        }
    }

    private fun setSeekBar(mode : SeekBarMode) {
        flickeringBar.visibility = View.VISIBLE
        thumbInitialPosition = flickeringBar.thumb.bounds.right
        hzInitialPosition = messageText.x.toInt()
        when (mode) {
            SeekBarMode.HOURS -> {
                flickeringBar.min = minTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.max = maxTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.progress = minTimerMinutes.inWholeMinutes.toInt()
                setMessageDisplayTextEnhanced(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.INIT)
            }
            SeekBarMode.HZ -> {
                flickeringBar.min = minFlickerHz
                flickeringBar.max = maxFlickerHz
                flickeringBar.progress = minFlickerHz
                setMessageDisplayTextEnhanced(flickerFlashlightHz.toString(), SeekBarMode.HZ, ACTION.INIT)
            }
            SeekBarMode.METERS -> {
                flickeringBar.min = minAltitude
                flickeringBar.max = maxAltitude
                if (initAltitudeLevel != minAltitude) {
                    flickeringBar.progress = initAltitudeLevel
                }
                setMessageDisplayTextEnhanced(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.INIT)
            }
            SeekBarMode.PERCENTAGE -> {
                flickeringBar.min = minBattery
                flickeringBar.max = maxBattery
                if (initBatteryLevel != minBattery) {
                    flickeringBar.progress = initBatteryLevel
                }
                setMessageDisplayTextEnhanced(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.INIT)
            }
        }
    }

    private fun resetSeekBar (hideBarOnly : Boolean = false) {
        flickeringBar.visibility = View.INVISIBLE
        if (!hideBarOnly) {
            flickeringBar.progress = flickeringBar.min
        }
        flickeringBar.min = when (token) {
            Token.FLICKER -> {minFlickerHz}
            Token.TIMER -> {minTimerMinutes.inWholeMinutes.toInt()}
            Token.BATTERY -> {minBattery}
            Token.ALTITUDE -> {minAltitude}
            else -> {minFlickerHz}
        }
    }

    private fun isAboveThreshold(buffer: ShortArray, bytesRead: Int): Boolean {
        for (i in 0 until bytesRead) {
            if (buffer[i] > sensitivitySoundThreshold || buffer[i] < -sensitivitySoundThreshold) {
                return true
            }
        }
        return false
    }

    private fun setTimerBtn (action : ACTION) {
        when (action) {
            ACTION.SET -> {
                timerBtn.setImageResource(R.drawable.timer_on_m3)
            }
            ACTION.RESET -> {
                timerBtn.setImageResource(R.drawable.timer_off_m3)
            }
            ACTION.SUCCESS -> {
                timerBtn.setImageResource(R.drawable.timer_success)
            }
            else -> {
                timerBtn.setImageResource(R.drawable.timer_off_m3)
            }
        }
    }


    private fun setBatteryBtn (action : ACTION) {
        when (action) {
            ACTION.SET -> {
                batteryBtn.setImageResource(R.drawable.battery_on_m3)
            }
            ACTION.RESET -> {
                batteryBtn.setImageResource(R.drawable.battery_off_m3)
            }
            ACTION.SUCCESS -> {
                batteryBtn.setImageResource(R.drawable.battery_success)
            }
            else -> {
                batteryBtn.setImageResource(R.drawable.battery_off_m3)
            }
        }
    }

    private fun setAltitudeBtn (action : ACTION) {
        when (action) {
            ACTION.SET -> {
                altitudeBtn.setImageResource(R.drawable.altitude_on_m3)
            }
            ACTION.RESET -> {
                altitudeBtn.setImageResource(R.drawable.altitude_off_m3)
            }
            ACTION.SUCCESS -> {
                altitudeBtn.setImageResource(R.drawable.altitude_success)
            }
            ACTION.NO_PERMISSION -> {
                altitudeBtn.setImageResource(R.drawable.altitude_no_permission_m3)
            }
            else -> {
                altitudeBtn.setImageResource(R.drawable.altitude_off_m3)
            }
        }
    }

    private fun setNetworkBtn (networkState : NetworkState = NetworkState.ASIS) {
        if (isPhoneInNetwork) {
            when (networkState) {
                NetworkState.LOST -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_lost_m3) // wifi_lost_r1
                }
                NetworkState.UNAVAILABLE -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_lost_m3) // wifi_lost_r1
                }
                NetworkState.ASIS -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_on_to_lost_m3) //wifi_off_enabled_r1
                }
                else -> {}
            }
        }
        else if (isPhoneOutOfNetwork) {
            when (networkState) {
                NetworkState.AVAILABLE -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_success) //wifi_on_found_r1
                }
                NetworkState.ASIS -> {
                    outInNetworkBtn.setImageResource(R.drawable.network_on_m3) //wifi_on_enabled_r1
                }
                else -> {}
            }
        }
    }

    private fun disableIncomingSMSHandler () {
        isIncomingSMS = false
        if (!isFlickeringOnDemand) {
            stopFlickering()
        }
        unregisterReceiver(incomingSMSReceiver)
    }

    private fun disableIncomingCallFlickering () {
        isIncomingCall = false
        if (!isFlickeringOnDemand) {
            stopFlickering()
        }
        unregisterReceiver(incomingCallReceiver)
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
                    setBtnImage(flashlightBtn, R.drawable.flashlight_on2)
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
                    setBtnImage(flashlightBtn, R.drawable.flashlight_off_m3)
                }
                Log.d("MainActivity", "FlashLight OFF")
            } catch (e: CameraAccessException) {
                Log.d("MainActivity", "FlashLight OFF - ERROR: $e")
            }
        }
    }

    private fun setBtnImage (btn : ImageButton, icon : Int) {
        btn.setImageResource(icon)
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
                setBtnImage(sosBtn, R.drawable.sos_on_m3)
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
                setBtnImage(sosBtn, R.drawable.sos_off_m3)
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
                    setBtnImage(incomingCallBtn, R.drawable.incoming_call_off_m3)
                }
                RequestKey.SMS.value -> {
                    permissionsKeys["SMS"] = true
                    setBtnImage(sosBtn, R.drawable.sos_off_m3)
                }
                RequestKey.AUDIO.value -> {
                    permissionsKeys["AUDIO"] = true
                    setBtnImage(incomingSoundBtn, R.drawable.sound_off_m3)
                }
                RequestKey.ALTITUDE.value -> {
                    permissionsKeys["ALTITUDE"] = true
                    setBtnImage(altitudeBtn, R.drawable.altitude_off_m3)
                }
            }
        }
        else {
            when (requestCode) {
                RequestKey.CALL.value -> {
                    Log.i("MainActivity", "Request NOT granted for CALL")
                    setBtnImage(incomingCallBtn, R.drawable.incoming_call_no_permission_m3)
                }
                RequestKey.SMS.value -> {
                    Log.i("MainActivity", "Request NOT granted for SMS")
                    setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_no_permission_m3)
                }
                RequestKey.AUDIO.value -> {
                    Log.i("MainActivity", "Request NOT granted for AUDIO")
                    setBtnImage(incomingSoundBtn, R.drawable.sound_no_permission_m3)
                }
                RequestKey.ALTITUDE.value -> {
                    Log.i("MainActivity", "Request NOT granted for LOCATION")
                    setBtnImage(altitudeBtn, R.drawable.altitude_no_permission_m3)
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

        if (isIncomingSMS) {
            unregisterReceiver(incomingSMSReceiver)
        }

        if (isPhoneTilt) {
            sensorManager.unregisterListener(sensorEventListener)
        }

        if (isNetworkConnectivityCbIsSet) {
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
        }

        if (isAudioIncoming) {
            audioRecordHandler.stop()
            audioRecordHandler.release()
            try {
                recordingThread?.interrupt()
            }
            catch (e : SecurityException) {
                Log.e("MainActivity", "onDestroy recordingThread exception $e")
            }
            recordingThread?.join()
            recordingThread = null
        }

        if (isAltitudeOn) {
            try {
                sensorManager.unregisterListener(sensorEventListener)
            }
            catch (e : SecurityException) {
                Log.e("MainActivity", "onDestroy sensorManager exception $e")
            }
        }

        if (isBatteryOn) {
            try {
                unregisterReceiver(batteryReceiver)
            }
            catch (e : SecurityException) {
                Log.e("MainActivity", "onDestroy batteryReceiver exception $e")
            }
        }

        if (isTimerOn) {
            try {
                timerLoopHandler.removeCallbacksAndMessages(null)
            }
            catch (e: java.lang.Exception) {
                // DO nothing here
            }
        }
    }

    private fun resetAllActivities (featureToken : Token) {
        Log.i("MainActivity", " --------- Reset all activities --------- ")
        var tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.SOS, Token.SOUND, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlashLightOn) {
            Log.i("MainActivity", "RAA - TURN OFF Flashlight")
            turnOffFlashlight(true)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLASHLIGHT, Token.SOS, Token.SOUND, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlickering && isFlickeringOnDemand) {
            Log.i("MainActivity", "RAA - STOP FLICKERING on demand")
            isFlickeringOnDemand = false
            stopFlickering()
            setBtnImage(flickerFlashlightBtn, R.drawable.flickering_off_m3)
            resetSeekBar()
            setFlickeringHz(minFlickerHz.toLong())
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isSendSOS) {
            Log.i("MainActivity", "RAA - DISABLE SOS")
            stopSOS(true)
        }

//        if ((featureToken in tokenValuesToCheckAgainst) && isIncomingCall) {
//            Log.i("MainActivity", "RAA - TURN OFF isIncomingCall")
//            disableIncomingCallFlickering()
//            setBtnImage(incomingCallBtn, R.drawable.incoming_call_off_m3)
//        }

//        if ((featureToken in tokenValuesToCheckAgainst) && isIncomingSMS) {
//            Log.i("MainActivity", "RAA - TURN OFF isIncomingSMS")
//            disableIncomingSMSHandler()
//            setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_off_m3)
//        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.SOS)
        if ((featureToken in tokenValuesToCheckAgainst) && isPhoneTilt) {
            Log.i("MainActivity", "RAA - TURN OFF isPhoneTilt")
            turnOffFlashlight()
            sensorManager.unregisterListener(sensorEventListener)
            setBtnImage(incomingTiltBtn, R.drawable.tilt_off_m3)
            isPhoneTilt = false
            loopHandler.removeCallbacksAndMessages(null)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.SOS)
        if ((featureToken in tokenValuesToCheckAgainst) && isAudioIncoming) {
            Log.i("MainActivity", "RAA - TURN OFF isAudioIncoming")
            isAudioIncoming = false
            turnOffFlashlight()
            setBtnImage(incomingSoundBtn, R.drawable.sound_off_m3)
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

//        if ((token in tokenValuesToCheckAgainst) && isNetworkConnectivityCbIsSet) {
//            Log.i("MainActivity", "RAA - TURN OFF isNetworkConnectivityCbIsSet")
//            isNetworkConnectivityCbIsSet = false
//            stopFlickering()
//            connectivityManager.unregisterNetworkCallback(connectivityCallback)
//            setBtnImage(outInNetworkBtn, R.drawable.network_off_m3)
//        }

        if (isBatteryOn) {
            if (featureToken != Token.BATTERY) {
                if (!isBatteryThresholdSet) {
                    Log.i("MainActivity", "RAA - TURN OFF isBatteryOn")
                    isBatteryOn = false
                    batteryThreshold = minBattery
                    initBatteryLevel = minBattery
                    turnOffFlashlight()
                    resetSeekBar()
                    setBatteryBtn(ACTION.RESET)
                    setPowerLevelDisplayText(ACTION.RESET)
                    try {
                        unregisterReceiver(batteryReceiver)
                        timerLoopHandler.removeCallbacksAndMessages(null)
                    }
                    catch (e : Exception) {
                        // We are OK, receiver is already unregistered
                    }
                }
                else {
                    Log.i("MainActivity", "RAA - TURN OFF callbacks (BATTERY)")
                    try {
                        loopHandler.removeCallbacksAndMessages(null)
                        messageLoopHandler.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // Do nothing
                    }
                }
            }
        }

        if (isAltitudeOn) {
            if (featureToken != Token.ALTITUDE) {
                if (!isAltitudeThresholdSet) {
                    Log.i("MainActivity", "RAA - TURN OFF isAltitudeOn")
                    isAltitudeOn = false
                    altitudeThreshold = minAltitude
                    turnOffFlashlight()
                    resetSeekBar()
                    sensorManager.unregisterListener(sensorEventListener)
                    setAltitudeBtn(ACTION.RESET)
                    setAltitudeLevelDisplayText(ACTION.RESET)
                    try {
                        timerLoopHandler.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // DO nothing here
                    }
                }
                else {
                    Log.i("MainActivity", "RAA - TURN OFF callbacks (ALTITUDE)")
                    try {
                        loopHandler.removeCallbacksAndMessages(null)
                        messageLoopHandler.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // Do nothing
                    }
                }
            }
        }

        if (isTimerOn) {
            if (featureToken != Token.TIMER) {
                if (!isTimerThresholdSet) {
                    Log.i("MainActivity", "RAA - TURN OFF isTimerOn")
                    isTimerOn = false
                    isTimerThresholdSet = false
                    timerSetAfter = minTimerMinutes
                    resetSeekBar()
                    setTimerBtn(ACTION.RESET)
                    setTimerThresholdDisplayText(ACTION.RESET)
                    try {
                        timerLoopHandler.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // DO nothing here
                    }
                }
                else {
                    Log.i("MainActivity", "RAA - TURN OFF callbacks (ALTITUDE)")
                    try {
                        loopHandler.removeCallbacksAndMessages(null)
                        messageLoopHandler.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // Do nothing
                    }
                }
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

class InfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_info)
        val closeButton = findViewById<ImageButton>(R.id.infoGoBackArrow)
        closeButton.setOnClickListener {
            finish()
        }
    }
}

class SettingsActivity : AppCompatActivity() {

    private var maxFlickerHz : Int = 0
    private var maxTimerMinutes : Int = 0
    private var sensitivityAngle : Int = 0
    private var sensitivitySoundThreshold : Int = 0
    private var maxFlickerDurationIncomingCall : Int = 0
    private var maxFlickerDurationIncomingSMS : Int = 0
    private var maxFlickerDurationBattery : Int = 0
    private var maxFlickerDurationAltitude : Int = 0
    private lateinit var maxFlickerHzEditText : EditText
    private lateinit var maxTimerTimeEditText : EditText
    private lateinit var tiltAngleEditText : EditText
    private lateinit var soundThresholdEditText : EditText
    private lateinit var flickTimeIncCallEditText : EditText
    private lateinit var flickTimeIncSMSEditText : EditText
    private lateinit var flickTimeBatteryEditText : EditText
    private lateinit var flickTimeAltitudeEditText : EditText
    private val _hzLow = 10
    private val _hzHigh = 100
    private val _flickTimeLow = 3
    private val _flickTimeHigh = 180
    private val _timerLow = 240
    private val _timerHigh = 640
    private val _angleLow = 45
    private val _angleHigh = 80
    private val _soundLow = 4000
    private val _soundHigh = 20000

    enum class CheckResult {
        SET,
        EMPTY,
        FAULT
    }


    @SuppressLint("WrongViewCast")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)

        // Init editTexts
        maxFlickerHzEditText = findViewById(R.id.maxFlickerHzId)
        maxTimerTimeEditText = findViewById(R.id.maxTimerTimeId)
        tiltAngleEditText = findViewById(R.id.tiltAngleId)
        soundThresholdEditText = findViewById(R.id.soundThresholdId)
        flickTimeIncCallEditText = findViewById(R.id.flickTimeIncCallId)
        flickTimeIncSMSEditText = findViewById(R.id.flickTimeIncSMSId)
        flickTimeBatteryEditText = findViewById(R.id.flickTimeBatteryId)
        flickTimeAltitudeEditText = findViewById(R.id.flickTimeAltitudeId)

        // Retrieve the data value from the intent of the MainActivity
        maxFlickerHz = intent.getIntExtra("maxFlickerHz", 0)
        maxTimerMinutes = intent.getIntExtra("maxTimerMinutes", 0)
        sensitivityAngle = intent.getIntExtra("sensitivityAngle", 0)
        sensitivitySoundThreshold = intent.getIntExtra("sensitivitySoundThreshold", 0)
        maxFlickerDurationIncomingCall = intent.getIntExtra("maxFlickerDurationIncomingCall", 0)
        maxFlickerDurationIncomingSMS = intent.getIntExtra("maxFlickerDurationIncomingSMS", 0)
        maxFlickerDurationBattery = intent.getIntExtra("maxFlickerDurationBattery", 0)
        maxFlickerDurationAltitude = intent.getIntExtra("maxFlickerDurationAltitude", 0)

        // Set data of the intent in local variables
        setHintValues()

        // apply button
        val settingsApplyBtn = findViewById<Button>(R.id.settingsApplyBtn)
        settingsApplyBtn.setOnClickListener {
            // Return the updated maxFlickerHz value back to MainActivity
            Log.i("SettingsActivity", "Input data are: $maxFlickerHz, $maxTimerMinutes,$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
            val resultIntent = Intent()
            if (getValues(resultIntent)) {
                Log.i("SettingsActivity", "Wrong values inserted by user")
            }
            else {
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }

        // reset button
        val resetButton = findViewById<MaterialButton>(R.id.resetBtnId)
        resetButton.setOnClickListener {
            resetTextValues()
        }

        // go-back arrow button
        val closeButton = findViewById<ImageButton>(R.id.settingsGoBackArrow)
        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun setHintValues() {
        maxFlickerHzEditText.hint = maxFlickerHz.toString()
        maxFlickerHzEditText.hint = maxFlickerHz.toString()
        maxTimerTimeEditText.hint = maxTimerMinutes.toString()
        tiltAngleEditText.hint = sensitivityAngle.toString()
        soundThresholdEditText.hint = sensitivitySoundThreshold.toString()
        flickTimeIncCallEditText.hint = maxFlickerDurationIncomingCall.toString()
        flickTimeIncSMSEditText.hint = maxFlickerDurationIncomingSMS.toString()
        flickTimeBatteryEditText.hint = maxFlickerDurationBattery.toString()
        flickTimeAltitudeEditText.hint = maxFlickerDurationAltitude.toString()
    }

    private fun resetTextValues() {
        maxFlickerHzEditText.text = null
        maxFlickerHzEditText.text = null
        maxTimerTimeEditText.text = null
        tiltAngleEditText.text = null
        soundThresholdEditText.text = null
        flickTimeIncCallEditText.text = null
        flickTimeIncSMSEditText.text = null
        flickTimeBatteryEditText.text = null
        flickTimeAltitudeEditText.text = null
    }

    private fun getValues(resultIntent : Intent) : Boolean {

        var wrongValueInsertedByUser = false

        when (checkTextValue(maxFlickerHzEditText, _hzLow, _hzHigh)) {
            CheckResult.SET -> {
                maxFlickerHz = maxFlickerHzEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerHz", maxFlickerHz)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(maxTimerTimeEditText, _timerLow, _timerHigh)) {
            CheckResult.SET -> {
                maxTimerMinutes = maxTimerTimeEditText.text.toString().toInt()
                resultIntent.putExtra("maxTimerMinutes", maxTimerMinutes)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(tiltAngleEditText, _angleLow, _angleHigh)) {
            CheckResult.SET -> {
                sensitivityAngle = tiltAngleEditText.text.toString().toInt()
                resultIntent.putExtra("sensitivityAngle", sensitivityAngle)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(soundThresholdEditText, _soundLow, _soundHigh)) {
            CheckResult.SET -> {
                sensitivitySoundThreshold = soundThresholdEditText.text.toString().toInt()
                resultIntent.putExtra("sensitivitySoundThreshold", sensitivitySoundThreshold)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(flickTimeIncCallEditText, _flickTimeLow, _flickTimeHigh)) {
            CheckResult.SET -> {
                maxFlickerDurationIncomingCall = flickTimeIncCallEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(flickTimeIncSMSEditText, _flickTimeLow, _flickTimeHigh)) {
            CheckResult.SET -> {
                maxFlickerDurationIncomingSMS = flickTimeIncSMSEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(flickTimeAltitudeEditText, _flickTimeLow, _flickTimeHigh)) {
            CheckResult.SET -> {
                maxFlickerDurationAltitude = flickTimeAltitudeEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(flickTimeBatteryEditText, _flickTimeLow, _flickTimeHigh)) {
            CheckResult.SET -> {
                maxFlickerDurationBattery = flickTimeBatteryEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerDurationBattery", maxFlickerDurationBattery)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        return wrongValueInsertedByUser
    }

    private fun checkTextValue (eText : EditText, boundLow : Int, boundHigh : Int) : CheckResult {
        try {
            if (eText.text.toString().isNotEmpty()) {
                val value = eText.text.toString().toInt()
                return if (value in boundLow..boundHigh) {
                    eText.setTextColor(Color.BLACK)
                    CheckResult.SET
                } else {
                    Log.e("SettingsActivity", "checkTextValue(): value $value out of bounds [$boundLow - $boundHigh] for ${resources.getResourceName(eText.id)}")
                    eText.setTextColor(Color.RED)
                    CheckResult.FAULT
                }
            }
        }
        catch (e : java.lang.Exception) {
            Log.e("SettingsActivity", "checkTextValue(): error $e")
            return CheckResult.FAULT
        }
        return CheckResult.EMPTY
    }
}


class DonateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.donate)
        val closeButton = findViewById<ImageButton>(R.id.donateGoBackArrow)
        closeButton.setOnClickListener {
            finish()
        }
    }
}