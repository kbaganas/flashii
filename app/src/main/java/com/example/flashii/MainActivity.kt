package com.example.flashii

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // setup cameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // flashLightBtn handler
        val flashlightButton: Button = findViewById<Button>(R.id.flashLightBtnId)
        flashLightId = getFlashLightId()
        flashlightButton.setOnClickListener {
            if (isFlashLightOn) {
                turnOffFlashlight()
            } else {
                turnOnFlashlight()
            }
        }

        // rearCameraFlashLightBtn handler
        val rearCameraFlashLightBtn: Button = findViewById<Button>(R.id.rearCameraFlashLightBtnId)
        rearCameraFlashLightBtn.setOnClickListener {
            if (isRearCameraAndFlashLightOn) {
                turnOffRearCameraAndFlashLight()
            } else {
                turnOnRearCameraAndFlashLight()
            }
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
            val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, DEFAULT_BACK_CAMERA, imageCapture)
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