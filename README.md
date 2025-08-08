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

## 📂 Project Structure
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

## 🔄 Workflow

Below is a simplified overview of the app's logic:

The robot follows this process:
1. Initialize and check readiness.
2. Monitor battery and charge if low.
3. Navigate to standby or alternative location.
4. Greet customers and display ordering options.
5. Handle dine-in, takeaway, or timeout scenarios.
6. Communicate with the server to track table and order status.
7. End session or loop back for continuous service.

## 🖥️ Backend

This project communicates with a **Google Apps Script** backend to:
- Log table occupation and release.
- Record QR selections.
- Register orders and generate order numbers.
- Maintain a real-time table status map.

## 🛠️ Tech Stack

- **Android (Java/Kotlin)** – Main application logic.
- **Google Apps Script** – Backend order & table status management.
- **Temi SDK** – Robot navigation and interaction.
- **REST API** – Communication between app and backend.

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




