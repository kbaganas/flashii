package com.ix8ys.flashii


import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.ads.nativetemplates.TemplateView
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.ix8ys.flashii.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import android.widget.Button
import androidx.activity.result.ActivityResult


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
    private val defaultMaxTiltAngle : Int = 90
    private val defaultTiltAngle : Int = 80
    private val defaultMinTiltAngle : Int = 45
    private val defaultSoundSenseLevel : Int = 20000
    private val minSoundSenseLevel : Int = 4000
    private val defaultMaxFlickerIncomingBattery : Int = 15000
    private val defaultMaxFlickerIncomingAltitude : Int = 15000
    private val delaySnackbarMsg : Long = 5000

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
    private var timerSetAfter = 0.minutes
    private var calcTimeToFlickerInMillis = 0L

    // SOS
    private val ditDuration : Long = 250
    private val spaceDuration : Long = ditDuration
    private val dahDuration : Long = 3 * ditDuration
    private val spaceCharsDuration : Long = 3 * spaceDuration
    private val spaceWordsDuration : Long = 4 * spaceDuration // results to 7*ditDuration, considering that we add spaceCharsDuration after each letter

    // Flickering
    val maxFlickerDuration30 : Long = 30000 // 30 seconds
    private var maxFlickerDurationBattery : Int = defaultMaxFlickerIncomingBattery
    private var maxFlickerDurationAltitude : Int = defaultMaxFlickerIncomingAltitude

    // Tilt
    private var touchStartTime : Long = 0
    private var sensitivityAngle = defaultTiltAngle
    private var sensitivitySoundThreshold = defaultSoundSenseLevel

    private lateinit var reviewInfo : ReviewInfo
    private lateinit var sharedPref : SharedPreferences // shared with Settings view
    private lateinit var intentSettings : Intent

    // Icons
    private lateinit var sosImageIcon : ImageView
    private lateinit var tiltImageIcon : ImageView
    private lateinit var batteryImageIcon : ImageView
    private lateinit var altitudeImageIcon : ImageView
    private lateinit var soundImageIcon : ImageView
    private lateinit var timerImageIcon : ImageView
    private lateinit var flickerImageIcon : ImageView
    private lateinit var networkImageIcon : ImageView
    private lateinit var flashlightImageIcon : ImageView

    // TextViews
    private lateinit var tiltSwitchText : TextView
    private lateinit var batterySwitchText : TextView
    private lateinit var altitudeSwitchText : TextView
    private lateinit var soundSwitchText : TextView
    private lateinit var timerSwitchText : TextView
    private lateinit var flickerSwitchText : TextView

    enum class ACTION {
        CREATE,
        RESUME,
        SUCCESS,
        INFO,
        ATTENTION,
        AUDIO,
        FLASHLIGHT,
        TIMER
    }

    enum class TypeOfEvent {
        PHONE_TILT,
        AUDIO,
        OUT_OF_SERVICE,
        IN_SERVICE
    }

    enum class RequestKey (val value: Int) {
        AUDIO(1),
        FLASHLIGHT (2),
        CREATE (3),
        TIMER (4)
    }

    enum class FEATURE (val value: String) {
        FLASHLIGHT("Flashlight"),
        AUDIO("Flicker on short sounds"),
        ALTITUDE("Flicker on Height reached"),
        BATTERY("Flicker on Battery Power reached"),
        SOS("SOS"),
        FLICKERING("Flashlight flickering"),
        TIMER("Flicker on Time"),
        NETWORK_FOUND("Flicker on Internet connection found"),
        NETWORK_LOST("Flicker on Internet connection lost"),
        TILT("Flashlight toggle on Phone Tilts")
    }

    enum class Token {
        FLICKER,
        TIMER,
        BATTERY,
        ALTITUDE,
        NETWORK,
        SOS,
        SOUND,
        TILT,
        FLASHLIGHT,
        OTHER
    }

    private val permissionsKeys = mutableMapOf (
        "AUDIO" to false,
        "ALTITUDE" to false,
        "FLASHLIGHT" to false,
        "TIMER" to false
    )

    // Handlers
    private var loopHandlerBattery : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerNetwork : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerFlickering : Handler = Handler(Looper.getMainLooper())
    private var loopHandlerAltitude : Handler = Handler(Looper.getMainLooper())
    private var loopHandler : Handler = Handler(Looper.getMainLooper())

    // Handlers, Threads, Managers, Receivers, Detectors
    private lateinit var audioRecordHandler : AudioRecord
    private var recordingThread: Thread? = null
    private lateinit var cameraManager : CameraManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sensorManager : SensorManager
    private lateinit var sensorRotationEventListener : SensorEventListener
    private lateinit var sensorPressureEventListener : SensorEventListener
    private lateinit var connectivityCallback: ConnectivityManager.NetworkCallback
    private lateinit var reviewManager : ReviewManager
    private lateinit var batteryReceiver : BroadcastReceiver
    private lateinit var alarmIntent: PendingIntent
    private lateinit var alarmReceiver : BroadcastReceiver

    // Booleans
    private var isFlashLightOn = false
    private var isFlickering : Boolean = false
    private var isFlickeringOnDemand : Boolean = false
    private var isSendSOS : Boolean = false
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
    private var batteryReceiverRegistered : Boolean = false
    private var rotationSensorRegistered : Boolean = false
    private var altitudeListenerRegistered : Boolean = false
    private var barometerAvailable : Boolean = true
    private var readCurrentBatteryLevel : Boolean = false
    private var readCurrentAltitude : Boolean = false

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
    private lateinit var outInNetworkSwitch : Switch
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

    // AdLoader
    private lateinit var adLoader : AdLoader

    @SuppressLint("InflateParams")
    private fun triggerSnackbar(view: View, text: CharSequence, action : ACTION = ACTION.ATTENTION, token: Token = Token.OTHER) {
        val customSnackView: View = layoutInflater.inflate(R.layout.custom_snackbar, null)
        val snackBarImg = customSnackView.findViewById<ImageView>(R.id.snackbar_img)
        val snackbarBtn: ImageButton = customSnackView.findViewById(R.id.snackbar_Btn)
        val snackBarText = customSnackView.findViewById<TextView>(R.id.snackbar_text)
        snackBarText.text = text.toString()

        val snackbar = Snackbar.make(view, "", Snackbar.LENGTH_INDEFINITE)
        snackbar.view.setBackgroundColor(Color.TRANSPARENT)
        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
        snackbarLayout.setPadding(0, 0, 0, 0)
        snackbarLayout.addView(customSnackView, 0)

        val params = snackbar.view.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.CENTER_VERTICAL
        // params.gravity = Gravity.TOP
        // params.topMargin = 200
        snackbar.view.layoutParams = params
        snackbar.show()

        snackbarBtn.setOnClickListener {
            resetFeature(token)
            snackbar.dismiss()
        }

        when (action) {
            ACTION.ATTENTION -> {
                snackBarImg.setImageResource(R.drawable.attention)
                loopHandler.postDelayed({snackbar.dismiss()}, delaySnackbarMsg)
            }
            ACTION.INFO -> {
                snackBarImg.setImageResource(R.drawable.info_snackbar)
                loopHandler.postDelayed({snackbar.dismiss()}, delaySnackbarMsg)
            }
            ACTION.SUCCESS -> {
                snackBarImg.setImageResource(R.drawable.success)
            }
            else -> {}
        }
    }

    @SuppressLint("SetTextI18n", "MissingPermission", "ClickableViewAccessibility", "MissingInflatedId",
        "RestrictedApi"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        //setTheme(R.style.Theme_Flashii)

        setContentView(R.layout.activity_main)
        rootView = findViewById(android.R.id.content)

        // setup cameraManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        ///////////////////////////////////////////////////////////////////////////////////////
        // activated features list
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ItemAdapter(itemList)

        // Init buttons and icons
        flashlightBtn = findViewById(R.id.flashLightBtnId)
        flashlightImageIcon = findViewById(R.id.flashLightImageId)
        sosImageIcon = findViewById(R.id.sosImageIcon)
        sosSwitch = findViewById(R.id.switchSOS)
        flickerImageIcon = findViewById(R.id.flickerImageIcon)
        flickerSwitchText = findViewById(R.id.flickerSwitchText)
        flickeringBar = findViewById(R.id.seekBarFlicker)
        flickerSwitch = findViewById(R.id.switchFlicker)
        soundImageIcon = findViewById(R.id.soundImageIcon)
        soundSwitchText = findViewById(R.id.soundSwitchText)
        soundBar = findViewById(R.id.seekBarSound)
        incomingSoundSwitch = findViewById(R.id.switchSound)
        tiltImageIcon = findViewById(R.id.tiltImageIcon)
        tiltSwitchText = findViewById(R.id.tiltSwitchText)
        tiltBar = findViewById(R.id.seekBarTilt)
        incomingTiltSwitch = findViewById(R.id.switchTilt)
        networkImageIcon = findViewById(R.id.networkImageIcon)
        outInNetworkSwitch = findViewById(R.id.switchNetwork)
        batteryImageIcon = findViewById(R.id.batteryImageIcon)
        batterySwitchText = findViewById(R.id.batterySwitchText)
        batteryBar = findViewById(R.id.seekBarBattery)
        batterySwitch = findViewById(R.id.switchBattery)
        timerImageIcon = findViewById(R.id.timerImageIcon)
        timerSwitchText = findViewById(R.id.timerSwitchText)
        timerSwitch = findViewById(R.id.switchTimer)
        altitudeImageIcon = findViewById(R.id.altitudeImageIcon)
        altitudeSwitchText = findViewById(R.id.altitudeSwitchText)
        altitudeBar = findViewById(R.id.seekBarAltitude)
        altitudeSwitch = findViewById(R.id.switchAltitude)

        // Initiate local log storage
        getExternalFilesDir(null)?.let { publicAppDirectory -> // getExternalFilesDir don't need storage permission
            val logDirectory = File("${publicAppDirectory.absolutePath}/logs")
            if (!logDirectory.exists()) {
                logDirectory.mkdirs()
            }
            val logFile = File(logDirectory, "logcat_${System.currentTimeMillis()}.txt")
            val processBuilder = ProcessBuilder("logcat", "-f", logFile.absolutePath)
            try {
                // Clear the previous logcat.
                Runtime.getRuntime().exec("logcat -c")

                // Start capturing new logcat entries to the specified log file.
                processBuilder.start()

            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        // Initialization of basic Settings
        if (isStoredSettings()) {
            retrieveStoredSettings()
            Log.i("MainActivity", "Retrieved stored settings are: $flickerFlashlightHz (current Hz), $maxFlickerHz (max Hz), $sensitivitySoundThreshold (sound), $sensitivityAngle (tilt degrees), $maxFlickerDurationBattery (battery),$maxFlickerDurationAltitude (altitude)")
        }

        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context != null) {
                    Log.d("MainActivity", "Alarm received! Perform your task here.")
                    resetActivitiesAndFlicker(Token.TIMER)
                    loopHandler.postDelayed({resetFeature(Token.TIMER)}, maxFlickerDuration30)
                }
            }
        }

        // Disclosure and Permissions handling
        preCheckPermissions(ACTION.FLASHLIGHT)

        if (permissionsKeys["FLASHLIGHT"] == false) {
            Log.i("MainActivity", "Disclosure dialog opens")
            showDisclosureDialog(ACTION.FLASHLIGHT)
        }
        else {
            Log.i("MainActivity", "Flashlight rights granted already")
            setFlashlightId()
            turnOnFlashlight(true)
            addActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flashLightBtn handler
        flashlightBtn.setOnTouchListener { _, event ->
            if (permissionsKeys["FLASHLIGHT"] == true) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartTime = System.currentTimeMillis()
                        if (isFlashLightOn) {
                            turnOffFlashlight(true)
                            removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
                        } else {
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
                            removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
                        }
                        false
                    }
                    else -> {false}
                }
            }
            else {
                // user should be asked for permissions again
                removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
                showDisclosureDialog(ACTION.FLASHLIGHT)
                false
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // sosSwitch handler
        sosSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["FLASHLIGHT"] == true) {
                if (isChecked) {
                    Log.i("MainActivity","sosSwitch is ON")
                    isSendSOS = true
                    resetAllActivities(Token.SOS)
                    repeatSOS()
                    sosImageIcon.setImageResource(R.drawable.sos_on)
                    addActivatedFeature(recyclerView, FEATURE.SOS)
                }
                else {
                    Log.i("MainActivity","sosSwitch is OFF")
                    isSendSOS = false
                    stopSOS()
                    sosImageIcon.setImageResource(R.drawable.sos_off)
                    removeActivatedFeature(recyclerView, FEATURE.SOS)
                }
            }
            else {
                // user should be asked for permissions again
                sosImageIcon.setImageResource(R.drawable.sos_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.SOS)
                sosSwitch.isChecked = false
                showDisclosureDialog(ACTION.FLASHLIGHT)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // flicker seekbar and textview handler
        flickeringBar.min = minFlickerHz
        flickeringBar.max = maxFlickerHz
        val flickerExpandArrow: ImageButton = findViewById(R.id.flickerExpandArrow)
        val flickerHiddenView: LinearLayout = findViewById(R.id.flickerHiddenView)

        tempText = "$flickerFlashlightHz Hz"
        setTextAndColor(flickerSwitchText, tempText, R.color.greyNoteDarker2)

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
                    Log.i("MainActivity", "Flickering ON on demand (${flickerFlashlightHz}Hz)")
                    startFlickering(Token.FLICKER)
                }
            }
        })

        // flicker flashlight button handler
        flickerSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["FLASHLIGHT"] == true) {
                if (isChecked) {
                    resetActivitiesAndFlicker(Token.FLICKER)
                    tempText = "$flickerFlashlightHz Hz"
                    setTextAndColor(flickerSwitchText, tempText, R.color.blueText)
                    flickerImageIcon.setImageResource(R.drawable.flicker_on)
                    addActivatedFeature(recyclerView, FEATURE.FLICKERING)
                }
                else {
                    stopFlickering(Token.FLICKER)
                    tempText = "$flickerFlashlightHz Hz"
                    setTextAndColor(flickerSwitchText, tempText, R.color.greyNoteDarker2)
                    flickerImageIcon.setImageResource(R.drawable.flicker_off)
                    removeActivatedFeature(recyclerView, FEATURE.FLICKERING)
                }
            }
            else {
                // user should be asked for permissions again
                flickerImageIcon.setImageResource(R.drawable.flicker_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.FLICKERING)
                flickerSwitch.isChecked = false
                showDisclosureDialog(ACTION.FLASHLIGHT)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // incoming sound handler
        val soundExpandArrow: ImageButton = findViewById(R.id.soundExpandArrow)
        val soundHiddenView: LinearLayout = findViewById(R.id.soundHiddenView)

        tempText = "Sensitivity\n${calcSensitivityLevel(sensitivitySoundThreshold)}"
        setTextAndColor(soundSwitchText, tempText, R.color.greyNoteDarker2)
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
        soundBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivitySoundThreshold = defaultSoundSenseLevel - (defaultSoundSenseLevel - minSoundSenseLevel) / 9 * progress
                tempText = "Sensitivity\n${calcSensitivityLevel(sensitivitySoundThreshold)}"
                soundSwitchText.text = tempText
                // Log.i("MainActivity","Sensitivity $progress, $sensitivitySoundThreshold")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Log.i("MainActivity","Sensitivity onStopTrackingTouch, $sensitivitySoundThreshold")
                // Do nothing
            }
        })

        incomingSoundSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["AUDIO"] == true) {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

                if (!isChecked) {
                    Log.i("MainActivity","incomingSoundSwitch is OFF ($audioRecordHandler, $recordingThread)")
                    resetFeature(Token.SOUND)
                }
                else {
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
                    Log.i("MainActivity","incomingSoundSwitch is ON ($audioRecordHandler, $recordingThread)")
                }
            }
            else {
                // user should be asked for permissions again
                resetFeature(Token.SOUND)
                soundImageIcon.setImageResource(R.drawable.sound_no_permission)
                showDisclosureDialog(ACTION.AUDIO)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // tilt phone handler
        initSensorManager()
        val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val tiltExpandArrow: ImageButton = findViewById(R.id.tiltExpandArrow)
        val tiltHiddenView: LinearLayout = findViewById(R.id.tiltHiddenView)
        tempText = "Angle ${sensitivityAngle}\u00B0"
        setTextAndColor(tiltSwitchText, tempText, R.color.greyNoteDarker2)

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
        tiltBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityAngle = defaultMinTiltAngle + (defaultMaxTiltAngle - defaultMinTiltAngle) / 9 * progress
                tempText = "Angle ${sensitivityAngle}\u00B0"
                tiltSwitchText.text = tempText
                // Log.i("MainActivity","Angle $progress, $sensitivityAngle")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                try {
                    if (rotationSensorRegistered) {
                        sensorManager.unregisterListener(sensorRotationEventListener)
                        rotationSensorRegistered = false
                        Log.i("MainActivity","unregisterListener sensorRotationEventListener $sensorRotationEventListener")
                    }
                }
                catch (_: Exception) {}
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (accelerometerSensor != null && incomingTiltSwitch.isChecked and !rotationSensorRegistered) {
                    registerRotationSensor(accelerometerSensor)
                    rotationSensorRegistered = true
                }
            }
        })

        incomingTiltSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["FLASHLIGHT"] == true) {
                if (isChecked) {
                    if (accelerometerSensor != null) {
                        Log.i("MainActivity","incomingTiltSwitch is ON")
                        resetAllActivities(Token.TILT)
                        registerRotationSensor(accelerometerSensor)
                        rotationSensorRegistered = true
                        isPhoneTilt = true
                        tempText = "Angle ${sensitivityAngle}\u00B0"
                        setTextAndColor(tiltSwitchText, tempText, R.color.blueText)
                        tiltImageIcon.setImageResource(R.drawable.tilt_on)
                        addActivatedFeature(recyclerView, FEATURE.TILT)
                    }
                    else {
                        // we have to disable the btn now since rotation sensor is not available on the device
                        Log.i("MainActivity","Accelerometer not available")
                        resetFeature(Token.TILT)
                        tiltImageIcon.setImageResource(R.drawable.tilt_no_permission)
                        showDisclosureDialog(ACTION.FLASHLIGHT)
                    }
                } else {
                    Log.i("MainActivity","incomingTiltSwitch is OFF ($sensorRotationEventListener)")
                    resetFeature(Token.TILT)
                }
            }
            else {
                // user should be asked for permissions again
                resetFeature(Token.TILT)
                tiltImageIcon.setImageResource(R.drawable.tilt_no_permission)
                showDisclosureDialog(ACTION.FLASHLIGHT)
            }
        }


        ///////////////////////////////////////////////////////////////////////////////////////
        // phone out/in network handler
        outInNetworkSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["FLASHLIGHT"] == true) {
                if (!isChecked) {
                    // User wants to disable the feature
                    Log.i("MainActivity","outInNetworkSwitch is OFF (unregister $connectivityCallback)")
                    resetFeature(Token.NETWORK)
                }
                else {
                    val networkRequest = NetworkRequest.Builder().build()
                    connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

                    // Check if network is currently available first
                    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    isNetworkConnectivityCbIsSet = true
                    if (!isConnected) {
                        Log.i("MainActivity", "NETWORK is right now UNAVAILABLE")
                        isPhoneOutOfNetwork = true
                        registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                        networkImageIcon.setImageResource(R.drawable.network_lost_to_found)
                        addActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
                        triggerSnackbar(rootView, "Flashlight will be flickering when Internet Connection (Mobile Data or Wi-Fi) is found.", ACTION.INFO)
                    }
                    else {
                        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                            override fun onUnavailable () {
                                super.onUnavailable()
                                Log.i("MainActivity", "NETWORK is currently UNAVAILABLE (unregister $connectivityCallback)")
                                isPhoneOutOfNetwork = true
                                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                                registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                                networkImageIcon.setImageResource(R.drawable.network_lost_to_found)
                                triggerSnackbar(rootView, "Flashlight will be flickering when Internet Connection (Mobile Data or Wi-Fi) is found.", ACTION.INFO)
                            }
                            override fun onLost(network: Network) {
                                super.onLost(network)
                                Log.i("MainActivity", "NETWORK is currently LOST (unregister $connectivityCallback)")
                                isPhoneOutOfNetwork = true
                                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                                registerIncomingEvents(TypeOfEvent.IN_SERVICE)
                                networkImageIcon.setImageResource(R.drawable.network_lost_to_found)
                                triggerSnackbar(rootView, "Flashlight will be flickering when Internet Connection (Mobile Data or Wi-Fi) is found.", ACTION.INFO)
                            }
                            override fun onAvailable(network: Network) {
                                super.onAvailable(network)
                                Log.i("MainActivity", "NETWORK is currently AVAILABLE (unregister $connectivityCallback)")
                                isPhoneInNetwork = true
                                connectivityManager.unregisterNetworkCallback(connectivityCallback)
                                registerIncomingEvents(TypeOfEvent.OUT_OF_SERVICE)
                                networkImageIcon.setImageResource(R.drawable.network_found_to_lost)
                                triggerSnackbar(rootView, "Flashlight will be flickering when Internet Connection (Mobile Data or Wi-Fi) is lost", ACTION.INFO)
                            }
                        }
                        Log.i("MainActivity", "Register CB $connectivityCallback")
                        connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
                        addActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                    }
                    Log.i("MainActivity","outInNetworkSwitch is ON")
                }
            }
            else {
                // user should be asked for permissions again
                networkImageIcon.setImageResource(R.drawable.network_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                removeActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
                outInNetworkSwitch.isChecked = false
                showDisclosureDialog(ACTION.FLASHLIGHT)
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // battery handler
        val batteryExpandArrow: ImageButton = findViewById(R.id.batteryExpandArrow)
        val batteryHiddenView: LinearLayout = findViewById(R.id.batteryHiddenView)
        tempText = "${batteryThreshold}%"
        setTextAndColor(batterySwitchText, tempText, R.color.greyNoteDarker2)

        // Expand or hide the main content
        batteryExpandArrow.setOnClickListener {
            // Toggle the visibility of the content view
            if (batteryHiddenView.visibility == View.VISIBLE) {
                batteryHiddenView.visibility = View.GONE
                batteryExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                batteryHiddenView.visibility = View.VISIBLE
                batteryExpandArrow.setImageResource(R.drawable.arrow_up)
                readCurrentBatteryLevel = !batterySwitch.isChecked
                initAndRegisterBatteryReceiver()
            }
        }

        // Bar listeners
        batteryBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                batteryThreshold = progress
                tempText = "${batteryThreshold}%"
                batterySwitchText.text = tempText
                // Log.i("MainActivity","batteryThreshold $progress, $batteryThreshold")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                resetFeature(Token.BATTERY)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (batterySwitch.isChecked) {
                    initAndRegisterBatteryReceiver()
                }
            }
        })

        batterySwitch.setOnCheckedChangeListener {_, isChecked ->
            if (permissionsKeys["FLASHLIGHT"] == true) {
                if (isChecked) {
                    Log.i("MainActivity","batterySwitch is ON")
                    isBatteryOn = true
                    initAndRegisterBatteryReceiver()
                    addActivatedFeature(recyclerView, FEATURE.BATTERY)
                    batteryImageIcon.setImageResource(R.drawable.battery_on)
                    tempText = "${batteryThreshold}%"
                    setTextAndColor(batterySwitchText, tempText, R.color.blueText)
                }
                else {
                    Log.i("MainActivity","batterySwitch is OFF (unregister $batteryReceiver")
                    resetFeature(Token.BATTERY)
                }
            }
            else {
                // user should be asked for permissions again
                batteryImageIcon.setImageResource(R.drawable.battery_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
                batterySwitch.isChecked = false
                showDisclosureDialog(ACTION.FLASHLIGHT)
            }
        }
        ///////////////////////////////////////////////////////////////////////////////////////
        // timer handler
        val timerExpandArrow: ImageButton = findViewById(R.id.timerExpandArrow)
        val timerHiddenView: LinearLayout = findViewById(R.id.timerHiddenView)
        val timerTimePicker = findViewById<TimePicker>(R.id.timePicker1)
        var selectedTime = ""
        var hourOfDayTimer = 0
        var minuteTimer = 0
        // timerTimePicker.setIs24HourView(true)

        tempText = "--:--"
        setTextAndColor(timerSwitchText, tempText, R.color.greyNoteDarker2)

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
                        triggerSnackbar(rootView, "You have to deactivate the service first, to select another timestamp.")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Apps targeting lower sdk versions than Android 12, API 31, can always schedule exact alarms.
            permissionsKeys["TIMER"] = true
        }

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: ActivityResult ->
            permissionsKeys["TIMER"] = result.resultCode == Activity.RESULT_OK
        }

        timerSwitch.setOnCheckedChangeListener {_, isChecked ->
            // Initialize AlarmManager
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Perform actions specific to Android 12 (API level 31) or later
                if (!alarmManager.canScheduleExactAlarms()) {
                    triggerSnackbar(rootView, "You have to provide Alarm rights to Flashii to run the service. See phone's Settings.")
                }
                else {


                    if (ContextCompat.checkSelfPermission(this,Manifest.permission.SCHEDULE_EXACT_ALARM) != PackageManager.PERMISSION_GRANTED) {
                        // Permission is not granted, request it
                        requestPermissionLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        )
                    } else {
                        // Permission is already granted, proceed with using it
                        permissionsKeys["TIMER"] = true
                        Log.i("MainActivity","PERMISSION_GRANTED TIMER TRUE")
                    }
                }
            }
            else {
                permissionsKeys["TIMER"] = true
            }

            if (permissionsKeys["TIMER"] == true) {
                if (isChecked) {
                    timerTimePicker.isEnabled = true
                    if (hourOfDayTimer == 0 && minuteTimer == 0) {
                        Log.i("MainActivity","Time error: set to past time")
                        triggerSnackbar(rootView, "Have to select a time first, before enabling the service.")
                        timerSwitch.isChecked = false
                    }
                    else {
                        calcTimeToFlickerInMillis = calcTimeToFlicker(hourOfDayTimer, minuteTimer)
                        if (calcTimeToFlickerInMillis < 0) {
                            Log.i("MainActivity","Time error: set to past time ($hourOfDayTimer, $minuteTimer)")
                            timerSwitch.isChecked = false
                        }
                        else {
                            isTimerOn = true
                            timerImageIcon.setImageResource(R.drawable.timer_on)
                            timerSwitchText.setTextColor(resources.getColor(R.color.blueText, theme))
                            addActivatedFeature(recyclerView, FEATURE.TIMER)
                            timerForFlickeringSet = true

                            // Create an intent to trigger the alarm
                            val intent = Intent("com.ix8ys.flashii.ALARM_ACTION")
                            alarmIntent = PendingIntent.getBroadcast(
                                this,
                                0,
                                intent,
                                PendingIntent.FLAG_IMMUTABLE
                            )

                            // Schedule the alarm to run after one hour
                            val triggerTimeMillis = SystemClock.elapsedRealtime() + calcTimeToFlickerInMillis
                            alarmManager.setExact(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                triggerTimeMillis,
                                alarmIntent
                            )

                            // Register the BroadcastReceiver
                            val filter = IntentFilter("com.ix8ys.flashii.ALARM_ACTION")
                            registerReceiver(alarmReceiver, filter)

                            // user can no longer interact with the timepicker
                            timerTimePicker.isEnabled = false
                            snackBarTimeSelected(calcTimeToFlickerInMillis)
                            Log.i("MainActivity", "TIME ON at $selectedTime or $calcTimeToFlickerInMillis ms")
                        }
                    }
                }
                else {
                    Log.i("MainActivity", "TIME OFF")
                    resetFeature(Token.TIMER)
                    timerTimePicker.isEnabled = true
                }
            }
            else {
                // user should be asked for permissions again
                timerImageIcon.setImageResource(R.drawable.timer_no_permission)
                removeActivatedFeature(recyclerView, FEATURE.TIMER)
                timerSwitch.isChecked = false
            }
        }

        ///////////////////////////////////////////////////////////////////////////////////////
        // altitude handler
        val altitudeExpandArrow: ImageButton = findViewById(R.id.altitudeExpandArrow)
        val altitudeHiddenView: LinearLayout = findViewById(R.id.altitudeHiddenView)
        tempText = "${altitudeThreshold}m"
        setTextAndColor(altitudeSwitchText, tempText, R.color.greyNoteDarker2)

        // Expand or hide the main content
        altitudeExpandArrow.setOnClickListener {
            if (altitudeHiddenView.visibility == View.VISIBLE) {
                altitudeHiddenView.visibility = View.GONE
                altitudeExpandArrow.setImageResource(R.drawable.arrow_down)
            } else {
                altitudeHiddenView.visibility = View.VISIBLE
                altitudeExpandArrow.setImageResource(R.drawable.arrow_up)
                if (barometerAvailable) {
                    readCurrentAltitude = !altitudeSwitch.isChecked
                    initAndRegisterAltitudeEventListener()
                }
            }
        }

        // Bar listeners
        altitudeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                altitudeThreshold = minAltitude + ((maxAltitude - minAltitude).toFloat()/1000 * progress).toInt()
                tempText = "${altitudeThreshold}m"
                altitudeSwitchText.text = tempText
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                resetFeature(Token.ALTITUDE)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (barometerAvailable && altitudeSwitch.isChecked) {
                    initAndRegisterAltitudeEventListener()
                }
            }
        })

        altitudeSwitch.setOnCheckedChangeListener {_, isChecked ->
            if (barometerAvailable) {
                if (isChecked) {
                    initAndRegisterAltitudeEventListener()
                    if (barometerAvailable) {
                        Log.i("MainActivity","altitudeSwitch is ON ($sensorPressureEventListener)")
                        isAltitudeOn = true
                        addActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                        altitudeImageIcon.setImageResource(R.drawable.altitude_on)
                        tempText = "${altitudeThreshold}m"
                        setTextAndColor(altitudeSwitchText, tempText, R.color.blueText)
                    }
                } else {
                    Log.i("MainActivity","altitudeSwitch is OFF ($sensorPressureEventListener)")
                    resetFeature(Token.ALTITUDE)
                }
            }
            else {
                triggerSnackbar(rootView, "There is no barometer sensor available. Service is not feasible in this phone device.")
                resetFeature(Token.ALTITUDE)
                altitudeImageIcon.setImageResource(R.drawable.altitude_no_permission)
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
                flickeringBar.max = maxFlickerHz
                if (isFlickeringOnDemand && flickerFlashlightHz > maxFlickerHz) {
                    // user has set a flickering Hz way lower than the running one; so we have to adapt to it
                    flickerFlashlightHz = maxFlickerHz.toLong()
                }

                maxFlickerDurationBattery = data?.getIntExtra("maxFlickerDurationBattery", maxFlickerDurationBattery) ?: maxFlickerDurationBattery
                maxFlickerDurationAltitude = data?.getIntExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude) ?: maxFlickerDurationAltitude

                Log.i("MainActivity", "Data from Settings are: $maxFlickerHz, $maxFlickerDurationBattery,$maxFlickerDurationAltitude")

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
            catch (_: Exception) {
                Log.e("RateActivity", "Probably no service bind with Google Play")
            }
        }

        ////////////////////////////////////////////////////////////////////////////////////////
        // support button
        supportBtn = findViewById(R.id.supportBtnId)
        supportBtn.setOnClickListener {
            val intent = Intent(this, SupportActivity::class.java)
            startActivity(intent)
        }


        /////////////////////////////////////////////////////////////////////////////////
        // Native Ad
        MobileAds.initialize(this)
        // ca-app-pub-3940256099942544/1044960115
        //
        adLoader = AdLoader.Builder(this, "ca-app-pub-1341475020557565/3243868836")
            .forNativeAd { nativeAd ->
                val template: TemplateView = findViewById(R.id.native_ad)
                template.setNativeAd(nativeAd)
            }
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }


    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////


    private fun showDisclosureDialog(action : ACTION) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.disclosure)

        val btnDeny = dialog.findViewById<Button>(R.id.btnDeny)
        val btnAccept = dialog.findViewById<Button>(R.id.btnAccept)
        val disclosureText = dialog.findViewById<TextView>(R.id.disclosureText)
        val flashlightFeatureList = dialog.findViewById<LinearLayout>(R.id.flashlightFeatureList)

        when (action) {
            ACTION.FLASHLIGHT -> {
                flashlightFeatureList.visibility = View.VISIBLE
            }
            ACTION.TIMER -> {
                disclosureText.text = getString(R.string.disclosure_timer)
            }
            ACTION.AUDIO -> {
                disclosureText.text = getString(R.string.disclosure_audio)
            }
            else -> {}
        }

        btnDeny.setOnClickListener {
            // Handle deny action
            dialog.dismiss()
        }

        btnAccept.setOnClickListener {
            checkPermissions(action)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun initAndRegisterAltitudeEventListener() {
        if (::sensorPressureEventListener.isInitialized && altitudeListenerRegistered) {
            // already initialized; do nothing
        }
        else {
            initSensorManager()
            val altitudeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (altitudeSensor != null) {
                sensorPressureEventListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.sensor?.type == Sensor.TYPE_PRESSURE) {
                            val pressureValue = event.values[0] // Get the pressure value in hPa
                            val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureValue) // altitude in meters

                            if (altitude >= minAltitude) {
                                if (initAltitudeLevel == minAltitude || readCurrentAltitude) {
                                    initAltitudeLevel = altitude.toInt()
                                    tempText = "${initAltitudeLevel}m"
                                    altitudeSwitchText.text = tempText
                                    altitudeBar.progress = ((altitude - minAltitude) * 1000 / (maxAltitude - minAltitude)).toInt()
                                    Log.d("MainActivity", "initAltitudeLevel set to ${initAltitudeLevel}m")
                                }

                                if (altitudeSwitch.isChecked && altitudeListenerRegistered) {
                                    if (altitudeThreshold > initAltitudeLevel) {
                                        // In case User is ascending in height
                                        if (altitude > altitudeThreshold) {
                                            if (!isFlickering) {
                                                Log.i("MainActivity", "Flickering ON while ascending to altitude of ${altitude}m")
                                                resetActivitiesAndFlicker(Token.ALTITUDE)
                                                stopFlickeringAfterTimeout(maxFlickerDurationAltitude.toLong(), Token.ALTITUDE)
                                                loopHandlerAltitude.postDelayed({ resetFeature(Token.ALTITUDE)}, maxFlickerDurationAltitude.toLong())
                                                sensorManager.unregisterListener(sensorPressureEventListener)
                                                triggerSnackbar(rootView, "Altitude Height ${altitudeThreshold}m has been reached.", ACTION.SUCCESS, Token.ALTITUDE)
                                            }
                                        }
                                    }
                                    else {
                                        // In case User is descending in height
                                        if (altitude < altitudeThreshold) {
                                            if (!isFlickering) {
                                                Log.i("MainActivity", "Flickering ON while descending to altitude of ${altitude}m")
                                                resetActivitiesAndFlicker(Token.ALTITUDE)
                                                stopFlickeringAfterTimeout(maxFlickerDurationAltitude.toLong(), Token.ALTITUDE)
                                                loopHandlerAltitude.postDelayed({ resetFeature(Token.ALTITUDE)}, maxFlickerDurationAltitude.toLong())
                                                sensorManager.unregisterListener(sensorPressureEventListener)
                                                triggerSnackbar(rootView, "Altitude Height ${altitudeThreshold}m has been reached.", ACTION.SUCCESS, Token.ALTITUDE)
                                            }
                                        }
                                    }
                                }
                                else if (readCurrentAltitude || altitudeListenerRegistered) {
                                    Log.i("MainActivity","Should unregistered sensorPressureEventListener $sensorPressureEventListener")
                                    resetFeature(Token.ALTITUDE)
                                }
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                        // Do nothing
                    }
                }
                if (!altitudeListenerRegistered) {
                    Log.i("MainActivity","registered sensorPressureEventListener $sensorPressureEventListener")
                    altitudeListenerRegistered = true
                    sensorManager.registerListener(sensorPressureEventListener, altitudeSensor, SensorManager.SENSOR_DELAY_NORMAL)
                }

                if (altitudeSwitch.isChecked) {
                    Log.i("MainActivity","altitudeSwitch is ON ($sensorPressureEventListener)")
                    isAltitudeOn = true
                    addActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                    altitudeImageIcon.setImageResource(R.drawable.altitude_on)
                    tempText = "${altitudeThreshold}m"
                    setTextAndColor(altitudeSwitchText, tempText, R.color.blueText)
                }
            }
            else {
                // we have to disable the btn now since sensor is not available on the device
                Log.i("MainActivity","Barometer not available for Altitude service")
                triggerSnackbar(rootView, "This phone device has no barometer sensor available; service is not feasible.")
                resetFeature(Token.ALTITUDE)
                altitudeImageIcon.setImageResource(R.drawable.altitude_no_permission)
                barometerAvailable = false
            }
        }
    }

    private fun calcSensitivityLevel (sensitivitySoundThreshold : Int) : String {
        val tempProgress = 9 * (defaultSoundSenseLevel - sensitivitySoundThreshold) / (defaultSoundSenseLevel - minSoundSenseLevel)
        val result = if (tempProgress > 7) {
            "Highest"
        }
        else if (tempProgress in 5..7) {
            "High"
        }
        else if (tempProgress in 2..4) {
            "Medium"
        }
        else {
            "Low"
        }
        return result
    }


    private fun initSensorManager () {
        if (::sensorManager.isInitialized) {
            // already initialized; do nothing
        }
        else {
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        }
    }

    private fun registerRotationSensor(accelerometerSensor : Sensor) {
        var firstPositionReached = false
        sensorRotationEventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientationAngles = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val angleInDegrees = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()

                    if (angleInDegrees > (sensitivityAngle.toFloat() - 90) && !firstPositionReached) {
                        // Phone rotated to the left for sensitivityAngle.toFloat() degrees
                        firstPositionReached = true
                    } else if (angleInDegrees < -80 && firstPositionReached) {
                        // Phone returned to portrait orientation
                        firstPositionReached = false
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
                // Do nothing
            }
        }
        Log.i("MainActivity","sensorRotationEventListener initialized ($sensorRotationEventListener)")
        sensorManager.registerListener(sensorRotationEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun initAndRegisterBatteryReceiver() {
        if (::batteryReceiver.isInitialized && batteryReceiverRegistered) {
            // already initialized; do nothing
            Log.i("MainActivity","batteryReceiver do nothing")
        }
        else {
            batteryReceiverRegistered = true
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (initBatteryLevel == minBattery || readCurrentBatteryLevel) {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        initBatteryLevel = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                        Log.i("MainActivity", "Battery initial level is ${initBatteryLevel}%")
                        if (!batterySwitch.isChecked) {
                            Log.i("MainActivity", "Should now resetFeature(Battery)")
                            batteryThreshold = initBatteryLevel
                            tempText = "${batteryThreshold}%"
                            batterySwitchText.text = tempText
                            batteryBar.progress = batteryThreshold
                            resetFeature(Token.BATTERY)
                        }
                    }

                    if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val batteryPercentage = (level.toFloat() / scale.toFloat()) * 100
                        // Log.i("MainActivity", "batteryThreshold = $batteryThreshold,  initBatteryLevel = $initBatteryLevel, batteryPercentage.toInt() = ${batteryPercentage.toInt()} ")
                        if (batteryThreshold > initBatteryLevel) {
                            // So the user is charging his phone and wants an optical indication when threshold is reached
                            if (batteryPercentage.toInt() >= batteryThreshold) {
                                Log.i("MainActivity", "Battery has been charged up to ${batteryPercentage}% (initBatteryLevel $initBatteryLevel, batteryThreshold $batteryThreshold)")
                                resetActivitiesAndFlicker(Token.BATTERY)
                                // should stop flickering and reset after time
                                stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong(), Token.BATTERY)
                                loopHandlerBattery.postDelayed({ resetFeature(Token.BATTERY)}, maxFlickerDurationBattery.toLong())
                                // Should unregister
                                Log.i("MainActivity", "unregisterReceiver batteryReceiver $batteryReceiver")
                                unregisterReceiver(batteryReceiver)
                                initBatteryLevel = minBattery
                                triggerSnackbar(rootView, "Battery Power charged up to ${batteryPercentage.toInt()}%.", ACTION.SUCCESS, Token.BATTERY)
                            }
                        }
                        else {
                            // So the phone is discharged and user wants an optical indication when threshold is reached
                            if (batteryPercentage.toInt() < batteryThreshold) {
                                Log.i("MainActivity", "Battery is discharged to ${batteryPercentage}% (initBatteryLevel $initBatteryLevel, batteryThreshold $batteryThreshold)")
                                resetActivitiesAndFlicker(Token.BATTERY)
                                // should stop flickering and reset after time
                                stopFlickeringAfterTimeout(maxFlickerDurationBattery.toLong(), Token.BATTERY)
                                loopHandlerBattery.postDelayed({ resetFeature(Token.BATTERY)}, maxFlickerDurationBattery.toLong())
                                // Should unregister
                                Log.i("MainActivity", "unregisterReceiver batteryReceiver $batteryReceiver")
                                unregisterReceiver(batteryReceiver)
                                initBatteryLevel = minBattery
                                triggerSnackbar(rootView, "Battery Power discharged to ${batteryPercentage.toInt()}%.", ACTION.SUCCESS, Token.BATTERY)
                            }
                        }
                    }
                }
            }
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            Log.i("MainActivity","batteryReceiver initialized ($batteryReceiver)")
        }
    }

    private fun snackBarTimeSelected(calcTimeToFlickerInMillis: Long) {
        val calcTimeToFlickerInSeconds = calcTimeToFlickerInMillis / 1000
        var calcTimeToFlickerInMinutes = calcTimeToFlickerInSeconds / 60
        val calcTimeToFlickerInHours = calcTimeToFlickerInMinutes / 60
        if (calcTimeToFlickerInHours > 0) {
            calcTimeToFlickerInMinutes -=  calcTimeToFlickerInHours * 60
            val hours = if (calcTimeToFlickerInHours > 1) {"hours"} else {"hour"}
            if (calcTimeToFlickerInMinutes > 0) {
                val minutes = if (calcTimeToFlickerInMinutes > 1) {"minutes"} else {"minute"}
                triggerSnackbar(rootView, "Flashlight will flicker after $calcTimeToFlickerInHours $hours: $calcTimeToFlickerInMinutes $minutes.", ACTION.INFO)
            } else {
                triggerSnackbar(rootView, "Flashlight will flicker after $calcTimeToFlickerInHours $hours.", ACTION.INFO)
            }
        }
        else if (calcTimeToFlickerInMinutes > 0) {
            triggerSnackbar(rootView, "Flashlight will flicker after $calcTimeToFlickerInMinutes minutes.", ACTION.INFO)
        }
        else {
            triggerSnackbar(rootView, "Flashlight will flicker after $calcTimeToFlickerInSeconds seconds.", ACTION.INFO)
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

    fun resetFeature (token : Token) {
        Log.i("MainActivity", "resetFeature for $token")
        when (token) {
            Token.BATTERY -> {
                isBatteryOn = false
                if (flickeringDueToBattery) {
                    stopFlickering(Token.BATTERY)
                }
                try {
                    batteryReceiverRegistered = false
                    loopHandlerBattery.removeCallbacksAndMessages(null)
                    Log.i("MainActivity", "removeCallbacksAndMessages $loopHandlerBattery")
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception loopHandlerBattery")
                }
                try {
                    unregisterReceiver(batteryReceiver)
                    Log.i("MainActivity", "unregisterReceiver batteryReceiver $batteryReceiver")
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception batteryReceiver")
                }

                removeActivatedFeature(recyclerView, FEATURE.BATTERY)
                batteryImageIcon.setImageResource(R.drawable.battery_off)
                batterySwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                batterySwitch.isChecked = false
                readCurrentBatteryLevel = false
            }
            Token.NETWORK -> {
                isNetworkConnectivityCbIsSet = false
                if (flickeringDueToNetworkConnection) {
                    stopFlickering(Token.NETWORK)
                }
                try {
                    connectivityManager.unregisterNetworkCallback(connectivityCallback)
                    Log.i("MainActivity", "unregisterReceiver connectivityCallback $connectivityCallback")
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception connectivityCallback")
                }
                try {
                    loopHandlerNetwork.removeCallbacksAndMessages(null)
                    Log.i("MainActivity", "removeCallbacksAndMessages $loopHandlerNetwork")
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception loopHandlerNetwork")
                }

                removeActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                removeActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
                networkImageIcon.setImageResource(R.drawable.network_off)
                outInNetworkSwitch.isChecked = false
            }
            Token.TIMER -> {
                isTimerOn = false
                if (flickeringDueToTimer) {
                    stopFlickering(Token.TIMER)
                }
                isTimerThresholdSet = false
                timerSetAfter = minTimerMinutes

                removeActivatedFeature(recyclerView, FEATURE.TIMER)
                timerImageIcon.setImageResource(R.drawable.timer_off)
                timerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                timerSwitch.isChecked = false
                timerForFlickeringSet = false
            }
            Token.ALTITUDE -> {
                isAltitudeOn = false
                try {
                    sensorManager.unregisterListener(sensorPressureEventListener)
                    Log.i("MainActivity", "unregisterReceiver sensorPressureEventListener $sensorPressureEventListener")
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception sensorPressureEventListener")
                }
                try {
                    altitudeListenerRegistered = false
                    loopHandlerAltitude.removeCallbacksAndMessages(null)
                    Log.i("MainActivity", "removeCallbacksAndMessages $loopHandlerAltitude")
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception loopHandlerAltitude")
                }

                if (flickeringDueToAltitude) {
                    stopFlickering(Token.ALTITUDE)
                }
                removeActivatedFeature(recyclerView, FEATURE.ALTITUDE)
                altitudeImageIcon.setImageResource(R.drawable.altitude_off)
                altitudeSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                altitudeSwitch.isChecked = false
                readCurrentAltitude = false
            }
            Token.SOUND -> {
                isAudioIncoming = false
                turnOffFlashlight()
                try {
                    audioRecordHandler.stop()
                    audioRecordHandler.release()
                    recordingThread?.interrupt()
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception Token.SOUND1")
                }
                recordingThread?.join()
                recordingThread = null
                try {
                    loopHandlerFlickering.removeCallbacksAndMessages(null)
                }
                catch (_: Exception) {
                    Log.e("MainActivity", "exception Token.SOUND2")
                }

                soundImageIcon.setImageResource(R.drawable.sound_off)
                soundSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                removeActivatedFeature(recyclerView, FEATURE.AUDIO)
                incomingSoundSwitch.isChecked = false
            }
            Token.SOS -> {
                isSendSOS = false
                stopSOS()
                sosImageIcon.setImageResource(R.drawable.sos_off)
                removeActivatedFeature(recyclerView, FEATURE.SOS)
                sosSwitch.isChecked = false
            }
            Token.TILT -> {
                turnOffFlashlight()
                try {
                    sensorManager.unregisterListener(sensorRotationEventListener)
                    Log.i("MainActivity", "unregisterReceiver sensorRotationEventListener $sensorRotationEventListener")
                }
                catch (_: Exception) { Log.e("MainActivity", "exception Token.TILT") }
                incomingTiltSwitch.isChecked = false
                isPhoneTilt = false
                rotationSensorRegistered = false
                tempText = "Angle ${sensitivityAngle}\u00B0"
                setTextAndColor(tiltSwitchText, tempText, R.color.greyNoteDarker2)
                tiltImageIcon.setImageResource(R.drawable.tilt_off)
                removeActivatedFeature(recyclerView, FEATURE.TILT)
            }
            Token.FLICKER -> {
                stopFlickering(Token.FLICKER)
                flickerSwitchText.setTextColor(resources.getColor(R.color.greyNoteDarker2, theme))
                flickerImageIcon.setImageResource(R.drawable.flicker_off)
                removeActivatedFeature(recyclerView, FEATURE.FLICKERING)
                flickerSwitch.isChecked = false
            }
            Token.FLASHLIGHT -> {
                turnOffFlashlight(true)
                removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
            }
            else -> {}
        }

    }

//    private fun timeExecutorShutdown (timerExecutor: ScheduledExecutorService) {
//        val backgroundThread = Thread {
//            Log.i("MainActivity", "timerExecutor shutdown $timerExecutor")
//            timerExecutor.shutdown()
//
//            try {
//                // Wait for previously submitted tasks to finish or a timeout occurs
//                if (!timerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
//                    // Timeout occurred; force shutdown if needed
//                    timerExecutor.shutdownNow()
//                    Log.i("MainActivity", "timerExecutor was shutdownNow")
//                }
//            } catch (e: InterruptedException) {
//                Log.e("MainActivity", "timerExecutor $e")
//            }
//        }
//        backgroundThread.start()
//    }

    private fun addActivatedFeature (recyclerView : RecyclerView, feature: FEATURE) {
        itemList.add(feature.value)
        recyclerView.adapter?.notifyItemInserted(itemList.size - 1)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun removeActivatedFeature (recyclerView: RecyclerView, feature: FEATURE) {
        try {
            itemList.removeIf { item -> item == feature.value }
            recyclerView.adapter?.notifyDataSetChanged()
        }
        catch (_: Exception) { Log.e("MainActivity", "exception removeActivatedFeature") }
    }

    private fun setSettingsIntent () {
        // set intent for Settings activity
        intentSettings = Intent(this, SettingsActivity::class.java)
        intentSettings.putExtra("maxFlickerHz", maxFlickerHz)
        intentSettings.putExtra("maxFlickerDurationBattery", maxFlickerDurationBattery)
        intentSettings.putExtra("maxFlickerDurationAltitude", maxFlickerDurationAltitude)
    }

    private fun isStoredSettings () : Boolean {
        sharedPref = getSharedPreferences("FlashiiSettings", MODE_PRIVATE)
        return sharedPref.contains("maxFlickerHz")
    }
    private fun retrieveStoredSettings () {
        flickerFlashlightHz = sharedPref.getInt("flickerFlashlightHz", defaultMaxFlickerHz).toLong() // default is 10
        sensitivitySoundThreshold = sharedPref.getInt("sensitivitySoundThreshold", defaultSoundSenseLevel) // default is 20000
        sensitivityAngle = sharedPref.getInt("sensitivityAngle", defaultTiltAngle) // default is 80

        // set seekbars accordingly
        flickeringBar.progress = flickerFlashlightHz.toInt()
        soundBar.progress = 9 * (defaultSoundSenseLevel - sensitivitySoundThreshold) / (defaultSoundSenseLevel - minSoundSenseLevel)
        tiltBar.progress = 9 * (sensitivityAngle - defaultMinTiltAngle) / (defaultMaxTiltAngle - defaultMinTiltAngle)

        maxFlickerHz = sharedPref.getInt("maxFlickerHz", defaultMaxFlickerHz) // max 10 - 100
        maxFlickerDurationBattery = sharedPref.getInt("maxFlickerDurationBattery", defaultMaxFlickerIncomingBattery)
        maxFlickerDurationAltitude = sharedPref.getInt("maxFlickerDurationAltitude", defaultMaxFlickerIncomingAltitude)
    }

    private fun storeSettings () {
        Log.i("MainActivity", "STORED Settings are: $flickerFlashlightHz (Hz), $maxFlickerHz (max Hz), $sensitivityAngle (degrees), $sensitivitySoundThreshold (sound),$maxFlickerDurationBattery,$maxFlickerDurationAltitude")
        val sharedPref = getSharedPreferences("FlashiiSettings", MODE_PRIVATE)
        val editor = sharedPref.edit()

        // settings User can configure with seekbars
        editor.putInt("flickerFlashlightHz", flickerFlashlightHz.toInt()) // flicker on demand
        editor.putInt("sensitivitySoundThreshold", sensitivitySoundThreshold) // finger-snap
        editor.putInt("sensitivityAngle", sensitivityAngle) // phone Tilt

        // settings User can configure in Settings
        editor.putInt("maxFlickerHz", maxFlickerHz) // max flickering Hz (3 - 100)
        editor.putInt("maxFlickerDurationBattery", maxFlickerDurationBattery)
        editor.putInt("maxFlickerDurationAltitude", maxFlickerDurationAltitude)
        editor.apply()
    }

    private fun preCheckPermissions (activity: ACTION) {
        when (activity) {
            ACTION.FLASHLIGHT -> {
                Log.i("MainActivity", "Ask for preCheckPermissions FLASHLIGHT (CAMERA)")
                // CAMERA
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "Ask for preCheckPermissions - NOT Granted for FLASHLIGHT (CAMERA)")
                }
                else {
                    Log.i("MainActivity", "Ask for preCheckPermissions - Granted for FLASHLIGHT (CAMERA)")
                    permissionsKeys["FLASHLIGHT"] = true
                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_off4)
                    setBtnImage(sosImageIcon, R.drawable.sos_off)
                    setBtnImage(flickerImageIcon, R.drawable.flicker_off)
                    setBtnImage(tiltImageIcon, R.drawable.tilt_off)
                    setBtnImage(networkImageIcon, R.drawable.network_off)
                    setBtnImage(batteryImageIcon, R.drawable.battery_off)
                }
            }
            ACTION.TIMER -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SCHEDULE_EXACT_ALARM) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "Ask for preCheckPermissions - NOT Granted for TIMER")
                }
                else {
                    Log.i("MainActivity", "Ask for preCheckPermissions - Granted for TIMER")
                    permissionsKeys["TIMER"] = true
                    setBtnImage(timerImageIcon, R.drawable.timer_off)
                }
            }
            ACTION.AUDIO -> {
                // AUDIO
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "Ask for preCheckPermissions - NOT Granted for  AUDIO")
                }
                else {
                    Log.i("MainActivity", "Ask for preCheckPermissions - Granted for AUDIO")
                    permissionsKeys["AUDIO"] = true
                    setBtnImage(soundImageIcon, R.drawable.sound_off)
                }
            }
            else -> {}
        }
    }
    private fun checkPermissions (activity: ACTION) {
        var permissions : Array<String> = arrayOf()
        when (activity) {
            ACTION.CREATE, ACTION.FLASHLIGHT -> {
                Log.i("MainActivity", "Ask for permissions FLASHLIGHT (CAMERA)")
                // CAMERA
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "requestPermissions for CAMERA")
                    permissions += Manifest.permission.CAMERA
                    ActivityCompat.requestPermissions(this, permissions, RequestKey.CREATE.value)
                }
                else {
                    Log.i("MainActivity", "requestPermissions CAMERA = TRUE")
                    permissionsKeys["FLASHLIGHT"] = true
                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_off4)
                    setBtnImage(sosImageIcon, R.drawable.sos_off)
                    setBtnImage(flickerImageIcon, R.drawable.flicker_off)
                    setBtnImage(tiltImageIcon, R.drawable.tilt_off)
                    setBtnImage(networkImageIcon, R.drawable.network_off)
                    setBtnImage(batteryImageIcon, R.drawable.battery_off)
                }
            }
            ACTION.AUDIO -> {
                // AUDIO
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "requestPermissions for AUDIO")
                    permissions += Manifest.permission.RECORD_AUDIO
                    ActivityCompat.requestPermissions(this, permissions, RequestKey.CREATE.value)
                }
                else {
                    Log.i("MainActivity", "requestPermissions AUDIO = TRUE")
                    permissionsKeys["AUDIO"] = true
                    setBtnImage(soundImageIcon, R.drawable.sound_off)
                }
            }
            ACTION.TIMER -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SCHEDULE_EXACT_ALARM) != PackageManager.PERMISSION_GRANTED) {
                    Log.i("MainActivity", "requestPermissions for TIMER")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissions += Manifest.permission.SCHEDULE_EXACT_ALARM
                        ActivityCompat.requestPermissions(this, permissions, RequestKey.CREATE.value)
                    } else {
                        permissionsKeys["TIMER"] = true
                    }
                }
                else {
                    Log.i("MainActivity", "requestPermissions TIMER = TRUE")
                    permissionsKeys["TIMER"] = true
                    setBtnImage(timerImageIcon, R.drawable.timer_off)
                }
            }
            ACTION.RESUME -> {
                // User may have changed the permissions in Settings/App/Flashii/Licenses, so we have to align with that
                Log.i("MainActivity", "Ask for permissions again RESUME")

                // CAMERA
                if (permissionsKeys["FLASHLIGHT"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "Ask for CAMERA permissions again RESUME ")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), RequestKey.FLASHLIGHT.value)
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "CAMERA permissions RESUME: CAMERA = FALSE ")
                        permissionsKeys["FLASHLIGHT"] = false
                        setBtnImage(flashlightImageIcon, R.drawable.flashlight_no_permission)
                        setBtnImage(sosImageIcon, R.drawable.sos_no_permission)
                        setBtnImage(flickerImageIcon, R.drawable.flicker_no_permission)
                        setBtnImage(tiltImageIcon, R.drawable.tilt_no_permission)
                        setBtnImage(networkImageIcon, R.drawable.network_no_permission)
                        setBtnImage(batteryImageIcon, R.drawable.battery_no_permission)

                        sosSwitch.isChecked = false
                        flickerSwitch.isChecked = false
                        incomingTiltSwitch.isChecked = false
                        outInNetworkSwitch.isChecked = false
                        batterySwitch.isChecked = false

                        removeActivatedFeature(recyclerView, FEATURE.FLASHLIGHT)
                        removeActivatedFeature(recyclerView, FEATURE.SOS)
                        removeActivatedFeature(recyclerView, FEATURE.FLICKERING)
                        removeActivatedFeature(recyclerView, FEATURE.TILT)
                        removeActivatedFeature(recyclerView, FEATURE.NETWORK_LOST)
                        removeActivatedFeature(recyclerView, FEATURE.NETWORK_FOUND)
                        removeActivatedFeature(recyclerView, FEATURE.BATTERY)
                    }
                }

                // TIMER
                if (permissionsKeys["TIMER"] == false) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.SCHEDULE_EXACT_ALARM) == PackageManager.PERMISSION_GRANTED) {
                        Log.i("MainActivity", "Ask for TIMER permissions again RESUME ")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SCHEDULE_EXACT_ALARM), RequestKey.TIMER.value)
                        }
                        else {
                            permissionsKeys["TIMER"] = true
                        }
                    }
                }
                else {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.SCHEDULE_EXACT_ALARM) != PackageManager.PERMISSION_GRANTED) {
                        setBtnImage(timerImageIcon, R.drawable.timer_no_permission)
                        Log.i("MainActivity", "CALL permissions RESUME: TIMER = FALSE ")
                        permissionsKeys["TIMER"] = false
                        timerSwitch.isChecked = false
                        removeActivatedFeature(recyclerView, FEATURE.TIMER)
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
                        tempText = "Sensitivity\n${calcSensitivityLevel(sensitivitySoundThreshold)}"
                        setTextAndColor(soundSwitchText, tempText, R.color.greyNoteDarker2)
                        incomingSoundSwitch.isChecked = false
                    }
                }
            }
            else -> {}
        }
    }

    private fun registerIncomingEvents (eventType : TypeOfEvent) {
        when (eventType) {
            TypeOfEvent.OUT_OF_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        Log.i("MainActivity", "Flickering ON due to NETWORK LOST")
                        resetActivitiesAndFlicker(Token.NETWORK)
                        stopFlickeringAfterTimeout(maxFlickerDuration30, Token.NETWORK)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                        triggerSnackbar(rootView, "Internet Connection is lost.", ACTION.SUCCESS, Token.NETWORK)
                        loopHandlerNetwork.postDelayed({ resetFeature(Token.NETWORK) }, (maxFlickerDuration30))
                    }
                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.i("MainActivity", "Flickering ON due to NETWORK UNAVAILABLE")
                        resetActivitiesAndFlicker(Token.NETWORK)
                        stopFlickeringAfterTimeout(maxFlickerDuration30, Token.NETWORK)
                        isPhoneOutOfNetwork = true
                        isPhoneInNetwork = false
                        triggerSnackbar(rootView, "Internet Connection is lost.", ACTION.SUCCESS, Token.NETWORK)
                        loopHandlerNetwork.postDelayed({ resetFeature(Token.NETWORK) }, (maxFlickerDuration30))
                    }}
                Log.i("MainActivity", "Register CB for OUT_OF_SERVICE ($connectivityCallback)")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
            }
            TypeOfEvent.IN_SERVICE -> {
                val networkRequest = NetworkRequest.Builder().build()
                connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.i("MainActivity", "Flickering ON due to NETWORK AVAILABLE")
                        resetActivitiesAndFlicker(Token.NETWORK)
                        stopFlickeringAfterTimeout(maxFlickerDuration30, Token.NETWORK)
                        isPhoneOutOfNetwork = false
                        isPhoneInNetwork = true
                        triggerSnackbar(rootView, "Internet Connection is found.", ACTION.SUCCESS, Token.NETWORK)
                        loopHandlerNetwork.postDelayed({ resetFeature(Token.NETWORK) }, (maxFlickerDuration30))
                    }}
                Log.i("MainActivity", "Register CB for IN_SERVICE ($connectivityCallback)")
                connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback)
            }
            TypeOfEvent.PHONE_TILT -> {
                // Do nothing
            }
            TypeOfEvent.AUDIO -> {
                // Do nothing
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////

    private fun setTextAndColor (textView : TextView, text : String, color : Int) {
        textView.text = text
        textView.setTextColor(resources.getColor(color, theme))
    }

    fun resetActivitiesAndFlicker (token: Token) {
        resetAllActivities(token)
        Log.i("MainActivity", "Flickering ON for $token")
        startFlickering(token)
        when (token) {
            Token.TIMER -> {
                triggerSnackbar(rootView, "Time ${timerSwitchText.text} is reached.", ACTION.SUCCESS, Token.TIMER)
            }
            else -> {}
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

    private fun stopFlickering(token: Token) {
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
            Log.i("MainActivity", "Flickering OFF for $token")
            isFlickering = false
            loopHandlerFlickering.removeCallbacksAndMessages(null)
            atomicFlashLightOff()
        }
    }

    fun startFlickering(token: Token) {
        var periodOfFlashLightInMilliseconds =  1000L // flicker with 1Hz as default
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
                periodOfFlashLightInMilliseconds =  1000 / flickerFlashlightHz
            }
            else -> {}
        }
        isFlickering = true
        atomicFlashLightOn()
        loopHandlerFlickering.postDelayed({ atomicFlashLightOff() }, (periodOfFlashLightInMilliseconds / 2))
        loopHandlerFlickering.postDelayed({ startFlickering(token) }, periodOfFlashLightInMilliseconds)
    }

    fun stopFlickeringAfterTimeout (timeout : Long, token: Token) {
        Log.i("MainActivity", "Flickering TIMEOUT set after ${timeout / 1000} seconds")
        loopHandlerFlickering.postDelayed({ stopFlickering(token) }, timeout)
    }

    @SuppressLint("MissingPermission")
    private fun atomicFlashLightOn () {
        try {
            cameraManager.setTorchMode(flashlightId, true)
        }
        catch (e : java.lang.IllegalArgumentException) {
            Log.e("MainActivity", "atomicFlashLightOn failed")
        }
    }

    private fun atomicFlashLightOff () {
        try {
            cameraManager.setTorchMode(flashlightId, false)
        }
        catch (e : java.lang.IllegalArgumentException) {
            Log.e("MainActivity", "atomicFlashLightOff failed")
        }
    }

    private fun turnOnFlashlight(setFlashlightBtn : Boolean = false) {
        if (!isFlashLightOn) {
            try {
                isFlashLightOn = true
                atomicFlashLightOn()
                if (setFlashlightBtn) {
                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_on6)
                }
                Log.i("MainActivity", "FlashLight ON")
            } catch (e: Exception) {
                Log.e("MainActivity", "FlashLight ON - ERROR: $e")
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
                Log.i("MainActivity", "FlashLight OFF")
            } catch (e: Exception) {
                Log.e("MainActivity", "FlashLight OFF - ERROR: $e")
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
        //Log.d("MainActivity", "setFlashLightAndCameraIds - flashlightId = $flashlightId")
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
        loopHandlerFlickering.removeCallbacksAndMessages(null)
        atomicFlashLightOff()
    }

    // Handle the permission request result
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission has been granted, so register the TelephonyCallback
            Log.i("MainActivity", "Request granted for $requestCode")
            when (requestCode) {
                RequestKey.FLASHLIGHT.value -> {
                    permissionsKeys["FLASHLIGHT"] = true
                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_off4)
                    setBtnImage(sosImageIcon, R.drawable.sos_off)
                    setBtnImage(flickerImageIcon, R.drawable.flicker_off)
                    setBtnImage(tiltImageIcon, R.drawable.tilt_off)
                    setBtnImage(networkImageIcon, R.drawable.network_off)
                    setBtnImage(batteryImageIcon, R.drawable.battery_off)
                }
                RequestKey.AUDIO.value -> {
                    permissionsKeys["AUDIO"] = true
                    setBtnImage(soundImageIcon, R.drawable.sound_off)
                }
                RequestKey.TIMER.value -> {
                    permissionsKeys["TIMER"] = true
                    setBtnImage(timerImageIcon, R.drawable.timer_off)
                }
            }
        }
        else {
            when (requestCode) {
                RequestKey.CREATE.value -> {
                    for (i in permissions.indices) {
                        val permission = permissions[i]
                        val grantResult = grantResults[i]

                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            // Permission granted for the specific permission
                            // Handle your logic here
                            when (permission) {
                                Manifest.permission.CAMERA -> {
                                    Log.i("MainActivity", "Request granted for FLASHLIGHT")
                                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_off4)
                                    setBtnImage(sosImageIcon, R.drawable.sos_off)
                                    setBtnImage(flickerImageIcon, R.drawable.flicker_off)
                                    setBtnImage(tiltImageIcon, R.drawable.tilt_off)
                                    setBtnImage(networkImageIcon, R.drawable.network_off)
                                    setBtnImage(batteryImageIcon, R.drawable.battery_off)
                                    permissionsKeys["FLASHLIGHT"] = true
                                }
                                Manifest.permission.RECORD_AUDIO -> {
                                    Log.i("MainActivity", "Request granted for MICROPHONE")
                                    setBtnImage(soundImageIcon, R.drawable.sound_off)
                                    permissionsKeys["AUDIO"] = true
                                }
                                Manifest.permission.SCHEDULE_EXACT_ALARM -> {
                                    Log.i("MainActivity", "Request granted for ALARM")
                                    setBtnImage(timerImageIcon, R.drawable.timer_off)
                                    permissionsKeys["TIMER"] = true
                                }
                            }
                        } else {
                            // Permission denied for the specific permission
                            // Handle your logic here
                            when (permission) {
                                Manifest.permission.CAMERA -> {
                                    Log.i("MainActivity", "Request NOT granted for Flashlight")
                                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_no_permission)
                                    setBtnImage(sosImageIcon, R.drawable.sos_no_permission)
                                    setBtnImage(flickerImageIcon, R.drawable.flicker_no_permission)
                                    setBtnImage(tiltImageIcon, R.drawable.tilt_no_permission)
                                    setBtnImage(networkImageIcon, R.drawable.network_no_permission)
                                    setBtnImage(batteryImageIcon, R.drawable.battery_no_permission)
                                    permissionsKeys["FLASHLIGHT"] = false
                                    triggerSnackbar(rootView, "To use the service, manually provide Camera rights to $applicationName.")
                                }
                                Manifest.permission.RECORD_AUDIO -> {
                                    Log.i("MainActivity", "Request NOT granted for MICROPHONE")
                                    setBtnImage(soundImageIcon, R.drawable.sound_no_permission)
                                    incomingSoundSwitch.isChecked = false
                                    tempText = "Sensitivity\n${calcSensitivityLevel(sensitivitySoundThreshold)}"
                                    setTextAndColor(soundSwitchText, tempText, R.color.greyNoteDarker2)
                                    permissionsKeys["AUDIO"] = false
                                    triggerSnackbar(rootView, "To use the service, manually provide Microphone rights to $applicationName.")
                                }
                                Manifest.permission.SCHEDULE_EXACT_ALARM -> {
                                    Log.i("MainActivity", "Request NOT granted for ALARM")
                                    setBtnImage(timerImageIcon, R.drawable.timer_no_permission)
                                    timerSwitch.isChecked = false
                                    permissionsKeys["TIMER"] = false
                                    triggerSnackbar(rootView, "To use the service, manually provide EXACT ALARM rights to $applicationName.")
                                }
                            }
                        }
                    }
                }
                RequestKey.FLASHLIGHT.value -> {
                    Log.i("MainActivity", "Request NOT granted for FLASHLIGHT")
                    setBtnImage(flashlightImageIcon, R.drawable.flashlight_no_permission)
                    setBtnImage(sosImageIcon, R.drawable.sos_no_permission)
                    setBtnImage(flickerImageIcon, R.drawable.flicker_no_permission)
                    setBtnImage(tiltImageIcon, R.drawable.tilt_no_permission)
                    setBtnImage(networkImageIcon, R.drawable.network_no_permission)
                    setBtnImage(batteryImageIcon, R.drawable.battery_no_permission)
                    permissionsKeys["FLASHLIGHT"] = false
                }
                RequestKey.AUDIO.value -> {
                    Log.i("MainActivity", "Request NOT granted for MICROPHONE")
                    setBtnImage(soundImageIcon, R.drawable.sound_no_permission)
                    incomingSoundSwitch.isChecked = false
                    tempText = "Sensitivity\n${calcSensitivityLevel(sensitivitySoundThreshold)}"
                    setTextAndColor(soundSwitchText, tempText, R.color.greyNoteDarker2)
                }
                RequestKey.TIMER.value -> {
                    Log.i("MainActivity", "Request NOT granted for TIMER")
                    setBtnImage(timerImageIcon, R.drawable.timer_no_permission)
                    timerSwitch.isChecked = false
                }
            }
        }
    }

    override fun onPause() {
        Log.i("MainActivity", "onPause is running")
        storeSettings()
        super.onPause()
    }

    override fun onRestart() {
        Log.i("MainActivity", "onRestart is running")
        super.onRestart()
    }

    override fun onDestroy() {
        Log.i("MainActivity", "onDestroy is running")

        if (isFlashLightOn) {
            Log.i("MainActivity", "Flashlight OFF")
            try {
                turnOffFlashlight()
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy turnOffFlashlight exception $e")
            }
        }
        else if (isSendSOS) {
            Log.i("MainActivity", "SOS OFF")
            try {
                stopSOS()
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy stopSOS exception $e")
            }
        }
        else if (isFlickering) {
            Log.i("MainActivity", "Flickering OFF")
            try {
                stopFlickering(Token.OTHER)
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy stopFlickering exception $e")
            }
        }

        if (isPhoneTilt) {
            try {
                Log.i("MainActivity", "sensorRotationEventListener OFF $sensorRotationEventListener ")
                sensorManager.unregisterListener(sensorRotationEventListener)
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy sensorRotationEventListener exception $e")
            }
        }

        if (isNetworkConnectivityCbIsSet) {
            try {
                Log.i("MainActivity", "connectivityCallback OFF $connectivityCallback ")
                connectivityManager.unregisterNetworkCallback(connectivityCallback)
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy connectivityCallback exception $e")
            }
        }

        if (isAudioIncoming) {
            try {
                Log.i("MainActivity", "audioRecordHandler OFF $audioRecordHandler ")
                audioRecordHandler.stop()
                audioRecordHandler.release()
                recordingThread?.interrupt()
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy audioRecordHandler exception $e")
            }
            recordingThread?.join()
            recordingThread = null
        }

        if (isAltitudeOn) {
            try {
                Log.i("MainActivity", "sensorPressureEventListener OFF $sensorPressureEventListener ")
                sensorManager.unregisterListener(sensorPressureEventListener)
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy sensorPressureEventListener exception $e")
            }
        }

        if (isBatteryOn) {
            try {
                Log.i("MainActivity", "batteryReceiver OFF $batteryReceiver ")
                unregisterReceiver(batteryReceiver)
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy batteryReceiver exception $e")
            }
        }

        if (isTimerOn) {
            try {
//                Log.i("MainActivity", "timerExecutor OFF $timerExecutor ")
//                timeExecutorShutdown(timerExecutor)
                unregisterReceiver(alarmReceiver)
            }
            catch (e : Exception) {
                Log.e("MainActivity", "onDestroy timerExecutor exception $e")
            }
        }

        super.onDestroy()
    }



    private fun resetAllActivities (featureToken : Token) {
        var tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.SOS, Token.SOUND, Token.TILT, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlashLightOn) {
            // Can be understood as: Until now I had Flashlight activated, but now I have activated
            // Flickering or TILT or Sound or SOS. So, Flashlight must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF Flashlight")
            resetFeature(Token.FLASHLIGHT)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLASHLIGHT, Token.SOS, Token.SOUND, Token.TILT, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isFlickering && isFlickeringOnDemand) {
            Log.i("MainActivity", "RAA - STOP FLICKERING on demand")
            resetFeature(Token.FLICKER)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.TILT, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isSendSOS) {
            Log.i("MainActivity", "RAA - DISABLE SOS")
            resetFeature(Token.SOS)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.SOUND, Token.SOS, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isPhoneTilt) {
            // Can be understood as:
            // Until now I had Phone Tilt activated, but now I have activated
            // Flickering or Flashlight or Sound or SOS.
            // So, Phone Tilt must be deactivated.
            Log.i("MainActivity", "RAA - TURN OFF isPhoneTilt")
            resetFeature(Token.TILT)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.SOS, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isAudioIncoming) {
            Log.i("MainActivity", "RAA - TURN OFF isAudioIncoming")
            resetFeature(Token.SOUND)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.SOS, Token.NETWORK, Token.TIMER, Token.ALTITUDE, Token.BATTERY)
        if ((featureToken in tokenValuesToCheckAgainst) && isNetworkConnectivityCbIsSet && flickeringDueToNetworkConnection) {
            Log.i("MainActivity", "RAA - TURN OFF isNetworkConnectivityCbIsSet")
            resetFeature(Token.NETWORK)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.SOS, Token.NETWORK, Token.ALTITUDE, Token.TIMER)
        if ((featureToken in tokenValuesToCheckAgainst) && isBatteryOn && flickeringDueToBattery) {
            Log.i("MainActivity", "RAA - TURN OFF isBatteryOn")
            resetFeature(Token.BATTERY)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.SOS, Token.NETWORK, Token.BATTERY, Token.TIMER)
        if ((featureToken in tokenValuesToCheckAgainst) && isAltitudeOn && flickeringDueToAltitude) {
            Log.i("MainActivity", "RAA - TURN OFF isAltitudeOn")
            resetFeature(Token.ALTITUDE)
        }

        tokenValuesToCheckAgainst = listOf(Token.FLICKER, Token.FLASHLIGHT, Token.TILT, Token.SOS, Token.NETWORK, Token.BATTERY, Token.ALTITUDE)
        if ((featureToken in tokenValuesToCheckAgainst) && isTimerOn && flickeringDueToTimer) {
            Log.i("MainActivity", "RAA - TURN OFF isTimerOn")
            resetFeature(Token.TIMER)
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
        adLoader.loadAd(AdRequest.Builder().build())
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


