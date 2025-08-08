# Ordering Service Robot

An Android application for an ordering service robot that assists customers with dine-in and takeaway orders, monitors battery status, navigates to locations, and communicates with a Google Apps Script backend.

## 📌 Features

- **Robot Initialization** – Ensures the robot is ready before starting service.
- **Battery Monitoring** – Automatically returns to charging station when battery is low.
- **Navigation** – Moves to standby, tables, takeaway counter, or alternative locations.
- **Order Processing** – Handles dine-in and takeaway workflows.
- **Server Communication** – Logs table status, order placement, and QR code selections to Google Apps Script.
- **Table Status Manager** – Fetches table availability from the server or uses a local fallback.
- **Error Handling** – Includes fallbacks for navigation, TTS, and server request failures.
  
📱 System Architecture
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Temi Robot    │    │  Android App    │    │  Google Apps    │
│                 │◄──►│                 │◄──►│     Script      │
│  - Navigation   │    │  - UI Control   │    │  - Data Logging │
│  - Speech       │    │  - State Mgmt   │    │  - Table Status │
│  - Battery      │    │  - API Calls    │    │  - Order Mgmt   │
└─────────────────┘    └─────────────────┘    └─────────────────┘

## 📂 Project Structure

```
app/
├── manifests/
├── kotlin+java/
│ └── com.example.orderingservice/
│ ├── adapters/
│ │ └── TableAdapter
│ ├── models/
│ │ ├── Table
│ │ ├── BatteryMonitorService
│ │ ├── Constants
│ │ ├── TableStatusManager
│ │ └── TemiUtils
│ ├── MainActivity
│ ├── NavigationActivity
│ ├── OrderingActivity
│ └── WebhookManager
├── java/ (generated)
├── res/
└── Gradle Scripts
```
## 🔄 Application Flow

1. **Initialization**
   - Robot readiness check
   - Battery level monitoring
   - Table status synchronization

2. **Navigation**
   - Automatic movement to greeting position
   - Fallback to current position if navigation fails

3. **Customer Greeting**
   - Automated welcome message
   - Present ordering options (Dine-in / Takeaway)

4. **Order Processing**
   - **Dine-in**: Table selection → Order placement
   - **Takeaway**: Direct order or QR code selection

5. **Backend Integration**
   - Real-time table status updates
   - Order number generation
   - Data logging and analytics

## 🔧 Configuration

### Robot Locations Setup
Configure these locations on your Temi robot:
- `standby`: Primary greeting position
- `home base`: Charging station
- Additional fallback locations as needed

### Battery Thresholds
- **Low Battery Warning**: 25%
- **Critical Battery**: 15% (automatic return to charging)

### Timeouts
- **User Interaction**: 60 seconds
- **Auto Greeting Delay**: 3 seconds
- **Table Occupation Duration**: 30 minutes

## 📡 API Integration

The app communicates with a Google Apps Script backend for:

### Endpoints Used
- `POST /`: Send order and table status updates
- `GET /?action=get_table_status`: Fetch current table occupancy

### Data Synchronization
- **table_occupied**: When customer selects dine-in table
- **qr_selected**: When customer chooses QR code ordering
- **order_placed**: When order is completed
- **table_released**: When dining session ends

## 🚨 Error Handling

- **Navigation Failures**: Fallback to current position
- **Network Issues**: Local caching with periodic retry
- **Speech Synthesis Errors**: Continue with visual interface
- **Battery Critical**: Automatic charging station return
- **Server Unavailable**: Local state management

## 🔍 Monitoring & Debugging

### Logging
The application provides comprehensive logging:
- Robot navigation status
- Table occupancy changes  
- Server communication
- Error conditions

### Debug Features
- Real-time battery display
- Table status indicators
- Server connectivity status
- Detailed error messages

## 🧪 Testing

### Manual Testing Scenarios
1. **Robot Navigation**: Test movement to all configured locations
2. **Battery Simulation**: Test low/critical battery responses
3. **Network Interruption**: Verify fallback behavior
4. **Table Management**: Confirm accurate occupancy tracking
5. **Order Flow**: Complete end-to-end ordering scenarios

### Recommended Testing Environment
- Controlled restaurant environment
- Multiple table configurations
- Network reliability testing
- Extended operation testing

## 🚀 Getting Started

### Prerequisites

- Temi Robot with SDK v1.136.0+
- Android Studio (Giraffe or later)
- GitHub account for menu hosting *(optional)*
- Google Account for Google Sheets + Apps Script setup

### Installation
1. Clone this repository:
   ```bash
   git clone https://github.com/NNSyaz/Ordering-Service.git
2. Open project in Android Studio

3. Replace the following placeholders:
   - Webhook URL in `WebhookManager.kt`
   - GitHub Pages menu URL in `OrderingActivity.kt`

4. Set up Google Sheet & Apps Script webhook:
   - [See Google Sheet Setup Guide](sheet_setup.md)

5. Configure Temi:
   - Create `table1`, `table2`, etc. in Temi locations
   - Set standby / home base location

6. Build and install APK:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🌐 Web Menu

You can host your menu using:
- GitHub Pages: [How to deploy](https://pages.github.com/)
- Firebase Hosting
- Or local assets: `file:///android_asset/menu.html`

Make sure the menu supports `window.Android.onOrderComplete(data)` to send real order data.

---

## 📊 Webhook Example (JSON)

```json
{
  "session_id": "session_abc124",
  "table_no": "Table 3",
  "order_type": "Dine-in",
  "menu_items": ["Nasi Goreng", "Teh Ais"],
  "battery": 47,
  "status": "order_placed",
  "timestamp": 1722801600000,
  "total_items": 2
}
```

---

## Testing & Edge Cases

- Inactivity timeout: Resets app if no action in 60s  
- Navigation failure: Retries or returns to base  
- Battery low (< 25%): Displays warning  
- Battery critical (< 15%): Ends current task and docks  

---

## 🛠️ TODOs & Enhancements

- [ ] Add real-time database (Firebase/Supabase)
- [ ] Multiple Temi coordination
- [ ] Staff-side dashboard
- [ ] Analytics dashboard for order patterns

---




