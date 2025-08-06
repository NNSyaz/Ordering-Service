package com.example.orderingservice

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object WebhookManager {

    // UPDATED URL with new Google Apps Script
    private const val WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbxNqFPi14W-Th2qTY-sKI0N_Ya4GF27IBBkBv4-EkX2h03n0GOFKbLHPKrU_iiYMZWlJw/exec"
    private const val TAG = "WebhookManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    sealed class WebhookPayload {
        abstract val session_id: String
        abstract val table_no: String
        abstract val order_type: String
        abstract val battery: Int
        abstract val status: String
        abstract val timestamp: Long
        abstract val customer_name: String
    }

    data class OrderPayload(
        override val session_id: String,
        override val table_no: String,
        override val order_type: String,
        val menu_items: List<String>,
        val total_items: Int,
        val total_amount: String = "0.00",
        override val battery: Int,
        override val customer_name: String = "Guest",
        override val status: String = "order_placed",
        override val timestamp: Long = System.currentTimeMillis()
    ) : WebhookPayload()

    data class TableOccupiedPayload(
        override val session_id: String,
        override val table_no: String,
        override val order_type: String,
        override val battery: Int,
        override val customer_name: String = "Guest",
        override val status: String = "table_occupied",
        override val timestamp: Long = System.currentTimeMillis()
    ) : WebhookPayload()

    /**
     * Sends an order webhook with menu items
     */
    fun sendOrderWebhook(
        sessionId: String,
        tableNo: String,
        orderType: String,
        menuItems: List<String>,
        battery: Int,
        orderNumber: String,
        customerName: String = "Guest",
        totalAmount: String = "0.00",
        onSuccess: ((String?) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val payload = OrderPayload(
            session_id = sessionId,
            table_no = tableNo,
            order_type = orderType,
            menu_items = menuItems,
            total_items = menuItems.size,
            total_amount = totalAmount,
            battery = battery,
            customer_name = customerName,
            status = "order_placed",
            timestamp = System.currentTimeMillis()
        )

        sendWebhook(payload, { responseOrderNumber ->
            // The Apps Script now returns an order number in the response
            onSuccess?.invoke(responseOrderNumber)
        }, onError)
    }

    /**
     * Sends a table occupied webhook
     */
    fun sendTableOccupiedWebhook(
        sessionId: String,
        tableNo: String,
        orderType: String,
        battery: Int,
        customerName: String = "Guest",
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val payload = TableOccupiedPayload(
            session_id = sessionId,
            table_no = tableNo,
            order_type = orderType,
            battery = battery,
            customer_name = customerName
        )

        sendWebhook(payload, { _ ->
            onSuccess?.invoke()
        }, onError)
    }

    /**
     * Generic method to send any webhook payload
     */
    private fun sendWebhook(
        payload: WebhookPayload,
        onSuccess: ((String?) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        try {
            val json = gson.toJson(payload)
            Log.d(TAG, "Sending ${payload.status} webhook for table ${payload.table_no} to: $WEBHOOK_URL")
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
                    Log.e(TAG, "Failed to send ${payload.status} webhook for table ${payload.table_no}", e)
                    onError?.invoke(errorMsg)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val responseBody = resp.body?.string() ?: ""
                            Log.d(TAG, "${payload.status} webhook sent successfully for table ${payload.table_no}: $responseBody")

                            // Try to extract order number from response for order webhooks
                            var orderNumber: String? = null
                            if (payload.status == "order_placed") {
                                try {
                                    val responseJson = gson.fromJson(responseBody, com.google.gson.JsonObject::class.java)
                                    if (responseJson.has("order_number")) {
                                        orderNumber = responseJson.get("order_number").asString
                                        Log.d(TAG, "Extracted order number from response: $orderNumber")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not parse order number from response: ${e.message}")
                                }
                            }

                            onSuccess?.invoke(orderNumber)
                        } else {
                            val errorMsg = "HTTP ${resp.code}: ${resp.message}"
                            Log.e(TAG, "Webhook failed for table ${payload.table_no} - $errorMsg")
                            onError?.invoke(errorMsg)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            val errorMsg = "Failed to create webhook request: ${e.message}"
            Log.e(TAG, errorMsg, e)
            onError?.invoke(errorMsg)
        }
    }

    /**
     * Cancels all pending webhook requests
     */
    fun cancelAllRequests() {
        client.dispatcher.cancelAll()
        Log.d(TAG, "All webhook requests cancelled")
    }
}