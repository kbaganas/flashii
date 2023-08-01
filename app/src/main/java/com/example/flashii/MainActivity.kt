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
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
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
import android.view.Menu
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
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.flashii.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import java.util.Locale
import kotlin.time.Duration.Companion.minutes


class MainActivity : AppCompatActivity() {

    // elements
    private lateinit var rootView : View
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var flickeringBar : SeekBar
    private lateinit var altitudeBar : SeekBar
    private lateinit var batteryBar : SeekBar
    private lateinit var soundBar : SeekBar
    private lateinit var tiltBar : SeekBar
    private val applicationName : String = "Flashii"

    // constants defaults
    private val defaultMaxFlickerHz : Int = 10
    private val defaultMaxTimer = 240.minutes
    private val defaultMaxTiltAngle : Int = 90
    private val defaultTiltAngle : Int = 80
    private val defaultMinTiltAngle : Int = 45
    private val defaultSoundSenseLevel : Int = 20000
    private val minSoundSenseLevel : Int = 4000
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
    private var batteryThreshold : Int = minBattery
    private var initBatteryLevel : Int = minBattery

    // Altitude
    private val minAltitude : Int = 0
    private val maxAltitude : Int = 8848
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
    private val maxFlickerDuration30 : Long = 30000 // 30 seconds
    private var maxFlickerDurationIncomingCall : Int = defaultMaxFlickerIncomingCall
    private var maxFlickerDurationIncomingSMS : Int = defaultMaxFlickerIncomingSMS
    private var maxFlickerDurationBattery : Int = defaultMaxFlickerIncomingBattery
    private var maxFlickerDurationAltitude : Int = defaultMaxFlickerIncomingAltitude

    // Tilt
    private val initRotationAngle : Float = -1000f
    private var touchStartTime : Long = 0
    private var thumbInitialPosition = 0
    private var sensitivityAngle = defaultTiltAngle
    private var sensitivitySoundThreshold = defaultSoundSenseLevel

    private lateinit var token : Token // token regarding which key is pressed
    private lateinit var reviewInfo : ReviewInfo
    private lateinit var sharedPref : SharedPreferences // shared with Settings view
    private lateinit var intentSettings : Intent

    // Icons
    private lateinit var smsImageIcon : ImageView
    private lateinit var sosImageIcon : ImageView
    private lateinit var callImageIcon : ImageView
    private lateinit var tiltImageIcon : ImageView
    private lateinit var batteryImageIcon : ImageView
    private lateinit var altitudeImageIcon : ImageView
    private lateinit var soundImageIcon : ImageView
    private lateinit var timerImageIcon : ImageView
    private lateinit var flickerImageIcon : ImageView
    private lateinit var networkImageIcon : ImageView
    private lateinit var flashlightImageIcon : ImageView

    // Switch texts
    private lateinit var tiltSwitchText : TextView
    private lateinit var batterySwitchText : TextView
    private lateinit var altitudeSwitchText : TextView
    private lateinit var soundSwitchText : TextView
    private lateinit var timerSwitchText : TextView
    private lateinit var flickerSwitchText : TextView

    enum class ACTION {
        CREATE,
        RESUME
    }

    enum class TypeOfEvent {
        INCOMING_CALL,
        SMS,
        PHONE_TILT,
        AUDIO,
        OUT_OF_SERVICE,
        IN_SERVICE
    }

    enum class RequestKey (val value: Int) {
        CALL(1),
        SMS(2),
        AUDIO(3),
        ALTITUDE(4)
    }

    enum class FEATURE (val value: String) {
        FLASHLIGHT("Flashlight"),
        CALL("Flashlight flicker on incoming Call"),
        SMS("Flashlight flicker on incoming SMS"),
        AUDIO("Flashlight flicker on short sounds"),
        ALTITUDE("Flashlight flicker on Height reached"),
        BATTERY("Flashlight flicker on Battery Power reached"),
        SOS("SOS"),
        FLICKERING("Flashlight flickering"),
        TIMER("Flashlight flicker at Time specified"),
        NETWORK_FOUND("Flashlight flicker on Network Connection found"),
        NETWORK_LOST("Flashlight flicker on Network Connection lost"),
        TILT("Flashlight toggle on Phone Tilts")
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
        FLASHLIGHT,
        OTHER
    }

    private val permissionsKeys = mutableMapOf (
        "CALL" to false,
        "SMS" to false,
        "AUDIO" to false,
        "ALTITUDE" to false
    )

    // Handlers
    private var loopHandlerTimer : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerFlickering : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerBattery : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerAltitude : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerNetwork : Handler = Handler(Looper.getMainLooper())

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
    private var flickeringDueToNetworkConnection : Boolean = false
    private var flickeringDueToBattery : Boolean = false
    private var flickeringDueToAltitude : Boolean = false
    private var flickeringDueToTimer : Boolean = false
    private var isPhoneTilt : Boolean = false
    private var isAudioIncoming : Boolean = false
    private var isNetworkConnectivityCbIsSet : Boolean = false
    private var isAltitudeOn : Boolean = false
    private var isBatteryOn : Boolean = false
    private var isTimerOn : Boolean = false
    private var isTimerThresholdSet : Boolean = false
    private var itemList = mutableListOf<String>()
    private lateinit var recyclerView: RecyclerView
    private var timerForFlickeringSet : Boolean = false

    // Buttons & Ids
    private var flashlightId : String = "0"
    private lateinit var flashlightBtn : ImageButton
    private lateinit var infoBtn : ImageButton
    private lateinit var settingsBtn : ImageButton
    private lateinit var rateBtn : ImageButton
    private lateinit var supportBtn : ImageButton
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var sosSwitch : Switch
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var flickerSwitch : Switch
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

    // Common use
    private lateinit var tempText : String

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
            Log.i("MainActivity", "RETRIEVED STORED Settings are: $maxFlickerHz,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
        }

        // setup cameraManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
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
        flashlightImageIcon = findViewById(R.id.flashLightImageId)
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
                        resetAllActivities(Token.FLASHLIGHT)
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
        // sosSwitch handler
        sosImageIcon = findViewById(R.id.sosImageIcon)
        sosSwitch = findViewById(R.id.switchSOS)
        sosSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                isSendSOS = true
                resetAllActivities(Token.SOS)
                Log.i("MainActivity","sosSwitch is ON")
                repeatSOS()
                sosImageIcon.setImageResource(R.drawable.sos_on)
                addActivatedFeature(recyclerView, FEATURE.SOS)
            }
            else {
                isSendSOS = false
                Log.i("MainActivity","sosSwitch is OFF")
                stopSOS()
                sosImageIcon.setImageResource(R.drawable.sos_off)
                removeActivatedFeature(recyclerView, FEATURE.SOS)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flicker seekbar and textview handler
        flickeringBar = findViewById(R.id.seekBarFlicker)
        flickeringBar.min = minFlickerHz
        flickeringBar.max = maxFlickerHz

        val flickerExpandArrow: ImageButton = findViewById(R.id.flickerExpandArrow)
        val flickerHiddenView: LinearLayout = findViewById(R.id.flickerHiddenView)
        flickerImageIcon = findViewById(R.id.flickerImageIcon)
        flickerSwitchText = findViewById(R.id.flickerSwitchText)
        tempText = "$flickerFlashlightHz Hz"
        flickerSwitchText.text = tempText
        flickerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))

        // Expand or hide the main content
        flickerExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (flickerHiddenView.visibility == View.VISIBLE) {
                flickerHiddenView.visibility = View.GONE
                flickerExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                flickerHiddenView.visibility = View.VISIBLE
                flickerExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        // Bar listeners
        flickeringBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                flickerFlashlightHz = progress.toLong()
                tempText = "$flickerFlashlightHz Hz"
                flickerSwitchText.text = tempText
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isFlickeringOnDemand) {
                    loopHandlerFlickering.removeCallbacksAndMessages(null)
                    atomicFlashLightOff()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isFlickeringOnDemand) {
                    startFlickering(Token.FLICKER)
                }
            }
        })

        // flicker flashlight button handler
        flickerSwitch = findViewById(R.id.switchFlicker)
        flickerSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                token = Token.FLICKER
                resetAllActivities(Token.FLICKER)
                Log.i("MainActivity","flickerSwitch is ON with ${flickerFlashlightHz}Hz")
                startFlickering(Token.FLICKER)
                tempText = "$flickerFlashlightHz Hz"
                flickerSwitchText.text = tempText
                flickerSwitchText.setTextColor(resources.getColor(R.color.blueText, theme))
                flickerImageIcon.setImageResource(R.drawable.flicker_on)
                addActivatedFeature(recyclerView, FEATURE.FLICKERING)
            }
            else {
                Log.i("MainActivity","flickerSwitch is OFF")
                stopFlickering(Token.FLICKER)
                tempText = "$flickerFlashlightHz Hz"
                flickerSwitchText.text = tempText
                flickerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                flickerImageIcon.setImageResource(R.drawable.flicker_off)
                removeActivatedFeature(recyclerView, FEATURE.FLICKERING)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming call handler
        callImageIcon = findViewById(R.id.callImageIcon)
        incomingCallSwitch = findViewById(R.id.switchCALL)
        incomingCallSwitch.setOnCheckedChangeListener {_, isChecked ->
            // Check first if permissions are granted
            if (permissionsKeys["CALL"] == true) {
                if (isChecked) {
                    Log.i("MainActivity","incomingCallSwitch is ON")
                    registerIncomingEvents(TypeOfEvent.INCOMING_CALL)
                    callImageIcon.setImageResource(R.drawable.call_on2)
                    addActivatedFeature(recyclerView, FEATURE.CALL)
                } else {
                    Log.i("MainActivity", "incomingCallSwitch is OFF")
                    disableIncomingCallFlickering()
                    callImageIcon.setImageResource(R.drawable.call_off2)
                    removeActivatedFeature(recyclerView, FEATURE.CALL)
                }
            }
            else {
                // user should be asked for permissions again
                callImageIcon.setImageResource(R.drawable.call_no_permission)
                incomingCallSwitch.isChecked = false
                removeActivatedFeature(recyclerView, FEATURE.CALL)
                Snackbar.make(rootView, "To use the feature, manually provide\nCall access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming sound handler
        val soundExpandArrow: ImageButton = findViewById(R.id.soundExpandArrow)
        val soundHiddenView: LinearLayout = findViewById(R.id.soundHiddenView)
        soundImageIcon = findViewById(R.id.soundImageIcon)
        soundSwitchText = findViewById(R.id.soundSwitchText)

        tempText = "Sensitivity\n Level 1"
        soundSwitchText.text = tempText
        soundSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
        Log.i("MainActivity","Sensitivity start $sensitivitySoundThreshold, ${soundSwitchText.text}")

        // Expand or hide the main content
        soundExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (soundHiddenView.visibility == View.VISIBLE) {
                soundHiddenView.visibility = View.GONE
                soundExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                soundHiddenView.visibility = View.VISIBLE
                soundExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        // Bar listeners
        soundBar = findViewById(R.id.seekBarSound)
        soundBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tempText = "Sensitivity\nLevel ${progress + 1}"
                sensitivitySoundThreshold = defaultSoundSenseLevel - (defaultSoundSenseLevel - minSoundSenseLevel) / 9 * progress
                soundSwitchText.text = tempText
                Log.i("MainActivity","Sensitivity $progress, $sensitivitySoundThreshold")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Log.i("MainActivity","Sensitivity onStopTrackingTouch, $sensitivitySoundThreshold")
                // Do nothing
            }
        })

        incomingSoundSwitch = findViewById(R.id.switchSound)
        incomingSoundSwitch.setOnCheckedChangeListener {_, isChecked ->
            Log.i("MainActivity","isAudioIncoming CLICKED")
            if (permissionsKeys["AUDIO"] == true) {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

                if (!isChecked) {
                    Log.i("MainActivity","incomingSoundSwitch is OFF")
                    resetFeature(Token.SOUND)
                }
                else {
                    Log.i("MainActivity","incomingSoundSwitch is ON")
                    resetAllActivities(Token.SOUND)
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
                    soundImageIcon.setImageResource(R.drawable.sound_on)
                    soundSwitchText.setTextColor(resources.getColor(R.color.blueText, theme))
                }
            }
            else {
                // user should be asked for permissions again
                removeActivatedFeature(recyclerView, FEATURE.AUDIO)
                soundImageIcon.setImageResource(R.drawable.sound_off)
                soundSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                incomingSoundSwitch.isChecked = false
                Snackbar.make(rootView, "To use the feature, manually provide\nAudio access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // tilt phone handler
        val tiltExpandArrow: ImageButton = findViewById(R.id.tiltExpandArrow)
        val tiltHiddenView: LinearLayout = findViewById(R.id.tiltHiddenView)
        tiltImageIcon = findViewById(R.id.tiltImageIcon)
        tiltSwitchText = findViewById(R.id.tiltSwitchText)
        tempText = "Angle ${sensitivityAngle}\u00B0"
        tiltSwitchText.text = tempText
        tiltSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))

        // Expand or hide the main content
        tiltExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (tiltHiddenView.visibility == View.VISIBLE) {
                tiltHiddenView.visibility = View.GONE
                tiltExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                tiltHiddenView.visibility = View.VISIBLE
                tiltExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        // Bar listeners
        tiltBar = findViewById(R.id.seekBarTilt)
        tiltBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityAngle = defaultMinTiltAngle + (defaultMaxTiltAngle - defaultMinTiltAngle) / 9 * progress
                tempText = "Angle ${sensitivityAngle}\u00B0"
                tiltSwitchText.text = tempText
                Log.i("MainActivity","Angle $progress, $sensitivityAngle")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Log.i("MainActivity","Angle onStopTrackingTouch, $sensitivityAngle")
                // Do nothing
            }
        })

        // TODO: 45 degrees don't work well
        incomingTiltSwitch = findViewById(R.id.switchTilt)
        incomingTiltSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
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
                            Log.i("MainActivity","onAccuracyChanged (accuracy = $accuracy)")
                        }
                    }
                    Log.i("MainActivity","incomingTiltSwitch is ON ($sensorEventListener)")
                    sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    isPhoneTilt = true
                    tempText = "Angle ${sensitivityAngle}\u00B0"
                    tiltSwitchText.text = tempText
                    tiltSwitchText.setTextColor(resources.getColor(R.color.blueText, theme))
                    tiltImageIcon.setImageResource(R.drawable.tilt_on)
                    addActivatedFeature(recyclerView, FEATURE.TILT)
                }
                else {
                    // we have to disable the btn now since rotation sensor is not available on the device
                    Log.i("MainActivity","Accelerometer not available")
                    Snackbar.make(rootView, "To use the feature, manually provide\nAudio access rights to $applicationName", Snackbar.LENGTH_LONG).show()
                    tempText = "Angle ${sensitivityAngle}\u00B0"
                    tiltSwitchText.text = tempText
                    tiltSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                    tiltImageIcon.setImageResource(R.drawable.tilt_off)
                    removeActivatedFeature(recyclerView, FEATURE.TILT)
                }
            } else {
                Log.i("MainActivity","incomingTiltSwitch is OFF ($sensorEventListener)")
                turnOffFlashlight()
                sensorManager.unregisterListener(sensorEventListener)
                isPhoneTilt = false
                tempText = "Angle ${sensitivityAngle}\u00B0"
                tiltSwitchText.text = tempText
                tiltSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                tiltImageIcon.setImageResource(R.drawable.tilt_off)
                removeActivatedFeature(recyclerView, FEATURE.TILT)
            }
        }

        //////////////////////////////////////////////////////////////////////////////////////
        // incoming SMS handler

        smsImageIcon = findViewById(R.id.smsImageIcon)
        incomingSMSSwitch = findViewById(R.id.switchSMS)
        incomingSMSSwitch.setOnCheckedChangeListener {_, isChecked ->
            // Check first if permissions are granted
            if (permissionsKeys["SMS"] == true) {
                if (isChecked) {
                    Log.i("MainActivity","incomingSMSSwitch is ON")
                    registerIncomingEvents(TypeOfEvent.SMS)
                    smsImageIcon.setImageResource(R.drawable.sms_on)
                    addActivatedFeature(recyclerView, FEATURE.SMS)
                } else {
                    Log.i("MainActivity", "incomingSMSSwitch is OFF")
                    disableIncomingSMSFlickering()
                    smsImageIcon.setImageResource(R.drawable.sms_off)
                    removeActivatedFeature(recyclerView, FEATURE.SMS)
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for SMS")
                smsImageIcon.setImageResource(R.drawable.sms_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.SMS)
                incomingSMSSwitch.isChecked = false
                Snackbar.make(rootView, "To use the feature, manually provide\nSMS access rights to $applicationName", Snackbar.LENGTH_LONG).show()
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // phone out/in network handler

        networkImageIcon = findViewById(R.id.networkImageIcon)
        outInNetworkSwitch = findViewById(R.id.switchNetwork)
        outInNetworkSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (!isChecked) {
                // User wants to disable the feature
                Log.i("MainActivity","outInNetworkSwitch is OFF")
                resetFeature(Token.NETWORK)
            }
            else {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

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
                    addActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                }
                Log.i("MainActivity","outInNetworkSwitch is ON")
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // battery handler

        // Get references to views
        val batteryExpandArrow: ImageButton = findViewById(R.id.batteryExpandArrow)
        val batteryHiddenView: LinearLayout = findViewById(R.id.batteryHiddenView)
        val seekBarBatteryText : TextView = findViewById(R.id.seekBarBatteryText)
        batteryImageIcon = findViewById(R.id.batteryImageIcon)
        batterySwitchText = findViewById(R.id.batterySwitchText)
        tempText = "${batteryThreshold}%"
        batterySwitchText.text = tempText
        batterySwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
        tempText = "Current Power: -%. Flicker at: -%"
        seekBarBatteryText.text = tempText

        // Expand or hide the main content
        batteryExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (batteryHiddenView.visibility == View.VISIBLE) {
                batteryHiddenView.visibility = View.GONE
                batteryExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                batteryHiddenView.visibility = View.VISIBLE
                batteryExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        // Bar listeners
        batteryBar = findViewById(R.id.seekBarBattery)
        batteryBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                batteryThreshold = progress
                tempText = "${batteryThreshold}%"
                batterySwitchText.text = tempText
                Log.i("MainActivity","batteryThreshold $progress, $batteryThreshold")
                tempText = if (initBatteryLevel == minBattery) {
                    "Current Power: -%. Flicker at ${batteryThreshold}%"
                } else {
                    "Current Power: ${initBatteryLevel}%. Flicker at ${batteryThreshold}%"
                }
                seekBarBatteryText.text = tempText
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }
        })

        batterySwitch = findViewById(R.id.switchBattery)
        batterySwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                Log.i("MainActivity","batterySwitch is ON")
                token = Token.BATTERY
                resetAllActivities(Token.BATTERY)
                isBatteryOn = true
                batteryReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (initBatteryLevel == minBattery) {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                            initBatteryLevel = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                            Log.i("MainActivity", "Battery initial level is ${initBatteryLevel}%")
                            tempText = "Current Power: ${initBatteryLevel}%. Flicker at ${batteryThreshold}%"
                            seekBarBatteryText.text = tempText
                        }

                        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                            val batteryPercentage = (level.toFloat() / scale.toFloat()) * 100

                            if (batteryThreshold > initBatteryLevel) {
                                // So the user is charging his phone and wants an optical indication when threshold is reached
                                if (batteryPercentage.toInt() >= batteryThreshold) {
                                    Log.i("MainActivity", "Battery has been charged up to ${batteryPercentage}%")
                                    startFlickering(Token.BATTERY)
                                    // should stop flickering and reset after time
                                    stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong(), Token.BATTERY)
                                    loopHandlerBattery.postDelayed({ resetFeature(Token.BATTERY)}, maxFlickerDurationBattery.toLong())
                                    // Should unregister
                                    unregisterReceiver(batteryReceiver)
                                }
                            }
                            else {
                                // So the phone is discharged and user wants an optical indication when threshold is reached
                                if (batteryPercentage.toInt() < batteryThreshold) {
                                    Log.i("MainActivity", "Battery is discharged to ${batteryPercentage}%")
                                    startFlickering(Token.BATTERY)
                                    // should stop flickering and reset after time
                                    stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong(), Token.BATTERY)
                                    loopHandlerBattery.postDelayed({ resetFeature(Token.BATTERY)}, maxFlickerDurationBattery.toLong())
                                    // Should unregister
                                    unregisterReceiver(batteryReceiver)
                                }
                            }
                        }
                    }
                }
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                addActivatedFeature(recyclerView, FEATURE.BATTERY)
                batteryImageIcon.setImageResource(R.drawable.battery_on)
                tempText = "${batteryThreshold}%"
                batterySwitchText.text = tempText
                batterySwitchText.setTextColor(resources.getColor(R.color.blueText, theme))
            }
            else {
                Log.i("MainActivity","batterySwitch is OFF")
                resetFeature(Token.BATTERY)
            }
        }
        ///////////////////////////////////////////////////////////////////////////////////////
        // timer handler

        // Get references to views
        val timerExpandArrow: ImageButton = findViewById(R.id.timerExpandArrow)
        val timerHiddenView: LinearLayout = findViewById(R.id.timerHiddenView)
        val timerTimePicker = findViewById<TimePicker>(R.id.timePicker1)
        var selectedTime = ""
        var hourOfDayTimer = 0
        var minuteTimer = 0
//        timerTimePicker.setIs24HourView(true)
        timerImageIcon = findViewById(R.id.timerImageIcon)
        timerSwitchText = findViewById(R.id.timerSwitchText)
        tempText = "--:--"
        timerSwitchText.text = tempText
        timerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))

        // Expand or hide the main content
        timerExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (timerHiddenView.visibility == View.VISIBLE) {
                timerHiddenView.visibility = View.GONE
                timerExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                timerHiddenView.visibility = View.VISIBLE
                timerExpandArrow.setImageResource(R.drawable.arrow_up)
                // Set a listener to handle the time selection
                timerTimePicker.setOnTimeChangedListener { _, hourOfDay, minute ->
                    if (!timerForFlickeringSet) {
                        selectedTime = formatTime(hourOfDay, minute)
                        hourOfDayTimer = hourOfDay
                        minuteTimer = minute
                        Log.i("MainActivity", "Time selected = $selectedTime")
                        timerSwitchText.text = selectedTime
                    }
                    else {
                        Snackbar.make(rootView, "You have to deactivate the feature first, to select another timestamp.", Snackbar.LENGTH_LONG).show()
                    }
                }

            }
        }

        timerSwitch = findViewById(R.id.switchTimer)
        timerSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (isChecked) {
                timerTimePicker.isEnabled = true
                if (hourOfDayTimer == 0 && minuteTimer == 0) {
                    Log.i("MainActivity","Time error: set to past time")
                    Snackbar.make(rootView, "Have to select a time first", Snackbar.LENGTH_LONG).show()
                    timerSwitch.isChecked = false
                }
                else {
                    val calcTimeToFlickerInMillis = calcTimeToFlicker(hourOfDayTimer, minuteTimer)
                    if (calcTimeToFlickerInMillis < 0) {
                        Log.i("MainActivity","Time error: set to past time ($hourOfDayTimer, $minuteTimer)")
                        timerSwitch.isChecked = false
                    }
                    else {
                        Log.i("MainActivity","timerSwitch is ON")
                        token = Token.TIMER
                        resetAllActivities(Token.TIMER)
                        isTimerOn = true
                        timerImageIcon.setImageResource(R.drawable.timer_on)
                        timerSwitchText.setTextColor(resources.getColor(R.color.blueText, theme))
                        addActivatedFeature(recyclerView, FEATURE.TIMER)
                        timerForFlickeringSet = true
                        Log.i("MainActivity", "TIME flickering at $selectedTime")
                        loopHandlerTimer.removeCallbacksAndMessages(null)
                        loopHandlerTimer.postDelayed({startFlickering(Token.TIMER)}, calcTimeToFlickerInMillis)
                        loopHandlerTimer.postDelayed({resetFeature(Token.TIMER)}, calcTimeToFlickerInMillis + maxFlickerDuration30)
                        // user can no longer interact with the timepicker
                        timerTimePicker.isEnabled = false
                        snackBarTimeSelected(calcTimeToFlickerInMillis)
                    }
                }
            }
            else {
                Log.i("MainActivity","timerSwitch is OFF")
                resetFeature(Token.TIMER)
                timerTimePicker.isEnabled = true
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // altitude handler

        val altitudeExpandArrow: ImageButton = findViewById(R.id.altitudeExpandArrow)
        val altitudeHiddenView: LinearLayout = findViewById(R.id.altitudeHiddenView)
        val seekBarAltitudeText : TextView = findViewById(R.id.seekBarAltitudeText)
        altitudeImageIcon = findViewById(R.id.altitudeImageIcon)
        altitudeSwitchText = findViewById(R.id.altitudeSwitchText)
        tempText = "${altitudeThreshold}m"
        altitudeSwitchText.text = tempText
        altitudeSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
        tempText = "Current Altitude: -m. Flicker at: -m"
        seekBarAltitudeText.text = tempText

        altitudeExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (altitudeHiddenView.visibility == View.VISIBLE) {
                altitudeHiddenView.visibility = View.GONE
                altitudeExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                altitudeHiddenView.visibility = View.VISIBLE
                altitudeExpandArrow.setImageResource(R.drawable.arrow_up)
            }
        }

        // Bar listeners
        altitudeBar = findViewById(R.id.seekBarAltitude)
        altitudeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                altitudeThreshold = minAltitude + ((maxAltitude - minAltitude).toFloat()/1000 * progress).toInt()
                tempText = "${altitudeThreshold}m"
                altitudeSwitchText.text = tempText
                tempText = if (initAltitudeLevel == minAltitude) {
                    "Current Altitude: -m. Flicker at: ${altitudeThreshold}m"
                } else {
                    "Current Altitude: ${initAltitudeLevel}m. Flicker at: ${altitudeThreshold}m"
                }
                seekBarAltitudeText.text = tempText
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }
        })

        altitudeSwitch = findViewById(R.id.switchAltitude)
        altitudeSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["ALTITUDE"] == true) {
                if (isChecked) {
                    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
                    val altitudeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
                    if (altitudeSensor != null) {
                        sensorEventListener = object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                if (event.sensor?.type == Sensor.TYPE_PRESSURE) {
                                    val pressureValue = event.values[0] // Get the pressure value in hPa
                                    val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureValue) // altitude in meters
                                    if (altitude > minAltitude) {
                                        if (initAltitudeLevel == minAltitude) {
                                            Log.d("MainActivity", "initAltitudeLevel set to ${initAltitudeLevel}m")
                                            initAltitudeLevel = altitude.toInt()
                                        }
                                        tempText = "Current Altitude: ${altitude.toInt()}m. Flicker at: ${altitudeThreshold}m"
                                        seekBarAltitudeText.text = tempText
                                        if (altitudeThreshold > initAltitudeLevel) {
                                            // In case User is ascending in height
                                            if (altitude > altitudeThreshold) {
                                                if (!isFlickering) {
                                                    turnOffFlashlight(true)
                                                    stopFlickering(Token.ALTITUDE)
                                                    Log.d("MainActivity", "Flickering ON while ascending \nto altitude of ${flickerFlashlightHz}m")
                                                    startFlickering(Token.ALTITUDE)
                                                    sensorManager.unregisterListener(sensorEventListener)
                                                }
                                            }
                                        }
                                        else {
                                            // In case User is descending in height
                                            if (altitude < altitudeThreshold) {
                                                if (!isFlickering) {
                                                    turnOffFlashlight(true)
                                                    stopFlickering(Token.ALTITUDE)
                                                    Log.d("MainActivity", "Flickering ON while descending \nto altitude of ${flickerFlashlightHz}m")
                                                    startFlickering(Token.ALTITUDE)
                                                    stopFlickeringAfterTimeout(maxFlickerDurationAltitude.toLong(), Token.ALTITUDE)
                                                    sensorManager.unregisterListener(sensorEventListener)
                                                }
                                            }
                                        }
                                    }
                                    else {
                                        tempText = "Current Altitude: ${altitude.toInt()}m. Flicker at: ${altitudeThreshold}m"
                                        seekBarAltitudeText.text = tempText
                                    }
                                }
                            }
                            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                                // Handle accuracy changes if needed
                            }
                        }
                        Log.i("MainActivity","altitudeSwitch is ON ($sensorEventListener)")
                        token = Token.ALTITUDE
                        resetAllActivities(Token.ALTITUDE)
                        isAltitudeOn = true
                        sensorManager.registerListener(sensorEventListener, altitudeSensor, SensorManager.SENSOR_DELAY_NORMAL)
                        addActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                        altitudeImageIcon.setImageResource(R.drawable.altitude_on)
                        tempText = "${altitudeThreshold}m"
                        altitudeSwitchText.text = tempText
                        altitudeSwitchText.setTextColor(resources.getColor(R.color.blueText, theme))
                    }
                    else {
                        // we have to disable the btn now since sensor is not available on the device
                        Log.i("MainActivity","Barometer not available")
                        Snackbar.make(rootView, "Device's barometer sensor is not available", Snackbar.LENGTH_LONG).show()
                        resetFeature(Token.ALTITUDE)
                        altitudeImageIcon.setImageResource(R.drawable.altitude_no_permission)
                    }
                } else {
                    Log.i("MainActivity","altitudeSwitch is OFF ($sensorEventListener)")
                    resetFeature(Token.ALTITUDE)
                }
            }
            else {
                // user should be asked for permissions again
                Log.i("MainActivity", "request permission for ALTITUDE")
                Snackbar.make(rootView, "To use the feature, manually provide\nLocation access rights to $applicationName", Snackbar.LENGTH_LONG).show()
                removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                altitudeImageIcon.setImageResource(R.drawable.altitude_off)
                tempText = "${altitudeThreshold}m"
                altitudeSwitchText.text = tempText
                altitudeSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                altitudeSwitch.isChecked = false
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
//
        val registerSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Handle the result from the SettingsActivity here
                val data = result.data
                maxFlickerHz = data?.getIntExtra("maxFlickerHz", maxFlickerHz) ?: maxFlickerHz
                flickeringBar.max = maxFlickerHz
                if (isFlickeringOnDemand && flickerFlashlightHz > maxFlickerHz) {
                    // user has set a flickering Hz way lower than the running one; so we have to adapt to it
                    flickerFlashlightHz = maxFlickerHz.toLong()
                }

                maxFlickerDurationIncomingSMS = data?.getIntExtra("maxFlickerDurationIncomingSMS", maxFlickerDurationIncomingSMS) ?: maxFlickerDurationIncomingSMS
                maxFlickerDurationBattery = data?.getIntExtra("maxFlickerDurationBattery", maxFlickerDurationBattery) ?: maxFlickerDurationBattery
                maxFlickerDurationAltitude = data?.getIntExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude) ?: maxFlickerDurationAltitude
                maxFlickerDurationIncomingCall = data?.getIntExtra("maxFlickerDurationIncomingCall", maxFlickerDurationIncomingCall) ?: maxFlickerDurationIncomingCall
                Log.i("MainActivity", "Data from Settings are: $maxFlickerHz,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")

                // Store User's personal settings
                storeSettings()
            }
        }

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
               // setBtnImage(rateBtn, R.drawable.rate_no_bind)
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
        // support button
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

    private fun snackBarTimeSelected(calcTimeToFlickerInMillis: Long) {
        val calcTimeToFlickerInSeconds = calcTimeToFlickerInMillis / 1000
        var calcTimeToFlickerInMinutes = calcTimeToFlickerInSeconds / 60
        val calcTimeToFlickerInHours = calcTimeToFlickerInMinutes / 60
        if (calcTimeToFlickerInHours > 0) {
            calcTimeToFlickerInMinutes -=  calcTimeToFlickerInHours * 60
            val hours = if (calcTimeToFlickerInHours > 1) {"hours"} else {"hour"}
            if (calcTimeToFlickerInMinutes > 0) {
                val minutes = if (calcTimeToFlickerInMinutes > 1) {"minutes"} else {"minute"}
                Snackbar.make(rootView, "Flashlight will flicker after $calcTimeToFlickerInHours $hours : $calcTimeToFlickerInMinutes $minutes", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(rootView, "Flashlight will flicker after $calcTimeToFlickerInHours $hours", Snackbar.LENGTH_LONG).show()
            }
        }
        else if (calcTimeToFlickerInMinutes > 0) {
            Snackbar.make(rootView, "Flashlight will flicker after $calcTimeToFlickerInMinutes minutes", Snackbar.LENGTH_LONG).show()
        }
        else {
            Snackbar.make(rootView, "Flashlight will flicker after $calcTimeToFlickerInSeconds seconds", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun calcTimeToFlicker(hourOfDay: Int, minute: Int): Long {
        val selectedTimeCalendar = Calendar.getInstance()
        selectedTimeCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
        selectedTimeCalendar.set(Calendar.MINUTE, minute)
        selectedTimeCalendar.set(Calendar.SECOND, 0)
        selectedTimeCalendar.set(Calendar.MILLISECOND, 0)
        var selectedTimeInMillis = selectedTimeCalendar.timeInMillis
        val currentTimeInMillis = Calendar.getInstance().timeInMillis
        if (selectedTimeInMillis < currentTimeInMillis) {
            // Assume that time selected is referred to the next day
            selectedTimeInMillis += 24 * 60 * 60 * 1000
        }
        return selectedTimeInMillis - currentTimeInMillis
    }

    // Function to format the selected time
    private fun formatTime(hourOfDay: Int, minute: Int): String {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
        calendar.set(Calendar.MINUTE, minute)
        val simpleDateFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return simpleDateFormat.format(calendar.time)
    }

    private fun resetFeature (token : Token) {
        when (token) {
            Token.BATTERY -> {
                isBatteryOn = false
                if (flickeringDueToBattery) {
                    stopFlickering(Token.BATTERY)
                }
                try {
                    unregisterReceiver(batteryReceiver)
                }
                catch (e : java.lang.Exception) {
                    // Do nothing
                }

                loopHandlerBattery.removeCallbacksAndMessages(null)
                removeActivatedFeature(recyclerView, FEATURE.BATTERY)
                batteryImageIcon.setImageResource(R.drawable.battery_off)
                batterySwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                batterySwitch.isChecked = false
            }
            Token.NETWORK -> {
                isNetworkConnectivityCbIsSet = false
                if (flickeringDueToNetworkConnection) {
                    stopFlickering(Token.NETWORK)
                }

                try {
                    connectivityManager.unregisterNetworkCallback(connectivityCallback)
                }
                catch (e : java.lang.Exception) {
                    // Do nothing
                }

                networkImageIcon.setImageResource(R.drawable.network_off)
                removeActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                removeActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
                outInNetworkSwitch.isChecked = false
            }
            Token.TIMER -> {
                isTimerOn = false
                if (flickeringDueToTimer) {
                    stopFlickering(Token.TIMER)
                }
                isTimerThresholdSet = false
                timerSetAfter = minTimerMinutes
                loopHandlerTimer.removeCallbacksAndMessages(null)
                timerImageIcon.setImageResource(R.drawable.timer_off)
                timerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                removeActivatedFeature(recyclerView, FEATURE.TIMER)
                timerSwitch.isChecked = false
                timerForFlickeringSet = false
            }
            Token.ALTITUDE -> {
                isAltitudeOn = false
                altitudeThreshold = minAltitude
                try {
                    sensorManager.unregisterListener(sensorEventListener)
                }
                catch (e : java.lang.Exception) {
                    // Do nothing
                }

                if (flickeringDueToAltitude) {
                    stopFlickering(Token.ALTITUDE)
                }
                removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                altitudeImageIcon.setImageResource(R.drawable.altitude_off)
                tempText = "${altitudeThreshold}m"
                altitudeSwitchText.text = tempText
                altitudeSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                altitudeSwitch.isChecked = false
                altitudeBar.progress = 0
            }
            Token.SOUND -> {
                isAudioIncoming = false
                turnOffFlashlight()
                try {
                    audioRecordHandler.stop()
                    audioRecordHandler.release()
                    recordingThread?.interrupt()
                }
                catch (e : java.lang.Exception) {
                    // Do nothing
                }
                recordingThread?.join()
                recordingThread = null
                loopHandlerFlickering.removeCallbacksAndMessages(null)
                soundImageIcon.setImageResource(R.drawable.sound_off)
                soundSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                removeActivatedFeature(recyclerView, FEATURE.AUDIO)
                incomingSoundSwitch.isChecked = false
            }
            else -> {}
        }

    }


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
        sharedPref = getSharedPreferences("FlashiiSettings", MODE_PRIVATE)
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
        val sharedPref = getSharedPreferences("FlashiiSettings", MODE_PRIVATE)
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
                        setBtnImage(callImageIcon, R.drawable.call_no_permission)
                        Log.i("MainActivity", "CALL permissions RESUME: CALL = FALSE ")
                        permissionsKeys["CALL"] = false
                        callImageIcon.setImageResource(R.drawable.call_no_permission)
                        incomingCallSwitch.isChecked = false
                        removeActivatedFeature(recyclerView, FEATURE.CALL)
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
                        setBtnImage(smsImageIcon, R.drawable.sms_no_permission)
                        Log.i("MainActivity", "CALL permissions RESUME: SMS = FALSE ")
                        permissionsKeys["SMS"] = false
                        smsImageIcon.setImageResource(R.drawable.sms_no_permission)
                        removeActivatedFeature(recyclerView, FEATURE.SMS)
                        incomingSMSSwitch.isChecked = false
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
                        setBtnImage(soundImageIcon, R.drawable.sound_no_permission)
                        Log.i("MainActivity", "CALL permissions RESUME: AUDIO = FALSE ")
                        permissionsKeys["AUDIO"] = false
                        removeActivatedFeature(recyclerView, FEATURE.AUDIO)
                        soundImageIcon.setImageResource(R.drawable.sound_off)
                        tempText = "Sensitivity\n Level $sensitivitySoundThreshold"
                        soundSwitchText.text = tempText
                        soundSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                        incomingSoundSwitch.isChecked = false
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
                        Log.i("MainActivity", "CALL permissions RESUME: ALTITUDE = FALSE ")
                        permissionsKeys["ALTITUDE"] = false
                        removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                        altitudeImageIcon.setImageResource(R.drawable.altitude_no_permission)
                        tempText = "${altitudeThreshold}m"
                        altitudeSwitchText.text = tempText
                        altitudeSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                        altitudeSwitch.isChecked = false
                    }
                }
            }
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
                                    resetAllActivities(Token.INCOMING_CALL)
                                    Log.d("MainActivity", "EXTRA_STATE_RINGING - Flickering ON with ${flickerFlashlightHz}Hz")
                                    startFlickering(Token.INCOMING_CALL)
                                }
                                TelephonyManager.EXTRA_STATE_IDLE -> {
                                    Log.i("MainActivity", "IDLE - Phone stops flickering")
                                    stopFlickering(Token.INCOMING_CALL)
                                }
                                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                                    Log.i("MainActivity", "OFF-HOOK - Phone stops flickering; feature is disabled")
                                    stopFlickering(Token.INCOMING_CALL)
                                   setBtnImage(callImageIcon, R.drawable.call_off2)
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
                        Log.i("MainActivity", "NETWORK from found to LOST")
                        resetAllActivities(Token.NETWORK)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering(Token.NETWORK)
                        stopFlickeringAfterTimeout(maxFlickerDuration30, Token.NETWORK)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }
                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.i("MainActivity", "NETWORK from available to UNAVAILABLE")
                        resetAllActivities(Token.NETWORK)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering(Token.NETWORK)
                        stopFlickeringAfterTimeout(maxFlickerDuration30, Token.NETWORK)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                    }}
                Log.i("MainActivity", "Register CB for OUT_OF_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
            }
            TypeOfEvent.IN_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i("MainActivity", "NETWORK from unavailable to AVAILABLE")
                        resetAllActivities(Token.NETWORK)
                        Log.d("MainActivity", "Flickering ON with ${flickerFlashlightHz}Hz")
                        startFlickering(Token.NETWORK)
                        stopFlickeringAfterTimeout(maxFlickerDuration30, Token.NETWORK)
                        isPhoneOutOfNetwork = false
                        isPhoneInNetwork = true
                    }}
                Log.i("MainActivity", "Register CB for IN_SERVICE $connectivityCallback")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
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
                            startFlickering(Token.INCOMING_SMS)
                            stopFlickeringAfterTimeout(maxFlickerDurationIncomingSMS.toLong(), Token.INCOMING_SMS)
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

    private fun isAboveThreshold(buffer: ShortArray, bytesRead: Int): Boolean {
        for (i in 0 until bytesRead) {
            if (buffer[i] > sensitivitySoundThreshold || buffer[i] < -sensitivitySoundThreshold) {
                return true
            }
        }
        return false
    }

    private fun disableIncomingSMSFlickering () {
        isIncomingSMS = false
        if (!isFlickeringOnDemand) {
            stopFlickering(Token.INCOMING_SMS)
        }
        unregisterReceiver(incomingSMSReceiver)
    }

    private fun disableIncomingCallFlickering () {
        isIncomingCall = false
        if (!isFlickeringOnDemand) {
            stopFlickering(Token.INCOMING_CALL)
        }
        unregisterReceiver(incomingCallReceiver)
    }

    fun stopFlickering(token: Token) {
        when (token) {
            Token.TIMER -> {
                flickeringDueToTimer = false
            }
            Token.BATTERY -> {
                flickeringDueToBattery = false
            }
            Token.ALTITUDE -> {
                flickeringDueToAltitude = false
            }
            Token.NETWORK -> {
                flickeringDueToNetworkConnection = false
            }
            Token.FLICKER -> {
                isFlickeringOnDemand = false
            }
            else -> {}
        }
        if (isFlickering) {
            Log.d("MainActivity", "Flickering OFF")
            isFlickering = false
            loopHandlerFlickering.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
        }
    }

    fun startFlickering(token: Token) {
        when (token) {
            Token.TIMER -> {
                flickeringDueToTimer = true
            }
            Token.BATTERY -> {
                flickeringDueToBattery = true
            }
            Token.ALTITUDE -> {
                flickeringDueToAltitude = true
            }
            Token.NETWORK -> {
                flickeringDueToNetworkConnection = true
            }
            Token.FLICKER -> {
                isFlickeringOnDemand = true
            }
            else -> {}
        }
        isFlickering = true
        val periodOfFlashLightInMilliseconds =  1000 / flickerFlashlightHz
        atomicFlashLightOn()
        loopHandlerFlickering.postDelayed({ atomicFlashLightOff() }, (periodOfFlashLightInMilliseconds / 2))
        loopHandlerFlickering.postDelayed({ startFlickering(Token.OTHER) }, periodOfFlashLightInMilliseconds)
    }

    fun stopFlickeringAfterTimeout (timeout : Long, token: Token) {
        Log.d("MainActivity", "Flickering TIMEOUT set after ${timeout / 1000} seconds")
        loopHandlerFlickering.postDelayed({ stopFlickering(token) }, timeout)
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
                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_on6)
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
                   setBtnImage(flashlightImageIcon, R.drawable.flashlight_off4)
                }
                Log.d("MainActivity", "FlashLight OFF")
            } catch (e: CameraAccessException) {
                Log.d("MainActivity", "FlashLight OFF - ERROR: $e")
            }
        }
    }

    private fun setBtnImage (img : ImageView, icon : Int) {
        img.setImageResource(icon)
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

    private fun repeatSOS() {
        val durationOfWord = s(o(s()))
        loopHandlerFlickering.postDelayed({ repeatSOS() }, durationOfWord + spaceWordsDuration)
    }

    private fun stopSOS () {
        Log.i("MainActivity", "STOP SOS")
        loopHandlerFlickering.removeCallbacksAndMessages(null)
        atomicFlashLightOff()
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
                    setBtnImage(callImageIcon, R.drawable.call_off2)
                }
                RequestKey.SMS.value -> {
                    permissionsKeys["SMS"] = true
                    setBtnImage(sosImageIcon, R.drawable.sos_off)
                }
                RequestKey.AUDIO.value -> {
                    permissionsKeys["AUDIO"] = true
                    setBtnImage(soundImageIcon, R.drawable.sound_off)
                }
                RequestKey.ALTITUDE.value -> {
                    permissionsKeys["ALTITUDE"] = true
                    setBtnImage(altitudeImageIcon, R.drawable.altitude_off)
                }
            }
        }
        else {
            when (requestCode) {
                RequestKey.CALL.value -> {
                    Log.i("MainActivity", "Request NOT granted for CALL")
                    setBtnImage(callImageIcon, R.drawable.call_no_permission)
                    incomingCallSwitch.isChecked = false
                }
                RequestKey.SMS.value -> {
                    Log.i("MainActivity", "Request NOT granted for SMS")
                    setBtnImage(smsImageIcon, R.drawable.sms_no_permission)
                    incomingSMSSwitch.isChecked = false
                }
                RequestKey.AUDIO.value -> {
                    Log.i("MainActivity", "Request NOT granted for AUDIO")
                    setBtnImage(soundImageIcon, R.drawable.sound_no_permission)
                    incomingSoundSwitch.isChecked = false
                    tempText = "Sensitivity\n Level $sensitivitySoundThreshold"
                    soundSwitchText.text = tempText
                    soundSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                }
                RequestKey.ALTITUDE.value -> {
                    Log.i("MainActivity", "Request NOT granted for LOCATION")
                    setBtnImage(altitudeImageIcon, R.drawable.altitude_no_permission)
                    altitudeSwitch.isChecked = false
                    tempText = "${altitudeThreshold}m"
                    altitudeSwitchText.text = tempText
                    altitudeSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
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
            stopFlickering(Token.OTHER)
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
            try {
                audioRecordHandler.stop()
                audioRecordHandler.release()
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
    }


    private fun resetAllActivities (featureToken : Token) {
        Log.i("MainActivity", " --------- Reset all activities --------- ")

        var tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.SOS, Token.SOUND, Token.INCOMING_CALL, Token.INCOMING_SMS, Token.TILT, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlashLightOn) {
            // Can be understood as: Until now I had Flashlight activated, but now I have activated
            // Flickering or TILT or Sound or SOS. So, Flashlight must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF Flashlight")
            turnOffFlashlight(true)
            removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLASHLIGHT, Token.SOS, Token.SOUND, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.TILT, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlickering && isFlickeringOnDemand) {
            Log.i("MainActivity", "RAA - STOP FLICKERING on demand")
            stopFlickering(Token.FLICKER)
            flickerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
            flickerImageIcon.setImageResource(R.drawable.flicker_off)
            removeActivatedFeature(recyclerView, FEATURE.FLICKERING)
            flickerSwitch.isChecked = false
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.TILT, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isSendSOS) {
            Log.i("MainActivity", "RAA - DISABLE SOS")
            stopSOS()
            sosImageIcon.setImageResource(R.drawable.sos_off)
            removeActivatedFeature(recyclerView, FEATURE.SOS)
            sosSwitch.isChecked = false
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isPhoneTilt) {
            // Can be understood as:
            // Until now I had Phone Tilt activated, but now I have activated
            // Flickering or Flashlight or Sound or SOS.
            // So, Phone Tilt must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF isPhoneTilt")
            turnOffFlashlight()
            sensorManager.unregisterListener(sensorEventListener)
            isPhoneTilt = false
            tiltSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
            tiltImageIcon.setImageResource(R.drawable.tilt_off)
            removeActivatedFeature(recyclerView, FEATURE.TILT)
            incomingTiltSwitch.isChecked = false
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isAudioIncoming) {
            Log.i("MainActivity", "RAA - TURN OFF isAudioIncoming")
            resetFeature(Token.SOUND)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isNetworkConnectivityCbIsSet && flickeringDueToNetworkConnection) {
            Log.i("MainActivity", "RAA - TURN OFF isNetworkConnectivityCbIsSet")
            isNetworkConnectivityCbIsSet = false
            stopFlickering(Token.NETWORK)
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
            networkImageIcon.setImageResource(R.drawable.network_off)
            removeActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
            removeActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
            outInNetworkSwitch.isChecked = false
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS, Token.NETWORK, Token.ALTITUDE, Token.TIMER)
        if ((featureToken in tokenValuesToCheckAgainst) && isBatteryOn && flickeringDueToBattery) {
            Log.i("MainActivity", "RAA - TURN OFF isBatteryOn")
            isBatteryOn = false
            batteryThreshold = minBattery
            initBatteryLevel = minBattery
            stopFlickering(Token.BATTERY)
            try {
                unregisterReceiver(batteryReceiver)
                loopHandlerFlickering.removeCallbacksAndMessages(null)
            }
            catch (e : Exception) {
                // We are OK, receiver is already unregistered
            }
            removeActivatedFeature(recyclerView, FEATURE.BATTERY)
            batteryImageIcon.setImageResource(R.drawable.battery_off)
            batterySwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
            batterySwitch.isChecked = false
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS, Token.NETWORK, Token.BATTERY, Token.TIMER)
        if ((featureToken in tokenValuesToCheckAgainst) && isAltitudeOn && flickeringDueToAltitude) {
            Log.i("MainActivity", "RAA - TURN OFF isAltitudeOn")
            isAltitudeOn = false
            altitudeThreshold = minAltitude
            stopFlickering(Token.ALTITUDE)
            sensorManager.unregisterListener(sensorEventListener)
            try {
                loopHandlerFlickering.removeCallbacksAndMessages(null)
            }
            catch (e: java.lang.Exception) {
                // DO nothing here
            }
            removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
            altitudeImageIcon.setImageResource(R.drawable.altitude_off)
            altitudeSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
            altitudeSwitch.isChecked = false
        }


        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.INCOMING_SMS, Token.INCOMING_CALL, Token.SOS, Token.NETWORK, Token.BATTERY, Token.ALTITUDE)
        if ((featureToken in tokenValuesToCheckAgainst) && isTimerOn && flickeringDueToTimer) {
            Log.i("MainActivity", "RAA - TURN OFF isTimerOn")
            isTimerOn = false
            timerSetAfter = minTimerMinutes
            stopFlickering(Token.TIMER)
            try {
                loopHandlerTimer.removeCallbacksAndMessages(null)
                loopHandlerFlickering.removeCallbacksAndMessages(null)
            }
            catch (e: java.lang.Exception) {
                // DO nothing here
            }
            removeActivatedFeature(recyclerView, FEATURE.TIMER)
            timerImageIcon.setImageResource(R.drawable.timer_off)
            timerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
            timerSwitch.isChecked = false
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
    private val defaultMaxFlickerIncomingCall : Int = 15000
    private val defaultMaxFlickerIncomingSMS : Int = 15000
    private val defaultMaxFlickerIncomingBattery : Int = 15000
    private val defaultMaxFlickerIncomingAltitude : Int = 15000

    private var maxFlickerHz : Int = defaultMaxFlickerHz
    private var maxFlickerDurationIncomingCall : Int = 0
    private var maxFlickerDurationIncomingSMS : Int = 0
    private var maxFlickerDurationBattery : Int = 0
    private var maxFlickerDurationAltitude : Int = 0
    private lateinit var maxFlickerHzEditText : EditText
    private lateinit var flickTimeIncCallEditText : EditText
    private lateinit var flickTimeIncSMSEditText : EditText
    private lateinit var flickTimeBatteryEditText : EditText
    private lateinit var flickTimeAltitudeEditText : EditText
    private val _hzLow = 10
    private val _hzHigh = 100
    private val _flickTimeLow = 10
    private val _flickTimeHigh = 180

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
        flickTimeIncCallEditText = findViewById(R.id.flickTimeIncCallId)
        flickTimeIncSMSEditText = findViewById(R.id.flickTimeIncSMSId)
        flickTimeBatteryEditText = findViewById(R.id.flickTimeBatteryId)
        flickTimeAltitudeEditText = findViewById(R.id.flickTimeAltitudeId)

        // Retrieve the data value from the intent of the MainActivity
        maxFlickerHz = intent.getIntExtra("maxFlickerHz", 0)
        maxFlickerDurationIncomingCall = intent.getIntExtra("maxFlickerDurationIncomingCall", 0)
        maxFlickerDurationIncomingSMS = intent.getIntExtra("maxFlickerDurationIncomingSMS", 0)
        maxFlickerDurationBattery = intent.getIntExtra("maxFlickerDurationBattery", 0)
        maxFlickerDurationAltitude = intent.getIntExtra("maxFlickerDurationAltitude", 0)

        // Set data of the intent in local variables
        setHintValues()

        Log.i("SettingsActivity", "oCreate Input data are: $maxFlickerHz,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")

        // apply button
        val settingsApplyBtn = findViewById<Button>(R.id.settingsApplyBtn)
        settingsApplyBtn.setOnClickListener {
            // Return the updated maxFlickerHz value back to MainActivity
            Log.i("SettingsActivity", "setOnClickListener Input data are: $maxFlickerHz,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
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
        maxFlickerDurationIncomingCall = defaultMaxFlickerIncomingCall
        maxFlickerDurationIncomingSMS = defaultMaxFlickerIncomingSMS
        maxFlickerDurationBattery = defaultMaxFlickerIncomingBattery
        maxFlickerDurationAltitude = defaultMaxFlickerIncomingAltitude

        maxFlickerHzEditText.text = Editable.Factory.getInstance().newEditable(maxFlickerHz.toString())
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
            itemImageView.setImageResource(R.drawable.activated)
            itemTextView.text = item
        }
    }
}