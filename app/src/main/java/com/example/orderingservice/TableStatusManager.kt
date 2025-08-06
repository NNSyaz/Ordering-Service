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
    private val GET_TABLE_STATUS_URL = "https://script.google.com/macros/s/AKfycbxNqFPi14W-Th2qTY-sKI0N_Ya4GF27IBBkBv4-EkX2h03n0GOFKbLHPKrU_iiYMZWlJw/exec?action=get_table_status"
    private val WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbxNqFPi14W-Th2qTY-sKI0N_Ya4GF27IBBkBv4-EkX2h03n0GOFKbLHPKrU_iiYMZWlJw/exec"

    private val listeners = mutableSetOf<TableStatusListener>()
    private var currentOccupiedTables = mutableListOf<String>()

    // Local fallback for when server is unavailable
    private var useLocalFallback = false
    private var lastServerCheckTime = 0L
    private val SERVER_RETRY_INTERVAL = 5 * 60 * 1000L // 5 minutes

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
     * Refresh table status from server with better error handling
     */
    fun refreshTableStatus(
        onSuccess: ((List<String>) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val currentTime = System.currentTimeMillis()

        // Check if we should retry the server or use local fallback
        if (useLocalFallback && (currentTime - lastServerCheckTime) < SERVER_RETRY_INTERVAL) {
            Log.d(TAG, "Using local fallback, server retry not due yet")
            onSuccess?.invoke(currentOccupiedTables.toList())
            return
        }

        Log.d(TAG, "Refreshing table status from server: $GET_TABLE_STATUS_URL")

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

                            val occupiedTables = parseTableStatusResponse(responseBody)

                            // Server is working, disable fallback mode
                            useLocalFallback = false
                            lastServerCheckTime = System.currentTimeMillis()

                            // Update internal state
                            val wasChanged = updateOccupiedTables(occupiedTables)

                            Log.d(TAG, "Successfully parsed occupied tables: $occupiedTables")
                            onSuccess?.invoke(occupiedTables)

                            // Notify listeners if changed
                            if (wasChanged) {
                                notifyListeners()
                            }

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

    private fun handleServerError(
        error: String,
        onSuccess: ((List<String>) -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        useLocalFallback = true
        lastServerCheckTime = System.currentTimeMillis()

        Log.w(TAG, "Switching to local fallback mode due to server error: $error")

        // For user experience, treat fallback as success but log the limitation
        Log.i(TAG, "Continuing with local table status. Current occupied: ${currentOccupiedTables.size} tables")

        onSuccess?.invoke(currentOccupiedTables.toList())

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

    private fun updateOccupiedTables(newOccupiedTables: List<String>): Boolean {
        val oldSet = currentOccupiedTables.toSet()
        val newSet = newOccupiedTables.toSet()

        if (oldSet != newSet) {
            currentOccupiedTables.clear()
            currentOccupiedTables.addAll(newOccupiedTables)

            Log.d(TAG, "Table occupancy changed. Old: $oldSet, New: $newSet")
            return true
        }

        return false
    }

    private fun notifyListeners() {
        Log.d(TAG, "Notifying ${listeners.size} listeners of table status change")
        listeners.forEach { listener ->
            try {
                listener.onTableStatusChanged(currentOccupiedTables.toList())
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }

    /**
     * Mark a table as occupied
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
        val payload = TableOccupancyPayload(
            session_id = sessionId,
            table_id = tableId,
            order_type = orderType,
            battery = battery,
            customer_name = customerName
        )

        sendTableStatusUpdate(payload, onSuccess, onError)

        // Update local state immediately for better UX
        if (!currentOccupiedTables.contains(tableId)) {
            currentOccupiedTables.add(tableId)
            notifyListeners()
            Log.d(TAG, "Table $tableId marked as occupied locally")
        }
    }

    /**
     * Mark a table as available (released)
     */
    fun releaseTable(
        sessionId: String,
        tableId: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val payload = TableReleasePayload(
            session_id = sessionId,
            table_id = tableId
        )

        sendTableStatusUpdate(payload, onSuccess, onError)

        // Update local state immediately for better UX
        if (currentOccupiedTables.contains(tableId)) {
            currentOccupiedTables.remove(tableId)
            notifyListeners()
            Log.d(TAG, "Table $tableId released locally")
        }
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
     * Get current occupied tables from memory
     */
    fun getCurrentOccupiedTables(): List<String> {
        return currentOccupiedTables.toList()
    }

    /**
     * Check if a specific table is occupied
     */
    fun isTableOccupied(tableId: String): Boolean {
        return currentOccupiedTables.contains(tableId)
    }

    /**
     * Manually add a table to occupied list (for local management)
     */
    fun addOccupiedTable(tableId: String) {
        if (!currentOccupiedTables.contains(tableId)) {
            currentOccupiedTables.add(tableId)
            notifyListeners()
            Log.d(TAG, "Manually added $tableId to occupied tables")
        }
    }

    /**
     * Manually remove a table from occupied list (for local management)
     */
    fun removeOccupiedTable(tableId: String) {
        if (currentOccupiedTables.contains(tableId)) {
            currentOccupiedTables.remove(tableId)
            notifyListeners()
            Log.d(TAG, "Manually removed $tableId from occupied tables")
        }
    }

    /**
     * Check server status
     */
    fun isServerAvailable(): Boolean {
        return !useLocalFallback
    }

    /**
     * Force server retry on next request
     */
    fun forceServerRetry() {
        useLocalFallback = false
        lastServerCheckTime = 0L
        Log.d(TAG, "Forced server retry on next request")
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
        cancelAllRequests()
        useLocalFallback = false
        Log.d(TAG, "TableStatusManager cleaned up")
    }
}