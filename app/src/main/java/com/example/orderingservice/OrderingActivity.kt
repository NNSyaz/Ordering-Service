package com.example.orderingservice

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.orderingservice.databinding.ActivityOrderingBinding
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import java.util.*

class OrderingActivity : AppCompatActivity(), Robot.TtsListener {

    private lateinit var binding: ActivityOrderingBinding
    private lateinit var robot: Robot
    private lateinit var tableStatusManager: TableStatusManager
    private val gson = Gson()

    private var sessionId: String = ""
    private var orderType: String = ""
    private var tableNo: String = ""
    private var currentBattery: Int = 100
    private var orderNumber: String = "" // This will be updated from server response

    // Store customer's actual order data
    private var customerOrderItems: List<String> = emptyList()
    private var orderTotal: String = "0.00"
    private var hasReceivedOrder: Boolean = false

    // Speech management
    private var isSpeaking = false
    private var pendingOrderCompletion = false

    // Inactivity timeout management
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var lastInteractionTime = System.currentTimeMillis()
    private var inactivityTimeoutRunnable: Runnable? = null

    // Customer choice tracking
    private var hasCustomerMadeChoice = false
    private var customerChoseQR = false

    // QR code display
    private var qrDisplayHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val INACTIVITY_TIMEOUT = 60000L // 1 minute of no interaction
        private const val INACTIVITY_CHECK_INTERVAL = 10000L // Check every 10 seconds
        private const val QR_DISPLAY_TIMEOUT = 60000L // 1 minute for QR code display
        private const val TAG = "OrderingActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrderingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from previous activity
        sessionId = intent.getStringExtra("SESSION_ID") ?: ""
        orderType = intent.getStringExtra("ORDER_TYPE") ?: ""
        tableNo = intent.getStringExtra("TABLE_NO") ?: ""
        currentBattery = intent.getIntExtra("BATTERY", 100)

        // FIXED: Don't set order number initially - will be set when order is placed
        orderNumber = ""

        setupUI()
        initializeTemi()
        initializeTableStatusManager()
        setupBackPressedHandler()

        // Start with ordering choice instead of immediately loading menu
        showOrderingChoice()
    }

    private fun setupBackPressedHandler() {
        // Modern way to handle back press
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isSpeaking) {
                    handleBackPressed()
                }
            }
        })
    }

    private fun setupUI() {
        binding.apply {
            tvTitle.text = "$orderType - $tableNo"
            // FIXED: Don't show order number initially
            tvOrderNumber.text = "Ready to Order"
            tvBattery.text = "Battery: ${currentBattery}%"

            btnBack.setOnClickListener {
                if (!isSpeaking) {
                    handleBackPressed()
                }
            }

            // Initially hide all ordering-related elements using new layout IDs
            layoutChoiceButtons.visibility = android.view.View.GONE
            completeOrderContainer.visibility = android.view.View.GONE
            webViewContainer.visibility = android.view.View.GONE
            layoutQrCode.visibility = android.view.View.GONE

            // Setup click listeners for choice buttons
            btnUseQr.setOnClickListener {
                if (!isSpeaking) {
                    customerChooseQR()
                }
            }

            btnUseTemiMenu.setOnClickListener {
                if (!isSpeaking) {
                    customerChooseMenu()
                }
            }

            btnNoOrder.setOnClickListener {
                if (!isSpeaking) {
                    customerChooseNoOrder()
                }
            }

            btnCompleteOrder.setOnClickListener {
                if (!isSpeaking) {
                    completeOrder()
                }
            }
        }
    }

    private fun initializeTemi() {
        robot = Robot.getInstance()
    }

    private fun initializeTableStatusManager() {
        tableStatusManager = TableStatusManager.getInstance()
    }

    override fun onStart() {
        super.onStart()
        robot.addTtsListener(this)
    }

    override fun onStop() {
        super.onStop()
        robot.removeTtsListener(this)
        stopInactivityTimer()
        stopQRDisplayTimer()
    }

    private fun showOrderingChoice() {
        // Reset interaction timer
        resetInactivityTimer()

        // SHORTENED DIALOG: Reduced from 3 sentences to 1 concise sentence
        val choiceMessage = "Please choose how you'd like to order"

        speakAndWait(choiceMessage) {
            // Show the enhanced choice interface
            runOnUiThread {
                binding.apply {
                    layoutChoiceButtons.visibility = android.view.View.VISIBLE
                }
            }

            // Start inactivity monitoring
            startInactivityMonitoring()
        }
    }

    private fun customerChooseQR() {
        hasCustomerMadeChoice = true
        customerChoseQR = true
        stopInactivityTimer()

        // FIXED: For QR choice, DON'T send any webhooks, just show QR code
        if (orderType.equals("Takeaway", ignoreCase = true)) {
            speakAndWait("Great! Here's your QR code. Scan it to order on your phone.") {
                showQRCodeForTakeaway()
            }
        } else {
            speakAndWait("Perfect! Use the QR code on your table to order. I'll return to entrance.") {
                // For dine-in, just return to base - NO webhooks needed for QR choice
                returnToStandby(releaseTable = false) // Keep table occupied for dine-in
            }
        }
    }

    private fun showQRCodeForTakeaway() {
        runOnUiThread {
            binding.apply {
                // Hide choice buttons and other elements using new IDs
                layoutChoiceButtons.visibility = android.view.View.GONE
                completeOrderContainer.visibility = android.view.View.GONE
                webViewContainer.visibility = android.view.View.GONE

                // Show QR code layout
                layoutQrCode.visibility = android.view.View.VISIBLE

                // Update texts - FIXED: Don't show order number for QR
                tvQrTitle.text = "Scan QR Code to Order"
                tvQrOrderNumber.text = "Order via Phone" // No order number for QR
                tvQrInstructions.text = "Scan this QR code with your phone to access our menu and place your order."

                // Load QR code image from drawable
                try {
                    imgQrCode.setImageResource(R.drawable.qr_code_menu) // Replace with your QR code image name
                } catch (e: Exception) {
                    Log.w(TAG, "QR code image not found, showing placeholder")
                    imgQrCode.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
        }

        // FIXED: NO webhooks for QR selection - just show the QR code
        Log.d(TAG, "Displaying QR code for $orderType - no webhook needed")

        // Start countdown to return to base
        startQRDisplayTimer()
    }

    private fun startQRDisplayTimer() {
        stopQRDisplayTimer() // Stop any existing timer

        qrDisplayHandler.postDelayed({
            speakAndWait("Time's up! Returning to position. Please use the QR code to place your order!") {
                returnToStandby(releaseTable = true) // Release table for takeaway
            }
        }, QR_DISPLAY_TIMEOUT)

        Log.d(TAG, "Started QR display timer for $QR_DISPLAY_TIMEOUT ms")
    }

    private fun stopQRDisplayTimer() {
        qrDisplayHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped QR display timer")
    }

    private fun customerChooseNoOrder() {
        hasCustomerMadeChoice = true
        stopInactivityTimer()

        // SHORTENED DIALOG: Much more concise
        speakAndWait("No problem! Thank you for visiting. Have a great day!") {
            // Always release table if customer chooses not to order
            releaseTableAndReturn()
        }
    }

    private fun customerChooseMenu() {
        hasCustomerMadeChoice = true
        customerChoseQR = false
        stopInactivityTimer()

        // SHORTENED DIALOG: Reduced from long explanation to concise message
        speakAndWait("Great! Loading our menu now.") {
            // Show WebView container and hide other elements
            runOnUiThread {
                binding.apply {
                    // Step 1: Hide ALL other layouts COMPLETELY
                    layoutChoiceButtons.visibility = android.view.View.GONE
                    layoutQrCode.visibility = android.view.View.GONE
                    completeOrderContainer.visibility = android.view.View.GONE

                    // Step 2: Show the WebView container
                    webViewContainer.visibility = android.view.View.VISIBLE
                    webViewLoadingIndicator.visibility = android.view.View.VISIBLE

                    // Step 3: Ensure WebView is also visible
                    webView.visibility = android.view.View.VISIBLE

                    Log.d(TAG, "✅ WebView container and WebView visibility set to VISIBLE")
                }
            }

            // Load the menu after UI update with a proper delay
            Handler(Looper.getMainLooper()).postDelayed({
                loadMenu()
            }, 500)
        }

        startInactivityMonitoring() // Resume monitoring while using menu
    }

    private fun startInactivityMonitoring() {
        stopInactivityTimer() // Stop any existing timer

        lastInteractionTime = System.currentTimeMillis()

        inactivityTimeoutRunnable = object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastInteraction = currentTime - lastInteractionTime

                if (timeSinceLastInteraction >= INACTIVITY_TIMEOUT) {
                    handleInactivityTimeout()
                } else {
                    // Check again after interval
                    inactivityHandler.postDelayed(this, INACTIVITY_CHECK_INTERVAL)
                }
            }
        }

        inactivityHandler.postDelayed(inactivityTimeoutRunnable!!, INACTIVITY_CHECK_INTERVAL)
        Log.d(TAG, "Started inactivity monitoring")
    }

    private fun stopInactivityTimer() {
        inactivityTimeoutRunnable?.let {
            inactivityHandler.removeCallbacks(it)
            inactivityTimeoutRunnable = null
        }
        Log.d(TAG, "Stopped inactivity timer")
    }

    private fun resetInactivityTimer() {
        lastInteractionTime = System.currentTimeMillis()
        Log.d(TAG, "Reset inactivity timer")
    }

    private fun handleInactivityTimeout() {
        if (hasCustomerMadeChoice && customerChoseQR) {
            // If customer chose QR, we should have already left
            return
        }

        Log.d(TAG, "Handling inactivity timeout")
        stopInactivityTimer()

        if (!hasCustomerMadeChoice) {
            speakAndWait("No selection made. Returning to position for other customers.") {
                releaseTableAndReturn()
            }
        } else if (!hasReceivedOrder && !customerChoseQR) {
            speakAndWait("No activity detected. Returning to position. Call me back anytime!") {
                releaseTableAndReturn()
            }
        }
    }

    private fun releaseTableAndReturn() {
        // CRITICAL FIX: Use TableStatusManager to properly release the table
        Log.d(TAG, "Releasing table $tableNo via TableStatusManager")
        tableStatusManager.releaseTable(
            sessionId = sessionId,
            tableId = tableNo,
            onSuccess = {
                Log.d(TAG, "✅ Table $tableNo released successfully via TableStatusManager")
            },
            onError = { error ->
                Log.e(TAG, "❌ Failed to release table via TableStatusManager: $error")
                // Try local release as fallback
                tableStatusManager.removeOccupiedTable(tableNo)
            }
        )

        returnToStandby(releaseTable = true)
    }

    private fun returnToStandby(releaseTable: Boolean = false) {
        if (releaseTable) {
            // CRITICAL FIX: Use TableStatusManager for proper table release
            Log.d(TAG, "Ensuring table $tableNo is released locally")
            tableStatusManager.removeOccupiedTable(tableNo)
        }

        // Return to standby position
        robot.goTo(Constants.STANDBY_LOCATION)

        // Finish activity after delay
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 2000)
    }

    // Implement Robot.TtsListener
    override fun onTtsStatusChanged(ttsRequest: TtsRequest) {
        Log.d(TAG, "TTS Status changed: ${ttsRequest.status} for message: ${ttsRequest.speech}")

        when (ttsRequest.status) {
            TtsRequest.Status.STARTED -> {
                isSpeaking = true
            }
            TtsRequest.Status.COMPLETED,
            TtsRequest.Status.CANCELED,
            TtsRequest.Status.ERROR -> {
                isSpeaking = false

                // Handle pending order completion
                if (pendingOrderCompletion) {
                    pendingOrderCompletion = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        proceedWithOrderCompletion()
                    }, 500)
                }
            }
            TtsRequest.Status.PENDING -> {
                Log.d(TAG, "TTS is pending")
            }
            TtsRequest.Status.PROCESSING -> {
                Log.d(TAG, "TTS is processing")
                isSpeaking = true
            }
            TtsRequest.Status.NOT_ALLOWED -> {
                Log.w(TAG, "TTS not allowed")
                isSpeaking = false

                // Handle pending order completion even when TTS not allowed
                if (pendingOrderCompletion) {
                    pendingOrderCompletion = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        proceedWithOrderCompletion()
                    }, 500)
                }
            }
        }
    }

    private fun speakAndWait(message: String, onComplete: (() -> Unit)? = null) {
        Log.d(TAG, "Speaking: $message")
        isSpeaking = true

        val ttsRequest = TtsRequest.create(message, false)
        robot.speak(ttsRequest)

        onComplete?.let { callback ->
            val checkCompletion = object : Runnable {
                override fun run() {
                    if (!isSpeaking) {
                        callback.invoke()
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed(this, 100)
                    }
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(checkCompletion, 100)
        }
    }

    private fun loadMenu() {
        binding.webView.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Menu loaded successfully")
                    resetInactivityTimer() // Reset timer when menu loads

                    // Hide loading indicator when page is loaded
                    runOnUiThread {
                        binding.webViewLoadingIndicator.visibility = android.view.View.GONE
                    }
                }
            }

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            // Add JavaScript interface to receive order data from the menu
            addJavascriptInterface(WebAppInterface(), "Android")

            // Load your GitHub Pages menu with parameters (no order number initially)
            val menuUrl = "${Constants.MENU_BASE_URL}?t=${tableNo}&session=${sessionId}&orderType=${orderType}"
            Log.d(TAG, "Loading menu: $menuUrl")
            loadUrl(menuUrl)
        }

        // Speak menu introduction
        Handler(Looper.getMainLooper()).postDelayed({
            speakAndWait("Here's our interactive menu. Take your time to browse and tap on items to add them to your order.") {
                Log.d(TAG, "Menu introduction completed")
            }
        }, 1000)
    }

    // JavaScript Interface to receive data from the web menu
    inner class WebAppInterface {

        @JavascriptInterface
        fun onOrderComplete(orderData: String) {
            Log.d(TAG, "Received order data: $orderData")
            resetInactivityTimer() // Reset timer on order interaction

            runOnUiThread {
                try {
                    // Parse the JSON data from the menu
                    val orderJson = gson.fromJson(orderData, JsonObject::class.java)

                    // Extract order information
                    val items = orderJson.getAsJsonArray("items")
                    customerOrderItems = items?.map { it.asString } ?: emptyList()
                    orderTotal = orderJson.get("total")?.asString ?: "0.00"
                    hasReceivedOrder = true

                    Log.d(TAG, "Parsed items: $customerOrderItems")
                    Log.d(TAG, "Total: RM $orderTotal")

                    // Validate order
                    if (customerOrderItems.isEmpty()) {
                        speakAndWait("It looks like you haven't selected any items yet. Please add some items to your order first.") {
                            // Return to menu
                        }
                        return@runOnUiThread
                    }

                    // FIXED: Send webhook for Temi menu orders and get order number
                    if (!customerChoseQR) {
                        WebhookManager.sendOrderWebhook(
                            sessionId = sessionId,
                            tableNo = tableNo,
                            orderType = orderType,
                            menuItems = customerOrderItems,
                            battery = currentBattery,
                            orderNumber = "TEMI_MENU_ORDER", // Placeholder - server will generate actual number
                            customerName = "Guest",
                            totalAmount = orderTotal,
                            onSuccess = { serverOrderNumber ->
                                // FIXED: Update order number from server response
                                if (serverOrderNumber != null && serverOrderNumber.isNotEmpty()) {
                                    orderNumber = serverOrderNumber
                                    runOnUiThread {
                                        binding.tvOrderNumber.text = "Order #$orderNumber"
                                    }
                                    Log.d(TAG, "✅ Temi menu order webhook sent successfully with order number: $orderNumber")
                                } else {
                                    // Fallback if no order number returned
                                    orderNumber = "PENDING"
                                    runOnUiThread {
                                        binding.tvOrderNumber.text = "Order Processing"
                                    }
                                    Log.w(TAG, "⚠️ Temi menu order webhook sent but no order number returned")
                                }

                                // Show order confirmation with proper order number
                                if (!isSpeaking) {
                                    showOrderConfirmation()
                                } else {
                                    pendingOrderCompletion = true
                                }
                            },
                            onError = { error ->
                                Log.e(TAG, "❌ Failed to send Temi menu order webhook: $error")
                                // Still proceed with order confirmation but without order number
                                orderNumber = "ERROR"
                                runOnUiThread {
                                    binding.tvOrderNumber.text = "Order #ERROR"
                                }

                                if (!isSpeaking) {
                                    showOrderConfirmation()
                                } else {
                                    pendingOrderCompletion = true
                                }
                            }
                        )
                    } else {
                        Log.d(TAG, "Skipping webhook for QR-based order - web will handle it")
                        // For QR orders, don't send webhook but still show confirmation
                        if (!isSpeaking) {
                            showOrderConfirmation()
                        } else {
                            pendingOrderCompletion = true
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing order data", e)
                    // Fallback: still complete the order but with generic message
                    customerOrderItems = listOf("Custom Order from Menu")
                    orderTotal = "0.00"
                    hasReceivedOrder = true
                    orderNumber = "FALLBACK"

                    // Update UI
                    runOnUiThread {
                        binding.tvOrderNumber.text = "Order #$orderNumber"
                    }

                    if (!isSpeaking) {
                        showOrderConfirmation()
                    } else {
                        pendingOrderCompletion = true
                    }
                }
            }
        }

        @JavascriptInterface
        fun onOrderUpdated(orderData: String) {
            Log.d(TAG, "Order updated: $orderData")
            resetInactivityTimer() // Reset timer on order updates
        }

        @JavascriptInterface
        fun onOrderCancelled() {
            Log.d(TAG, "Order cancelled by customer")
            resetInactivityTimer() // Reset timer on order cancellation
            runOnUiThread {
                speakAndWait("No problem! Your order has been cancelled. Feel free to browse the menu again and add items when you're ready.") {
                    // Reset state
                    hasReceivedOrder = false
                    customerOrderItems = emptyList()
                    orderTotal = "0.00"
                    orderNumber = ""
                    binding.tvOrderNumber.text = "Ready to Order"
                }
            }
        }

        @JavascriptInterface
        fun onMenuReady() {
            Log.d(TAG, "Menu is ready for interaction")
            resetInactivityTimer() // Reset timer when menu becomes ready
        }

        @JavascriptInterface
        fun logMessage(message: String) {
            Log.d("MenuWebView", message)
        }

        @JavascriptInterface
        fun requestHelp(helpType: String) {
            Log.d(TAG, "Customer requested help: $helpType")
            resetInactivityTimer() // Reset timer on help requests
            runOnUiThread {
                val helpMessage = when (helpType.lowercase()) {
                    "navigation" -> "To navigate the menu, simply tap on the categories at the top, then tap on any item to see details and add it to your order."
                    "ordering" -> "To place an order, tap on items to add them to your cart. When you're ready, tap the 'Complete Order' button."
                    "payment" -> "Payment will be handled at the counter after your order is prepared. We accept cash and card payments."
                    else -> "I'm here to help! You can browse the menu by tapping on categories and items. When ready, tap 'Complete Order' to send your order to the kitchen."
                }
                speakAndWait(helpMessage) {}
            }
        }

        @JavascriptInterface
        fun onUserInteraction() {
            // Called whenever user interacts with the menu
            resetInactivityTimer()
        }
    }

    private fun showOrderConfirmation() {
        stopInactivityTimer() // Stop monitoring once order is confirmed

        val itemsText = if (customerOrderItems.isNotEmpty()) {
            customerOrderItems.joinToString(", ")
        } else {
            "Your selected items"
        }

        // FIXED: Use actual order number and customer order details in TTS
        val confirmationMessage = when {
            orderNumber.isEmpty() || orderNumber == "PENDING" -> {
                "Order received! Your order for ${itemsText.take(100)} totaling ${orderTotal} ringgit is being processed."
            }
            orderNumber == "ERROR" -> {
                "Order received! Your order for ${itemsText.take(100)} totaling ${orderTotal} ringgit has been sent to the kitchen."
            }
            customerOrderItems.isEmpty() -> {
                "Order number ${orderNumber} has been sent to the kitchen!"
            }
            customerOrderItems.size <= 2 -> {
                "Order number ${orderNumber} confirmed! Your ${itemsText} for ${orderTotal} ringgit is being prepared."
            }
            else -> {
                "Order number ${orderNumber} confirmed! Your ${customerOrderItems.size} items totaling ${orderTotal} ringgit are being prepared."
            }
        }

        Log.d(TAG, "Speaking confirmation with order number: $orderNumber")
        Log.d(TAG, "Customer items: $customerOrderItems")
        Log.d(TAG, "Order total: RM $orderTotal")

        speakAndWait(confirmationMessage) {
            updateUIForOrderComplete(itemsText)

            // Start return sequence after shorter delay
            Handler(Looper.getMainLooper()).postDelayed({
                returnAfterOrder()
            }, 2000)
        }
    }

    private fun updateUIForOrderComplete(itemsText: String) {
        runOnUiThread {
            // Update UI to show confirmation using new container IDs
            binding.apply {
                tvTitle.text = "Order Confirmed! ✅"
                // FIXED: Show actual order number
                tvOrderNumber.text = if (orderNumber.isNotEmpty() && orderNumber != "PENDING") {
                    "Order #$orderNumber"
                } else {
                    "Order Processing"
                }

                layoutChoiceButtons.visibility = android.view.View.GONE
                layoutQrCode.visibility = android.view.View.GONE
                webViewContainer.visibility = android.view.View.GONE
                completeOrderContainer.visibility = android.view.View.GONE
                btnBack.visibility = android.view.View.GONE

                // FIXED: Show order summary with actual customer data
                val summaryText = """
                ✅ Order Confirmed!
                ${if (orderNumber.isNotEmpty() && orderNumber != "PENDING") "Order #$orderNumber" else "Order Processing"}
                
                ${if (customerOrderItems.isNotEmpty()) customerOrderItems.joinToString("\n• ", "• ") else "Your order"}
                Total: RM $orderTotal
                
                Battery: ${currentBattery}%
                Thank you for your order!
            """.trimIndent()

                tvBattery.text = summaryText
            }
        }
    }

    private fun returnAfterOrder() {
        // FIXED: Use actual order number in return message
        val finalOrderNumber = when {
            orderNumber.isNotEmpty() && orderNumber != "PENDING" && orderNumber != "ERROR" -> "number $orderNumber"
            else -> ""
        }

        val returnMessage = if (finalOrderNumber.isNotEmpty()) {
            "Your order $finalOrderNumber is being prepared. Thank you! I'm returning to my position now."
        } else {
            "Your order is being prepared. Thank you! I'm returning to my position."
        }

        speakAndWait(returnMessage) {
            // CRITICAL FIX: Release table if it's a takeaway order using TableStatusManager
            if (orderType.equals("Takeaway", ignoreCase = true)) {
                Log.d(TAG, "Releasing takeaway table $tableNo via TableStatusManager")
                tableStatusManager.releaseTable(
                    sessionId = sessionId,
                    tableId = tableNo,
                    onSuccess = {
                        Log.d(TAG, "✅ Takeaway table $tableNo released successfully")
                    },
                    onError = { error ->
                        Log.e(TAG, "❌ Failed to release takeaway table: $error")
                        // Fallback to local release
                        tableStatusManager.removeOccupiedTable(tableNo)
                    }
                )
            } else {
                // For dine-in orders, keep table occupied but log the order completion
                Log.d(TAG, "Dine-in order completed for table $tableNo - keeping table occupied")
            }

            returnToStandby()
        }
    }

    private fun completeOrder() {
        resetInactivityTimer()

        if (!hasReceivedOrder) {
            speakAndWait("Please select items from the menu first, then use the order button in the menu.") {
                // Guide user back to menu
            }
            return
        }

        if (!isSpeaking) {
            showOrderConfirmation()
        } else {
            pendingOrderCompletion = true
        }
    }

    private fun proceedWithOrderCompletion() {
        showOrderConfirmation()
    }

    private fun handleBackPressed() {
        speakAndWait("Going back to table selection.") {
            // CRITICAL FIX: When going back, release the table locally to make it available again
            Log.d(TAG, "Back pressed - releasing table $tableNo locally")
            tableStatusManager.removeOccupiedTable(tableNo)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopInactivityTimer()
        stopQRDisplayTimer()

        // CRITICAL: Proper WebView cleanup to prevent memory leaks
        try {
            binding.webView.apply {
                // Stop loading
                stopLoading()

                // Clear WebView
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")

                // Remove JavaScript interface
                removeJavascriptInterface("Android")

                // Remove from parent
                (parent as? android.view.ViewGroup)?.removeView(this)

                // Destroy WebView
                destroy()
            }

            Log.d(TAG, "✅ WebView cleaned up properly")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cleaning up WebView", e)
        }
    }

    override fun onBackPressed() {
        if (!isSpeaking) {
            handleBackPressed()
        } else {
            super.onBackPressed()
        }
    }

    // Handle user interactions to reset timer
    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }
}