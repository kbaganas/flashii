package com.ichthis.flashii

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputFilter
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class SupportActivity : AppCompatActivity() {
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.support)

        var supportAmount = 0
        val supportContinueBtn = findViewById<Button>(R.id.supportContinueBtn)
        val radioGroup = findViewById<RadioGroup>(R.id.radioGroup)

        // handlers of supportManualText
        val supportManualText = findViewById<EditText>(R.id.supportManualText)
        val positiveFilter = InputFilter { source, _, _, _, _, _ ->
            if (source.matches(Regex("[0-9]+"))) {
                null // Accept the input as it is (positive integer numbers)
            } else {
                // Reject the input (don't allow other characters)
                ""
            }
        }

        val firstDigitNotZeroFilter = InputFilter { source, _, _, dest, _, _ ->
            if (dest.isEmpty() && source == "0") {
                "" // Reject the input (don't allow 0 as the first digit)
            } else {
                null // Accept the input as it is (other characters allowed)
            }
        }

        supportManualText.filters = arrayOf(positiveFilter, firstDigitNotZeroFilter)

        supportManualText.setOnFocusChangeListener { _, hasFocus ->
            Log.i("SupportActivity", "focus supportManualText")
            if (hasFocus) {
                supportManualText.hint = ""
                supportManualText.setTextColor(resources.getColor(R.color.white, theme))
            } else {
                resetText(supportManualText)
            }
        }

        findViewById<RadioButton>(R.id.support1Btn).setOnClickListener {
            Log.i("SupportActivity", "support1Btn clicked")
            resetText(supportManualText)
        }

        findViewById<RadioButton>(R.id.support10Btn).setOnClickListener {
            Log.i("SupportActivity", "support10Btn clicked")
            resetText(supportManualText)
        }

        findViewById<RadioButton>(R.id.support100Btn).setOnClickListener {
            Log.i("SupportActivity", "support100Btn clicked")
            resetText(supportManualText)
        }

        // Support
        supportContinueBtn.setOnClickListener {
            var manualSupport = 0
            if (supportManualText.text.toString().isNotEmpty()) {
                manualSupport = supportManualText.text.toString().toInt()
            }

            if (manualSupport > 0) {
                supportAmount = manualSupport
            }
            else {
                val selectedId = radioGroup.checkedRadioButtonId
                if (selectedId != -1) {
                    val radioButton = findViewById<RadioButton>(selectedId)
                    supportAmount = radioButton.text.toString().replace(Regex("[^\\d]"), "").toInt()
                }
            }

            // TODO: here we have to sent supportAmount to our bank account
            Log.i("SupportActivity", "bank account $supportAmount dollars")
        }

        val closeButton = findViewById<ImageButton>(R.id.supportGoBackArrow)
        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun resetText(txt : EditText) {
        txt.hint = resources.getString(R.string.enterAmount)
        txt.setTextColor(resources.getColor(R.color.blueOffBack4, theme))
        txt.text = null
        if (txt.isFocused) {
            txt.clearFocus()
        }
    }
}