package com.ix8ys.flashii

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.*
import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.collect.ImmutableList

class SupportActivity : AppCompatActivity() {

    private lateinit var billingClient: BillingClient
    private var supportAmount = ""

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Flashii)
        setContentView(R.layout.support)

        val supportContinueBtn = findViewById<Button>(R.id.supportContinueBtn)
        val radioGroup = findViewById<RadioGroup>(R.id.radioGroup)

        // Support
        supportContinueBtn.setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId
            if (selectedId != -1) {
                val radioButton = findViewById<RadioButton>(selectedId)
                supportAmount = radioButton.text.toString().replace(Regex("\\u20ac"), "")
            }

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
                        Log.i("SupportActivity", "BillingClient is ready: support EUR $supportAmount")
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
                            .setProductId(supportAmount)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()))
                .build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) {
                billingResult,
                productDetailsList ->

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (productDetails in productDetailsList) {
                    Log.i("SupportActivity", "productDetails productId = ${productDetails.productId}")

                    val productDetailsParams = listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails).build()
                    )
                    val billingFlowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(productDetailsParams).build()
                    val billingResultLaunch = billingClient.launchBillingFlow(this@SupportActivity, billingFlowParams)
                    Log.i("SupportActivity", "billingResultLaunch = $billingResultLaunch")
                }
            } else {
                // Handle billingResult error
                Log.e("SupportActivity", "Query product details billingResult.responseCode = ${billingResult.responseCode}, productDetailsList = $productDetailsList")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (supportAmount != "") {
            billingClient.endConnection()
        }
    }

}

