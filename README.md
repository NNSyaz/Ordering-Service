# Ordering Service Robot

An Android application for an ordering service robot that assists customers with dine-in and takeaway orders, monitors battery status, navigates to locations, and communicates with a Google Apps Script backend.

## 📌 Features

* **Robot Initialization** – Ensures the robot is ready before starting service.
* **Battery Monitoring** – Automatically returns to charging station when battery is low.
* **Navigation** – Moves to standby, tables, takeaway counter, or alternative locations.
* **Order Processing** – Handles dine-in and takeaway workflows.
* **Server Communication** – Logs table status, order placement, and QR code selections to Google Apps Script.
* **Table Status Manager** – Fetches table availability from the server or uses a local fallback.
* **Error Handling** – Includes fallbacks for navigation, TTS, and server request failures.

## 📱 System Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Temi Robot    │    │  Android App    │    │  Google Apps    │
│                 │◄──►│                 │◄──►│     Script      │
│  - Navigation   │    │  - UI Control   │    │  - Data Logging │
│  - Speech       │    │  - State Mgmt   │    │  - Table Status │
│  - Battery      │    │  - API Calls    │    │  - Order Mgmt   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

## 📂 Project Structure

```
app/
├── manifests/
├── kotlin+java/
│  └── com.example.orderingservice/
│     ├── adapters/
│     │  └── TableAdapter
│     ├── models/
│     │  ├── Table
│     │  ├── BatteryMonitorService
│     │  ├── Constants
│     │  ├── TableStatusManager
│     │  └── TemiUtils
│     ├── MainActivity
│     ├── NavigationActivity
│     ├── OrderingActivity
│     └── WebhookManager
├── java/ (generated)
├── res/
└── Gradle Scripts
```

## 🔄 Application Flow

1. **Initialization**

   * Robot readiness check
   * Battery level monitoring
   * Table status synchronization

2. **Navigation**

   * Automatic movement to greeting position
   * Fallback to current position if navigation fails

3. **Customer Greeting**

   * Automated welcome message
   * Present ordering options (Dine-in / Takeaway)

4. **Order Processing**

   * **Dine-in**: Table selection → Order placement
   * **Takeaway**: Direct order or QR code selection

5. **Backend Integration**

   * Real-time table status updates
   * Order number generation
   * Data logging and analytics

## 🔧 Configuration

### Robot Locations Setup

Configure these locations on your Temi robot:

* `standby`: Primary greeting position
* `home base`: Charging station
* Additional fallback locations as needed

### Battery Thresholds

* **Low Battery Warning**: 25%
* **Critical Battery**: 15% (automatic return to charging)

### Timeouts

* **User Interaction**: 60 seconds
* **Auto Greeting Delay**: 3 seconds
* **Table Occupation Duration**: 30 minutes

## 📡 API Integration

The app communicates with a Google Apps Script backend for:

### Endpoints Used

* `POST /`: Send order and table status updates
* `GET /?action=get_table_status`: Fetch current table occupancy

### Data Synchronization

* **table\_occupied**: When customer selects dine-in table
* **qr\_selected**: When customer chooses QR code ordering
* **order\_placed**: When order is completed
* **table\_released**: When dining session ends

## 🚨 Error Handling

* **Navigation Failures**: Fallback to current position
* **Network Issues**: Local caching with periodic retry
* **Speech Synthesis Errors**: Continue with visual interface
* **Battery Critical**: Automatic charging station return
* **Server Unavailable**: Local state management

## 🔍 Monitoring & Debugging

### Logging

The application provides comprehensive logging:

* Robot navigation status
* Table occupancy changes
* Server communication
* Error conditions

### Debug Features

* Real-time battery display
* Table status indicators
* Server connectivity status
* Detailed error messages

## 🧪 Testing

### Manual Testing Scenarios

1. **Robot Navigation**: Test movement to all configured locations
2. **Battery Simulation**: Test low/critical battery responses
3. **Network Interruption**: Verify fallback behavior
4. **Table Management**: Confirm accurate occupancy tracking
5. **Order Flow**: Complete end-to-end ordering scenarios

### Recommended Testing Environment

* Controlled restaurant environment
* Multiple table configurations
* Network reliability testing
* Extended operation testing

## 🚀 Getting Started

### Prerequisites

* Temi Robot with SDK **v1.136.0+**
* Android Studio (Giraffe or later)
* **ADB platform-tools** installed and on your `PATH`
* GitHub account for menu hosting *(optional)*
* Google Account for Google Sheets + Apps Script setup

### Installation (Local Build)

1. Clone this repository:

   ```bash
   git clone https://github.com/NNSyaz/Ordering-Service.git
   ```
2. Open the project in Android Studio.
3. Replace the following placeholders:

   * Webhook URL in `WebhookManager.kt`
   * GitHub Pages menu URL in `OrderingActivity.kt`
4. Set up Google Sheet & Apps Script webhook:

   * See **sheet\_setup.md**
5. Configure Temi locations:

   * Create `table1`, `table2`, etc. in Temi locations
   * Set `standby` and `home base` locations
6. Build APK:

   * **Android Studio**: *Build > Build Bundle(s) / APK(s) > Build APK(s)*
   * **CLI**:

     ```bash
     ./gradlew assembleDebug
     ```

---

## 📲 Install on Temi Robot (ADB over Wi‑Fi)

> These steps incorporate your requested flow:
>
> 1. Open Temi robot port in Developer settings
> 2. Open the project in Android Studio; **Clean** and **Rebuild** after each change
> 3. Use terminal: `adb connect xxx.xxx.xxx.xxx` (Temi IP + port)
> 4. Once connected, install the APK

### 1) Enable Developer Options & ADB on Temi

* On Temi tablet: **Settings → About tablet** (or **About**), tap **Build number** 7 times to enable **Developer options** (if not already enabled).
* Go to **Settings → Developer options** and enable:

  * **USB debugging** (recommended for first setup), and
  * **ADB over network** / **Wireless debugging**.
* Note the **IP address** of the Temi (Settings → Wi‑Fi → the connected network details). The default ADB TCP port is usually **5555** unless specified otherwise by your Temi build.

### 2) Clean & Rebuild the App (every change)

* **Android Studio**: *Build → Clean Project*, then *Build → Rebuild Project*.
* Or via CLI:

  ```bash
  ./gradlew clean assembleDebug
  ```

### 3) Connect ADB to Temi over Wi‑Fi

From your development machine terminal:

```bash
# If you previously connected via USB, you can switch Temi to TCP/IP mode first:
adb tcpip 5555

# Connect to Temi (replace with Temi's IP and port if different)
adb connect 192.168.1.123:5555

# Verify device is listed
adb devices
```

You should see a device entry like `192.168.1.123:5555    device`.

### 4) Install (or Reinstall) the APK

Locate the built APK (typical path shown below) and install:

```bash
# Install fresh
adb install app/build/outputs/apk/debug/app-debug.apk

# Or update the existing app in-place
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Optional: Uninstall / App ID

If you need to uninstall first (replace with your actual applicationId from `app/build.gradle`):

```bash
adb uninstall com.example.orderingservice
```

To check if the package exists:

```bash
adb shell pm list packages | grep orderingservice
```

### Troubleshooting

* **`device offline` / `unauthorized`**: On Temi, confirm the ADB authorization prompt; toggle ADB over network off/on, then reconnect.
* **`failed to connect`**: Ensure Temi and your PC are on the **same Wi‑Fi**; verify IP/port; check that wireless debugging is enabled.
* **`INSTALL_FAILED_VERSION_DOWNGRADE`**: Uninstall the app first or bump `versionCode`.
* **`INSTALL_FAILED_ALREADY_EXISTS`**: Use `-r` to replace or uninstall before installing.
* **Slow transfer**: Use USB once to run `adb tcpip 5555`, then switch to Wi‑Fi.

---

## 🌐 Web Menu

You can host your menu using:

* GitHub Pages: [How to deploy](https://pages.github.com/)
* Firebase Hosting
* Or local assets: `file:///android_asset/menu.html`

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

## 🧪 Testing & Edge Cases

* Inactivity timeout: Resets app if no action in 60s
* Navigation failure: Retries or returns to base
* Battery low (< 25%): Displays warning
* Battery critical (< 15%): Ends current task and docks

---

## 🛠️ TODOs & Enhancements

* [ ] Add real-time database (Firebase/Supabase)
* [ ] Multiple Temi coordination
* [ ] Staff-side dashboard
* [ ] Analytics dashboard for order patterns



