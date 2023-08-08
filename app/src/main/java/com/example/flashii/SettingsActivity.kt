package com.example.flashii

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    // constants, variables
    private val defaultMaxFlickerHz : Int = 10
    private val defaultMaxFlickerIncomingCall : Int = 15000
    private val defaultMaxFlickerIncomingSMS : Int = 15000
    private val defaultMaxFlickerIncomingBattery : Int = 15000
    private val defaultMaxFlickerIncomingAltitude : Int = 15000

    private var maxFlickerHz : Int = defaultMaxFlickerHz
    private var maxFlickerDurationIncomingCall : Int = defaultMaxFlickerIncomingCall
    private var maxFlickerDurationIncomingSMS : Int = defaultMaxFlickerIncomingSMS
    private var maxFlickerDurationBattery : Int = defaultMaxFlickerIncomingBattery
    private var maxFlickerDurationAltitude : Int = defaultMaxFlickerIncomingAltitude
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
        maxFlickerHz = intent.getIntExtra("maxFlickerHz", defaultMaxFlickerHz)
        maxFlickerDurationIncomingCall = intent.getIntExtra("maxFlickerDurationIncomingCall", defaultMaxFlickerIncomingCall)
        maxFlickerDurationIncomingSMS = intent.getIntExtra("maxFlickerDurationIncomingSMS", defaultMaxFlickerIncomingSMS)
        maxFlickerDurationBattery = intent.getIntExtra("maxFlickerDurationBattery", defaultMaxFlickerIncomingBattery)
        maxFlickerDurationAltitude = intent.getIntExtra("maxFlickerDurationAltitude", defaultMaxFlickerIncomingAltitude)

        // Set data of the intent in local variables
        setHintValues()

        Log.i("SettingsActivity", "oCreate Input data are: $maxFlickerHz,$maxFlickerDurationIncomingCall,$maxFlickerDurationIncomingSMS,$maxFlickerDurationBattery,$maxFlickerDurationAltitude")


        // Clear Hint on focus
        flickTimeIncSMSEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                flickTimeIncSMSEditText.hint = ""
            } else {
                val tempInt = maxFlickerDurationIncomingSMS / 1000
                flickTimeIncSMSEditText.hint = tempInt.toString()
                flickTimeIncSMSEditText.clearFocus()
            }
        }
        flickTimeIncCallEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                flickTimeIncCallEditText.hint = ""
            } else {
                val tempInt = defaultMaxFlickerIncomingCall / 1000
                flickTimeIncCallEditText.hint = tempInt.toString()
                flickTimeIncCallEditText.clearFocus()
            }
        }
        maxFlickerHzEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                maxFlickerHzEditText.hint = ""
            } else {
                maxFlickerHzEditText.hint = maxFlickerHz.toString()
                maxFlickerHzEditText.clearFocus()
            }
        }
        flickTimeBatteryEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                flickTimeBatteryEditText.hint = ""
            } else {
                val tempInt = maxFlickerDurationBattery / 1000
                flickTimeBatteryEditText.hint = tempInt.toString()
                flickTimeBatteryEditText.clearFocus()
            }
        }
        flickTimeAltitudeEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                flickTimeAltitudeEditText.hint = ""
            } else {
                val tempInt = maxFlickerDurationAltitude / 1000
                flickTimeAltitudeEditText.hint = tempInt.toString()
                flickTimeAltitudeEditText.clearFocus()
            }
        }

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