package com.example.flashii

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File


class MainActivity : AppCompatActivity() {

    // constants
    private val MIN_FLICKER_HZ : Int = 1
    private val MAX_FLICKER_HZ : Int = 10
    private val DIT_DURATION : Long = 250
    private val SPACE_DURATION : Long = DIT_DURATION
    private val DAH_DURATION : Long = 3 * DIT_DURATION
    private val SPACE_CHARS_DURATION : Long = 3 * SPACE_DURATION
    private val SPACE_WORDS_DURATION : Long = 4 * SPACE_DURATION // results to 7*DIT_DURATION, considering that we add SPACE_CHARS_DURATION after each letter

    // variables
    private lateinit var cameraManager : CameraManager
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var flashLightId : String
    private var isFlashLightOn = false
    private var isRearCameraAndFlashLightOn = false
    private lateinit var imageCapture : ImageCapture
    private var flickerFlashLightHz : Long = 1
    private var isFlickering : Boolean = false
    private var loopHandler : Handler = Handler(Looper.getMainLooper())
    private var receiverIsRegistered : Boolean = false
    private var incomingCallReceiver : IncomingCallReceiver? = null
    private var sendSOS : Boolean = false

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // setup cameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager


        // flashLightBtn handler
        val flashlightButton: Button = findViewById(R.id.flashLightBtnId)
        flashLightId = getFlashLightId()
        flashlightButton.setOnClickListener {
            if (isFlashLightOn) {
                turnOffFlashlight()
            } else {
                turnOnFlashlight()
            }
        }

        // rearCameraFlashLightBtn handler
        val rearCameraFlashLightBtn: Button = findViewById(R.id.rearCameraFlashLightBtnId)
        rearCameraFlashLightBtn.setOnClickListener {
            if (isRearCameraAndFlashLightOn) {
                turnOffRearCameraAndFlashLight()
            } else {
                turnOnRearCameraAndFlashLight()
            }
        }

        // takePhotoBtn handler
        val takePhotoBtn: Button = findViewById(R.id.takePhotoBtnId)
        takePhotoBtn.setOnClickListener {
            takePhoto()
        }

        // flickeringBar handler
        val flickeringBar = findViewById<SeekBar>(R.id.flickeringBarId)
        flickeringBar.min = MIN_FLICKER_HZ
        flickeringBar.max = MAX_FLICKER_HZ
        flickeringBar.visibility = View.INVISIBLE
        flickeringBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                setFlickeringHz(progress.toLong())
                Log.d("MainActivity", "onProgressChanged $flickerFlashLightHz")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                stopFlickering()
                //Log.d("MainActivity", "onStartTrackingTouch")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                //Log.d("MainActivity", "onStopTrackingTouch")
                startFlickering()
            }
        })

        // flickerFlashLightBtn handler
        val flickerFlashLightBtn : Button = findViewById(R.id.flickerFlashLightId)
        flickerFlashLightBtn.setOnClickListener {
            if (!isFlickering) {
                startFlickering()
                isFlickering = true
                flickeringBar.visibility = View.VISIBLE
            }
            else {
                stopFlickering()
                isFlickering = false
                flickeringBar.visibility = View.INVISIBLE
            }
        }

        val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        incomingCallReceiver = IncomingCallReceiver()
        Log.i("MainActivity","incomingCallReceiver = $incomingCallReceiver, intentFilter = $intentFilter")
        registerReceiver(incomingCallReceiver, intentFilter)


//        var incomingCallFlickerSwitch = findViewById<Switch>(R.id.switchFlickerIncomingCallsId)
//        incomingCallFlickerSwitch.setOnCheckedChangeListener { _, switchedOn ->
//            if (switchedOn) {
//                Log.i("MainActivity","incomingCallFlickerSwitch is ON")
//                if (receiverIsRegistered) {
//                    Log.i("MainActivity","receiverIsRegistered is already true")
//                }
//                else {
//                    Log.i("MainActivity","intent and registration is ON")
//                    val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
//                    registerReceiver(incomingCallReceiver, intentFilter)
//                    receiverIsRegistered = true
//                }
//            } else {
//                Log.i("MainActivity", "incomingCallFlickerSwitch is OFF")
//                unregisterReceiver(incomingCallReceiver)
//                receiverIsRegistered = false
//            }
//        }


        // sosBtn handler
        val sosBtn : Button = findViewById(R.id.sosBtn)
        sosBtn.setOnClickListener {
            sendSOS = !sendSOS
            if (sendSOS) {
                repeatSOS()
            }
            else {
                Log.i("MainActivity", "STOP SOS")
                loopHandler.removeCallbacksAndMessages(null)
                turnOffFlashlight()
            }
        }
    }

    private fun repeatSOS() {
        if (sendSOS) {
            Log.i("MainActivity", "SEND SOS")
            val durationOfWord = S(O(S()))
            loopHandler.postDelayed({repeatSOS()}, durationOfWord + SPACE_WORDS_DURATION)
        }
    }

    override fun onPause() {
        Log.i("MainActivity", "onPause is running")
        super.onPause()
        if (isFlickering) {
            stopFlickering()
            isFlickering = false
        }
        if (isFlashLightOn) {
            turnOffFlashlight()
        }
    }

    override fun onRestart() {
        Log.i("MainActivity", "onRestart is running")
        super.onRestart()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(incomingCallReceiver)
        Log.i("MainActivity", "onDestroy is running")
    }

    override fun onStop() {
        Log.i("MainActivity", "onStop is running")
        super.onStop()
        if (isFlickering) {
            stopFlickering()
            isFlickering = false
        }
        if (isFlashLightOn) {
            turnOffFlashlight()
        }
        if (isRearCameraAndFlashLightOn) {
            cameraProvider.unbindAll()
        }
        if (receiverIsRegistered) {
            unregisterReceiver(incomingCallReceiver)
        }
    }

    override fun onResume() {
        super.onResume()
        //val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        //registerReceiver(incomingCallReceiver, intentFilter)
    }

    fun setFlickeringHz(hz : Long) {
        flickerFlashLightHz = hz
    }

    fun stopFlickering() {
        loopHandler.removeCallbacksAndMessages(null)
        turnOffFlashlight()
    }

    fun startFlickering() {
        // flicker as flickerFlashLightHz
        val delayedMilliseconds : Long =  1000 / (2 * flickerFlashLightHz)
        turnOnFlashlight()
        loopHandler.postDelayed({ turnOffFlashlight() }, delayedMilliseconds)
        loopHandler.postDelayed({ startFlickering() }, delayedMilliseconds * 2)
    }

    private fun takePhoto() {
        // Ensure the cameraProvider is available
        if (::cameraProvider.isInitialized) {
            val outputFile = File(externalMediaDirs.first(), "flashii_photo.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

            cameraProvider.bindToLifecycle(this as LifecycleOwner, DEFAULT_BACK_CAMERA, imageCapture)

            imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val savedUri = outputFileResults.savedUri ?: Uri.fromFile(outputFile)
                        Log.i("MainActivity","takePicture was successful : $savedUri")
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.d("MainActivity", "takePhoto - ERROR: $exception")
                    }
                })
        }
    }

    private fun turnOnRearCameraAndFlashLight() {
        try {
            turnOnRearCameraForPhoto()
            turnOnFlashlight()
            isRearCameraAndFlashLightOn = true
            Log.i("MainActivity","RearCameraAndFlashLight are ON")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d("MainActivity", "RearCameraAndFlashLight ON - ERROR: $e")
        }
    }

    private fun turnOnRearCameraForPhoto() {
        val rearCameraProvider = ProcessCameraProvider.getInstance(this)

        rearCameraProvider.addListener({
            cameraProvider = rearCameraProvider.get()
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this as LifecycleOwner, DEFAULT_BACK_CAMERA, imageCapture)
                Log.d("MainActivity", "RearCameraPhoto are ON")
            } catch (e: Exception) {
                Log.d("MainActivity", "RearCameraPhoto ON - ERROR: $e")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun turnOffRearCameraAndFlashLight() {
        try {
            turnOffRearCameraForPhoto()
            turnOffFlashlight()
            isRearCameraAndFlashLightOn = false
            Log.i("MainActivity","RearCameraAndFlashLight are OFF")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d("MainActivity", "RearCameraAndFlashLight OFF - ERROR: $e")
        }
    }

    private fun turnOffRearCameraForPhoto() {
        try {
            cameraProvider.unbindAll()
            Log.d("MainActivity", "RearCameraPhoto OFF")
        }
        catch (e : CameraAccessException) {
            Log.d("MainActivity", "RearCameraPhoto OFF - ERROR: $e")
        }
    }

    private fun turnOnFlashlight() {
        try {
            cameraManager.setTorchMode(flashLightId, true)
            isFlashLightOn = true
            //Log.i("MainActivity","FlashLight is ON")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d("MainActivity", "FlashLight ON - ERROR: $e")
        }
    }

    private fun turnOffFlashlight() {
        try {
            cameraManager.setTorchMode(flashLightId, false)
            isFlashLightOn = false
            //Log.i("MainActivity","FlashLight is OFF")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d("MainActivity", "FlashLight OFF - ERROR: $e")
        }
    }

    private fun getFlashLightId () : String {
        var flashLightId = ""
        // Iterate over the available camera devices
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

            // Check if the camera is the rear camera
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                // Rear camera found. Now check if the rear camera has a flashlight
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    flashLightId = id
                    break
                }
            }
        }
        return flashLightId
    }



    // dit flashing per Morse code
    private fun dit () {
        turnOnFlashlight()
        loopHandler.postDelayed({ turnOffFlashlight() }, DIT_DURATION)
    }

    // dah flashing per Morse code
    private fun dah () {
        turnOnFlashlight()
        loopHandler.postDelayed({ turnOffFlashlight() }, DAH_DURATION)
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

}



class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("MainActivity", "Phone is ringing loc1")
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                Log.i("MainActivity", "Phone is ringing")
                // Perform your desired actions when a call is received
                // For example, you can toggle the flashlight here
                // or trigger any other functionality
            }
        }
    }
}

