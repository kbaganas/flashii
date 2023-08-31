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
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.*
import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.collect.ImmutableList

class SupportActivity : AppCompatActivity() {

    private lateinit var billingClient: BillingClient

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Flashii)
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

            Log.i("SupportActivity", "bank account $supportAmount dollars")

            /////////////////////////////////////////////////////////////////////////
            // Billing section here
            val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
                Log.i("SupportActivity", "purchasesUpdatedListener started")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        // Access product details like product ID, title, price, etc.
                        Log.i("SupportActivity", "purchase  = $purchase}")
                    }
                }
                else if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    Log.i("SupportActivity", "ITEM_ALREADY_OWNED")
                }

            }

            billingClient = BillingClient.newBuilder(this)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases()
                .build()

            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.i("SupportActivity", "BillingClient is ready for purchases")
                        loadSkuDetails()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Handle disconnected state
                    Log.i("SupportActivity", "BillingClient disconnected")
                }
            })
        }
        /////////////////////////////////////////////////////////////////////////

        val closeButton = findViewById<ImageButton>(R.id.supportGoBackArrow)
        closeButton.setOnClickListener {
            finish()
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////

    private fun loadSkuDetails() {

        val queryProductDetailsParams =
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    ImmutableList.of(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId("android.test.purchased")
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()))
                .build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) {
                billingResult,
                productDetailsList ->

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i("SupportActivity", "Query product OK with productDetailsList = $productDetailsList")
                for (productDetails in productDetailsList) {
                    // Access product details like product ID, title, price, etc.
                    Log.i("SupportActivity", "productDetails description = ${productDetails.description}")
                    Log.i("SupportActivity", "productDetails productId = ${productDetails.productId}")
                    Log.i("SupportActivity", "productDetails subscriptionOfferDetails = ${productDetails.subscriptionOfferDetails}")
                    Log.i("SupportActivity", "productDetails oneTimePurchaseOfferDetails.priceCurrencyCode = ${productDetails.oneTimePurchaseOfferDetails?.priceCurrencyCode}")
                    Log.i("SupportActivity", "productDetails oneTimePurchaseOfferDetails.formattedPrice = ${productDetails.oneTimePurchaseOfferDetails?.formattedPrice}")
//                    val offerToken = productDetails.subscriptionOfferDetails?.get(productDetails.productId.toInt())
//                        ?.let {
//                            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails).setOfferToken(
//                                it.offerToken)
//                        }
                    val offerToken = "AEuhp4Ky0enab_5oZRM8VtABXiHdO92wMVte9HMc4gkLU-8iFDpGrXoQmDjX7FeWMs4="
                    val productDetailsParams = listOf(
                        offerToken?.let {
                            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails).setOfferToken(
                                it
                            ).build()
                        }
                    )
                    Log.i("SupportActivity", "productDetailsParams = $productDetailsParams")
                    val billingFlowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(productDetailsParams).build()
                    Log.i("SupportActivity", "billingFlowParams = $billingFlowParams")
                    val billingResult = billingClient.launchBillingFlow(this@SupportActivity, billingFlowParams)
                    Log.i("SupportActivity", "billingResult = $billingResult")
                }
            } else {
                // Handle billingResult error
                Log.e("SupportActivity", "Query product details billingResult.responseCode = ${billingResult.responseCode}, productDetailsList = $productDetailsList")
            }
        }
    }


    //    private fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
//        Log.i("SupportActivity", "onPurchasesUpdated  loc1")
//        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
//            for (purchase in purchases) {
//                // Handle the purchased item
//                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
//                    // Item was purchased, process it accordingly
//                }
//            }
//        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
//            // Handle user cancellation
//        } else {
//            // Handle other errors
//        }
//    }

    private fun resetText(txt : EditText) {
        txt.hint = resources.getString(R.string.enterAmount)
        txt.setTextColor(resources.getColor(R.color.blueOffBack4, theme))
        txt.text = null
        if (txt.isFocused) {
            txt.clearFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billingClient.endConnection()
    }

}

