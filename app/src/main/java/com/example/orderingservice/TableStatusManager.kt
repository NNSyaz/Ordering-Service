package com.example.orderingservice

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TableStatusManager private constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // UPDATED URLs with new Google Apps Script
    private val GET_TABLE_STATUS_URL = "https://script.google.com/macros/s/AKfycbzFwuNl9EHue-XMDEd14qPIJ1SC0IMgFHx00O60nKxteS1gqL80xi1WdSo8pM_yoi57NQ/exec?action=get_table_status"
    private val WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbzFwuNl9EHue-XMDEd14qPIJ1SC0IMgFHx00O60nKxteS1gqL80xi1WdSo8pM_yoi57NQ/exec"

    private val listeners = mutableSetOf<TableStatusListener>()

    // CRITICAL FIX: Properly maintain local state to sync with server
    private var currentOccupiedTables = mutableSetOf<String>() // Use Set for better performance
    private var serverOccupiedTables = mutableSetOf<String>() // Track server state separately
    private var localOccupiedTables = mutableSetOf<String>() // Track local state separately

    // Local fallback for when server is unavailable
    private var useLocalFallback = false
    private var lastServerCheckTime = 0L
    private val SERVER_RETRY_INTERVAL = 5 * 60 * 1000L // 5 minutes
    private var isServerAvailable = true

    companion object {
        private const val TAG = "TableStatusManager"

        @Volatile
        private var INSTANCE: TableStatusManager? = null

        fun getInstance(): TableStatusManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TableStatusManager().also { INSTANCE = it }
            }
        }
    }

    interface TableStatusListener {
        fun onTableStatusChanged(occupiedTables: List<String>)
    }

    data class TableOccupancyPayload(
        val session_id: String,
        val table_id: String,
        val order_type: String,
        val battery: Int,
        val status: String = "table_occupied",
        val timestamp: Long = System.currentTimeMillis(),
        val customer_name: String = "Guest"
    )

    data class TableReleasePayload(
        val session_id: String,
        val table_id: String,
        val status: String = "table_released",
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addListener(listener: TableStatusListener) {
        listeners.add(listener)
        Log.d(TAG, "Added table status listener. Total listeners: ${listeners.size}")
    }

    fun removeListener(listener: TableStatusListener) {
        listeners.remove(listener)
        Log.d(TAG, "Removed table status listener. Total listeners: ${listeners.size}")
    }

    /**
     * Refresh table status from server with better error handling and local state management
     */
    fun refreshTableStatus(
        onSuccess: ((List<String>) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val currentTime = System.currentTimeMillis()

        // Check if we should retry the server or use local fallback
        if (useLocalFallback && (currentTime - lastServerCheckTime) < SERVER_RETRY_INTERVAL) {
            Log.d(TAG, "Using local fallback, server retry not due yet")
            val combinedTables = getCombinedOccupiedTables()
            onSuccess?.invoke(combinedTables.toList())
            return
        }

        Log.d(TAG, "Refreshing table status from server: $GET_TABLE_STATUS_URL")
        Log.d(TAG, "Current local occupied tables: $localOccupiedTables")

        val request = Request.Builder()
            .url(GET_TABLE_STATUS_URL)
            .get()
            .addHeader("User-Agent", "OrderingService/1.0")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val errorMsg = "Network error while fetching table status: ${e.message}"
                Log.e(TAG, errorMsg, e)

                handleServerError(errorMsg, onSuccess, onError)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        try {
                            val responseBody = resp.body?.string() ?: ""
                            Log.d(TAG, "Table status response received (${responseBody.length} chars)")
                            Log.d(TAG, "Raw response: ${responseBody.take(200)}") // Log first 200 chars

                            // Check if response looks like HTML (error page)
                            if (responseBody.trim().startsWith("<!DOCTYPE html") ||
                                responseBody.contains("<html>") ||
                                responseBody.contains("Script function not found")) {

                                Log.w(TAG, "Received HTML error page instead of JSON")
                                handleServerError("Google Apps Script error: doGet function not found", onSuccess, onError)
                                return
                            }

                            val serverTables = parseTableStatusResponse(responseBody)

                            // Server is working, disable fallback mode
                            useLocalFallback = false
                            lastServerCheckTime = System.currentTimeMillis()
                            isServerAvailable = true

                            // CRITICAL FIX: Update server state and merge with local state
                            updateServerTables(serverTables)

                            val combinedTables = getCombinedOccupiedTables()
                            Log.d(TAG, "Successfully parsed server tables: $serverTables")
                            Log.d(TAG, "Combined with local tables: $combinedTables")

                            onSuccess?.invoke(combinedTables.toList())

                            // Notify listeners of the change
                            notifyListeners()

                        } catch (e: Exception) {
                            val errorMsg = "Error parsing table status response: ${e.message}"
                            Log.e(TAG, errorMsg, e)
                            handleServerError(errorMsg, onSuccess, onError)
                        }
                    } else {
                        val errorMsg = "HTTP ${resp.code}: ${resp.message}"
                        Log.e(TAG, "Failed to fetch table status - $errorMsg")
                        handleServerError(errorMsg, onSuccess, onError)
                    }
                }
            }
        })
    }

    /**
     * CRITICAL FIX: Update server tables and maintain combined state
     */
    private fun updateServerTables(newServerTables: List<String>) {
        val previousServerTables = serverOccupiedTables.toSet()
        serverOccupiedTables.clear()
        serverOccupiedTables.addAll(newServerTables)

        Log.d(TAG, "Server tables updated: $previousServerTables -> $serverOccupiedTables")

        // Update combined state
        updateCombinedTables()
    }

    /**
     * CRITICAL FIX: Maintain combined state from both server and local data
     */
    private fun updateCombinedTables() {
        val previousCombined = currentOccupiedTables.toSet()

        // Combine server and local tables
        currentOccupiedTables.clear()
        currentOccupiedTables.addAll(serverOccupiedTables)
        currentOccupiedTables.addAll(localOccupiedTables)

        val currentCombined = currentOccupiedTables.toSet()

        if (previousCombined != currentCombined) {
            Log.d(TAG, "Combined tables changed: $previousCombined -> $currentCombined")
            Log.d(TAG, "  Server: $serverOccupiedTables")
            Log.d(TAG, "  Local: $localOccupiedTables")
            Log.d(TAG, "  Combined: $currentOccupiedTables")
        }
    }

    /**
     * Get combined occupied tables from both server and local state
     */
    private fun getCombinedOccupiedTables(): Set<String> {
        val combined = mutableSetOf<String>()
        combined.addAll(serverOccupiedTables)
        combined.addAll(localOccupiedTables)
        return combined
    }

    private fun handleServerError(
        error: String,
        onSuccess: ((List<String>) -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        useLocalFallback = true
        lastServerCheckTime = System.currentTimeMillis()
        isServerAvailable = false

        Log.w(TAG, "Switching to local fallback mode due to server error: $error")

        // Use combined local and last known server state
        val combinedTables = getCombinedOccupiedTables()
        Log.i(TAG, "Continuing with combined table status. Occupied: ${combinedTables.size} tables - $combinedTables")

        onSuccess?.invoke(combinedTables.toList())

        // Still call onError but don't let it block the operation
        onError?.invoke("Server unavailable, using local data: $error")
    }

    private fun parseTableStatusResponse(responseBody: String): List<String> {
        return try {
            // First, try to handle empty or whitespace-only responses
            if (responseBody.isBlank()) {
                Log.d(TAG, "Empty response body, returning empty list")
                return emptyList()
            }

            // Try to parse as JSON object first
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)

            // Handle different response formats based on new Apps Script structure
            when {
                jsonResponse.has("occupied_tables") -> {
                    val occupiedArray = jsonResponse.getAsJsonArray("occupied_tables")
                    occupiedArray.map { it.asString }
                }
                jsonResponse.has("data") -> {
                    val dataArray = jsonResponse.getAsJsonArray("data")
                    dataArray.mapNotNull { element ->
                        val obj = element.asJsonObject
                        if (obj.has("table_id") && obj.has("status")) {
                            val status = obj.get("status").asString
                            if (status == "occupied" || status == "table_occupied" || status == "Occupied" || status == "Pending") {
                                obj.get("table_id").asString
                            } else null
                        } else null
                    }
                }
                jsonResponse.has("result") -> {
                    // Handle result wrapper format
                    val result = jsonResponse.get("result")
                    if (result.isJsonArray) {
                        result.asJsonArray.map { it.asString }
                    } else {
                        emptyList()
                    }
                }
                jsonResponse.has("status") && jsonResponse.get("status").asString == "success" -> {
                    // New Apps Script returns this format
                    if (jsonResponse.has("occupied_tables")) {
                        val occupiedArray = jsonResponse.getAsJsonArray("occupied_tables")
                        occupiedArray.map { it.asString }
                    } else {
                        emptyList()
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown JSON format, trying as simple array")
                    // Try to parse as simple array
                    try {
                        val array = gson.fromJson(responseBody, JsonArray::class.java)
                        array.map { it.asString }
                    } catch (e: JsonSyntaxException) {
                        Log.w(TAG, "Not a valid JSON array either, returning empty list")
                        emptyList()
                    }
                }
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
            Log.d(TAG, "Problematic response body: ${responseBody.take(500)}") // Log first 500 chars

            // Try to extract any table information from malformed response
            extractTableIdsFromText(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing table status", e)
            emptyList()
        }
    }

    private fun extractTableIdsFromText(text: String): List<String> {
        // Fallback: try to extract table IDs from text using regex
        return try {
            val tablePattern = Regex("table[_\\s]?(\\d+|\\w+)", RegexOption.IGNORE_CASE)
            val matches = tablePattern.findAll(text)
            val tableIds = matches.map { it.value.lowercase().replace(" ", "_") }.distinct().toList()

            if (tableIds.isNotEmpty()) {
                Log.d(TAG, "Extracted table IDs from text: $tableIds")
            }

            tableIds
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting table IDs from text", e)
            emptyList()
        }
    }

    private fun notifyListeners() {
        val currentTables = getCombinedOccupiedTables().toList()
        Log.d(TAG, "Notifying ${listeners.size} listeners of table status change: $currentTables")
        listeners.forEach { listener ->
            try {
                listener.onTableStatusChanged(currentTables)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }

    /**
     * Mark a table as occupied with immediate local update and server sync
     */
    fun markTableOccupied(
        sessionId: String,
        tableId: String,
        orderType: String,
        battery: Int,
        customerName: String = "Guest",
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val normalizedTableId = normalizeTableId(tableId)

        Log.d(TAG, "Marking table $normalizedTableId as occupied (session: $sessionId)")

        // CRITICAL FIX: Immediately update local state for instant UI response
        addOccupiedTable(normalizedTableId)

        val payload = TableOccupancyPayload(
            session_id = sessionId,
            table_id = normalizedTableId,
            order_type = orderType,
            battery = battery,
            customer_name = customerName
        )

        sendTableStatusUpdate(payload, {
            Log.d(TAG, "✅ Table $normalizedTableId occupation webhook sent successfully")
            onSuccess?.invoke()
        }, { error ->
            Log.e(TAG, "❌ Table $normalizedTableId occupation webhook failed: $error")
            // Keep the local state even if server fails - it will sync later
            onError?.invoke(error)
        })
    }

    /**
     * Mark a table as available (released) with immediate local update and server sync
     */
    fun releaseTable(
        sessionId: String,
        tableId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val normalizedTableId = normalizeTableId(tableId)

        Log.d(TAG, "Releasing table $normalizedTableId (session: $sessionId)")

        // CRITICAL FIX: Immediately update local state for instant UI response
        removeOccupiedTable(normalizedTableId)

        val payload = TableReleasePayload(
            session_id = sessionId,
            table_id = normalizedTableId
        )

        sendTableStatusUpdate(payload, {
            Log.d(TAG, "✅ Table $normalizedTableId release webhook sent successfully")
            onSuccess?.invoke()
        }, { error ->
            Log.e(TAG, "❌ Table $normalizedTableId release webhook failed: $error")
            // Keep the local state even if server fails - it will sync later
            onError?.invoke(error)
        })
    }

    private fun sendTableStatusUpdate(
        payload: Any,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        try {
            val json = gson.toJson(payload)
            Log.d(TAG, "Sending table status update to: $WEBHOOK_URL")
            Log.d(TAG, "Payload: $json")

            val body = json.toRequestBody(JSON)

            val request = Request.Builder()
                .url(WEBHOOK_URL)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "OrderingService/1.0")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val errorMsg = "Network error: ${e.message}"
                    Log.e(TAG, "Failed to send table status update", e)
                    onError?.invoke(errorMsg)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val responseBody = resp.body?.string() ?: ""
                            Log.d(TAG, "Table status update sent successfully: $responseBody")
                            onSuccess?.invoke()
                        } else {
                            val errorMsg = "HTTP ${resp.code}: ${resp.message}"
                            Log.e(TAG, "Table status update failed - $errorMsg")
                            onError?.invoke(errorMsg)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            val errorMsg = "Failed to create table status request: ${e.message}"
            Log.e(TAG, errorMsg, e)
            onError?.invoke(errorMsg)
        }
    }

    /**
     * CRITICAL FIX: Normalize table ID consistently
     */
    private fun normalizeTableId(tableId: String): String {
        if (tableId.isBlank()) return ""

        val normalized = tableId.lowercase().trim()

        // Handle takeaway/counter specifically
        if (normalized == "takeaway" || normalized == "counter") {
            return "takeaway"
        }

        // Handle various table formats and convert to standard tableX format
        return when {
            normalized.matches(Regex("table\\d+")) -> normalized // Already in correct format
            normalized.matches(Regex("table[_\\s]+\\d+")) -> {
                val number = normalized.replace(Regex("[^\\d]"), "")
                "table$number"
            }
            normalized.matches(Regex("\\d+")) -> "table$normalized" // Just a number
            else -> normalized
        }
    }

    /**
     * FIXED: Get current occupied tables from combined state (USED BY UI)
     */
    fun getCurrentOccupiedTables(): List<String> {
        val combined = getCombinedOccupiedTables()
        Log.d(TAG, "getCurrentOccupiedTables() called - returning: $combined")
        return combined.toList()
    }

    /**
     * FIXED: Check if a specific table is occupied (USED BY UI)
     */
    fun isTableOccupied(tableId: String): Boolean {
        val normalizedId = normalizeTableId(tableId)
        val isOccupied = currentOccupiedTables.contains(normalizedId)
        Log.d(TAG, "isTableOccupied($tableId -> $normalizedId) = $isOccupied")
        return isOccupied
    }

    /**
     * FIXED: Manually add a table to occupied list with proper state management (USED BY UI)
     */
    fun addOccupiedTable(tableId: String) {
        val normalizedId = normalizeTableId(tableId)

        if (!localOccupiedTables.contains(normalizedId)) {
            localOccupiedTables.add(normalizedId)
            updateCombinedTables()
            notifyListeners()
            Log.d(TAG, "✅ Manually added $normalizedId to local occupied tables")
            Log.d(TAG, "Local occupied tables now: $localOccupiedTables")
            Log.d(TAG, "Combined occupied tables now: $currentOccupiedTables")
        } else {
            Log.d(TAG, "Table $normalizedId already in local occupied list")
        }
    }

    /**
     * FIXED: Manually remove a table from occupied list with proper state management (USED BY UI)
     */
    fun removeOccupiedTable(tableId: String) {
        val normalizedId = normalizeTableId(tableId)

        if (localOccupiedTables.contains(normalizedId)) {
            localOccupiedTables.remove(normalizedId)
            updateCombinedTables()
            notifyListeners()
            Log.d(TAG, "✅ Manually removed $normalizedId from local occupied tables")
            Log.d(TAG, "Local occupied tables now: $localOccupiedTables")
            Log.d(TAG, "Combined occupied tables now: $currentOccupiedTables")
        } else {
            Log.d(TAG, "Table $normalizedId was not in local occupied list")
        }
    }

    /**
     * FIXED: Check server availability status (USED BY UI)
     */
    fun isServerAvailable(): Boolean {
        Log.d(TAG, "isServerAvailable() = $isServerAvailable (fallback: $useLocalFallback)")
        return isServerAvailable && !useLocalFallback
    }

    /**
     * FIXED: Force server retry on next request (USED BY UI)
     */
    fun forceServerRetry() {
        useLocalFallback = false
        lastServerCheckTime = 0L
        isServerAvailable = true
        Log.d(TAG, "✅ Forced server retry - next refresh will attempt server connection")
    }

    /**
     * Cancel all pending requests
     */
    fun cancelAllRequests() {
        client.dispatcher.cancelAll()
        Log.d(TAG, "All table status requests cancelled")
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        listeners.clear()
        currentOccupiedTables.clear()
        serverOccupiedTables.clear()
        localOccupiedTables.clear()
        cancelAllRequests()
        useLocalFallback = false
        isServerAvailable = true
        Log.d(TAG, "TableStatusManager cleaned up")
    }

    /**
     * DEBUGGING: Get detailed status information
     */
    fun getDetailedStatus(): String {
        return buildString {
            appendLine("=== TableStatusManager Status ===")
            appendLine("Server Available: $isServerAvailable")
            appendLine("Using Local Fallback: $useLocalFallback")
            appendLine("Last Server Check: ${java.util.Date(lastServerCheckTime)}")
            appendLine("Server Tables (${serverOccupiedTables.size}): $serverOccupiedTables")
            appendLine("Local Tables (${localOccupiedTables.size}): $localOccupiedTables")
            appendLine("Combined Tables (${currentOccupiedTables.size}): $currentOccupiedTables")
            appendLine("Listeners: ${listeners.size}")
            appendLine("================================")
        }
    }
}