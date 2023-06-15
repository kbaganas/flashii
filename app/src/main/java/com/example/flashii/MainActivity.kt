package com.example.flashii

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager : CameraManager
    private lateinit var flashLightId : String
    private var isFlashlightOn = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // setup cameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // flashLightBtn handler
        val flashlightButton: Button = findViewById<Button>(R.id.flashLightBtnId)
        flashLightId = getFlashLightId()
        flashlightButton.setOnClickListener {
            if (isFlashlightOn) {
                turnOffFlashlight()
            } else {
                turnOnFlashlight()
            }
        }
    }

    private fun turnOnFlashlight() {
        try {
            cameraManager.setTorchMode(flashLightId, true)
            isFlashlightOn = true
            Log.i("MainActivity","FlashLight is ON")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d("MainActivity", "FlashLight ON ERROR: $e")
        }
    }

    private fun turnOffFlashlight() {
        try {
            cameraManager.setTorchMode(flashLightId, false)
            isFlashlightOn = false
            Log.i("MainActivity","FlashLight is OFF")
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Log.d("MainActivity", "FlashLight OFF ERROR: $e")
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