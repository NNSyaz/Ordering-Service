package com.example.orderingservice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.orderingservice.databinding.ActivityGreetingBinding
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnRobotReadyListener
import com.robotemi.sdk.listeners.OnBatteryStatusChangedListener
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.BatteryData
import java.util.*

class MainActivity : AppCompatActivity(),
    OnRobotReadyListener,
    OnBatteryStatusChangedListener,
    Robot.TtsListener,
    OnGoToLocationStatusChangedListener {

    private lateinit var binding: ActivityGreetingBinding
    private lateinit var robot: Robot
    private lateinit var tableStatusManager: TableStatusManager
    private var sessionId: String = ""
    private var currentBattery: Int = 100
    private val timeoutHandler = Handler(Looper.getMainLooper())

    // Speech and navigation management
    private var isSpeaking = false
    private var isNavigatingToStandby = false
    private var hasArrivedAtStandby = false
    private var hasGreeted = false
    private var pendingNavigation: (() -> Unit)? = null
    private var staffAccessClickCount = 0
    private var lastClickTime = 0L
    private val staffAccessHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TIMEOUT_DURATION = 60000L // 60 seconds for user interaction
        const val LOW_BATTERY_THRESHOLD = 25
        const val CRITICAL_BATTERY_THRESHOLD = 15
        const val STANDBY_LOCATION = "standby"
        const val AUTO_GREETING_DELAY = 3000L // 3 seconds delay before auto greeting
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGreetingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Generate new session ID
        sessionId = UUID.randomUUID().toString()
        Log.d(TAG, "New session started: $sessionId")

        setupUI()
        initializeTemi()
        initializeTableStatusManager()

        // Don't go to standby immediately - wait for robot to be ready
        Log.d(TAG, "Waiting for robot to be ready before navigation...")
    }

    private fun setupUI() {
        binding.apply {
            btnDineIn.setOnClickListener {
                if (!isSpeaking) {
                    navigateToTableSelection("Dine-in")
                } else {
                    // Queue the navigation for after speech completes
                    pendingNavigation = { navigateToTableSelection("Dine-in") }
                }
            }

            btnTakeaway.setOnClickListener {
                if (!isSpeaking) {
                    navigateToOrdering("Takeaway", "Counter")
                } else {
                    // Queue the navigation for after speech completes
                    pendingNavigation = { navigateToOrdering("Takeaway", "Counter") }
                }
            }

            tvWelcome.text = "🚀 Getting ready to greet you..."
            tvBattery.text = "Battery: ${currentBattery}%"

            // Initially hide buttons until ready
            btnDineIn.visibility = android.view.View.INVISIBLE
            btnTakeaway.visibility = android.view.View.INVISIBLE
        }
        setupStaffAccess()  // Add staff close button functionality
    }

    private fun initializeTemi() {
        robot = Robot.getInstance()
    }

    private fun initializeTableStatusManager() {
        tableStatusManager = TableStatusManager.getInstance()

        // CRITICAL FIX: Initialize table status manager and check server connectivity
        Log.d(TAG, "Initializing TableStatusManager and checking server status...")

        // Perform initial refresh to check server connectivity
        tableStatusManager.refreshTableStatus(
            onSuccess = { occupiedTables ->
                Log.d(TAG, "✅ TableStatusManager initialized successfully")
                Log.d(TAG, "Initial occupied tables: $occupiedTables")
                Log.d(TAG, "Server status: ${if (tableStatusManager.isServerAvailable()) "Available" else "Unavailable"}")
            },
            onError = { error ->
                Log.w(TAG, "⚠️ TableStatusManager initialization with server error: $error")
                Log.d(TAG, "Server status: ${if (tableStatusManager.isServerAvailable()) "Available" else "Unavailable"}")
                // Continue anyway - will use local fallback if needed
            }
        )
    }

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
        robot.addOnBatteryStatusChangedListener(this)
        robot.addTtsListener(this)
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    override fun onStop() {
        super.onStop()
        robot.removeOnRobotReadyListener(this)
        robot.removeOnBatteryStatusChangedListener(this)
        robot.removeTtsListener(this)
        robot.removeOnGoToLocationStatusChangedListener(this)
        timeoutHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutHandler.removeCallbacksAndMessages(null)
        staffAccessHandler.removeCallbacksAndMessages(null)  // Clean up staff access handler

        // CRITICAL FIX: Cleanup TableStatusManager when app is destroyed
        try {
            Log.d(TAG, "Cleaning up TableStatusManager...")
            tableStatusManager.cleanup()
            Log.d(TAG, "✅ TableStatusManager cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cleaning up TableStatusManager", e)
        }
    }

    private fun goToStandbyPosition() {
        Log.d(TAG, "Attempting to go to standby position")

        // Check if robot is ready first
        if (!robot.isReady) {
            Log.w(TAG, "Robot not ready for navigation, waiting...")
            binding.tvWelcome.text = "⚠️ Robot not ready, please wait..."

            // Retry after a delay
            Handler(Looper.getMainLooper()).postDelayed({
                if (robot.isReady) {
                    goToStandbyPosition()
                } else {
                    Log.e(TAG, "Robot still not ready after delay, starting from current position")
                    hasArrivedAtStandby = true
                    startAutoGreeting()
                }
            }, 3000)
            return
        }

        binding.tvWelcome.text = "🚶‍♂️ Moving to greeting position..."
        isNavigatingToStandby = true
        hasArrivedAtStandby = false
        hasGreeted = false

        try {
            Log.d(TAG, "Calling robot.goTo($STANDBY_LOCATION)")
            robot.goTo(STANDBY_LOCATION)
        } catch (e: Exception) {
            Log.e(TAG, "Error calling goTo: ${e.message}")
            isNavigatingToStandby = false

            // Fallback to current position
            binding.tvWelcome.text = "⚠️ Navigation error, starting from here..."
            hasArrivedAtStandby = true
            Handler(Looper.getMainLooper()).postDelayed({
                startAutoGreeting()
            }, 2000)
        }
    }

    override fun onRobotReady(isReady: Boolean) {
        if (isReady) {
            Log.d(TAG, "Robot is ready!")

            // Check initial battery
            val batteryData = robot.batteryData
            currentBattery = batteryData?.level ?: 100
            updateBatteryDisplay()
            checkBatteryStatus()

            // Check if standby location exists
            checkAndGoToStandby()
        } else {
            Log.w(TAG, "Robot is not ready yet")
            binding.tvWelcome.text = "⚠️ Robot not ready, please wait..."
        }
    }

    private fun checkAndGoToStandby() {
        // Get list of saved locations to verify standby exists
        val locations = robot.locations
        Log.d(TAG, "Available locations: ${locations.joinToString(", ")}")

        if (locations.contains(STANDBY_LOCATION)) {
            Log.d(TAG, "Standby location found, navigating...")
            goToStandbyPosition()
        } else {
            Log.w(TAG, "Standby location '$STANDBY_LOCATION' not found!")
            Log.w(TAG, "Available locations: ${locations.joinToString(", ")}")

            // Try alternative locations
            val alternativeLocations = listOf("greeting", "entrance", "front", "lobby")
            val foundAlternative = alternativeLocations.find { locations.contains(it) }

            if (foundAlternative != null) {
                Log.d(TAG, "Using alternative location: $foundAlternative")
                binding.tvWelcome.text = "📍 Going to $foundAlternative position..."
                robot.goTo(foundAlternative)
                isNavigatingToStandby = true
            } else {
                Log.w(TAG, "No suitable location found, starting greeting from current position")
                hasArrivedAtStandby = true
                binding.tvWelcome.text = "👋 Ready to greet customers..."
                Handler(Looper.getMainLooper()).postDelayed({
                    startAutoGreeting()
                }, 2000)
            }
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        Log.d(TAG, "Navigation status: $status for location: $location (description: $description)")

        // Handle both standby and alternative locations
        if ((location == STANDBY_LOCATION || location in listOf("standby")) && isNavigatingToStandby) {
            when (status) {
                "start" -> {
                    Log.d(TAG, "Started navigation to $location")
                    binding.tvWelcome.text = "🚶‍♂️ Moving to greeting position..."
                }
                "going" -> {
                    Log.d(TAG, "Going to $location")
                    binding.tvWelcome.text = "🎯 On my way to greet customers..."
                }
                "complete" -> {
                    Log.d(TAG, "Arrived at $location")
                    hasArrivedAtStandby = true
                    isNavigatingToStandby = false
                    binding.tvWelcome.text = "👋 Ready to welcome customers..."

                    // Start auto greeting after arrival
                    Handler(Looper.getMainLooper()).postDelayed({
                        startAutoGreeting()
                    }, 1000)
                }
                "abort" -> {
                    Log.w(TAG, "Failed to reach $location - reason: $description")
                    isNavigatingToStandby = false
                    hasArrivedAtStandby = true // Treat as arrived for fallback
                    binding.tvWelcome.text = "⚠️ Navigation failed, starting from here..."

                    // Fallback: start greeting anyway after a delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        startAutoGreeting()
                    }, 2000)
                }
                else -> {
                    Log.d(TAG, "Navigation status '$status' for $location: $description")
                }
            }
        }
    }

    private fun startAutoGreeting() {
        if (!hasArrivedAtStandby || hasGreeted) {
            Log.d(TAG, "Skipping auto greeting - not ready or already greeted")
            return
        }

        Log.d(TAG, "Starting auto greeting sequence")

        // CRITICAL FIX: Refresh table status before greeting customers
        refreshTableStatusBeforeGreeting()

        // Wait a bit then start greeting automatically
        Handler(Looper.getMainLooper()).postDelayed({
            if (!hasGreeted) {
                greetCustomer()
            }
        }, AUTO_GREETING_DELAY)
    }

    /**
     * CRITICAL FIX: Refresh table status before greeting to ensure accurate availability
     */
    private fun refreshTableStatusBeforeGreeting() {
        Log.d(TAG, "🔄 Refreshing table status before greeting customers...")

        tableStatusManager.refreshTableStatus(
            onSuccess = { occupiedTables ->
                Log.d(TAG, "✅ Table status refreshed successfully before greeting")
                Log.d(TAG, "Currently occupied tables: $occupiedTables")

                if (occupiedTables.isNotEmpty()) {
                    Log.d(TAG, "📊 Table occupancy status:")
                    occupiedTables.forEach { tableId ->
                        Log.d(TAG, "  - $tableId: OCCUPIED")
                    }
                }

                // Log current TableStatusManager status
                Log.d(TAG, tableStatusManager.getDetailedStatus())
            },
            onError = { error ->
                Log.w(TAG, "⚠️ Table status refresh error before greeting: $error")
                Log.d(TAG, "Server available: ${tableStatusManager.isServerAvailable()}")

                // Still show detailed status for debugging
                Log.d(TAG, tableStatusManager.getDetailedStatus())
            }
        )
    }

    private fun greetCustomer() {
        if (hasGreeted) {
            return
        }

        hasGreeted = true
        Log.d(TAG, "Starting customer greeting")

        val greetingMessages = listOf(
            "Hello there! Welcome to our restaurant! I'm your friendly assistant robot. How would you like to enjoy your meal with us?",
            "Hi! Welcome to our restaurant! I'm here to make your dining experience fantastic. What sounds good to you today?",
            "Greetings, valued customer! Welcome to our restaurant! I'm your personal dining assistant, ready to help you have an incredible meal experience."
        )

        val randomGreeting = greetingMessages.random()
        binding.tvWelcome.text = "😊 Hello! Welcome to our restaurant!"

        speakAndWait(randomGreeting) {
            showOrderingOptions()
        }
    }

    private fun showOrderingOptions() {
        runOnUiThread {
            binding.tvWelcome.text = "Welcome to our restaurant!\nHow would you like to order today?"
            binding.btnDineIn.visibility = android.view.View.VISIBLE
            binding.btnTakeaway.visibility = android.view.View.VISIBLE

            // Start user interaction timeout
            startTimeoutTimer()
        }
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

                // Execute any pending navigation
                pendingNavigation?.let { navigation ->
                    pendingNavigation = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        navigation.invoke()
                    }, 500) // Small delay for better UX
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

                // Execute any pending navigation even when not allowed
                pendingNavigation?.let { navigation ->
                    pendingNavigation = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        navigation.invoke()
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
            // Monitor TTS completion
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

    override fun onBatteryStatusChanged(batteryData: BatteryData?) {
        batteryData?.let { data ->
            currentBattery = data.level
            updateBatteryDisplay()
            checkBatteryStatus()
        }
    }

    private fun updateBatteryDisplay() {
        runOnUiThread {
            val batteryEmoji = when {
                currentBattery > 80 -> "🔋"
                currentBattery > 50 -> "🔋"
                currentBattery > 25 -> "🪫"
                else -> "🪫"
            }

            binding.tvBattery.text = "$batteryEmoji Battery: ${currentBattery}%"

            // Change color based on battery level
            binding.tvBattery.setTextColor(
                when {
                    currentBattery <= CRITICAL_BATTERY_THRESHOLD -> getColor(android.R.color.holo_red_dark)
                    currentBattery <= LOW_BATTERY_THRESHOLD -> getColor(android.R.color.holo_orange_dark)
                    else -> getColor(android.R.color.holo_green_dark)
                }
            )
        }
    }

    private fun checkBatteryStatus() {
        if (currentBattery <= CRITICAL_BATTERY_THRESHOLD) {
            speakAndWait("I'm sorry, but my battery is critically low and I need to return to my charging station right away. Please look for another assistant or visit our counter for help. Thank you for understanding!") {
                robot.goTo("home base")
                finish()
            }
        } else if (currentBattery <= LOW_BATTERY_THRESHOLD) {
            robot.speak(TtsRequest.create("Just so you know, my battery is getting a bit low, but I can still help you with your order!", false))
        }
    }

    private fun setupStaffAccess() {
        // Triple tap on app title to reveal close button (staff access)
        binding.tvAppTitle.setOnClickListener {
            val currentTime = System.currentTimeMillis()

            // Reset count if more than 2 seconds between clicks
            if (currentTime - lastClickTime > 2000) {
                staffAccessClickCount = 0
            }

            staffAccessClickCount++
            lastClickTime = currentTime

            if (staffAccessClickCount >= 3) {
                // Show staff close button
                binding.staffCloseButton.visibility = android.view.View.VISIBLE
                staffAccessClickCount = 0

                speakAndWait("Staff access activated. Close button is now available.") {}

                // Auto-hide after 30 seconds
                staffAccessHandler.postDelayed({
                    binding.staffCloseButton.visibility = android.view.View.GONE
                    speakAndWait("Staff close button hidden for security.") {}
                }, 30000)
            } else if (staffAccessClickCount == 2) {
                speakAndWait("Tap once more to access staff controls.") {}
            }
        }

        // Close button functionality
        binding.btnStaffClose.setOnClickListener {
            speakAndWait("Application shutting down. Have a great day!") {
                // Clean up resources
                try {
                    tableStatusManager.cleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up during close", e)
                }

                // Close application completely
                finishAffinity()
                System.exit(0)
            }
        }
    }
    private fun navigateToTableSelection(orderType: String) {
        timeoutHandler.removeCallbacksAndMessages(null)

        val messages = listOf(
            "Excellent choice! Let me show you our wonderful selection of available tables where you can enjoy your meal in comfort.",
            "Perfect! I'll guide you to see all our lovely tables so you can pick the perfect spot for your dining experience.",
            "Great decision! Let me present you with our available seating options so you can choose your ideal dining spot."
        )

        speakAndWait(messages.random()) {
            val intent = Intent(this, NavigationActivity::class.java).apply {
                putExtra("SESSION_ID", sessionId)
                putExtra("ORDER_TYPE", orderType)
                putExtra("BATTERY", currentBattery)
            }
            startActivity(intent)
        }
    }

    private fun navigateToOrdering(orderType: String, tableNo: String) {
        timeoutHandler.removeCallbacksAndMessages(null)

        val messages = listOf(
            "Fantastic choice! Let's get started!",
            "Perfect for on-the-go! I'm excited to help you put together a delicious takeaway meal.",
            "Great idea! Let me assist you in selecting some amazing dishes for your takeaway order."
        )

        speakAndWait(messages.random()) {
            // CRITICAL FIX: For takeaway, immediately mark as occupied locally to prevent conflicts
            if (orderType.equals("Takeaway", ignoreCase = true)) {
                Log.d(TAG, "Marking takeaway counter as occupied locally")
                tableStatusManager.addOccupiedTable("takeaway")
            }

            val intent = Intent(this, OrderingActivity::class.java).apply {
                putExtra("SESSION_ID", sessionId)
                putExtra("ORDER_TYPE", orderType)
                putExtra("TABLE_NO", tableNo)
                putExtra("BATTERY", currentBattery)
            }
            startActivity(intent)
        }
    }

    private fun startTimeoutTimer() {
        timeoutHandler.postDelayed({
            handleTimeout()
        }, TIMEOUT_DURATION)
    }

    private fun handleTimeout() {
        if (!isSpeaking) {
            val timeoutMessages = listOf(
                "Thank you for visiting our restaurant! I'll be right here waiting if you need any assistance with your dining experience.",
                "No worries at all! I'm here whenever you're ready to order. Just let me know when you'd like to get started!",
                "Take your time! I'm happy to wait here for you. When you're ready to order, I'll be right here to help make it amazing!"
            )

            speakAndWait(timeoutMessages.random()) {
                // Reset to greeting screen
                Handler(Looper.getMainLooper()).postDelayed({
                    resetToGreeting()
                }, 3000)
            }
        }
    }

    private fun resetToGreeting() {
        hasGreeted = false
        hasArrivedAtStandby = false
        binding.btnDineIn.visibility = android.view.View.VISIBLE
        binding.btnTakeaway.visibility = android.view.View.VISIBLE

        // CRITICAL FIX: Refresh table status when resetting to ensure accuracy
        Log.d(TAG, "Resetting to greeting - refreshing table status for accuracy")
        refreshTableStatusBeforeGreeting()

        Handler(Looper.getMainLooper()).postDelayed({
            goToStandbyPosition()
        }, 1000)
    }
}
