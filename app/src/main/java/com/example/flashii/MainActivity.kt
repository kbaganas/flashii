package com.example.flashii

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import android.text.Editable
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.flashii.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlin.time.Duration.Companion.minutes

class MainActivity : AppCompatActivity() {

    // elements
    private lateinit var rootView : View
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var flickeringBar : SeekBar
    private val applicationName : String = "Flashii"

    // constants defaults
    private val defaultMaxFlickerHz : Int = 10
    private val defaultMaxTimer = 240.minutes
    private val defaultMaxTiltAngle : Int = 80
    private val defaultSoundSenseLevel : Int = 20000
    private val defaultMaxFlickerIncomingCall : Int = 15000
    private val defaultMaxFlickerIncomingSMS : Int = 15000
    private val defaultMaxFlickerIncomingBattery : Int = 15000
    private val defaultMaxFlickerIncomingAltitude : Int = 15000

    // HZ
    private var minFlickerHz : Int = 1
    private var maxFlickerHz : Int = defaultMaxFlickerHz
    private var flickerFlashlightHz : Long = 1

    // Battery
    private val minBattery : Int = 1
    private val maxBattery : Int = 100
    private var batteryThreshold : Int = minBattery
    private var initBatteryLevel : Int = minBattery

    // Altitude
    private val minAltitude : Int = 1
    private val maxAltitude : Int = 7000
    private var altitudeThreshold : Int = minAltitude
    private var initAltitudeLevel : Int = minAltitude

    // Timer
    private val minTimerMinutes = 1.minutes
    private var maxTimerMinutes = defaultMaxTimer
    private var timerSetAfter = 0.minutes

    // SOS
    private val ditDuration : Long = 250
    private val spaceDuration : Long = ditDuration
    private val dahDuration : Long = 3 * ditDuration
    private val spaceCharsDuration : Long = 3 * spaceDuration
    private val spaceWordsDuration : Long = 4 * spaceDuration // results to 7*ditDuration, considering that we add spaceCharsDuration after each letter

    // Flickering
    private val maxFlickerDuration15 : Long = 15000 // 15 seconds
    private val maxFlickerDuration30 : Long = 30000 // 30 seconds
    private var maxFlickerDurationIncomingCall : Int = defaultMaxFlickerIncomingCall
    private var maxFlickerDurationIncomingSMS : Int = defaultMaxFlickerIncomingSMS
    private var maxFlickerDurationBattery : Int = defaultMaxFlickerIncomingBattery
    private var maxFlickerDurationAltitude : Int = defaultMaxFlickerIncomingAltitude

    // Tilt
    private val initRotationAngle : Float = -1000f
    private var touchStartTime : Long = 0
    private var thumbInitialPosition = 0
    private var sensitivityAngle = defaultMaxTiltAngle
    private var sensitivitySoundThreshold = defaultSoundSenseLevel

    private val hideSeekBarAfterDelay35 : Long = 3500 // 3.5 seconds
    private val checkInterval50 : Long = 5000 // checkForInactivity after interval
    private lateinit var token : Token // token regarding which key is pressed
    private lateinit var reviewInfo : ReviewInfo
    private lateinit var sharedPref : SharedPreferences // shared with Settings view
    private lateinit var intentSettings : Intent

    // TextViews
    private lateinit var seekBarTitle : TextView
    private lateinit var tiltBtnSet : TextView
    private lateinit var soundBtnSetId : TextView
    private lateinit var flickerBtnSetId : TextView
    private lateinit var networkBtnSetId : TextView
    private lateinit var timerBtnTimeSetId : TextView
    private lateinit var altitudeBtnSetId : TextView
    private lateinit var batteryBtnSetId : TextView

    // ImageViews
    private lateinit var timerBtnSuccessId : ImageView
    private lateinit var altitudeBtnSuccessId : ImageView
    private lateinit var batteryBtnSuccessId : ImageView

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

    // Handlers
    private var loopHandlerSeekBar : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerForInactivity : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerTimer : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerFlickering : Handler = Handler(Looper.getMainLooper())

    // Handlers, Threads, Managers, Receivers, Detectors
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
    private lateinit var supportBtn : ImageButton

    @SuppressLint("SetTextI18n", "MissingPermission", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootView = findViewById(android.R.id.content)

        // Initialization of basic Settings
        if (isStoredSettings()) {
            retrieveStoredSettings()
            Log.i("MainActivity", "RETRIEVED STORED Settings are: $maxFlickerHz,${maxTimerMinutes.inWholeMinutes.toInt()},$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
        }

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
                    touchStartTime = System.currentTimeMillis()
                    if (isFlashLightOn) {
                        Log.i("MainActivity","flashlightBtn is OFF")
                        turnOffFlashlight(true)
                    } else {
                        Log.i("MainActivity","flashlightBtn is ON")
                        resetAllActivities(Token.FLASHLIGHT)
                        turnOnFlashlight(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If touch duration > 350ms, then its a press-and-hold action and we need to turn-off flashlight.
                    // If otherwise, user just clicked to enable/disable the flashlight.
                    if (System.currentTimeMillis() - touchStartTime > 350) {
                        Log.i("MainActivity","flashlightBtn is OFF after press/hold")
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
                resetAllActivities(Token.SOS)
                Log.i("MainActivity","sosBtn is ON")
                repeatSOS(true)
            }
            else {
                Log.i("MainActivity","sosBtn is OFF")
                stopSOS(true)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flicker seekbar and textview handler
        flickeringBar = findViewById(R.id.flickeringBarId)
        flickeringBar.min = minFlickerHz
        flickeringBar.max = maxFlickerHz
        flickeringBar.visibility = View.INVISIBLE
        seekBarTitle = findViewById(R.id.seekBarTitleId)
        seekBarTitle.visibility = View.INVISIBLE

        flickeringBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (token == Token.FLICKER && isFlickeringOnDemand) {
                    setFlickeringHz(progress.toLong())
                    setMessageTextEnhanced(progress.toString(), SeekBarMode.HZ, ACTION.PROGRESS)
                }
                else if (token == Token.BATTERY && isBatteryOn && !isBatteryThresholdSet) {
                    batteryThreshold = progress
                    setMessageTextEnhanced(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.PROGRESS)
                }
                else if (token == Token.ALTITUDE && isAltitudeOn && !isAltitudeThresholdSet) {
                    altitudeThreshold = progress
                    setMessageTextEnhanced(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.PROGRESS)
                }
                else if (token == Token.TIMER && isTimerOn && !isTimerThresholdSet) {
                    timerSetAfter = progress.minutes
                    setMessageTextEnhanced(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.PROGRESS)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                loopHandlerSeekBar.removeCallbacksAndMessages(null)
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
                    setMessageTextEnhanced(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.STOP)
                    Log.d("MainActivity", "Battery power level \nset to ${batteryThreshold}%")
                    setPowerLevelDisplayText(ACTION.SET)
                    loopHandlerSeekBar.postDelayed({ resetSeekBarAndTitle() }, hideSeekBarAfterDelay35)
                }
                else if (token == Token.ALTITUDE && isAltitudeOn && !isAltitudeThresholdSet) {
                    isAltitudeThresholdSet = true
                    setMessageTextEnhanced(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.STOP)
                    Log.d("MainActivity", "Altitude point set to ${altitudeThreshold}m")
                    setAltitudeLevelDisplayText(ACTION.SET)
                    loopHandlerSeekBar.postDelayed({ resetSeekBarAndTitle() }, hideSeekBarAfterDelay35)
                }
                else if (token == Token.TIMER && isTimerOn && !isTimerThresholdSet) {
                    isTimerThresholdSet = true
                    setMessageTextEnhanced(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.STOP)
                    Log.d("MainActivity", "Timer set to $timerSetAfter")
                    loopHandlerTimer.postDelayed({ startFlickering() }, timerSetAfter.inWholeMilliseconds)
                    loopHandlerTimer.postDelayed({ setBtnImage(timerBtn, R.drawable.timer_success) }, timerSetAfter.inWholeMilliseconds)
                    loopHandlerTimer.postDelayed({ stopFlickering() }, timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
                    loopHandlerTimer.postDelayed({ setBtnImage(timerBtn, R.drawable.timer_off_m3) }, timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
                    setTimerThresholdDisplayText(ACTION.SET)
                    loopHandlerSeekBar.postDelayed({ resetSeekBarAndTitle() }, hideSeekBarAfterDelay35)
                }
                isStartTrackingTouched = false
            }
        })

        // flicker flashlight button handler
        flickerBtnSetId = findViewById(R.id.flickerBtnSetId)
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
                flickerBtnSetId.text = "Frequency ${flickerFlashlightHz}Hz"
            }
            else {
                Log.i("MainActivity","flickerFlashlightBtn is OFF")
                isFlickeringOnDemand = false
                stopFlickering()
                setBtnImage(flickerFlashlightBtn, R.drawable.flickering_off_m3)
                resetSeekBarAndTitle()
                setFlickeringHz(minFlickerHz.toLong())
                flickerBtnSetId.text = getString(R.string.pavlaHz)
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
                } else {
                    Log.i("MainActivity", "incomingCallBtn is OFF")
                    disableIncomingCallFlickering()
                    setBtnImage(incomingCallBtn, R.drawable.incoming_call_off_m3)
                }
            }
            else {
                // user should be asked for permissions again
                setBtnImage(incomingCallBtn, R.drawable.incoming_call_no_permission_m3)
                Snackbar.make(rootView, "To use the feature, manually provide\nCall access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming sound handler
        soundBtnSetId = findViewById(R.id.soundBtnSetId)
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
                    setBtnImage(incomingSoundBtn, R.drawable.sound_off_m3)
                    try {
                        recordingThread?.interrupt()
                    }
                    catch (e : SecurityException) {
                        Log.e("MainActivity", "THREAD SecurityException $e")
                    }
                    recordingThread?.join()
                    recordingThread = null
                    soundBtnSetId.text = getString(R.string.pavla)
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
                                }
                                else {
                                    Log.i("MainActivity","LOOP ABOVE THRESHOLD - TURN ON Flashlight")
                                    turnOnFlashlight()
                                }
                            }
                        }
                    }
                    recordingThread?.start()
                    soundBtnSetId.text = "Sensitivity: $sensitivitySoundThreshold"
                }
            }
            else {
                // user should be asked for permissions again
                setBtnImage(incomingSoundBtn, R.drawable.sound_no_permission_m3)
                soundBtnSetId.text = getString(R.string.pavla)
                Snackbar.make(rootView, "To use the feature, manually provide\nAudio access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // tilt phone handler
        // TODO: 45 degrees don't work well
        tiltBtnSet = findViewById(R.id.tiltBtnSetId)
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
                    setBtnImage(incomingTiltBtn, R.drawable.tilt_on_m3)
                    isPhoneTilt = true
                    tiltBtnSet.text = "Tilt by $sensitivityAngle degrees"
                }
                else {
                    // we have to disable the btn now since rotation sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    Snackbar.make(rootView, "To use the feature, manually provide\nAudio access rights to $applicationName", Snackbar.LENGTH_LONG).show()
                    setBtnImage(incomingTiltBtn, R.drawable.tilt_no_permission_m3)
                    tiltBtnSet.text = getString(R.string.pavla)
                }
            } else {
                Log.i("MainActivity","incomingTiltBtn is OFF ($sensorEventListener)")
                turnOffFlashlight()
                sensorManager.unregisterListener(sensorEventListener)
                setBtnImage(incomingTiltBtn, R.drawable.tilt_off_m3)
                tiltBtnSet.text = getString(R.string.pavla)
                isPhoneTilt = false
            }
        }

        //////////////////////////////////////////////////////////////////////////////////////
        // incoming SMS handler
        incomingSMSBtn = findViewById(R.id.smsBtnId)
        incomingSMSBtn.setOnClickListener {
            // Check first if permissions are granted
            if (permissionsKeys["SMS"] == true) {
                if (!isIncomingSMS) {
                    Log.i("MainActivity","incomingSMSBtn is ON")
                    registerIncomingEvents(TypeOfEvent.SMS)
                    setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_on_m3)
                } else {
                    Log.i("MainActivity", "incomingSMSBtn is OFF")
                    disableIncomingSMSFlickering()
                    setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_off_m3)
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for SMS")
                Snackbar.make(rootView, "To use the feature, manually provide\nSMS access rights to $applicationName", Snackbar.LENGTH_LONG).show()
                setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_no_permission_m3)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // phone out/in network handler
        networkBtnSetId = findViewById(R.id.networkBtnSetId)
        outInNetworkBtn = findViewById(R.id.networkConnectionBtn)
        outInNetworkBtn.setOnClickListener {

            if (isNetworkConnectivityCbIsSet) {
                // User wants to disable the feature
                Log.i("MainActivity","outInNetworkBtn is OFF")
                isNetworkConnectivityCbIsSet = false
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                Log.i("MainActivity", "Unregister running CB $connectivityCallback")
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                setBtnImage(outInNetworkBtn, R.drawable.network_off_m3)
                networkBtnSetId.text = getString(R.string.pavla)
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
                    networkBtnSetId.text = "Flicker on NW available"
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
                            networkBtnSetId.text = "Flicker on NW available"
                        }
                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Log.i("MainActivity", "NETWORK is currently LOST")
                            isPhoneOutOfNetwork = true
                            setNetworkBtn()
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                            networkBtnSetId.text = "Flicker on NW available"
                        }
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            Log.i("MainActivity", "NETWORK is currently AVAILABLE")
                            isPhoneInNetwork = true
                            setNetworkBtn()
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.OUT_OF_SERVICE)
                            networkBtnSetId.text = "Flicker on NW unavailable"
                        }
                    }
                    Log.i("MainActivity", "Register CB $connectivityCallback")
                    connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                    networkBtnSetId.text = "Flicker on NW unavailable"
                }
                Log.i("MainActivity","outInNetworkBtn is ON")
                //setMessageText("Flickering on Network connection changes is On", hideMessageTextAfter35, Token.NETWORK)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // battery handler
        batteryBtnSetId = findViewById(R.id.batteryBtnSetId)
        batteryBtnSuccessId = findViewById(R.id.batteryBtnSuccessId)
        batteryBtnSuccessId.visibility = View.INVISIBLE
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
                batteryBtnSetId.text = "Flicker on\nBattery power $batteryThreshold%"
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
                                    //setMessageText("Battery has been charged up to ${batteryPercentage}%", hideMessageTextAfter35, Token.BATTERY)
                                    Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                    stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong())
                                    // Should unregister
                                    unregisterReceiver(batteryReceiver)
                                    // Should changed Btn to SUCCESS
                                    setBatteryBtn(ACTION.SUCCESS)
                                    batteryBtnSetId.text = "Battery charged up to $batteryThreshold%"
                                    batteryBtnSuccessId.visibility = View.VISIBLE
                                }
                            }
                            else {
                                // So the phone is discharged and user wants an optical indication when threshold is reached
                                if (batteryPercentage.toInt() < batteryThreshold) {
                                    Log.i("MainActivity", "Battery is discharged to ${batteryPercentage}%")
                                    //setMessageText("Battery is discharged ${batteryPercentage}%", hideMessageTextAfter35, Token.BATTERY)
                                    Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                    stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong())
                                    // Should unregister
                                    unregisterReceiver(batteryReceiver)
                                    // Should changed Btn to SUCCESS
                                    setBatteryBtn(ACTION.SUCCESS)
                                    batteryBtnSetId.text = "Battery discharged to $batteryThreshold%"
                                    batteryBtnSuccessId.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                loopHandlerForInactivity.postDelayed({ checkForInactivity(Token.BATTERY) }, checkInterval50)
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
                setBatteryBtn(ACTION.RESET)
                resetSeekBarAndTitle()
                setPowerLevelDisplayText(ACTION.RESET)
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                initBatteryLevel = minBattery
                loopHandlerForInactivity.removeCallbacksAndMessages(null)
                batteryBtnSetId.text = getString(R.string.pavlaPerc)
                batteryBtnSuccessId.visibility = View.INVISIBLE
            }
        }
        ///////////////////////////////////////////////////////////////////////////////////////
        // timer handler
        timerBtnTimeSetId = findViewById(R.id.timerBtnTimeSetId)
        timerBtnSuccessId = findViewById(R.id.timerBtnSuccessId)
        timerBtnSuccessId.visibility = View.INVISIBLE
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
                loopHandlerForInactivity.postDelayed({ checkForInactivity(Token.TIMER) }, checkInterval50)
                timerBtnTimeSetId.text = "Flicker after\n$timerSetAfter"
            }
            else {
                Log.i("MainActivity","timerBtn is OFF")
                isTimerOn = false
                isTimerThresholdSet = false
                timerSetAfter = minTimerMinutes
                resetSeekBarAndTitle()
                setTimerBtn(ACTION.RESET)
                setTimerThresholdDisplayText(ACTION.RESET)
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                loopHandlerForInactivity.removeCallbacksAndMessages(null)
                timerBtnTimeSetId.text = getString(R.string.mainFeatureTextTimerSet)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // altitude handler
        altitudeBtnSetId = findViewById(R.id.altitudeBtnSetId)
        altitudeBtnSuccessId = findViewById(R.id.altitudeBtnSuccessId)
        altitudeBtnSuccessId.visibility = View.INVISIBLE
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
                                            // In case User is ascending in height
                                            if (altitude > altitudeThreshold) {
                                                if (!isFlickering) {
                                                    turnOffFlashlight(true)
                                                    stopFlickering()
                                                    Log.d("MainActivity", "Flickering ON while ascending \nto altitude of ${flickerFlashlightHz}m")
                                                    startFlickering()
                                                    //setMessageText("Altitude reached: ${altitude.toInt()}m high", hideMessageTextAfter35, Token.ALTITUDE)
                                                    stopFlickeringAfterTimeout(
                                                        maxFlickerDurationAltitude.toLong()
                                                    )
                                                    sensorManager.unregisterListener(sensorEventListener)
                                                    setAltitudeBtn(ACTION.SUCCESS)
                                                    setAltitudeLevelDisplayText(ACTION.SET)
                                                    altitudeBtnSetId.text = "Height reached ${altitude.toInt()}m"
                                                    altitudeBtnSuccessId.visibility = View.VISIBLE
                                                }
                                            }
                                        }
                                        else {
                                            // In case User is descending in height
                                            if (altitude < altitudeThreshold) {
                                                if (!isFlickering) {
                                                    turnOffFlashlight(true)
                                                    stopFlickering()
                                                    Log.d("MainActivity", "Flickering ON while descending \nto altitude of ${flickerFlashlightHz}m")
                                                    startFlickering()
                                                    //setMessageText("Altitude reached: ${altitude.toInt()}m low", hideMessageTextAfter35, Token.ALTITUDE)
                                                    stopFlickeringAfterTimeout(
                                                        maxFlickerDurationAltitude.toLong()
                                                    )
                                                    sensorManager.unregisterListener(sensorEventListener)
                                                    setAltitudeBtn(ACTION.SUCCESS)
                                                    setAltitudeLevelDisplayText(ACTION.SET)
                                                    altitudeBtnSetId.text = "Descended to ${altitude.toInt()}m"
                                                    altitudeBtnSuccessId.visibility = View.VISIBLE
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
                        loopHandlerForInactivity.postDelayed({ checkForInactivity(Token.ALTITUDE) }, checkInterval50)
                        altitudeBtnSetId.text = "Flicker at\nHeight of ${altitudeThreshold}m"
                        altitudeBtnSuccessId.visibility = View.INVISIBLE
                    }
                    else {
                        // we have to disable the btn now since sensor is not available on the device
                        Log.i("MainActivity","Barometer not available")
                        Snackbar.make(rootView, "Device's barometer sensor\nis not available", Snackbar.LENGTH_LONG).show()
                        setAltitudeBtn(ACTION.NO_PERMISSION)
                        setAltitudeLevelDisplayText(ACTION.NO_PERMISSION)
                        altitudeBtnSetId.text = getString(R.string.pavlaMeter)
                        altitudeBtnSuccessId.visibility = View.INVISIBLE
                    }
                } else {
                    Log.i("MainActivity","altitudeBtn is OFF ($sensorEventListener)")
                    isAltitudeOn = false
                    altitudeThreshold = minAltitude
                    resetSeekBarAndTitle()
                    setAltitudeBtn(ACTION.RESET)
                    setAltitudeLevelDisplayText(ACTION.RESET)
                    sensorManager.unregisterListener(sensorEventListener)
                    if (!isFlickeringOnDemand) {
                        stopFlickering()
                    }
                    loopHandlerForInactivity.removeCallbacksAndMessages(null)
                    altitudeBtnSetId.text = getString(R.string.pavlaMeter)
                    altitudeBtnSuccessId.visibility = View.INVISIBLE
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for ALTITUDE")
                altitudeBtnSetId.text = getString(R.string.pavlaMeter)
                altitudeBtnSuccessId.visibility = View.INVISIBLE
                Snackbar.make(rootView, "To use the feature, manually provide\nLocation access rights to $applicationName", Snackbar.LENGTH_LONG).show()
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
                Log.i("MainActivity", "Data from Settings are: $maxFlickerHz,${maxTimerMinutes.inWholeMinutes.toInt()},$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")


                // Store User's personal settings
                storeSettings()
            }
        }

        // TODO: how to store personalized settings in Customer's phone device
        settingsBtn.setOnClickListener{
            setSettingsIntent()
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
        supportBtn = findViewById(R.id.supportBtnId)
        supportBtn.setOnClickListener {
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

    private fun setSettingsIntent () {
        // set intent for Settings activity
        intentSettings = Intent(this, SettingsActivity::class.java)
        intentSettings.putExtra("maxFlickerHz", maxFlickerHz)
        intentSettings.putExtra("maxTimerMinutes", maxTimerMinutes.inWholeMinutes.toInt())
        intentSettings.putExtra("sensitivityAngle", sensitivityAngle)
        intentSettings.putExtra("sensitivitySoundThreshold", sensitivitySoundThreshold)
        intentSettings.putExtra("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall)
        intentSettings.putExtra("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS)
        intentSettings.putExtra("maxFlickerDurationBattery", maxFlickerDurationBattery)
        intentSettings.putExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude)
    }

    private fun isStoredSettings () : Boolean {
        sharedPref = getSharedPreferences("FlashiiSettings", Context.MODE_PRIVATE)
        return sharedPref.contains("maxFlickerHz")
    }
    private fun retrieveStoredSettings () {
        maxFlickerHz = sharedPref.getInt("maxFlickerHz", defaultMaxFlickerHz)
        maxTimerMinutes = sharedPref.getInt("maxTimerMinutes", defaultMaxTimer.inWholeMinutes.toInt()).minutes
        sensitivityAngle = sharedPref.getInt("sensitivityAngle", defaultMaxTiltAngle)
        sensitivitySoundThreshold = sharedPref.getInt("sensitivitySoundThreshold", defaultSoundSenseLevel)
        maxFlickerDurationIncomingCall = sharedPref.getInt("maxFlickerDurationIncomingCall", defaultMaxFlickerIncomingCall)
        maxFlickerDurationIncomingSMS = sharedPref.getInt("maxFlickerDurationIncomingSMS", defaultMaxFlickerIncomingSMS)
        maxFlickerDurationBattery = sharedPref.getInt("maxFlickerDurationBattery", defaultMaxFlickerIncomingBattery)
        maxFlickerDurationAltitude = sharedPref.getInt("maxFlickerDurationAltitude", defaultMaxFlickerIncomingAltitude)
    }

    private fun storeSettings () {
        Log.i("MainActivity", "STORED Settings are: $maxFlickerHz,${maxTimerMinutes.inWholeMinutes.toInt()},$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
        val sharedPref = getSharedPreferences("FlashiiSettings", Context.MODE_PRIVATE)
        val editor = sharedPref.edit()
        editor.putInt("maxFlickerHz", maxFlickerHz)
        editor.putInt("maxTimerMinutes", maxTimerMinutes.inWholeMinutes.toInt())
        editor.putInt("sensitivityAngle", sensitivityAngle)
        editor.putInt("sensitivitySoundThreshold", sensitivitySoundThreshold)
        editor.putInt("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall)
        editor.putInt("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS)
        editor.putInt("maxFlickerDurationBattery", maxFlickerDurationBattery)
        editor.putInt("maxFlickerDurationAltitude", maxFlickerDurationAltitude)
        editor.apply()
    }


    private fun checkPermissions (activity: ACTION) {
        when (activity) {
            ACTION.CREATE -> {
                // CALL
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "requestPermissions for CALL")
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), RequestKey.CALL.value)
                }
                else {
                    Log.i("MainActivity", "requestPermissions CALL = TRUE")
                    permissionsKeys["CALL"] = true
                }

                // SMS
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "requestPermissions for SMS")
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), RequestKey.SMS.value)
                }
                else {
                    Log.i("MainActivity", "requestPermissions SMS = TRUE")
                    permissionsKeys["SMS"] = true
                }

                // AUDIO
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "requestPermissions for AUDIO")
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RequestKey.AUDIO.value)
                }
                else {
                    Log.i("MainActivity", "requestPermissions AUDIO = TRUE")
                    permissionsKeys["AUDIO"] = true
                }

                // ALTITUDE
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "requestPermissions for ALTITUDE")
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), RequestKey.ALTITUDE.value)
                }
                else {
                    Log.i("MainActivity", "requestPermissions ALTITUDE = TRUE")
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
                        Log.i("MainActivity", "CALL permissions RESUME: CALL = FALSE ")
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
                        Log.i("MainActivity", "CALL permissions RESUME: SMS = FALSE ")
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
                        Log.i("MainActivity", "CALL permissions RESUME: AUDIO = FALSE ")
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
                        Log.i("MainActivity", "CALL permissions RESUME: ALTITUDE = FALSE ")
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
                //setMessageText("Flashlight will be flickering\non incoming phone Calls", hideMessageTextAfter35, Token.INCOMING_CALL)
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
                //setMessageText("Flashlight will be flickering if\nWiFi or Network signal gets lost", hideMessageTextAfter35, Token.NETWORK)
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
                //setMessageText("Flashlight will be flickering if\nWiFi or Network signal are found", hideMessageTextAfter35, Token.NETWORK)
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
                //setMessageText("Flashlight will be flickering\non incoming SMSes", hideMessageTextAfter35, Token.INCOMING_SMS)
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////


    private fun checkForInactivity (token : Token) {
        when (token) {
            Token.TIMER -> {
                if (isTimerOn && !isTimerThresholdSet && !isStartTrackingTouched) {
                    Log.i("MainActivity", "TURN OFF isTimerOn after inactivity")
                    // we have to reset timer key due to user inactivity
                    isTimerOn = false
                    timerSetAfter = minTimerMinutes
                    setTimerBtn(ACTION.RESET)
                    setTimerThresholdDisplayText(ACTION.RESET)

                    // make sure to restore MessageText and keep the Seekbar if needed
                    if (isSendSOS || isFlickeringOnDemand || isAudioIncoming || isPhoneTilt) {
                        if (isFlickeringOnDemand) {
                            setSeekBar(SeekBarMode.HZ, resetToCurrentState = true)
                        }
                        else {
                            resetSeekBarAndTitle()
                        }
                    }
                    else {
                        resetSeekBarAndTitle()
                        //setMessageText(token = Token.TIMER)
                    }
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
                    batteryThreshold = minBattery
                    initBatteryLevel = minBattery
                    setPowerLevelDisplayText(ACTION.RESET)

                    // make sure to restore MessageText and keep the Seekbar if needed
                    if (isSendSOS || isFlickeringOnDemand || isAudioIncoming || isPhoneTilt) {
                        if (!isFlickeringOnDemand) {
                            resetSeekBarAndTitle()
                        }
                    }
                    else {
                        resetSeekBarAndTitle()
                        //setMessageText(token = Token.BATTERY)
                    }
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
                        altitudeThreshold = minAltitude
                        setAltitudeLevelDisplayText(ACTION.RESET)

                        // make sure to restore MessageText and keep the Seekbar if needed
                        if (isSendSOS || isFlickeringOnDemand || isAudioIncoming || isPhoneTilt) {
                            if (!isFlickeringOnDemand) {
                                resetSeekBarAndTitle()
                            }
                        }
                        else {
                            resetSeekBarAndTitle()
                            //setMessageText(token = Token.ALTITUDE)
                        }
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
//        val textView = findViewById<TextView>(R.id.powerLevelId)
//        var text = "$batteryThreshold%"
//        when (action) {
//            ACTION.SET -> {
//                textView.setTextColor(Color.parseColor("#4ECAF6"))
//                textView.text = text
//            }
//            ACTION.RESET -> {
//                textView.setTextColor(Color.parseColor("#5a85a0"))
//                text = "Power -"
//                textView.text = text
//            }
//            ACTION.NO_PERMISSION -> {
//                textView.setTextColor(Color.parseColor("#383838"))
//                text = "Power -"
//                textView.text = text
//            }
//            else -> {}
//        }
    }

    private fun setTimerThresholdDisplayText (action : ACTION) {
//        val textView = findViewById<TextView>(R.id.timerThresholdId)
//        var text = timerSetAfter.toString()
//        when (action) {
//            ACTION.SET -> {
//                textView.text = text
//                textView.setTextColor(Color.parseColor("#4ECAF6"))
//            }
//            ACTION.RESET -> {
//                text = "Timer -"
//                textView.text = text
//                textView.setTextColor(Color.parseColor("#5a85a0"))
//            }
//            else -> {}
//        }
    }

    private fun setAltitudeLevelDisplayText(action : ACTION) {
        // target textView
//        val textView = findViewById<TextView>(R.id.targetAltitudeLevelId)
//        var text = "${altitudeThreshold}m"
//        textView.visibility = TextView.VISIBLE
//        when (action) {
//            ACTION.SET -> {
//                textView.text = text
//                textView.setTextColor(Color.parseColor("#4ECAF6"))
//            }
//            ACTION.RESET -> {
//                text = "Height -"
//                textView.text = text
//                textView.setTextColor(Color.parseColor("#5a85a0"))
//            }
//            ACTION.NO_PERMISSION -> {
//                text = "Height -"
//                textView.text = text
//                textView.setTextColor(Color.parseColor("#383838"))
//            }
//            else -> {}
//        }
    }

    private fun setMessageTextEnhanced (displayValue: String, mode: SeekBarMode, action : ACTION) {
//        Log.d("MainActivity", "setMessageTextEnhanced: $displayValue, $mode, $action")
        val displayText : String
        when (mode) {
            SeekBarMode.HOURS -> {
                displayText = "Set Time"
                seekBarTitle.text = displayText
                when (action) {
                    ACTION.INIT -> {
                   //     displayText = "Phone will be flickering after"
                        //setMessageText(displayText, token = Token.TIMER)
                    }
                    ACTION.PROGRESS -> {
                   //     displayText = "Phone will be flickering after $displayValue"
                        //setMessageText(displayText, hideMessageTextAfter35, Token.TIMER)
                    }
                    ACTION.STOP -> {
                   //     displayText = "Phone will be flickering after $displayValue"
                       // setMessageText(displayText, hideMessageTextAfter35, Token.TIMER)
                    }
                    else -> {
                     //   displayText = "Phone will be flickering after $displayValue"
                       // setMessageText(displayText, hideMessageTextAfter35, Token.TIMER)
                    }
                }
            }
            SeekBarMode.HZ -> {
                displayText = "Set Frequency"
                seekBarTitle.text = displayText
//                displayText = when (action) {
//                    ACTION.INIT -> {
//                        "Flashlight is flickering at ${displayValue.toInt()}Hz"
//                    }
//
//                    ACTION.PROGRESS -> {
//                        "Flashlight is flickering at ${displayValue.toInt()}Hz"
//                    }
//
//                    ACTION.STOP -> {
//                        "Flashlight is flickering at ${displayValue.toInt()}Hz"
//                    }
//
//                    else -> {
//                        "Flashlight is flickering at ${displayValue.toInt()}Hz"
//                    }
//                }
                //setMessageText(displayText, token = Token.FLICKER)
            }
            SeekBarMode.METERS -> {
                displayText = "Set Height"
                seekBarTitle.text = displayText
                when (action) {
                    ACTION.INIT -> {
                        Log.i("INIT", "INIT ALTITUDE")
                     //   displayText = "Current Height is ${initAltitudeLevel}m\nGet notified at "
                      //  setMessageText(displayText, token = Token.ALTITUDE)
                    }
                    ACTION.PROGRESS -> {
                        Log.i("PROGRESS", "PROGRESS ALTITUDE")
                     //   displayText = "Current Height is ${initAltitudeLevel}m\nGet notified at ${displayValue.toInt()}m"
                     //   setMessageText(displayText, hideMessageTextAfter35, Token.ALTITUDE)
                    }
                    ACTION.STOP -> {
                    //    displayText = "Current Height is ${initAltitudeLevel}m\nGet notified at ${displayValue.toInt()}m"
                      //  setMessageText(displayText, hideMessageTextAfter35, Token.ALTITUDE)
                    }
                    else -> {
                     //   displayText = "Current Height is ${initAltitudeLevel}m\nGet notified at ${displayValue.toInt()}m"
                    //    setMessageText(displayText, hideMessageTextAfter35, Token.ALTITUDE)
                    }
                }
            }
            SeekBarMode.PERCENTAGE -> {
                displayText = "Set Power %"
                seekBarTitle.text = displayText
                when (action) {
                    ACTION.INIT -> {
                     //   displayText = "Current Battery Power is $initBatteryLevel%\nGet notified at "
                     //   setMessageText(displayText, token = Token.BATTERY)
                    }
                    ACTION.PROGRESS -> {
                      //  displayText = "Current Battery Power is $initBatteryLevel%\nGet notified at ${displayValue.toInt()}%"
                    //    setMessageText(displayText, hideMessageTextAfter35, Token.BATTERY)
                    }
                    ACTION.STOP -> {
                       // displayText = "Current Battery Power is $initBatteryLevel%\nGet notified at ${displayValue.toInt()}%"
                      //  setMessageText(displayText, hideMessageTextAfter35, Token.BATTERY)
                    }
                    else -> {
                        //displayText = "Current Battery Power is $initBatteryLevel%\nGet notified at ${displayValue.toInt()}%"
                        //setMessageText(displayText, hideMessageTextAfter35, Token.BATTERY)
                    }
                }
                //messageText.text = displayText
            }
        }
    }

    private fun setSeekBar(mode : SeekBarMode, resetToCurrentState : Boolean = false) {
        flickeringBar.visibility = View.VISIBLE
        thumbInitialPosition = flickeringBar.thumb.bounds.right
        seekBarTitle.visibility = View.VISIBLE
        when (mode) {
            SeekBarMode.HOURS -> {
                flickeringBar.min = minTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.max = maxTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.progress = minTimerMinutes.inWholeMinutes.toInt()
                setMessageTextEnhanced(timerSetAfter.toString(), SeekBarMode.HOURS, ACTION.INIT)
            }
            SeekBarMode.HZ -> {
                flickeringBar.min = minFlickerHz
                flickeringBar.max = maxFlickerHz
                if (resetToCurrentState) {
                    flickeringBar.progress = flickerFlashlightHz.toInt()
                }
                else {
                    flickeringBar.progress = minFlickerHz
                }
                setMessageTextEnhanced(flickerFlashlightHz.toString(), SeekBarMode.HZ, ACTION.INIT)
            }
            SeekBarMode.METERS -> {
                flickeringBar.min = minAltitude
                flickeringBar.max = maxAltitude
                if (initAltitudeLevel != minAltitude) {
                    flickeringBar.progress = initAltitudeLevel
                }
                setMessageTextEnhanced(altitudeThreshold.toString(), SeekBarMode.METERS, ACTION.INIT)
            }
            SeekBarMode.PERCENTAGE -> {
                flickeringBar.min = minBattery
                flickeringBar.max = maxBattery
                if (initBatteryLevel != minBattery) {
                    flickeringBar.progress = initBatteryLevel
                }
                setMessageTextEnhanced(batteryThreshold.toString(), SeekBarMode.PERCENTAGE, ACTION.INIT)
            }
        }
    }

    private fun resetSeekBarAndTitle (hideBarOnly : Boolean = false) {
        flickeringBar.visibility = View.INVISIBLE
        seekBarTitle.visibility = View.INVISIBLE
        if (!hideBarOnly) {
            flickeringBar.progress = flickeringBar.min
        }
        val displayText : String
        flickeringBar.min = when (token) {
            Token.FLICKER -> {
                displayText = "Set Frequency"
                seekBarTitle.text = displayText
                minFlickerHz
            }
            Token.TIMER -> {
                displayText = "Set Time"
                seekBarTitle.text = displayText
                minTimerMinutes.inWholeMinutes.toInt()
            }
            Token.BATTERY -> {
                displayText = "Set Power %"
                seekBarTitle.text = displayText
                minBattery
            }
            Token.ALTITUDE -> {
                displayText = "Set Height"
                seekBarTitle.text = displayText
                minAltitude
            }
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

    private fun disableIncomingSMSFlickering () {
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
            loopHandlerFlickering.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
        }
    }

    fun startFlickering() {
        isFlickering = true
        val periodOfFlashLightInMilliseconds =  1000 / flickerFlashlightHz
        atomicFlashLightOn()
        loopHandlerFlickering.postDelayed({ atomicFlashLightOff() }, (periodOfFlashLightInMilliseconds / 2))
        loopHandlerFlickering.postDelayed({ startFlickering() }, periodOfFlashLightInMilliseconds)
    }

    fun stopFlickeringAfterTimeout (timeout : Long) {
        Log.d("MainActivity", "Flickering TIMEOUT set after ${timeout / 1000} seconds")
        loopHandlerFlickering.postDelayed({ stopFlickering() }, timeout)
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
                    setBtnImage(flashlightBtn, R.drawable.flashlight_on4)
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
                    setBtnImage(flashlightBtn, R.drawable.flashlight_off4)
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
        loopHandlerFlickering.postDelayed({ atomicFlashLightOff() }, ditDuration)
    }

    // dah flashing per Morse code
    private fun dah () {
        atomicFlashLightOn()
        loopHandlerFlickering.postDelayed({ atomicFlashLightOff() }, dahDuration)
    }

    // S = ...
    // Function return the duration of S in milliseconds
    private fun s (initialPauseByMilliseconds : Long = 0) : Long {
        loopHandlerFlickering.postDelayed({ dit() }, initialPauseByMilliseconds)
        loopHandlerFlickering.postDelayed({ dit() }, initialPauseByMilliseconds + ditDuration + spaceDuration)
        loopHandlerFlickering.postDelayed({ dit() }, initialPauseByMilliseconds + 2 * ditDuration + 2 * spaceDuration)
        return initialPauseByMilliseconds + 3 * ditDuration + 2 * spaceDuration + spaceCharsDuration
    }

    // O = - - -
    private fun o (initialPauseByMilliseconds : Long = 0) : Long {
        loopHandlerFlickering.postDelayed({ dah() }, initialPauseByMilliseconds)
        loopHandlerFlickering.postDelayed({ dah() }, initialPauseByMilliseconds + dahDuration + spaceDuration)
        loopHandlerFlickering.postDelayed({ dah() }, initialPauseByMilliseconds + 2 * dahDuration + 2 * spaceDuration)
        return initialPauseByMilliseconds + 3 * dahDuration + 2 * spaceDuration + spaceCharsDuration
    }

    private fun repeatSOS(setSOSBtn : Boolean = false) {
        if (!isSendSOS) {
            val durationOfWord = s(o(s()))
            loopHandlerFlickering.postDelayed({ repeatSOS() }, durationOfWord + spaceWordsDuration)
            if (setSOSBtn) {
                setBtnImage(sosBtn, R.drawable.sos_on_m3)
            }
            isSendSOS = true
        }
    }

    private fun stopSOS (resetSOSBtn : Boolean = false) {
        if (isSendSOS) {
            Log.i("MainActivity", "STOP SOS")
            loopHandlerFlickering.removeCallbacksAndMessages(null)
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
            try {
                unregisterReceiver(incomingCallReceiver)
            }
            catch (e : java.lang.Exception) {
                // Do nothing
            }
        }

        if (isIncomingSMS) {
            try {
                unregisterReceiver(incomingSMSReceiver)
            }
            catch (e : java.lang.Exception) {
                // Do nothing
            }
        }

        if (isPhoneTilt) {
            try {
                sensorManager.unregisterListener(sensorEventListener)
            }
            catch (e : java.lang.Exception) {
                // Do nothing
            }
        }

        if (isNetworkConnectivityCbIsSet) {
            try {
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
            }
            catch (e : java.lang.Exception) {
                // Do nothing
            }
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

//        if (isTimerOn) {
//        }

        loopHandlerForInactivity.removeCallbacksAndMessages(null)
        loopHandlerSeekBar.removeCallbacksAndMessages(null)
    }

    private fun resetAllActivities (featureToken : Token) {
        Log.i("MainActivity", " --------- Reset all activities --------- ")

        var tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.SOS, Token.SOUND, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlashLightOn) {
            // Can be understood as:
            // Until now I had Flashlight activated, but now I have activated
            // Flickering or TILT or Sound or SOS.
            // So, Phone Tilt must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF Flashlight")
            turnOffFlashlight(true)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLASHLIGHT, Token.SOS, Token.SOUND, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlickering && isFlickeringOnDemand) {
            Log.i("MainActivity", "RAA - STOP FLICKERING on demand")
            isFlickeringOnDemand = false
            stopFlickering()
            setBtnImage(flickerFlashlightBtn, R.drawable.flickering_off_m3)
            resetSeekBarAndTitle()
            setFlickeringHz(minFlickerHz.toLong())
            flickerBtnSetId.text = getString(R.string.pavlaHz)
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
//            disableIncomingSMSFlickering()
//            setBtnImage(incomingSMSBtn, R.drawable.incoming_sms_off_m3)
//        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.SOS)
        if ((featureToken in tokenValuesToCheckAgainst) && isPhoneTilt) {
            // Can be understood as:
            // Until now I had Phone Tilt activated, but now I have activated
            // Flickering or Flashlight or Sound or SOS.
            // So, Phone Tilt must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF isPhoneTilt")
            turnOffFlashlight()
            sensorManager.unregisterListener(sensorEventListener)
            setBtnImage(incomingTiltBtn, R.drawable.tilt_off_m3)
            isPhoneTilt = false
            tiltBtnSet.text = getString(R.string.pavla)
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
            loopHandlerFlickering.removeCallbacksAndMessages(null)
            soundBtnSetId.text = getString(R.string.pavla)
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
                    resetSeekBarAndTitle()
                    setBatteryBtn(ACTION.RESET)
                    setPowerLevelDisplayText(ACTION.RESET)
                    try {
                        unregisterReceiver(batteryReceiver)
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
                    }
                    catch (e : Exception) {
                        // We are OK, receiver is already unregistered
                    }
                    batteryBtnSetId.text = getString(R.string.pavlaPerc)
                    batteryBtnSuccessId.visibility = View.INVISIBLE
                }
                else {
                    Log.i("MainActivity", "RAA - TURN OFF callbacks (BATTERY)")
                    try {
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
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
                    resetSeekBarAndTitle()
                    sensorManager.unregisterListener(sensorEventListener)
                    setAltitudeBtn(ACTION.RESET)
                    setAltitudeLevelDisplayText(ACTION.RESET)
                    try {
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // DO nothing here
                    }
                    altitudeBtnSetId.text = getString(R.string.pavlaMeter)
                    altitudeBtnSuccessId.visibility = View.INVISIBLE
                }
                else {
                    Log.i("MainActivity", "RAA - TURN OFF callbacks (ALTITUDE)")
                    try {
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
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
                    resetSeekBarAndTitle()
                    setTimerBtn(ACTION.RESET)
                    setTimerThresholdDisplayText(ACTION.RESET)
                    try {
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerTimer.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // DO nothing here
                    }
                    timerBtnTimeSetId.text = getString(R.string.mainFeatureTextTimerSet)
                    timerBtnSuccessId.visibility = View.INVISIBLE
                }
                else {
                    Log.i("MainActivity", "RAA - TURN OFF callbacks (ALTITUDE)")
                    try {
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerTimer.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
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

    // constants, variables
    private val defaultMaxFlickerHz : Int = 10
    private val defaultMaxTimer = 240.minutes
    private val defaultMaxTiltAngle : Int = 80
    private val defaultSoundSenseLevel : Int = 20000
    private val defaultMaxFlickerIncomingCall : Int = 15000
    private val defaultMaxFlickerIncomingSMS : Int = 15000
    private val defaultMaxFlickerIncomingBattery : Int = 15000
    private val defaultMaxFlickerIncomingAltitude : Int = 15000

    private var maxFlickerHz : Int = defaultMaxFlickerHz
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

        Log.i("SettingsActivity", "oCreate Input data are: $maxFlickerHz, $maxTimerMinutes,$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")


        // apply button
        val settingsApplyBtn = findViewById<Button>(R.id.settingsApplyBtn)
        settingsApplyBtn.setOnClickListener {
            // Return the updated maxFlickerHz value back to MainActivity
            Log.i("SettingsActivity", "setOnClickListener Input data are: $maxFlickerHz, $maxTimerMinutes,$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
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

        // reset default button
        val resetDefaultButton = findViewById<Button>(R.id.settingsResetDefaultBtn)
        resetDefaultButton.setOnClickListener {
            resetTextValues()
            resetToDefaultHint()
        }

        // go-back arrow button
        val closeButton = findViewById<ImageButton>(R.id.settingsGoBackArrow)
        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun setHintValues() {
        maxFlickerHzEditText.hint = maxFlickerHz.toString()
        maxTimerTimeEditText.hint = maxTimerMinutes.toString()
        tiltAngleEditText.hint = sensitivityAngle.toString()
        soundThresholdEditText.hint = sensitivitySoundThreshold.toString()
        var tempInt = defaultMaxFlickerIncomingCall / 1000
        flickTimeIncCallEditText.hint = tempInt.toString()
        tempInt = maxFlickerDurationIncomingSMS / 1000
        flickTimeIncSMSEditText.hint = tempInt.toString()
        tempInt = maxFlickerDurationBattery / 1000
        flickTimeBatteryEditText.hint = tempInt.toString()
        tempInt = maxFlickerDurationAltitude / 1000
        flickTimeAltitudeEditText.hint = tempInt.toString()
    }

    private fun resetTextValues() {
        maxFlickerHz = defaultMaxFlickerHz
        maxTimerMinutes = defaultMaxTimer.inWholeMinutes.toInt()
        sensitivityAngle = defaultMaxTiltAngle
        sensitivitySoundThreshold = defaultSoundSenseLevel
        maxFlickerDurationIncomingCall = defaultMaxFlickerIncomingCall
        maxFlickerDurationIncomingSMS = defaultMaxFlickerIncomingSMS
        maxFlickerDurationBattery = defaultMaxFlickerIncomingBattery
        maxFlickerDurationAltitude = defaultMaxFlickerIncomingAltitude

        maxFlickerHzEditText.text = Editable.Factory.getInstance().newEditable(maxFlickerHz.toString())
        maxTimerTimeEditText.text = Editable.Factory.getInstance().newEditable(maxTimerMinutes.toString())
        tiltAngleEditText.text = Editable.Factory.getInstance().newEditable(sensitivityAngle.toString())
        soundThresholdEditText.text = Editable.Factory.getInstance().newEditable(sensitivitySoundThreshold.toString())
        var temp = maxFlickerDurationIncomingCall / 1000
        flickTimeIncCallEditText.text = Editable.Factory.getInstance().newEditable(temp.toString())
        temp = maxFlickerDurationIncomingSMS / 1000
        flickTimeIncSMSEditText.text = Editable.Factory.getInstance().newEditable(temp.toString())
        temp = maxFlickerDurationBattery / 1000
        flickTimeBatteryEditText.text = Editable.Factory.getInstance().newEditable(temp.toString())
        temp = maxFlickerDurationAltitude / 1000
        flickTimeAltitudeEditText.text = Editable.Factory.getInstance().newEditable(temp.toString())

    }

    private fun resetToDefaultHint () {
        maxFlickerHzEditText.hint = defaultMaxFlickerHz.toString()
        maxTimerTimeEditText.hint = defaultMaxTimer.inWholeMinutes.toInt().toString()
        tiltAngleEditText.hint = defaultMaxTiltAngle.toString()
        soundThresholdEditText.hint = defaultSoundSenseLevel.toString()
        var tempInt = defaultMaxFlickerIncomingCall / 1000
        flickTimeIncCallEditText.hint = tempInt.toString()
        tempInt = defaultMaxFlickerIncomingSMS / 1000
        flickTimeIncSMSEditText.hint = tempInt.toString()
        tempInt = defaultMaxFlickerIncomingBattery / 1000
        flickTimeBatteryEditText.hint = tempInt.toString()
        tempInt = defaultMaxFlickerIncomingAltitude / 1000
        flickTimeAltitudeEditText.hint = tempInt.toString()
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
                resultIntent.putExtra("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall * 1000)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(flickTimeIncSMSEditText, _flickTimeLow, _flickTimeHigh)) {
            CheckResult.SET -> {
                maxFlickerDurationIncomingSMS = flickTimeIncSMSEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS * 1000)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(flickTimeAltitudeEditText, _flickTimeLow, _flickTimeHigh)) {
            CheckResult.SET -> {
                maxFlickerDurationAltitude = flickTimeAltitudeEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude * 1000)
            }
            CheckResult.FAULT -> {
                wrongValueInsertedByUser = true
            }
            else -> {}
        }

        when (checkTextValue(flickTimeBatteryEditText, _flickTimeLow, _flickTimeHigh)) {
            CheckResult.SET -> {
                maxFlickerDurationBattery = flickTimeBatteryEditText.text.toString().toInt()
                resultIntent.putExtra("maxFlickerDurationBattery", maxFlickerDurationBattery * 1000)
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