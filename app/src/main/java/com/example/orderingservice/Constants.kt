package com.example.orderingservice

object Constants {
    // Timeouts
    const val USER_INTERACTION_TIMEOUT = 60000L // 60 seconds
    const val NAVIGATION_TIMEOUT = 120000L // 2 minutes
    const val ORDER_COMPLETION_DELAY = 5000L // 5 seconds
    const val FACE_DETECTION_TIMEOUT = 10000L // 10 seconds
    const val SPEECH_COMPLETION_TIMEOUT = 500L // 500ms delay after speech

    // Battery thresholds
    const val CRITICAL_BATTERY = 15
    const val LOW_BATTERY = 25
    const val OPTIMAL_BATTERY = 50

    // Table occupancy
    const val TABLE_OCCUPATION_MINUTES = 30
    const val AUTO_RELEASE_TIMEOUT = 30 * 60 * 1000L // 30 minutes
    const val TABLE_STATUS_REFRESH_INTERVAL = 60000L // 1 minute

    // Locations - CUSTOMIZE THESE FOR YOUR RESTAURANT
    const val HOME_BASE_LOCATION = "home base"
    const val STANDBY_LOCATION = "standby"
    const val COUNTER_LOCATION = "counter"
    const val CHARGING_STATION = "home base"

    // Navigation locations for tables - map table IDs to Temi locations
    val TABLE_LOCATIONS = mapOf(
        "table1" to "table_1_location",
        "table2" to "table_2_location",
        "table3" to "table_3_location",
        "table4" to "table_4_location",
        "table5" to "table_5_location",
        "counter" to COUNTER_LOCATION
    )

    // URLs - UPDATED WITH NEW GOOGLE APPS SCRIPT URL
    const val MENU_BASE_URL = "https://nnsyaz.github.io/restaurant-menu/"
    const val WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbzFwuNl9EHue-XMDEd14qPIJ1SC0IMgFHx00O60nKxteS1gqL80xi1WdSo8pM_yoi57NQ/exec"
    const val TABLE_STATUS_API_URL = "https://script.google.com/macros/s/AKfycbzFwuNl9EHue-XMDEd14qPIJ1SC0IMgFHx00O60nKxteS1gqL80xi1WdSo8pM_yoi57NQ/exec?action=get_table_status"

    // Face Detection Settings (for Temi SDK 1.136.0)
    const val FACE_DETECTION_ENABLED = true
    const val FACE_DETECTION_DISTANCE = 2.5f // meters
    const val FACE_DETECTION_CONFIDENCE = 0.7f
    const val MIN_FACE_SIZE = 100 // pixels
    const val MAX_FACES_TO_DETECT = 5

    // Speech Settings
    const val DEFAULT_SPEECH_SPEED = 1.0f
    const val DEFAULT_SPEECH_PITCH = 1.0f
    const val ENABLE_SPEECH_QUEUE = true
    const val MAX_SPEECH_QUEUE_SIZE = 3

    // UI Settings
    const val BUTTON_ANIMATION_DURATION = 300L
    const val STATUS_UPDATE_INTERVAL = 5000L // 5 seconds
    const val BATTERY_UPDATE_INTERVAL = 30000L // 30 seconds

    // Restaurant Settings - CUSTOMIZE THESE
    const val RESTAURANT_NAME = "Robopreneur Restaurant"
    const val SUPPORT_CONTACT = "rapid@robopreneur.com"
    const val WIFI_SSID = "RestaurantWiFi" // For network connectivity checks

    // Error Messages
    const val ERROR_NETWORK_UNAVAILABLE = "Network connection is not available. Some features may not work properly."
    const val ERROR_LOCATION_NOT_FOUND = "I couldn't find that location. Please try again or contact staff for assistance."
    const val ERROR_BATTERY_LOW = "My battery is getting low. I may need to return to charging soon."
    const val ERROR_NAVIGATION_FAILED = "I had trouble navigating to that location. Let me try again."
    const val ERROR_ORDER_FAILED = "There was an issue processing your order. Please try again or ask staff for help."

    // Success Messages
    const val SUCCESS_ORDER_PLACED = "Your order has been successfully placed and sent to the kitchen!"
    const val SUCCESS_NAVIGATION_COMPLETE = "We've arrived at your destination!"
    const val SUCCESS_TABLE_SELECTED = "Great choice! Let me take you to your table."

    // Greeting Messages - Randomized for variety
    val GREETING_MESSAGES = listOf(
        "Hello! Welcome to $RESTAURANT_NAME! I'm here to help you with your order.",
        "Welcome! I'm your robotic assistant. How can I help you dine with us today?",
        "Hi there! Ready to explore our delicious menu? I'm here to guide you!",
        "Welcome to $RESTAURANT_NAME! I'm excited to help you have a great dining experience!"
    )

    val FACE_DETECTED_GREETINGS = listOf(
        "Hello there! I can see you're ready to order. Welcome to $RESTAURANT_NAME!",
        "Hi! I noticed you approaching. How can I help you with your dining experience today?",
        "Welcome! I'm here to make your ordering experience smooth and enjoyable!",
        "Hello! Perfect timing - I'm ready to help you explore our menu!"
    )

    // Utility Functions
    fun getRandomGreeting(): String {
        return GREETING_MESSAGES.random()
    }

    fun getRandomFaceDetectedGreeting(): String {
        return FACE_DETECTED_GREETINGS.random()
    }

    fun getTableLocationId(tableId: String): String {
        return TABLE_LOCATIONS[tableId] ?: tableId
    }

    fun isValidTableId(tableId: String): Boolean {
        return TABLE_LOCATIONS.containsKey(tableId)
    }

    // Network timeout settings
    const val NETWORK_CONNECT_TIMEOUT = 10L // seconds
    const val NETWORK_READ_TIMEOUT = 30L // seconds
    const val NETWORK_WRITE_TIMEOUT = 10L // seconds

    // Retry settings
    const val MAX_RETRY_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 1000L
    const val EXPONENTIAL_BACKOFF_MULTIPLIER = 2.0

    // Logging
    const val LOG_TAG = "TemiOrderingService"
    const val ENABLE_DEBUG_LOGGING = true
    const val MAX_LOG_ENTRIES = 1000

    // Performance monitoring
    const val PERFORMANCE_MONITORING_ENABLED = true
    const val TRACK_NAVIGATION_TIME = true
    const val TRACK_ORDER_COMPLETION_TIME = true
    const val TRACK_SPEECH_DURATION = true
}