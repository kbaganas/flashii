package com.example.flashii

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputFilter
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class SupportActivity : AppCompatActivity() {
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.support)

        var supportAmount = 0
        val support1Btn = findViewById<Button>(R.id.support1Btn)
        val support10Btn = findViewById<Button>(R.id.support10Btn)
        val support100Btn = findViewById<Button>(R.id.support100Btn)
        val supportContinueBtn = findViewById<Button>(R.id.supportContinueBtn)

        // handlers of supportManualText
        val supportManualText = findViewById<EditText>(R.id.supportManualText)
        val positiveFilter = InputFilter { source, _, _, _, _, _ ->
            if (source.matches(Regex("[1-9]+"))) {
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

        // click listeners on buttons
        support1Btn.setOnClickListener {
            supportAmount = 1
            Log.i("SupportActivity", "support1Btn()")
            support1Btn.setTextColor(resources.getColor(R.color.white, theme))
            support1Btn.setBackgroundColor(resources.getColor(R.color.dollarColor, theme))
            resetBtn(support10Btn)
            resetBtn(support100Btn)
            resetText(supportManualText)
        }

        support10Btn.setOnClickListener {
            supportAmount = 10
            Log.i("SupportActivity", "support10Btn()")
            support10Btn.setTextColor(resources.getColor(R.color.white, theme))
            support10Btn.setBackgroundColor(resources.getColor(R.color.dollarColor, theme))
            resetBtn(support1Btn)
            resetBtn(support100Btn)
            resetText(supportManualText)
        }

        support100Btn.setOnClickListener {
            supportAmount = 100
            Log.i("SupportActivity", "support100Btn()")
            support100Btn.setTextColor(resources.getColor(R.color.white, theme))
            support100Btn.setBackgroundColor(resources.getColor(R.color.dollarColor, theme))
            resetBtn(support1Btn)
            resetBtn(support10Btn)
            resetText(supportManualText)
        }

        supportManualText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                resetBtn(support1Btn)
                resetBtn(support10Btn)
                resetBtn(support100Btn)
                supportManualText.hint = ""
                supportManualText.setTextColor(resources.getColor(R.color.white, theme))
            } else {
                resetText(supportManualText)
            }
        }

        // Support
        supportContinueBtn.setOnClickListener {
            var manualSupport = 0
            if (supportManualText.text.toString().isNotEmpty()) {
                manualSupport = supportManualText.text.toString().toInt()
            }

            if (supportAmount < 1 && manualSupport < 1) {
                Log.i("SupportActivity", "supportContinueBtn with no support")
            }
            else if (manualSupport > 0) {
                Log.i("SupportActivity", "supportContinueBtn $ = $manualSupport")
            }
            else {
                Log.i("SupportActivity", "supportContinueBtn $ = $supportAmount")
            }
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
    private fun resetBtn(btn : Button) {
        btn.setTextColor(resources.getColor(R.color.greyNoteDarker6, theme))
        btn.setBackgroundColor(resources.getColor(R.color.blueOffBack2, theme))
    }
}