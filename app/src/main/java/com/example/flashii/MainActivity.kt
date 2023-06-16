package com.example.flashii

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager : CameraManager
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var flashLightId : String
    private var isFlashLightOn = false
    private var isRearCameraAndFlashLightOn = false
    private lateinit var imageCapture : ImageCapture
    private var flickerFlashLightHertz : Long = 1
    private var isFlickering : Boolean = false
    private var loopHandler : Handler = Handler(Looper.getMainLooper())

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

        // flickerFlashLightBtn handler
        val flickerFlashLightBtn : Button = findViewById(R.id.flickerFlashLightId)
        flickerFlashLightBtn.setOnClickListener {
            if (!isFlickering) {
                startFlickering()
                isFlickering = true
            }
            else {
                stopFlickering()
                isFlickering = false
            }
        }

    }

    private fun stopFlickering() {
        loopHandler.removeCallbacksAndMessages(null)
        turnOffFlashlight()
    }

    private fun startFlickering() {
        // flicker as flickerFlashLightHertz
        val delayedMilliseconds : Long =  flickerFlashLightHertz * 1000 / 2
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
            Log.i("MainActivity","FlashLight is ON")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d("MainActivity", "FlashLight ON - ERROR: $e")
        }
    }

    private fun turnOffFlashlight() {
        try {
            cameraManager.setTorchMode(flashLightId, false)
            isFlashLightOn = false
            Log.i("MainActivity","FlashLight is OFF")
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


}