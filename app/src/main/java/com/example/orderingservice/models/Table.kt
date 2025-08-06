package com.example.orderingservice.models

import com.example.orderingservice.R

/**
 * Data class representing a restaurant table
 */
data class Table(
    val name: String,           // Display name (e.g., "Table 1")
    var isAvailable: Boolean,   // Whether the table is available for seating
    val locationId: String,     // Temi location ID for navigation (e.g., "table1")
    val capacity: Int = 4,      // Number of seats (optional)
    val description: String = "", // Optional description (e.g., "Window seat")
    var lastOccupied: Long = 0L, // Timestamp when table was last occupied
    var currentSessionId: String = "",  // Current session ID if occupied
    var occupancyStatus: OccupancyStatus = OccupancyStatus.AVAILABLE
) {

    enum class OccupancyStatus {
        AVAILABLE,
        OCCUPIED,
        PROCESSING,  // Being navigated to or being processed
        OUT_OF_ORDER
    }

    /**
     * Get display status text
     */
    fun getStatusText(): String {
        return when (occupancyStatus) {
            OccupancyStatus.AVAILABLE -> "Available"
            OccupancyStatus.OCCUPIED -> "Occupied"
            OccupancyStatus.PROCESSING -> "Processing..."
            OccupancyStatus.OUT_OF_ORDER -> "Out of Order"
        }
    }

    /**
     * Get status color resource
     */
    fun getStatusColor(): Int {
        return when (occupancyStatus) {
            OccupancyStatus.AVAILABLE -> R.color.table_available
            OccupancyStatus.OCCUPIED -> R.color.table_occupied
            OccupancyStatus.PROCESSING -> R.color.table_processing
            OccupancyStatus.OUT_OF_ORDER -> R.color.table_out_of_order
        }
    }

    /**
     * Get occupancy duration text
     */
    fun getOccupancyDuration(): String {
        if (occupancyStatus != OccupancyStatus.OCCUPIED || lastOccupied <= 0) {
            return ""
        }

        val currentTime = System.currentTimeMillis()
        val duration = currentTime - lastOccupied
        val minutes = (duration / (1000 * 60)).toInt()

        return when {
            minutes < 1 -> "Just occupied"
            minutes < 60 -> "${minutes}m"
            else -> {
                val hours = minutes / 60
                val remainingMinutes = minutes % 60
                if (remainingMinutes == 0) "${hours}h" else "${hours}h ${remainingMinutes}m"
            }
        }
    }

    /**
     * Check if table has been occupied for more than specified minutes
     */
    fun isOccupiedLongerThan(minutes: Int): Boolean {
        if (occupancyStatus != OccupancyStatus.OCCUPIED) return false
        val currentTime = System.currentTimeMillis()
        val occupiedDuration = currentTime - lastOccupied
        return occupiedDuration > (minutes * 60 * 1000)
    }

    /**
     * Mark table as occupied
     */
    fun markAsOccupied(sessionId: String = "") {
        this.isAvailable = false
        this.occupancyStatus = OccupancyStatus.OCCUPIED
        this.lastOccupied = System.currentTimeMillis()
        this.currentSessionId = sessionId
    }

    /**
     * Mark table as available
     */
    fun markAsAvailable() {
        this.isAvailable = true
        this.occupancyStatus = OccupancyStatus.AVAILABLE
        this.lastOccupied = 0L
        this.currentSessionId = ""
    }

    /**
     * Mark table as processing
     */
    fun markAsProcessing() {
        this.occupancyStatus = OccupancyStatus.PROCESSING
    }

    /**
     * Get formatted capacity text
     */
    fun getCapacityText(): String {
        return "Seats $capacity guests"
    }

    /**
     * Create a copy with updated availability
     */
    fun withAvailability(available: Boolean): Table {
        return this.copy(isAvailable = available,
            occupancyStatus = if (available) OccupancyStatus.AVAILABLE else OccupancyStatus.OCCUPIED)
    }

    override fun toString(): String {
        return "Table(name='$name', isAvailable=$isAvailable, locationId='$locationId', capacity=$capacity, status=$occupancyStatus)"
    }
}