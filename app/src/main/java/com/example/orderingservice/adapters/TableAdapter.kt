package com.example.orderingservice.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.orderingservice.R
import com.example.orderingservice.models.Table

class TableAdapter(
    private var tables: MutableList<Table>,
    private val onTableClick: (Table) -> Unit
) : RecyclerView.Adapter<TableAdapter.TableViewHolder>() {

    companion object {
        private const val TAG = "TableAdapter"
    }

    // Track tables currently being processed
    private val processingTables = mutableSetOf<String>()

    inner class TableViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTableName: TextView = itemView.findViewById(R.id.tvTableName)
        private val tvTableStatus: TextView = itemView.findViewById(R.id.tvTableStatus)

        fun bind(table: Table) {
            tvTableName.text = table.name
            updateStatus(table)
            setClickListener(table)
        }

        fun updateStatus(table: Table) {
            // Determine current status
            val isProcessing = processingTables.contains(table.locationId)
            val isClickable = table.isAvailable && !isProcessing

            // CRITICAL FIX: Update status text based on ACTUAL table availability
            val statusText = when {
                isProcessing -> "Processing..."
                !table.isAvailable -> "Occupied"  // Show occupied if not available
                table.isAvailable -> "Available"
                else -> "Unknown"
            }
            tvTableStatus.text = statusText

            Log.d(TAG, "Updating ${table.name}: available=${table.isAvailable}, processing=$isProcessing, clickable=$isClickable, status='$statusText'")

            // CRITICAL FIX: Set visual appearance based on ACTUAL availability
            if (isClickable && table.isAvailable) {
                // Available table - green background
                itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.table_available)
                )
                tvTableName.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.black)
                )
                tvTableStatus.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.black)
                )
                itemView.alpha = 1.0f

                // Make it clearly clickable
                itemView.isClickable = true
                itemView.isEnabled = true
                itemView.isFocusable = true

            } else {
                // Occupied or processing table - gray background
                itemView.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.table_occupied)
                )
                tvTableName.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.white)
                )
                tvTableStatus.setTextColor(
                    ContextCompat.getColor(itemView.context, android.R.color.white)
                )
                itemView.alpha = if (isProcessing) 0.7f else 0.5f

                // Make it clearly NOT clickable
                itemView.isClickable = false
                itemView.isEnabled = false
                itemView.isFocusable = false
            }

            Log.d(TAG, "${table.name} final state: clickable=$isClickable, enabled=${itemView.isEnabled}, available=${table.isAvailable}")
        }

        private fun setClickListener(table: Table) {
            itemView.setOnClickListener {
                val isProcessing = processingTables.contains(table.locationId)
                val isClickable = table.isAvailable && !isProcessing

                Log.d(TAG, "${table.name} clicked: available=${table.isAvailable}, processing=$isProcessing, clickable=$isClickable")

                if (isClickable) {
                    Log.d(TAG, "✅ ${table.name} click accepted - calling onTableClick")
                    onTableClick(table)
                } else {
                    Log.w(TAG, "❌ ${table.name} click rejected - table not available or processing")
                    // Optionally provide user feedback here
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TableViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_table_enhanced, parent, false)
        return TableViewHolder(view)
    }

    override fun onBindViewHolder(holder: TableViewHolder, position: Int) {
        if (position < tables.size) {
            holder.bind(tables[position])
        } else {
            Log.e(TAG, "Attempted to bind invalid position $position (size: ${tables.size})")
        }
    }

    override fun getItemCount(): Int = tables.size

    /**
     * CRITICAL FIX: Update tables with proper validation and immediate UI refresh
     */
    fun updateTables(newTables: List<Table>) {
        Log.d(TAG, "=== UPDATE TABLES CALLED ===")
        Log.d(TAG, "Current tables count: ${tables.size}")
        Log.d(TAG, "New tables count: ${newTables.size}")

        // CRITICAL: Validate new tables list
        if (newTables.isEmpty()) {
            Log.e(TAG, "⚠️ WARNING: Attempting to update with empty table list!")
            Log.e(TAG, "Current tables: ${tables.map { "${it.name}(${it.isAvailable})" }}")
            Log.e(TAG, "This update will be ignored to prevent UI breaking")
            return
        }

        // Validate that all tables have proper data
        val invalidTables = newTables.filter { it.name.isBlank() || it.locationId.isBlank() }
        if (invalidTables.isNotEmpty()) {
            Log.e(TAG, "⚠️ WARNING: Found invalid tables in new list:")
            invalidTables.forEach { table ->
                Log.e(TAG, "  Invalid table: name='${table.name}', locationId='${table.locationId}'")
            }
            Log.e(TAG, "Update will be ignored due to invalid data")
            return
        }

        // Log the availability changes BEFORE updating
        newTables.forEach { newTable ->
            val oldTable = tables.find { it.locationId == newTable.locationId }
            if (oldTable != null && oldTable.isAvailable != newTable.isAvailable) {
                Log.d(TAG, "🔄 ${newTable.name} availability changing: ${oldTable.isAvailable} -> ${newTable.isAvailable}")
            }
        }

        // Preserve processing states from current tables
        val currentProcessingStates = mutableMapOf<String, Boolean>()
        tables.forEach { table ->
            if (processingTables.contains(table.locationId)) {
                currentProcessingStates[table.locationId] = true
            }
        }

        try {
            // CRITICAL FIX: Replace the tables list completely and force full refresh
            tables.clear()
            tables.addAll(newTables)

            // Restore processing states
            currentProcessingStates.forEach { (locationId, _) ->
                processingTables.add(locationId)
            }

            // CRITICAL: Force complete refresh of all items to ensure UI updates
            notifyDataSetChanged()

            Log.d(TAG, "✅ Tables updated successfully: ${tables.size} tables")
            logCurrentState()

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR during table update", e)
            Log.e(TAG, "Tables count after error: ${tables.size}")

            // If tables got corrupted, try to restore from newTables
            if (tables.isEmpty() && newTables.isNotEmpty()) {
                Log.w(TAG, "🔧 Attempting emergency restoration from newTables")
                tables.clear()
                tables.addAll(newTables)
                notifyDataSetChanged()
                Log.w(TAG, "Emergency restoration complete: ${tables.size} tables")
            }
        }

        // Final safety check
        if (tables.isEmpty() && newTables.isNotEmpty()) {
            Log.e(TAG, "🚨 CRITICAL: Tables list is empty after update! Performing emergency fix...")
            tables.addAll(newTables)
            notifyDataSetChanged()
            Log.e(TAG, "Emergency fix applied: ${tables.size} tables restored")
        }
    }

    /**
     * Update a specific table and force refresh
     */
    fun updateTable(updatedTable: Table) {
        val index = tables.indexOfFirst { it.locationId == updatedTable.locationId }
        if (index != -1) {
            val oldAvailable = tables[index].isAvailable
            tables[index] = updatedTable

            Log.d(TAG, "Updated table ${updatedTable.name} at position $index: available ${oldAvailable} -> ${updatedTable.isAvailable}")

            // Force refresh this specific item
            notifyItemChanged(index)
        } else {
            Log.w(TAG, "Could not find table with locationId ${updatedTable.locationId} to update")
        }
    }

    /**
     * Mark a table as being processed (navigating to it)
     */
    fun markTableProcessing(locationId: String) {
        processingTables.add(locationId)
        val index = tables.indexOfFirst { it.locationId == locationId }
        if (index != -1) {
            notifyItemChanged(index)
            Log.d(TAG, "✅ Marked table $locationId as processing")
        } else {
            Log.w(TAG, "Could not find table $locationId to mark as processing")
        }
    }

    /**
     * Clear processing status for a table
     */
    fun clearTableProcessing(locationId: String) {
        val wasProcessing = processingTables.remove(locationId)
        if (wasProcessing) {
            val index = tables.indexOfFirst { it.locationId == locationId }
            if (index != -1) {
                notifyItemChanged(index)
                Log.d(TAG, "✅ Cleared processing status for table $locationId")
            }
        } else {
            Log.w(TAG, "Table $locationId was not being processed")
        }
    }

    /**
     * Get tables currently being processed
     */
    fun getProcessingTables(): Set<String> {
        return processingTables.toSet()
    }

    /**
     * Get table by location ID
     */
    fun getTableByLocationId(locationId: String): Table? {
        return tables.find { it.locationId == locationId }
    }

    /**
     * Get available tables count
     */
    fun getAvailableTablesCount(): Int {
        val count = tables.count { it.isAvailable && !processingTables.contains(it.locationId) }
        Log.d(TAG, "Available tables count: $count (total: ${tables.size}, processing: ${processingTables.size})")
        return count
    }

    /**
     * Enhanced logging for debugging
     */
    fun logCurrentState() {
        Log.d(TAG, "=== TableAdapter Current State ===")
        Log.d(TAG, "Total tables: ${tables.size}")
        Log.d(TAG, "Processing tables: ${processingTables.size} -> ${processingTables.joinToString(", ")}")

        if (tables.isEmpty()) {
            Log.e(TAG, "❌ CRITICAL: Tables list is EMPTY!")
        } else {
            tables.forEachIndexed { index, table ->
                val isProcessing = processingTables.contains(table.locationId)
                val status = when {
                    isProcessing -> "PROCESSING"
                    table.isAvailable -> "AVAILABLE"
                    else -> "OCCUPIED"
                }
                val clickable = table.isAvailable && !isProcessing
                Log.d(TAG, "  [$index] ${table.name} (${table.locationId}) -> $status (clickable: $clickable)")
            }
        }

        val availableCount = getAvailableTablesCount()
        Log.d(TAG, "Available for selection: $availableCount")
        Log.d(TAG, "================================")
    }

    /**
     * Force refresh all items (useful when processing states change)
     */
    fun refreshAllItems() {
        Log.d(TAG, "Force refreshing all ${tables.size} items")
        notifyDataSetChanged()
    }

    /**
     * Safe update that preserves existing data on failure
     */
    fun safeUpdateTables(newTables: List<Table>) {
        if (newTables.isEmpty()) {
            Log.w(TAG, "Ignoring empty table update")
            return
        }

        // Create backup of current state
        val backupTables = tables.toList()
        val backupProcessing = processingTables.toSet()

        try {
            // Log what we're updating
            Log.d(TAG, "=== SAFE UPDATE TABLES ===")
            newTables.forEach { table ->
                Log.d(TAG, "New table data: ${table.name} -> available=${table.isAvailable}")
            }

            updateTables(newTables)

            // Verify update was successful
            if (tables.isEmpty()) {
                Log.e(TAG, "Update failed - restoring backup")
                tables.clear()
                tables.addAll(backupTables)
                processingTables.clear()
                processingTables.addAll(backupProcessing)
                notifyDataSetChanged()
            } else {
                Log.d(TAG, "✅ Safe update successful: ${tables.size} tables")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Safe update failed - restoring backup", e)
            tables.clear()
            tables.addAll(backupTables)
            processingTables.clear()
            processingTables.addAll(backupProcessing)
            notifyDataSetChanged()
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        processingTables.clear()
        tables.clear()
        Log.d(TAG, "TableAdapter cleaned up")
    }

    enum class SortCriteria {
        NAME,
        AVAILABILITY,
        OCCUPANCY_DURATION
    }

    /**
     * Enhanced DiffUtil callback for efficient list updates
     */
    private class TableDiffCallback(
        private val oldList: List<Table>,
        private val newList: List<Table>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].locationId == newList[newItemPosition].locationId
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldTable = oldList[oldItemPosition]
            val newTable = newList[newItemPosition]

            return oldTable.isAvailable == newTable.isAvailable &&
                    oldTable.lastOccupied == newTable.lastOccupied &&
                    oldTable.currentSessionId == newTable.currentSessionId &&
                    oldTable.occupancyStatus == newTable.occupancyStatus
        }

        override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
            val oldTable = oldList[oldItemPosition]
            val newTable = newList[newItemPosition]

            // Return specific change type for partial updates
            return when {
                oldTable.isAvailable != newTable.isAvailable -> "availability_changed"
                oldTable.lastOccupied != newTable.lastOccupied -> "duration_changed"
                oldTable.occupancyStatus != newTable.occupancyStatus -> "status_changed"
                else -> null
            }
        }
    }
}