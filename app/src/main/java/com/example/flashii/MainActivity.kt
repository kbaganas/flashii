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
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.flashii.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
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

    private val hideSeekBarAfterDelay25 : Long = 2000 // 3.5 seconds
    private val checkInterval35 : Long = 3500 // checkForInactivity after interval
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
        PAUSE,
        SET,
        RESET,
        RESTART,
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

    enum class FEATURE (val value: String) {
        FLASHLIGHT("Flashlight is On"),
        CALL("Flicker on incoming Call"),
        SMS("Flicker on incoming SMS"),
        AUDIO("Flicker on short Sounds"),
        ALTITUDE("Flicker on Altitude Height reached"),
        BATTERY("Flicker on Battery Power reached"),
        SOS("SOS is transmitted"),
        FLICKERING("Flickering is On"),
        TIMER("Flicker at Time specified"),
        NETWORK_FOUND("Flicker when Network Connection is found"),
        NETWORK_LOST("Flicker when Network Connection is lost"),
        TILT("Flashlight toggles on Phone Tilts")
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
    private var loopHandlerBtnSelector : Handler = Handler(Looper.getMainLooper())

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
    private var itemList = mutableListOf<String>()
    private lateinit var recyclerView: RecyclerView

    // Buttons & Ids
    private var flashlightId : String = "0"
    private lateinit var flashlightBtn : ImageButton
    private lateinit var infoBtn : ImageButton
    private lateinit var settingsBtn : ImageButton
    private lateinit var rateBtn : ImageButton
    private lateinit var supportBtn : ImageButton
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var sosBtnSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var flickerFlashlightBtn : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var incomingCallSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var outInNetworkSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var incomingSMSSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var incomingTiltSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var incomingSoundSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var altitudeSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var batterySwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var timerSwitch : Switch

    @SuppressLint("SetTextI18n", "MissingPermission", "ClickableViewAccessibility",
        "MissingInflatedId"
    )
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
        // activated features list
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ItemAdapter(itemList)


        ///////////////////////////////////////////////////////////////////////////////////////
        // flashLightBtn handler
        setFlashlightId()
        flashlightBtn = findViewById(R.id.flashLightBtnId)
        turnOnFlashlight(true)
        addActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
        flashlightBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartTime = System.currentTimeMillis()
                    if (isFlashLightOn) {
                        Log.i("MainActivity","flashlightBtn is OFF")
                        turnOffFlashlight(true)
                        removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
                    } else {
                        Log.i("MainActivity","flashlightBtn is ON")
                        //resetAllActivities(Token.FLASHLIGHT)
                        turnOnFlashlight(true)
                        addActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
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
        // sosBtnSwitch handler

        // Get references to views
        val sosExpandArrow: ImageButton = findViewById(R.id.sosExpandArrow)
        val sosHiddenView: LinearLayout = findViewById(R.id.sosHiddenView)
        val sosImageIcon: ImageView = findViewById(R.id.sosImageIcon)
        val sosSwitchText: TextView = findViewById(R.id.sosSwitchText)

        // Expand or hide the main content
        sosExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (sosHiddenView.visibility == View.VISIBLE) {
                sosHiddenView.visibility = View.GONE
                sosExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                sosHiddenView.visibility = View.VISIBLE
                sosExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        sosBtnSwitch = findViewById(R.id.switchSOS)
        sosBtnSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                //resetAllActivities(Token.SOS)
                Log.i("MainActivity","sosBtnSwitch is ON")
                repeatSOS(true)
                sosImageIcon.setImageResource(R.drawable.sos_on)
                sosSwitchText.text = "Enabled"
                addActivatedFeature(recyclerView, FEATURE.SOS)
            }
            else {
                Log.i("MainActivity","sosBtnSwitch is OFF")
                stopSOS(true)
                sosImageIcon.setImageResource(R.drawable.sos_off)
                sosSwitchText.text = "Disabled"
                removeActivatedFeature(recyclerView, FEATURE.SOS)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flicker seekbar and textview handler
//        flickeringBar = findViewById(R.id.switchFlicker)
//        flickeringBar.min = minFlickerHz
//        flickeringBar.max = maxFlickerHz
//        flickeringBar.visibility = View.INVISIBLE
        //seekBarTitle = findViewById(R.id.seekBarTitleId)
        //seekBarTitle.visibility = View.INVISIBLE

//        flickeringBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (token == Token.FLICKER && isFlickeringOnDemand) {
//                    setFlickeringHz(progress.toLong())
//                    setSeekBarTitle(SeekBarMode.HZ, ACTION.SET)
//                }
//                else if (token == Token.BATTERY && isBatteryOn && isStartTrackingTouched && !isBatteryThresholdSet) {
//                    batteryThreshold = progress
//                    setSeekBarTitle(SeekBarMode.PERCENTAGE, ACTION.SET)
//                }
//                else if (token == Token.ALTITUDE && isAltitudeOn && isStartTrackingTouched && !isAltitudeThresholdSet) {
//                    altitudeThreshold = progress
//                    setSeekBarTitle(SeekBarMode.METERS, ACTION.SET)
//                }
//                else if (token == Token.TIMER && isTimerOn && isStartTrackingTouched && !isTimerThresholdSet) {
//                    timerSetAfter = progress.minutes
//                    setSeekBarTitle(SeekBarMode.HOURS, ACTION.SET)
//                }
//            }

//            override fun onStartTrackingTouch(seekBar: SeekBar?) {
//                loopHandlerSeekBar.removeCallbacksAndMessages(null)
//                atomicFlashLightOff()
//                isStartTrackingTouched = true
//
//                if (token == Token.BATTERY && isBatteryOn && isBatteryThresholdSet) {
//                    isBatteryThresholdSet = false
//                    setSeekBarTitle(SeekBarMode.PERCENTAGE, ACTION.SET)
//                }
//                else if (token == Token.ALTITUDE && isAltitudeOn && isAltitudeThresholdSet) {
//                    isAltitudeThresholdSet = false
//                    setSeekBarTitle(SeekBarMode.METERS, ACTION.SET)
//                }
//                else if (token == Token.TIMER && isTimerOn && isTimerThresholdSet) {
//                    isTimerThresholdSet = false
//                    setSeekBarTitle(SeekBarMode.HOURS, ACTION.SET)
////                }
//            }
//
////            override fun onStopTrackingTouch(seekBar: SeekBar?) {
////                if (isFlickeringOnDemand) {
////                    startFlickering()
////                }
////                else if (token == Token.BATTERY && isBatteryOn && !isBatteryThresholdSet) {
////                    isBatteryThresholdSet = true
////                    Log.d("MainActivity", "Battery power level \nset to ${batteryThreshold}%")
////                    loopHandlerSeekBar.postDelayed({ resetSeekBarAndTitle() }, hideSeekBarAfterDelay25)
////                    //setBtnSelector(layoutBattery, ACTION.SET)
////                }
////                else if (token == Token.ALTITUDE && isAltitudeOn && !isAltitudeThresholdSet) {
////                    isAltitudeThresholdSet = true
////                    Log.d("MainActivity", "Altitude point set to ${altitudeThreshold}m")
////                    loopHandlerSeekBar.postDelayed({ resetSeekBarAndTitle() }, hideSeekBarAfterDelay25)
////                }
////                else if (token == Token.TIMER && isTimerOn && !isTimerThresholdSet) {
////                    isTimerThresholdSet = true
////                    Log.d("MainActivity", "Timer set to $timerSetAfter")
////                    // set success
////                    loopHandlerTimer.postDelayed({ startFlickering() }, timerSetAfter.inWholeMilliseconds)
////                    loopHandlerBtnSelector.postDelayed({ flickerBtnSelector(layoutTimer, ACTION.SET) }, timerSetAfter.inWholeMilliseconds)
////                    // reset
////                    loopHandlerTimer.postDelayed({ stopFlickering() }, timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
////                    loopHandlerBtnSelector.postDelayed({ flickerBtnSelector(layoutTimer, ACTION.RESET) }, timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
////                    //loopHandlerTimer.postDelayed({ setBtnImage(timerSwitch, R.drawable.timer_off_m3) }, timerSetAfter.inWholeMilliseconds.toInt() + maxFlickerDuration15)
////                    loopHandlerSeekBar.postDelayed({ resetSeekBarAndTitle() }, hideSeekBarAfterDelay25)
////                }
////                isStartTrackingTouched = false
////            }
//        })

        // flicker flashlight button handler
        flickerFlashlightBtn = findViewById(R.id.switchFlicker)
        flickerFlashlightBtn.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                token = Token.FLICKER
                //resetAllActivities(Token.FLICKER)
                setFlickeringHz(minFlickerHz.toLong())
                Log.i("MainActivity","flickerFlashlightBtn is ON with ${flickerFlashlightHz}Hz")
                isFlickeringOnDemand = true
                startFlickering()
                addActivatedFeature(recyclerView, FEATURE.FLICKERING)
            }
            else {
                Log.i("MainActivity","flickerFlashlightBtn is OFF")
                isFlickeringOnDemand = false
                stopFlickering()
                setFlickeringHz(minFlickerHz.toLong())
                removeActivatedFeature(recyclerView, FEATURE.FLICKERING)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming call handler

        // Get references to views
        val callExpandArrow: ImageButton = findViewById(R.id.callExpandArrow)
        val callHiddenView: LinearLayout = findViewById(R.id.callHiddenView)
        val callImageIcon: ImageView = findViewById(R.id.callImageIcon)
        val callSwitchText: TextView = findViewById(R.id.callSwitchText)

        // Expand or hide the main content
        callExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (callHiddenView.visibility == View.VISIBLE) {
                callHiddenView.visibility = View.GONE
                callExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                callHiddenView.visibility = View.VISIBLE
                callExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        incomingCallSwitch = findViewById(R.id.switchCALL)
        incomingCallSwitch.setOnCheckedChangeListener {_, isChecked ->
            // Check first if permissions are granted
            if (permissionsKeys["CALL"] == true) {
                if (isChecked) {
                    Log.i("MainActivity","incomingCallSwitch is ON")
                    registerIncomingEvents(TypeOfEvent.INCOMING_CALL)
                    callImageIcon.setImageResource(R.drawable.call_on2)
                    callSwitchText.text = "Enabled"
                    addActivatedFeature(recyclerView, FEATURE.CALL)
                } else {
                    Log.i("MainActivity", "incomingCallSwitch is OFF")
                    disableIncomingCallFlickering()
                    callImageIcon.setImageResource(R.drawable.call_off2)
                    callSwitchText.text = "Disabled"
                    removeActivatedFeature(recyclerView, FEATURE.CALL)
                }
            }
            else {
                // user should be asked for permissions again
                callImageIcon.setImageResource(R.drawable.call_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.CALL)
                Snackbar.make(rootView, "To use the feature, manually provide\nCall access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming sound handler
        incomingSoundSwitch = findViewById(R.id.switchSound)
        incomingSoundSwitch.setOnCheckedChangeListener {_, isChecked ->
            Log.i("MainActivity","isAudioIncoming CLICKED")
            if (permissionsKeys["AUDIO"] == true) {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

                if (!isChecked) {
                    Log.i("MainActivity","incomingSoundSwitch is OFF")
                    isAudioIncoming = false
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
                    removeActivatedFeature(recyclerView, FEATURE.AUDIO)
                }
                else {
                    Log.i("MainActivity","incomingSoundSwitch is ON")
                    //resetAllActivities(Token.SOUND)
                    isAudioIncoming = true
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
                    addActivatedFeature(recyclerView, FEATURE.AUDIO)
                }
            }
            else {
                // user should be asked for permissions again
                removeActivatedFeature(recyclerView, FEATURE.AUDIO)
                Snackbar.make(rootView, "To use the feature, manually provide\nAudio access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // tilt phone handler
        // TODO: 45 degrees don't work well
        incomingTiltSwitch = findViewById(R.id.switchTilt)
        incomingTiltSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (accelerometerSensor != null) {
                    //resetAllActivities(Token.TILT)
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
                            Log.i("MainActivity","onAccuracyChanged (accuracy = $accuracy)")
                        }
                    }
                    Log.i("MainActivity","incomingTiltSwitch is ON ($sensorEventListener)")
                    sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    isPhoneTilt = true
                    addActivatedFeature(recyclerView, FEATURE.TILT)
                }
                else {
                    // we have to disable the btn now since rotation sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    Snackbar.make(rootView, "To use the feature, manually provide\nAudio access rights to $applicationName", Snackbar.LENGTH_LONG).show()
                    resetMainBtnSetText(Token.TILT)
                    removeActivatedFeature(recyclerView, FEATURE.TILT)
                }
            } else {
                Log.i("MainActivity","incomingTiltSwitch is OFF ($sensorEventListener)")
                turnOffFlashlight()
                sensorManager.unregisterListener(sensorEventListener)
                isPhoneTilt = false
                removeActivatedFeature(recyclerView, FEATURE.TILT)
            }
        }

        //////////////////////////////////////////////////////////////////////////////////////
        // incoming SMS handler

        // Get references to views
        val smsExpandArrow: ImageButton = findViewById(R.id.smsExpandArrow)
        val smsHiddenView: LinearLayout = findViewById(R.id.smsHiddenView)
        val smsImageIcon: ImageView = findViewById(R.id.smsImageIcon)
        val smsSwitchText: TextView = findViewById(R.id.smsSwitchText)

        // Expand or hide the main content
        smsExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (smsHiddenView.visibility == View.VISIBLE) {
                smsHiddenView.visibility = View.GONE
                smsExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                smsHiddenView.visibility = View.VISIBLE
                smsExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        // Switch listener
        incomingSMSSwitch = findViewById(R.id.switchSMS)
        incomingSMSSwitch.setOnCheckedChangeListener {_, isChecked ->
            // Check first if permissions are granted
            if (permissionsKeys["SMS"] == true) {
                if (isChecked) {
                    Log.i("MainActivity","incomingSMSSwitch is ON")
                    registerIncomingEvents(TypeOfEvent.SMS)
                    smsImageIcon.setImageResource(R.drawable.sms_on)
                    smsSwitchText.text = "Enabled"
                    addActivatedFeature(recyclerView, FEATURE.SMS)
                } else {
                    Log.i("MainActivity", "incomingSMSSwitch is OFF")
                    disableIncomingSMSFlickering()
                    smsImageIcon.setImageResource(R.drawable.sms_off)
                    smsSwitchText.text = "Disabled"
                    removeActivatedFeature(recyclerView, FEATURE.SMS)
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for SMS")
                smsImageIcon.setImageResource(R.drawable.sms_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.SMS)
                Snackbar.make(rootView, "To use the feature, manually provide\nSMS access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // phone out/in network handler

        // Get references to views
        val networkExpandArrow: ImageButton = findViewById(R.id.networkExpandArrow)
        val networkHiddenView: LinearLayout = findViewById(R.id.networkHiddenView)
        val networkImageIcon: ImageView = findViewById(R.id.networkImageIcon)
        val networkSwitchText: TextView = findViewById(R.id.networkSwitchText)

        // Expand or hide the main content
        networkExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (networkHiddenView.visibility == View.VISIBLE) {
                networkHiddenView.visibility = View.GONE
                networkExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                networkHiddenView.visibility = View.VISIBLE
                networkExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        outInNetworkSwitch = findViewById(R.id.switchNetwork)
        outInNetworkSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (!isChecked) {
                // User wants to disable the feature
                Log.i("MainActivity","outInNetworkSwitch is OFF")
                isNetworkConnectivityCbIsSet = false
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                Log.i("MainActivity", "Unregister running CB $connectivityCallback")
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                networkImageIcon.setImageResource(R.drawable.network_off)
                networkSwitchText.text = "Disabled"
                removeActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                removeActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
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
                    registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                    networkImageIcon.setImageResource(R.drawable.network_lost_to_found)
                    addActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
                }
                else {
                    Log.i("MainActivity", "NETWORK is right now AVAILABLE")
                    connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onUnavailable () {
                            super.onUnavailable()
                            Log.i("MainActivity", "NETWORK is currently UNAVAILABLE")
                            isPhoneOutOfNetwork = true
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                            networkImageIcon.setImageResource(R.drawable.network_lost_to_found)
                        }
                        override fun onLost(network: Network) {
                            super.onLost(network)
                            Log.i("MainActivity", "NETWORK is currently LOST")
                            isPhoneOutOfNetwork = true
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                            networkImageIcon.setImageResource(R.drawable.network_lost_to_found)
                        }
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            Log.i("MainActivity", "NETWORK is currently AVAILABLE")
                            isPhoneInNetwork = true
                            Log.i("MainActivity", "Unregister status CB $connectivityCallback")
                            connectivityManager.unregisterNetworkCallback(connectivityCallback)
                            registerIncomingEvents(TypeOfEvent.OUT_OF_SERVICE)
                            networkImageIcon.setImageResource(R.drawable.network_found_to_lost)
                        }
                    }
                    Log.i("MainActivity", "Register CB $connectivityCallback")
                    connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                }
                Log.i("MainActivity","outInNetworkSwitch is ON")
                networkSwitchText.text = "Enabled"
                if (isPhoneInNetwork) {
                    addActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                }
                else if (isPhoneOutOfNetwork) {
                    addActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
                }
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // battery handler
        batterySwitch = findViewById(R.id.switchBattery)
        batterySwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                Log.i("MainActivity","batterySwitch is ON")
                token = Token.BATTERY
                //resetAllActivities(Token.BATTERY)
                isBatteryOn = true
                batteryReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (initBatteryLevel == minBattery) {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                            initBatteryLevel = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                            Log.i("MainActivity", "Battery initial level is ${initBatteryLevel}%")
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
                                    batteryBtnSetId.text = "Battery charged up to $batteryThreshold%"
                                }
                            }
                            else {
                                // So the phone is discharged and user wants an optical indication when threshold is reached
                                if (batteryPercentage.toInt() < batteryThreshold) {
                                    Log.i("MainActivity", "Battery is discharged to ${batteryPercentage}%")
                                    Log.d("MainActivity", "flickeringBar ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                    stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong())
                                    // Should unregister
                                    unregisterReceiver(batteryReceiver)
                                    // and reset after time
                                    batteryBtnSetId.text = "Battery discharged to $batteryThreshold%"
                                }
                            }
                        }
                    }
                }
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                loopHandlerForInactivity.postDelayed({ checkForInactivity(Token.BATTERY) }, checkInterval35)
                addActivatedFeature(recyclerView, FEATURE.BATTERY)
            }
            else {
                Log.i("MainActivity","batterySwitch is OFF")
                try {
                    unregisterReceiver(batteryReceiver)
                }
                catch (e : Exception) {
                    // We are OK, receiver is already unregistered
                }
                isBatteryOn = false
                batteryThreshold = minBattery
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                initBatteryLevel = minBattery
                loopHandlerForInactivity.removeCallbacksAndMessages(null)
                removeActivatedFeature(recyclerView, FEATURE.BATTERY)
            }
        }
        ///////////////////////////////////////////////////////////////////////////////////////
        // timer handler

        // Get references to views
        val timerExpandArrow: ImageButton = findViewById(R.id.timerExpandArrow)
        val timerHiddenView: LinearLayout = findViewById(R.id.timerHiddenView)
        val timerImageIcon: ImageView = findViewById(R.id.timerImageIcon)
        val timerSwitchText: TextView = findViewById(R.id.timerSwitchText)

        // Expand or hide the main content
        timerExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (timerHiddenView.visibility == View.VISIBLE) {
                timerHiddenView.visibility = View.GONE
                timerExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                timerHiddenView.visibility = View.VISIBLE
                timerExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        timerSwitch = findViewById(R.id.switchTimer)
        timerSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                Log.i("MainActivity","timerSwitch is ON")
                token = Token.TIMER
                //resetAllActivities(Token.TIMER)
                isTimerOn = true
                timerImageIcon.setImageResource(R.drawable.timer_on)
                timerSwitchText.text = "Enabled"
                addActivatedFeature(recyclerView, FEATURE.TIMER)
            }
            else {
                Log.i("MainActivity","timerSwitch is OFF")
                isTimerOn = false
                isTimerThresholdSet = false
                timerSetAfter = minTimerMinutes
                if (!isFlickeringOnDemand) {
                    stopFlickering()
                }
                loopHandlerForInactivity.removeCallbacksAndMessages(null)
                timerImageIcon.setImageResource(R.drawable.timer_off)
                timerSwitchText.text = "Disabled"
                removeActivatedFeature(recyclerView, FEATURE.TIMER)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // altitude handler
        altitudeSwitch = findViewById(R.id.switchAltitude)
        altitudeSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["ALTITUDE"] == true) {
                if (isChecked) {
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
                                                    sensorManager.unregisterListener(sensorEventListener)
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
                                                    stopFlickeringAfterTimeout(maxFlickerDurationAltitude.toLong())
                                                    sensorManager.unregisterListener(sensorEventListener)
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
                        Log.i("MainActivity","altitudeSwitch is ON ($sensorEventListener)")
                        token = Token.ALTITUDE
                        //resetAllActivities(Token.ALTITUDE)
                        isAltitudeOn = true
                        sensorManager.registerListener(sensorEventListener, altitudeSensor, SensorManager.SENSOR_DELAY_NORMAL)
                        addActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                    }
                    else {
                        // we have to disable the btn now since sensor is not available on the device
                        Log.i("MainActivity","Barometer not available")
                        Snackbar.make(rootView, "Device's barometer sensor\nis not available", Snackbar.LENGTH_LONG).show()
                        setAltitudeBtn(ACTION.NO_PERMISSION)
                        removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                    }
                } else {
                    Log.i("MainActivity","altitudeSwitch is OFF ($sensorEventListener)")
                    isAltitudeOn = false
                    altitudeThreshold = minAltitude
                    sensorManager.unregisterListener(sensorEventListener)
                    if (!isFlickeringOnDemand) {
                        stopFlickering()
                    }
                    loopHandlerForInactivity.removeCallbacksAndMessages(null)
                    removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for ALTITUDE")
                Snackbar.make(rootView, "To use the feature, manually provide\nLocation access rights to $applicationName", Snackbar.LENGTH_LONG).show()
                removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
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
//        settingsBtn = findViewById(R.id.settingsBtnId)
//
//        val registerSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//            if (result.resultCode == RESULT_OK) {
//                // Handle the result from the SettingsActivity here
//                val data = result.data
//                maxFlickerHz = data?.getIntExtra("maxFlickerHz", maxFlickerHz) ?: maxFlickerHz
//                sensitivityAngle = data?.getIntExtra("sensitivityAngle", sensitivityAngle) ?: sensitivityAngle
//                sensitivitySoundThreshold = data?.getIntExtra("sensitivitySoundThreshold", sensitivitySoundThreshold) ?: sensitivitySoundThreshold
//                maxFlickerDurationIncomingSMS = data?.getIntExtra("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS) ?: maxFlickerDurationIncomingSMS
//                maxFlickerDurationBattery = data?.getIntExtra("maxFlickerDurationBattery", maxFlickerDurationBattery) ?: maxFlickerDurationBattery
//                maxFlickerDurationAltitude = data?.getIntExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude) ?: maxFlickerDurationAltitude
//                maxFlickerDurationIncomingCall = data?.getIntExtra("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall) ?: maxFlickerDurationIncomingCall
//                val maxTimerMinutesFromSettings = data?.getIntExtra("maxTimerMinutes", maxTimerMinutes.inWholeMinutes.toInt()) ?: maxTimerMinutes.inWholeMinutes.toInt()
//                maxTimerMinutes = maxTimerMinutesFromSettings.minutes
//                Log.i("MainActivity", "Data from Settings are: $maxFlickerHz,${maxTimerMinutes.inWholeMinutes.toInt()},$sensitivityAngle,$sensitivitySoundThreshold,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
//
//                // Store User's personal settings
//                storeSettings()
//            }
//        }
//
//        // TODO: how to store personalized settings in Customer's phone device
//        settingsBtn.setOnClickListener{
//            setSettingsIntent()
//            registerSettings.launch(intentSettings)
//        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // rate button
//        reviewManager = ReviewManagerFactory.create(this)
//        val request = reviewManager.requestReviewFlow()
//        rateBtn = findViewById(R.id.rateBtnId)
//
//        request.addOnCompleteListener { requestInfo ->
//            if (requestInfo.isSuccessful) {
//                reviewInfo = requestInfo.result
//                // Use the reviewInfo to launch the review flow
//                Log.e("RateActivity", "request addOnCompleteListener: reviewInfo is ready}")
//
//            } else {
//                // Handle the error case
//               // setBtnImage(rateBtn, R.drawable.rate_no_bind)
//                Log.e("RateActivity", "request addOnCompleteListener: reviewErrorCode = ${requestInfo.exception.toString()}")
//            }
//        }
//
//        rateBtn.setOnClickListener{
//            try {
//                val flow = reviewManager.launchReviewFlow(this, reviewInfo)
//                flow.addOnCompleteListener {
//                    // The flow has finished. The API does not indicate whether the user
//                    // reviewed or not, or even whether the review dialog was shown. Thus, no
//                    // matter the result, we continue our app flow.
//                    Log.e("RateActivity", "flow addOnCompleteListener: complete")
//                }
//            }
//            catch (e : java.lang.Exception) {
//                Log.e("RateActivity", "Probably no service bind with Google Play")
//            }
//        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // support button
        supportBtn = findViewById(R.id.supportBtnId)
        supportBtn.setOnClickListener {
            val intent = Intent(this, DonateActivity::class.java)
            startActivity(intent)
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // Permissions handling
//        checkPermissions(ACTION.CREATE)
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////


    private fun addActivatedFeature (recyclerView : RecyclerView, feature: FEATURE) {
        itemList.add(feature.value)
        recyclerView.adapter?.notifyItemInserted(itemList.size - 1)
    }

    private fun removeActivatedFeature (recyclerView: RecyclerView, feature: FEATURE) {
        itemList.removeIf { item -> item == feature.value }
        recyclerView.adapter?.notifyDataSetChanged()
    }

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
                       // setBtnImage(incomingCallSwitch, R.drawable.incoming_call_no_permission_m3)
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
                       // setBtnImage(incomingSMSSwitch, R.drawable.incoming_sms_no_permission_m3)
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
                        //setBtnImage(incomingSoundSwitch, R.drawable.sound_no_permission_m3)
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
                            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                                TelephonyManager.EXTRA_STATE_RINGING -> {
                                    //resetAllActivities(Token.INCOMING_CALL)
                                    Log.d("MainActivity", "EXTRA_STATE_RINGING - Flickering ON with ${flickerFlashlightHz}Hz")
                                    startFlickering()
                                }
                                TelephonyManager.EXTRA_STATE_IDLE -> {
                                    Log.i("MainActivity", "IDLE - Phone stops flickering")
                                    stopFlickering()
                                }
                                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                                    Log.i("MainActivity", "OFF-HOOK - Phone stops flickering; feature is disabled")
                                    stopFlickering()
                                   // setBtnImage(incomingCallSwitch, R.drawable.incoming_call_off_m3)
                                }
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
                        //resetAllActivities(Token.NETWORK)
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
                        //resetAllActivities(Token.NETWORK)
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
                        //resetAllActivities(Token.NETWORK)
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
                            //resetAllActivities(Token.INCOMING_SMS)
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

    private fun resetMainBtnSetText (token : Token) {
        when (token) {
            Token.TILT -> {
                tiltBtnSet.text = getString(R.string.pavla)
            }
            Token.FLICKER -> {
                flickerBtnSetId.text = getString(R.string.pavlaHz)
            }
            Token.BATTERY -> {
                batteryBtnSetId.text = getString(R.string.pavlaPerc)
            }
            Token.TIMER -> {
                timerBtnTimeSetId.text = getString(R.string.pavla)
            }
            Token.ALTITUDE -> {
                altitudeBtnSetId.text = getString(R.string.pavlaMeter)
            }
            Token.SOUND -> {
                soundBtnSetId.text = getString(R.string.pavla)
            }
            Token.NETWORK -> {
                networkBtnSetId.text = getString(R.string.pavla)
            }
            else -> {}
        }
    }

    private fun checkForInactivity (token : Token) {
        when (token) {
            Token.TIMER -> {
                if (isTimerOn && !isTimerThresholdSet && !isStartTrackingTouched) {
                    Log.i("MainActivity", "TURN OFF isTimerOn after inactivity")
                    // we have to reset timer key due to user inactivity
                    isTimerOn = false
                    timerSetAfter = minTimerMinutes
                    setTimerBtn(ACTION.RESET)
                    //setBtnSelector(layoutTimer, ACTION.RESET)
                    resetMainBtnSetText(Token.TIMER)

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
                    //setBtnSelector(layoutBattery, ACTION.RESET)
                    isBatteryOn = false
                    batteryThreshold = minBattery
                    initBatteryLevel = minBattery
                    resetMainBtnSetText(Token.BATTERY)

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
                        //setBtnSelector(layoutAltitude, ACTION.RESET)
                        isAltitudeOn = false
                        altitudeThreshold = minAltitude
                        resetMainBtnSetText(Token.ALTITUDE)

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

    private fun setSeekBar(mode : SeekBarMode, resetToCurrentState : Boolean = false) {
        flickeringBar.visibility = View.VISIBLE
        thumbInitialPosition = flickeringBar.thumb.bounds.right
        //seekBarTitle.visibility = View.VISIBLE
        when (mode) {
            SeekBarMode.HOURS -> {
                flickeringBar.min = minTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.max = maxTimerMinutes.inWholeMinutes.toInt()
                flickeringBar.progress = minTimerMinutes.inWholeMinutes.toInt()
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
            }
            SeekBarMode.METERS -> {
                flickeringBar.min = minAltitude
                flickeringBar.max = maxAltitude
                if (initAltitudeLevel != minAltitude) {
                    flickeringBar.progress = initAltitudeLevel
                }
            }
            SeekBarMode.PERCENTAGE -> {
                flickeringBar.min = minBattery
                flickeringBar.max = maxBattery
                if (initBatteryLevel != minBattery) {
                    flickeringBar.progress = initBatteryLevel
                }
            }
        }
    }

    private fun resetSeekBarAndTitle (hideBarOnly : Boolean = false) {
        flickeringBar.visibility = View.INVISIBLE
        //seekBarTitle.visibility = View.INVISIBLE
        if (!hideBarOnly) {
            flickeringBar.progress = flickeringBar.min
        }
        flickeringBar.min = when (token) {
            Token.FLICKER -> {
                minFlickerHz
            }
            Token.TIMER -> {
                minTimerMinutes.inWholeMinutes.toInt()
            }
            Token.BATTERY -> {
                minBattery
            }
            Token.ALTITUDE -> {
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
               // timerSwitch.setImageResource(R.drawable.timer_on_n1)
            }
            ACTION.RESET -> {
               // timerSwitch.setImageResource(R.drawable.timer_off_m3)
            }
            else -> {
             //   timerSwitch.setImageResource(R.drawable.timer_off_m3)
            }
        }
    }


    private fun setBatteryBtn (action : ACTION) {
        when (action) {
            ACTION.SET -> {
               // batterySwitch.setImageResource(R.drawable.battery_on_n1)
            }
            ACTION.RESET -> {
             //   batterySwitch.setImageResource(R.drawable.battery_off_m3)
            }
            else -> {
             //   batterySwitch.setImageResource(R.drawable.battery_off_m3)
            }
        }
    }

    private fun setAltitudeBtn (action : ACTION) {
        when (action) {
            ACTION.SET -> {
              //  altitudeSwitch.setImageResource(R.drawable.altitude_on_n1)
            }
            ACTION.RESET -> {
             //   altitudeSwitch.setImageResource(R.drawable.altitude_off_m3)
            }
            ACTION.NO_PERMISSION -> {
              //  altitudeSwitch.setImageResource(R.drawable.altitude_no_permission_m3)
            }
            else -> {
              //  altitudeSwitch.setImageResource(R.drawable.altitude_off_m3)
            }
        }
    }

    private fun setNetworkBtn (networkState : NetworkState = NetworkState.ASIS) {
        if (isPhoneInNetwork) {
            when (networkState) {
                NetworkState.LOST -> {
                   // outInNetworkSwitch.setImageResource(R.drawable.network_lost_m3) // wifi_lost_r1
                }
                NetworkState.UNAVAILABLE -> {
                  //  outInNetworkSwitch.setImageResource(R.drawable.network_lost_m3) // wifi_lost_r1
                }
                NetworkState.ASIS -> {
                  //  outInNetworkSwitch.setImageResource(R.drawable.network_on_to_off_n1) //wifi_off_enabled_r1
                }
                else -> {}
            }
        }
        else if (isPhoneOutOfNetwork) {
            when (networkState) {
                NetworkState.AVAILABLE -> {
                    //setBtnSelector(layoutNetwork, ACTION.SET)
                }
                NetworkState.ASIS -> {
               //     outInNetworkSwitch.setImageResource(R.drawable.network_off_to_on_n1) //wifi_on_enabled_r1
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
                    setBtnImage(flashlightBtn, R.drawable.flashlight_on6)
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
            isSendSOS = true
        }
    }

    private fun stopSOS (resetSOSBtn : Boolean = false) {
        if (isSendSOS) {
            Log.i("MainActivity", "STOP SOS")
            loopHandlerFlickering.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
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
                   // setBtnImage(incomingCallSwitch, R.drawable.incoming_call_off_m3)
                }
                RequestKey.SMS.value -> {
                    permissionsKeys["SMS"] = true
                   // setBtnImage(sosBtnSwitch, R.drawable.sos_off_m3)
                }
                RequestKey.AUDIO.value -> {
                    permissionsKeys["AUDIO"] = true
                  //  setBtnImage(incomingSoundSwitch, R.drawable.sound_off_m3)
                }
                RequestKey.ALTITUDE.value -> {
                    permissionsKeys["ALTITUDE"] = true
                  //  setBtnImage(altitudeSwitch, R.drawable.altitude_off_m3)
                }
            }
        }
        else {
            when (requestCode) {
                RequestKey.CALL.value -> {
                    Log.i("MainActivity", "Request NOT granted for CALL")
                   // setBtnImage(incomingCallSwitch, R.drawable.incoming_call_no_permission_m3)
                }
                RequestKey.SMS.value -> {
                    Log.i("MainActivity", "Request NOT granted for SMS")
                  //  setBtnImage(incomingSMSSwitch, R.drawable.incoming_sms_no_permission_m3)
                }
                RequestKey.AUDIO.value -> {
                    Log.i("MainActivity", "Request NOT granted for AUDIO")
                  //  setBtnImage(incomingSoundSwitch, R.drawable.sound_no_permission_m3)
                }
                RequestKey.ALTITUDE.value -> {
                    Log.i("MainActivity", "Request NOT granted for LOCATION")
                  //  setBtnImage(altitudeSwitch, R.drawable.altitude_no_permission_m3)
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

        var tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.SOS, Token.SOUND, Token.INCOMING_CALL, Token.INCOMING_SMS, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlashLightOn) {
            // Can be understood as:
            // Until now I had Flashlight activated, but now I have activated
            // Flickering or TILT or Sound or SOS.
            // So, Phone Tilt must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF Flashlight")
            turnOffFlashlight(true)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLASHLIGHT, Token.SOS, Token.SOUND, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlickering && isFlickeringOnDemand) {
            Log.i("MainActivity", "RAA - STOP FLICKERING on demand")
            isFlickeringOnDemand = false
            stopFlickering()
            setFlickeringHz(minFlickerHz.toLong())
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.TILT)
        if ((featureToken in tokenValuesToCheckAgainst) && isSendSOS) {
            Log.i("MainActivity", "RAA - DISABLE SOS")
            stopSOS(true)
        }

//        if ((featureToken in tokenValuesToCheckAgainst) && isIncomingCall) {
//            Log.i("MainActivity", "RAA - TURN OFF isIncomingCall")
//            disableIncomingCallFlickering()
//            setBtnImage(incomingCallSwitch, R.drawable.incoming_call_off_m3)
//        }

//        if ((featureToken in tokenValuesToCheckAgainst) && isIncomingSMS) {
//            Log.i("MainActivity", "RAA - TURN OFF isIncomingSMS")
//            disableIncomingSMSFlickering()
//            setBtnImage(incomingSMSSwitch, R.drawable.incoming_sms_off_m3)
//        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS)
        if ((featureToken in tokenValuesToCheckAgainst) && isPhoneTilt) {
            // Can be understood as:
            // Until now I had Phone Tilt activated, but now I have activated
            // Flickering or Flashlight or Sound or SOS.
            // So, Phone Tilt must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF isPhoneTilt")
            turnOffFlashlight()
            sensorManager.unregisterListener(sensorEventListener)
            isPhoneTilt = false
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS)
        if ((featureToken in tokenValuesToCheckAgainst) && isAudioIncoming) {
            Log.i("MainActivity", "RAA - TURN OFF isAudioIncoming")
            isAudioIncoming = false
            turnOffFlashlight()
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
        }

        if ((token in tokenValuesToCheckAgainst) && isNetworkConnectivityCbIsSet) {
            Log.i("MainActivity", "RAA - TURN OFF isNetworkConnectivityCbIsSet")
            isNetworkConnectivityCbIsSet = false
            stopFlickering()
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
        }

        if (isBatteryOn) {
            if (featureToken != Token.BATTERY) {
                if (!isBatteryThresholdSet) {
                    Log.i("MainActivity", "RAA - TURN OFF isBatteryOn")
                    isBatteryOn = false
                    batteryThreshold = minBattery
                    initBatteryLevel = minBattery
                    turnOffFlashlight()
                    try {
                        unregisterReceiver(batteryReceiver)
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
                    }
                    catch (e : Exception) {
                        // We are OK, receiver is already unregistered
                    }
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
                    sensorManager.unregisterListener(sensorEventListener)
                    try {
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // DO nothing here
                    }
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
                    try {
                        loopHandlerSeekBar.removeCallbacksAndMessages(null)
                        loopHandlerForInactivity.removeCallbacksAndMessages(null)
                        loopHandlerTimer.removeCallbacksAndMessages(null)
                        loopHandlerFlickering.removeCallbacksAndMessages(null)
                    }
                    catch (e: java.lang.Exception) {
                        // DO nothing here
                    }
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

// Create a custom RecyclerView adapter to handle the list of items
class ItemAdapter(private val itemList: List<String>) :
    RecyclerView.Adapter<ItemAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Bind data to the ViewHolder
        holder.bind(itemList[position])
    }

    override fun getItemCount(): Int {
        return itemList.size
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemImageView: ImageView = itemView.findViewById(R.id.itemImageView)
        private val itemTextView: TextView = itemView.findViewById(R.id.itemTextView)

        fun bind(item: String) {
            // Set data to the views
            itemImageView.setImageResource(R.drawable.success5)
            itemTextView.text = item
        }
    }
}