# Google Apps Script Backend - Restaurant Ordering Service

Backend server implementation for the Restaurant Ordering Service using Google Apps Script. Handles order management, table status tracking, and real-time data synchronization.

## 🎯 Overview

This Google Apps Script serves as the backend API for the Temi robot restaurant ordering system. It manages:
- Table occupancy tracking
- Order number generation
- QR code order integration
- Real-time status synchronization
- Data logging and analytics

## 🏗️ Architecture

```
Google Sheets ←→ Apps Script ←→ Android App
     ↓              ↓              ↓
 Data Storage   API Logic    User Interface
```

## 📊 Data Structure

### Google Sheets Columns
| Column | Field | Description |
|--------|-------|-------------|
| A | Timestamp | When the event occurred |
| B | Session ID | Unique session identifier |
| C | Table ID | Table identifier (table1, table2, takeaway) |
| D | Order Type | dine-in or takeaway |
| E | Order Info | Menu items and details |
| F | Status | Current status (Table Occupied, Available, etc.) |
| G | Battery | Robot battery level |
| H | End Time | When table becomes available |
| I | Customer Name | Customer identifier |
| J | Order Number | Generated order number (1000-1999 dine-in, 2000-2999 takeaway) |

## 🔌 API Endpoints

### POST Requests
Handle various order and table management operations:

#### Table Occupation
```json
{
  "status": "table_occupied",
  "session_id": "uuid-string",
  "table_id": "table1",
  "order_type": "dine-in",
  "battery": 85,
  "customer_name": "Guest"
}
```

#### QR Code Selection
```json
{
  "status": "qr_selected",
  "session_id": "uuid-string",
  "order_type": "takeaway",
  "battery": 82
}
```

#### Order Placement
```json
{
  "status": "order_placed",
  "session_id": "uuid-string",
  "table_no": "table1",
  "order_type": "dine-in",
  "menu_items": ["Burger", "Fries"],
  "total_amount": "25.90",
  "customer_name": "John Doe",
  "battery": 80
}
```

#### Table Release
```json
{
  "status": "table_released",
  "session_id": "uuid-string",
  "table_id": "table1"
}
```

### GET Requests

#### Get Table Status
```
GET /?action=get_table_status
```

Response:
```json
{
  "occupied_tables": ["table1", "table3"],
  "timestamp": 1640995200000,
  "status": "success"
}
```

## 🚀 Deployment Guide

### 1. Create Google Apps Script Project

1. Go to [Google Apps Script](https://script.google.com)
2. Click "New Project"
3. Replace default code with `Code.gs` content
4. Save the project with a descriptive name

### 2. Create Google Sheets Database

1. Create a new Google Sheets document
2. Rename "Sheet1" or ensure the sheet name matches the script
3. Add headers in row 1:
   ```
   Timestamp | Session ID | Table ID | Order Type | Order Info | Status | Battery | End Time | Customer Name | Order Number
   ```

### 3. Configure Permissions

1. In Apps Script, go to "Services" 
2. Add "Google Sheets API" if not already present
3. Ensure the script has access to your sheets

### 4. Deploy as Web App

1. Click "Deploy" → "New Deployment"
2. Choose type: "Web app"
3. Execute as: "Me"
4. Who has access: "Anyone" (for external API access)
5. Click "Deploy"
6. Copy the web app URL - this is your API endpoint

### 5. Test the Deployment

Test with curl or Postman:
```bash
# Test GET request
curl "YOUR_WEB_APP_URL?action=get_table_status"

# Test POST request
curl -X POST YOUR_WEB_APP_URL \
  -H "Content-Type: application/json" \
  -d '{"status":"table_occupied","session_id":"test-123","table_id":"table1","order_type":"dine-in"}'
```

## ⚙️ Configuration

### Order Number Ranges
- **Dine-in Orders**: 1000-1999
- **Takeaway Orders**: 2000-2999
- **Daily Reset**: Order numbers reset at midnight

### Table ID Normalization
The system automatically normalizes table IDs:
- `"1"` → `"table1"`
- `"Table 5"` → `"table5"`
- `"takeaway"` → `"takeaway"`
- `"counter"` → `"takeaway"`

### Occupation Duration
- **Dine-in**: 30 minutes default
- **Takeaway**: Immediate availability
- **Configurable**: Modify `30 * 60000` in the code

## 🔍 Monitoring & Logging

### Built-in Logging
The script includes comprehensive logging:
```javascript
console.log('Received data:', JSON.stringify(data));
console.log('Generated order number: ' + orderNumber);
console.log('Current occupied tables:', occupiedTables);
```

### View Logs
1. In Apps Script editor, click "Executions"
2. Click on any execution to view detailed logs
3. Monitor API usage and errors

### Error Handling
- Invalid JSON requests return error responses
- Database errors are caught and logged
- Fallback order number generation for edge cases

## 🛠️ Maintenance

### Regular Tasks
1. **Monitor Sheet Size**: Archive old data monthly
2. **Check API Quotas**: Monitor Google Apps Script usage
3. **Backup Data**: Regular sheet backups
4. **Performance Review**: Monitor response times

### Troubleshooting Common Issues

#### Issue: "Script function not found"
**Solution**: Ensure function names match exactly, redeploy if necessary

#### Issue: "Permission denied" 
**Solution**: Check sharing settings on Google Sheets, verify script permissions

#### Issue: "Timeout errors"
**Solution**: Optimize database queries, consider data archiving

#### Issue: "Order numbers not generating"
**Solution**: Check date calculations, verify data format in sheets

## 📈 Analytics & Reporting

### Available Data Points
- Order volume by type (dine-in vs takeaway)
- Peak usage times
- Average table occupation duration
- Robot battery patterns
- Customer flow analytics

### Creating Reports
Use Google Sheets built-in tools:
1. **Pivot Tables**: Analyze order patterns
2. **Charts**: Visualize usage trends
3. **Conditional Formatting**: Highlight important data
4. **Filters**: Focus on specific time periods

## 🔐 Security Considerations

### Best Practices
- Regularly rotate deployment URLs if compromised
- Monitor access logs for unusual activity
- Validate all input data thoroughly
- Keep session IDs truly random and unique

### Data Protection
- Customer data is minimal (names only if provided)
- No sensitive payment information stored
- Consider data retention policies

## 📊 Performance Optimization

### Database Optimization
- **Indexing**: Google Sheets automatically optimizes
- **Data Archiving**: Move old records to separate sheets
- **Query Efficiency**: Start searches from most recent data

### Script Optimization
- **Minimize API Calls**: Batch operations when possible
- **Efficient Loops**: Process data from newest to oldest
- **Memory Management**: Clear large variables after use

## 🚀 Advanced Features

### Custom Extensions
1. **Email Notifications**: Alert on critical events
2. **Analytics Dashboard**: Real-time monitoring interface
3. **Integration APIs**: Connect with POS systems
4. **Mobile Admin App**: Remote monitoring capabilities

### Scaling Considerations
- **Multiple Restaurants**: Use separate sheets per location
- **High Volume**: Consider upgrading to Google Cloud Functions
- **Real-time Updates**: Implement WebSocket connections for instant updates

## 🤝 API Integration Examples

### Android (Kotlin)
```kotlin
// Example API call from Android app
val json = JSONObject().apply {
    put("status", "order_placed")
    put("session_id", sessionId)
    put("table_no", tableNo)
    put("order_type", orderType)
    put("total_amount", "25.90")
}

// POST request to Google Apps Script
```

### JavaScript (Web)
```javascript
// Example web integration
fetch('YOUR_WEB_APP_URL', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        status: 'qr_selected',
        session_id: 'web-' + Date.now(),
        order_type: 'takeaway'
    })
});
```

## 📞 Support & Troubleshooting

### Common Solutions
1. **Redeploy**: Often fixes permission issues
2. **Check Logs**: Use Apps Script execution transcript
3. **Test Manually**: Use Apps Script editor's run function
4. **Validate JSON**: Ensure proper request format

### Getting Help
- Google Apps Script [Documentation](https://developers.google.com/apps-script)
- Google Sheets [API Reference](https://developers.google.com/sheets/api)
- Stack Overflow for community support

---


## 🏷️ Tags

`google-apps-script` `restaurant-management` `api` `temi-robot` `order-system` `table-management`
