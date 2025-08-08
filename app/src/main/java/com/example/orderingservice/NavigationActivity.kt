package com.example.orderingservice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.orderingservice.adapters.TableAdapter
import com.example.orderingservice.databinding.ActivityNavigationBinding
import com.example.orderingservice.models.Table
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener

class NavigationActivity : AppCompatActivity(),
    OnGoToLocationStatusChangedListener,
    Robot.TtsListener,
    TableStatusManager.TableStatusListener {

    private lateinit var binding: ActivityNavigationBinding
    private lateinit var robot: Robot
    private lateinit var tableAdapter: TableAdapter
    private lateinit var tableStatusManager: TableStatusManager

    private var sessionId: String = ""
    private var orderType: String = ""
    private var currentBattery: Int = 100
    private val timeoutHandler = Handler(Looper.getMainLooper())

    // Speech management
    private var isSpeaking = false
    private var pendingTableSelection: Table? = null

    // Navigation management
    private var isNavigatingToTable = false
    private var selectedTable: Table? = null

    // FIXED: Loading state management with proper UI control
    private var isLoadingTableStatus = true

    // CRITICAL FIX: Master table list with proper initialization - NEVER CLEAR THIS
    private val masterTables = listOf(
        Table("Table 1", false, "table1"), // Start with false availability
        Table("Table 2", false, "table2"),
        Table("Table 3", false, "table3"),
        Table("Table 4", false, "table4"),
        Table("Table 5", false, "table5"),
    )

    // Working copy for the adapter - this gets updated with server data
    private var workingTables = mutableListOf<Table>()

    companion object {
        private const val TAG = "NavigationActivity"
        private const val TABLE_SELECTION_TIMEOUT = 30000L // 30 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from previous activity
        sessionId = intent.getStringExtra("SESSION_ID") ?: ""
        orderType = intent.getStringExtra("ORDER_TYPE") ?: ""
        currentBattery = intent.getIntExtra("BATTERY", 100)

        // CRITICAL: Initialize working tables from master list FIRST with unavailable state
        initializeWorkingTablesAsUnavailable()

        setupUI()
        initializeTemi()
        initializeTableStatusManager()

        // Load current table status AFTER everything is initialized
        loadTableStatus()
    }

    private fun initializeWorkingTablesAsUnavailable() {
        workingTables.clear()

        // Create fresh copies of master tables with UNAVAILABLE state initially
        masterTables.forEach { masterTable ->
            val workingTable = masterTable.copy(
                isAvailable = false, // CRITICAL: Start with all tables UNAVAILABLE
                lastOccupied = 0L,
                currentSessionId = "",
                occupancyStatus = Table.OccupancyStatus.OCCUPIED // Show as occupied until loaded
            )
            workingTables.add(workingTable)
        }

        Log.d(TAG, "✅ Initialized ${workingTables.size} working tables as UNAVAILABLE (loading state):")
        workingTables.forEach { table ->
            Log.d(TAG, "  - ${table.name} (${table.locationId}): available=${table.isAvailable}")
        }
    }

    private fun setupUI() {
        binding.apply {
            tvTitle.text = "$orderType - Select Your Table"
            tvBattery.text = "Battery: ${currentBattery}%"

            // FIXED: Show loading state clearly
            tvStatus.text = "⏳ Loading table availability..."

            btnBack.setOnClickListener {
                if (!isSpeaking && !isNavigatingToTable) {
                    handleBackPressed()
                }
            }

            // Setup RecyclerView for tables with the working tables (all unavailable initially)
            rvTables.layoutManager = GridLayoutManager(this@NavigationActivity, 4)
            tableAdapter = TableAdapter(workingTables) { table ->
                onTableSelected(table)
            }
            rvTables.adapter = tableAdapter

            // CRITICAL FIX: COMPLETELY DISABLE the RecyclerView while loading
            disableTableInteraction("Loading table status...")

            Log.d(TAG, "✅ UI setup complete with ${workingTables.size} tables in adapter (all unavailable)")
        }
    }

    // FIXED: New method to properly disable table interaction
    private fun disableTableInteraction(reason: String) {
        runOnUiThread {
            binding.apply {
                // Make RecyclerView completely non-interactive
                rvTables.isEnabled = false
                rvTables.isClickable = false
                rvTables.isFocusable = false
                rvTables.alpha = 0.4f // Make it visually clear it's disabled

                // Add a semi-transparent overlay effect
                rvTables.foreground = getDrawable(android.R.color.transparent)

                // Update status to show why it's disabled
                tvStatus.text = "⏳ $reason"

                Log.d(TAG, "🔒 Table interaction DISABLED: $reason")
            }
        }
    }

    // FIXED: New method to properly enable table interaction
    private fun enableTableInteraction() {
        runOnUiThread {
            binding.apply {
                // Re-enable RecyclerView interaction
                rvTables.isEnabled = true
                rvTables.isClickable = true
                rvTables.isFocusable = true
                rvTables.alpha = 1.0f // Full opacity

                // Remove overlay
                rvTables.foreground = null

                isLoadingTableStatus = false
                Log.d(TAG, "🔓 Table interaction ENABLED")
            }
        }
    }

    private fun initializeTemi() {
        robot = Robot.getInstance()
    }

    private fun initializeTableStatusManager() {
        tableStatusManager = TableStatusManager.getInstance()
        tableStatusManager.addListener(this)
    }

    private fun loadTableStatus() {
        Log.d(TAG, "=== STARTING TABLE STATUS LOAD ===")
        isLoadingTableStatus = true

        // FIXED: Ensure UI is properly disabled during loading
        disableTableInteraction("Loading table availability...")

        // Log initial state before server call
        Log.d(TAG, "Working tables BEFORE server call: ${workingTables.size}")
        workingTables.forEachIndexed { index, table ->
            Log.d(TAG, "  [$index] ${table.name} -> locationId: '${table.locationId}', available: ${table.isAvailable}")
        }

        // CRITICAL: Ensure we have tables before calling server
        if (workingTables.isEmpty()) {
            Log.e(TAG, "🚨 CRITICAL: Working tables is empty before server call!")
            initializeWorkingTablesAsUnavailable() // Emergency re-initialization
        }

        tableStatusManager.refreshTableStatus(
            onSuccess = { occupiedTables ->
                Log.d(TAG, "=== SERVER SUCCESS ===")
                Log.d(TAG, "Server response - occupied tables: $occupiedTables")
                Log.d(TAG, "Working tables count before update: ${workingTables.size}")

                // CRITICAL: Only update if we still have tables
                if (workingTables.isNotEmpty()) {
                    updateTableAvailability(occupiedTables)
                } else {
                    Log.e(TAG, "🚨 EMERGENCY: Working tables became empty, reinitializing...")
                    initializeWorkingTablesAsUnavailable()
                    updateTableAvailability(occupiedTables)
                }

                // FIXED: Enable interaction ONLY after loading completes
                enableTableInteraction()

                // Speak introduction after loading table status
                speakAndWait("Please select an available table and I'll guide you there.") {
                    updateStatusText()
                    startSelectionTimeout()
                }
            },
            onError = { error ->
                Log.e(TAG, "=== SERVER ERROR ===")
                Log.e(TAG, "Failed to load table status: $error")
                Log.d(TAG, "Working tables count on error: ${workingTables.size}")

                // CRITICAL: Ensure we still have tables even on error
                if (workingTables.isEmpty()) {
                    Log.e(TAG, "🚨 EMERGENCY: Working tables empty on error, reinitializing...")
                    initializeWorkingTablesAsUnavailable()
                }

                // CRITICAL FIX: Use server availability status to determine fallback behavior
                if (tableStatusManager.isServerAvailable()) {
                    Log.w(TAG, "Server available but returned error - showing all as available")
                    // Make all current tables available on error (fallback)
                    workingTables.forEach { table ->
                        table.isAvailable = true
                        table.occupancyStatus = Table.OccupancyStatus.AVAILABLE
                        Log.d(TAG, "  Set ${table.name} as available (error fallback)")
                    }
                } else {
                    Log.w(TAG, "Server not available - using local occupied state")
                    // Use local occupied tables from TableStatusManager
                    val localOccupiedTables = tableStatusManager.getCurrentOccupiedTables()
                    Log.d(TAG, "Using local occupied tables: $localOccupiedTables")
                    updateTableAvailability(localOccupiedTables)
                }

                // CRITICAL: Use safeUpdateTables to prevent clearing
                tableAdapter.safeUpdateTables(workingTables)

                // FIXED: Enable interaction ONLY after error handling completes
                enableTableInteraction()

                // Continue with error message
                speakAndWait("I'm having trouble checking table availability in real-time. All tables are shown as available, but some may be occupied. Please let me know if you encounter any issues.") {
                    updateStatusText()
                    startSelectionTimeout()
                }
            }
        )
    }

    private fun updateTableAvailability(occupiedTables: List<String>) {
        Log.d(TAG, "=== UPDATING TABLE AVAILABILITY ===")
        Log.d(TAG, "Working tables BEFORE update: ${workingTables.size}")
        Log.d(TAG, "Occupied tables from server/local: $occupiedTables")

        // CRITICAL: Validate working tables before updating
        if (workingTables.isEmpty()) {
            Log.e(TAG, "🚨 CRITICAL: Working tables is empty! Reinitializing...")
            initializeWorkingTablesAsUnavailable()
        }

        runOnUiThread {
            // Create a backup before making changes
            val backupTables = workingTables.map { it.copy() }.toMutableList()

            try {
                // Update availability based on server data
                workingTables.forEach { table ->
                    val wasAvailable = table.isAvailable

                    // Check if this table is in the occupied list using multiple matching strategies
                    val isOccupied = occupiedTables.any { occupiedId ->
                        occupiedId == table.locationId ||
                                occupiedId.equals(table.locationId, ignoreCase = true) ||
                                normalizeTableId(occupiedId) == normalizeTableId(table.locationId)
                    }

                    // Update table status
                    table.isAvailable = !isOccupied
                    table.occupancyStatus = if (isOccupied) {
                        Table.OccupancyStatus.OCCUPIED
                    } else {
                        Table.OccupancyStatus.AVAILABLE
                    }

                    Log.d(TAG, "  ${table.name} (${table.locationId}): occupied=$isOccupied, available=${table.isAvailable}")

                    if (wasAvailable != table.isAvailable) {
                        Log.d(TAG, "  ✅ ${table.name} availability changed: $wasAvailable -> ${table.isAvailable}")
                    }
                }

                Log.d(TAG, "Working tables count AFTER update: ${workingTables.size}")

                // CRITICAL: Use safeUpdateTables instead of updateTables - NEVER pass empty list
                if (workingTables.isNotEmpty()) {
                    // FIXED: Create a defensive copy to prevent clearing during update
                    val safeCopyForUpdate = workingTables.map { it.copy() }.toMutableList()
                    tableAdapter.safeUpdateTables(safeCopyForUpdate)
                } else {
                    Log.e(TAG, "🚨 Working tables became empty after update! Restoring backup...")
                    workingTables.clear()
                    workingTables.addAll(backupTables)
                    val restoreCopy = workingTables.map { it.copy() }.toMutableList()
                    tableAdapter.safeUpdateTables(restoreCopy)
                }

                updateStatusText()

                val availableCount = workingTables.count { it.isAvailable }
                Log.d(TAG, "🎯 FINAL RESULT: $availableCount/${workingTables.size} tables available")

                if (availableCount == 0) {
                    Log.w(TAG, "⚠️ No tables available - all are occupied")
                } else {
                    Log.d(TAG, "✅ SUCCESS: $availableCount tables are available for selection!")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ ERROR during table update", e)
                // Restore backup on error
                workingTables.clear()
                workingTables.addAll(backupTables)
                val errorRecoveryCopy = workingTables.map { it.copy() }.toMutableList()
                tableAdapter.safeUpdateTables(errorRecoveryCopy)
            }
        }
    }

    private fun updateStatusText() {
        val availableCount = workingTables.count { it.isAvailable }
        val totalCount = workingTables.size

        val statusText = if (isLoadingTableStatus) {
            "⏳ Loading table availability..."
        } else {
            "$availableCount of $totalCount tables available"
        }

        binding.tvStatus.text = statusText
        Log.d(TAG, "Status updated: $statusText")
    }

    // Helper function to normalize table IDs for better matching
    private fun normalizeTableId(tableId: String): String {
        return tableId.lowercase()
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
            .let { normalized ->
                // Extract number if present (e.g., "table1", "table_1", "Table 1" all become "table1")
                val numberMatch = Regex("table(\\d+)").find(normalized)
                if (numberMatch != null) {
                    "table${numberMatch.groupValues[1]}"
                } else {
                    normalized
                }
            }
    }

    private fun startSelectionTimeout() {
        timeoutHandler.postDelayed({
            if (!isNavigatingToTable && selectedTable == null) {
                handleSelectionTimeout()
            }
        }, TABLE_SELECTION_TIMEOUT)
    }

    private fun handleSelectionTimeout() {
        speakAndWait("No table was selected. Let me take you back to the main menu.") {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        robot.addOnGoToLocationStatusChangedListener(this)
        robot.addTtsListener(this)
    }

    override fun onStop() {
        super.onStop()
        robot.removeOnGoToLocationStatusChangedListener(this)
        robot.removeTtsListener(this)
        timeoutHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        tableStatusManager.removeListener(this)
        tableAdapter.cleanup()

        // Log final state for debugging
        Log.d(TAG, "NavigationActivity destroyed - final working tables count: ${workingTables.size}")
    }

    private fun onTableSelected(table: Table) {
        Log.d(TAG, "Table selection attempted: ${table.name}, available: ${table.isAvailable}, loading: $isLoadingTableStatus")

        // CRITICAL FIX: Prevent ANY selection while loading
        if (isLoadingTableStatus) {
            Log.w(TAG, "🚫 Table selection BLOCKED - still loading table status")
            speakAndWait("Please wait while I finish loading the current table availability.")
            return
        }

        // FIXED: Double-check that table interaction is enabled
        if (!binding.rvTables.isEnabled) {
            Log.w(TAG, "🚫 Table selection BLOCKED - RecyclerView is disabled")
            speakAndWait("Please wait, I'm still preparing the table information.")
            return
        }

        if (isSpeaking) {
            pendingTableSelection = table
            Log.d(TAG, "Speech in progress, queuing table selection for ${table.name}")
            return
        }

        // Cancel timeout since user made a selection
        timeoutHandler.removeCallbacksAndMessages(null)

        // CRITICAL: Check availability before proceeding
        if (!table.isAvailable) {
            Log.w(TAG, "Attempted to select unavailable table: ${table.name}")
            speakAndWait("Sorry, ${table.name} is currently occupied by other guests. Please select another available table shown in green.") {
                // Refresh table status after informing user
                loadTableStatus()
            }
            return
        }

        // Check if table is already being processed
        if (tableAdapter.getProcessingTables().contains(table.locationId)) {
            Log.d(TAG, "Table ${table.name} is already being processed, ignoring selection")
            return
        }

        // CRITICAL FIX: Immediately mark table as occupied locally to prevent double selection
        Log.d(TAG, "✅ Table ${table.name} selected - immediately marking as occupied locally")
        tableStatusManager.addOccupiedTable(table.locationId)

        // Mark as selected and start navigation
        selectedTable = table
        navigateToTable(table)
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

                // Handle pending table selection ONLY if not loading
                pendingTableSelection?.let { table ->
                    pendingTableSelection = null
                    if (!isLoadingTableStatus) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            onTableSelected(table)
                        }, 300)
                    } else {
                        Log.d(TAG, "Skipping pending table selection - still loading")
                    }
                }
            }
            TtsRequest.Status.PENDING -> {
                Log.d(TAG, "TTS is pending")
                // TTS request is queued, don't change isSpeaking state
            }
            TtsRequest.Status.PROCESSING -> {
                Log.d(TAG, "TTS is processing")
                isSpeaking = true
            }
            TtsRequest.Status.NOT_ALLOWED -> {
                Log.w(TAG, "TTS not allowed")
                isSpeaking = false

                // Handle pending table selection even when TTS not allowed, but only if not loading
                pendingTableSelection?.let { table ->
                    pendingTableSelection = null
                    if (!isLoadingTableStatus) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            onTableSelected(table)
                        }, 300)
                    } else {
                        Log.d(TAG, "Skipping pending table selection - still loading")
                    }
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

    private fun navigateToTable(table: Table) {
        if (isNavigatingToTable) {
            Log.d(TAG, "Already navigating to a table, ignoring new navigation request")
            return
        }

        Log.d(TAG, "Starting navigation to ${table.name} (${table.locationId})")

        speakAndWait("Excellent choice! I'll take you to ${table.name}. Please follow me and I'll guide you to your perfect dining spot.") {

            // Mark table as occupied immediately in local state and UI
            table.isAvailable = false
            table.occupancyStatus = Table.OccupancyStatus.PROCESSING
            tableAdapter.markTableProcessing(table.locationId)

            // FIXED: Disable table interaction during navigation
            disableTableInteraction("Navigating to ${table.name}...")

            // Update UI
            binding.btnBack.isEnabled = false

            // Set navigation state
            isNavigatingToTable = true

            // Start navigation to table
            try {
                robot.goTo(table.locationId)
                Log.d(TAG, "Navigation command sent to ${table.locationId}")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting navigation to ${table.locationId}", e)
                handleNavigationError(table, "Failed to start navigation")
            }
        }
    }

    private fun handleNavigationError(table: Table, error: String) {
        Log.e(TAG, "Navigation error for ${table.name}: $error")

        // Reset states
        isNavigatingToTable = false
        selectedTable = null
        table.isAvailable = true
        table.occupancyStatus = Table.OccupancyStatus.AVAILABLE
        tableAdapter.clearTableProcessing(table.locationId)

        // CRITICAL FIX: Remove from local occupied list on navigation error
        tableStatusManager.removeOccupiedTable(table.locationId)

        // FIXED: Re-enable table interaction after error
        enableTableInteraction()

        // Re-enable UI
        binding.btnBack.isEnabled = true
        updateStatusText()

        speakAndWait("I'm having trouble navigating to ${table.name}. Please try selecting another table or ask staff for assistance.") {
            // Refresh table status
            loadTableStatus()
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        Log.d(TAG, "Navigation status: $status for location: $location (description: $description)")

        // Only handle navigation for our selected table
        if (!isNavigatingToTable || selectedTable?.locationId != location) {
            return
        }

        runOnUiThread {
            when (status) {
                "start" -> {
                    binding.tvStatus.text = "🚀 Starting navigation to ${selectedTable?.name}..."
                    Log.d(TAG, "Started navigation to $location")
                }
                "calculating" -> {
                    binding.tvStatus.text = "🧭 Calculating route to ${selectedTable?.name}..."
                    Log.d(TAG, "Calculating route to $location")
                }
                "going" -> {
                    binding.tvStatus.text = "🚶‍♂️ On my way to ${selectedTable?.name}..."
                    Log.d(TAG, "Going to $location")
                }
                "complete" -> {
                    binding.tvStatus.text = "✅ Arrived at ${selectedTable?.name}!"
                    Log.d(TAG, "Arrived at $location")
                    onNavigationComplete(location)
                }
                "abort" -> {
                    Log.w(TAG, "Navigation aborted to $location: $description")
                    selectedTable?.let { table ->
                        handleNavigationError(table, "Navigation was aborted: $description")
                    }
                }
                else -> {
                    Log.d(TAG, "Navigation status '$status' for $location: $description")
                }
            }
        }
    }

    private fun onNavigationComplete(location: String) {
        val table = selectedTable ?: return

        Log.d(TAG, "Navigation complete to ${table.name}")

        // FIXED: Shortened message, removed duplicate "arrived" confirmation
        speakAndWait("Perfect! Welcome to ${table.name}. You can scan the QR code or use my ordering system. What would you prefer?") {

            // Send webhook to mark table as occupied with customer name
            tableStatusManager.markTableOccupied(
                sessionId = sessionId,
                tableId = location,
                orderType = orderType,
                battery = currentBattery,
                customerName = "Guest", // You could get this from user input if needed
                onSuccess = {
                    Log.d(TAG, "✅ Table $location marked as occupied successfully via webhook")
                },
                onError = { error ->
                    Log.e(TAG, "❌ Failed to mark table as occupied via webhook: $error")
                    // Local state is already updated, so continue anyway
                }
            )

            // Show ordering options after a shorter delay
            Handler(Looper.getMainLooper()).postDelayed({
                showOrderingOptions(location)
            }, 1000) // Reduced from 2000ms to 1000ms
        }
    }

    private fun showOrderingOptions(tableNo: String) {
        val intent = Intent(this, OrderingActivity::class.java).apply {
            putExtra("SESSION_ID", sessionId)
            putExtra("ORDER_TYPE", orderType)
            putExtra("TABLE_NO", tableNo)
            putExtra("BATTERY", currentBattery)
        }
        startActivity(intent)
        finish()
    }

    // CRITICAL FIX: TableStatusListener implementation to handle real-time updates
    override fun onTableStatusChanged(occupiedTables: List<String>) {
        Log.d(TAG, "🔔 Real-time table status change notification received: $occupiedTables")

        // FIXED: Only update if not currently loading and have working tables initialized
        if (workingTables.isNotEmpty() && !isLoadingTableStatus) {
            Log.d(TAG, "Applying real-time table status update")
            updateTableAvailability(occupiedTables)
        } else {
            Log.w(TAG, "Ignoring real-time table status change - working tables not initialized or still loading")
        }
    }

    private fun handleBackPressed() {
        speakAndWait("Going back to the main menu.") {
            finish()
        }
    }

    override fun onBackPressed() {
        if (!isSpeaking && !isNavigatingToTable) {
            handleBackPressed()
        } else {
            Log.d(TAG, "Back press ignored - robot is speaking or navigating")
            // Call super to maintain proper back press handling chain
            super.onBackPressed()
        }
    }
}