# 🎯 VISUAL FLOW DIAGRAM

## Complete User Journey & System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     USER FIRST TIME SETUP                        │
└─────────────────────────────────────────────────────────────────┘

    📱 Install App
         ↓
    🎨 Onboarding (4 screens)
         ├─ Welcome to ERS
         ├─ Quick Trigger Info
         ├─ Auto Location & Alerts
         └─ You're All Set!
         ↓
    🔐 [NEW] Accessibility Setup ⚠️ CRITICAL
         ├─ See why it's needed
         ├─ Privacy explanation
         ├─ Tap "Open Settings"
         ├─ Enable "ERS Emergency Monitor"
         ├─ Return to app
         └─ ✅ Status: ENABLED
         ↓
    🎤 Volume Button Testing
         ├─ Test starting recording
         ├─ Test stopping recording
         └─ Test sending
         ↓
    📞 Emergency Contacts Setup
         ├─ WhatsApp: +919876543210
         └─ Email: emergency@example.com
         ↓
    🏠 Main App
         ├─ Toggle Service ON
         └─ Ready for emergencies!


┌─────────────────────────────────────────────────────────────────┐
│                  EMERGENCY RECORDING FLOW                        │
└─────────────────────────────────────────────────────────────────┘

PHONE STATE: Screen OFF, Locked, In Pocket
         ↓
    👤 USER HOLDS VOLUME DOWN 5 SECONDS
         ↓
    ┌──────────────────────────────────────┐
    │ VolumeButtonAccessibilityService     │
    │  • Detects key press (even screen off)│
    │  • Starts timer                      │
    │  • Measures hold duration            │
    └──────────────────────────────────────┘
         ↓ (5 seconds elapsed)
    📳 Phone Vibrates LONG (500ms)
         ↓
    Broadcast: "com.ers.START_RECORDING"
         ↓
    ┌──────────────────────────────────────┐
    │ ERSRecordingService                  │
    │  • Receives broadcast               │
    │  • Initializes MediaRecorder        │
    │  • Starts recording audio           │
    │  • Shows notification: "🔴 RECORDING"│
    └──────────────────────────────────────┘
         ↓
    ⏱️ RECORDING... (minimum 5 seconds)
         ↓
    Notification: "HOLD 5s to STOP"
         ↓
    👤 USER HOLDS VOLUME DOWN AGAIN 5 SECONDS
         ↓
    📳 Phone Vibrates DOUBLE (200-100-200-100-200ms)
         ↓
    Broadcast: "com.ers.STOP_RECORDING"
         ↓
    ┌──────────────────────────────────────┐
    │ ERSRecordingService                  │
    │  • Stops MediaRecorder              │
    │  • Saves file                       │
    │  • Gets location                    │
    │  • Waits 5 seconds                  │
    └──────────────────────────────────────┘
         ↓
    🚀 AUTO-SEND INITIATED
         ↓
    ┌──────────── WhatsApp Priority ─────────────┐
    │                                             │
    │  Try WhatsApp First (if phone number set): │
    │  1. Format number: +919876543210           │
    │  2. Open WhatsApp API URL                  │
    │  3. Pre-fill message:                      │
    │     "🚨 EMERGENCY ALERT                    │
    │      ⏰ Time: [timestamp]                  │
    │      📍 Location: [GPS link]"             │
    │  4. Wait 2 seconds                         │
    │  5. Open attachment picker                 │
    │  6. Attach audio file                      │
    │     👤 USER PRESSES SEND                   │
    │                                             │
    │  If WhatsApp fails or not set, try Gmail:  │
    │  1. Open Gmail app                         │
    │  2. Pre-fill recipient                     │
    │  3. Pre-fill subject:                      │
    │     "🚨 EMERGENCY ALERT - [time]"         │
    │  4. Pre-fill body:                         │
    │     "⚠️ EMERGENCY SITUATION DETECTED      │
    │      ⏰ Time: [timestamp]                  │
    │      📍 Location: [GPS link]              │
    │      🎙️ Audio: See attachment"            │
    │  5. Attach audio recording                 │
    │     👤 USER PRESSES SEND                   │
    └─────────────────────────────────────────────┘
         ↓
    ✅ EMERGENCY ALERT SENT!


┌─────────────────────────────────────────────────────────────────┐
│              TECHNICAL ARCHITECTURE                              │
└─────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────┐
│                    Android System Layer                        │
│                                                                │
│  Volume Buttons → System Key Event Dispatcher                │
│                           ↓                                    │
│  [AccessibilityService Can Intercept Key Events]             │
└───────────────────────────────────────────────────────────────┘
                            ↓
┌───────────────────────────────────────────────────────────────┐
│          VolumeButtonAccessibilityService                      │
│                                                                │
│  @Override                                                     │
│  protected boolean onKeyEvent(KeyEvent event) {              │
│      if (isVolumeButton && matchesTriggerType) {             │
│          if (ACTION_DOWN) onButtonPressed();                 │
│          if (ACTION_UP) onButtonReleased();                  │
│          return true; // Consume event                       │
│      }                                                         │
│  }                                                             │
│                                                                │
│  Hold Duration Tracking:                                      │
│  • Timer starts on ACTION_DOWN                               │
│  • Checks every 100ms                                        │
│  • At 5000ms → broadcast START/STOP                          │
│                                                                │
│  State Management:                                            │
│  • isRecording (from service)                                │
│  • canStopRecording (after 5s min)                           │
└───────────────────────────────────────────────────────────────┘
                            ↓ (BroadcastIntent)
                            ↓
┌───────────────────────────────────────────────────────────────┐
│              ERSRecordingService                               │
│              (Foreground Service)                              │
│                                                                │
│  BroadcastReceiver Handles:                                   │
│  • "com.ers.START_RECORDING"                                  │
│  • "com.ers.STOP_RECORDING"                                   │
│  • "com.ers.CAN_STOP_RECORDING"                               │
│  • "com.ers.VOLUME_BUTTON_PRESSED"                            │
│  • "com.ers.VOLUME_BUTTON_RELEASED_EARLY"                     │
│                                                                │
│  Recording Management:                                        │
│  • MediaRecorder initialization                              │
│  • Audio source: MIC                                          │
│  • Format: MPEG_4 / AAC                                       │
│  • Output: ERS_Recordings/ERS_timestamp.m4a                  │
│  • Wake lock: Keep CPU alive                                 │
│                                                                │
│  Sending Management:                                          │
│  • sendWhatsAppEnhanced()                                     │
│  • sendGmailEnhanced()                                        │
│  • getLocation() with GPS                                     │
└───────────────────────────────────────────────────────────────┘
                            ↓
┌───────────────────────────────────────────────────────────────┐
│              External Apps                                     │
│                                                                │
│  WhatsApp:                                                     │
│  • Intent.ACTION_VIEW                                         │
│  • URL: api.whatsapp.com/send?phone=...&text=...            │
│  • Then: Intent.ACTION_SEND with audio/*                     │
│                                                                │
│  Gmail:                                                        │
│  • Intent.ACTION_SEND                                         │
│  • Type: message/rfc822                                       │
│  • Package: com.google.android.gm                            │
│  • Extras: EMAIL, SUBJECT, TEXT, STREAM                      │
└───────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────┐
│                  DATA FLOW DIAGRAM                              │
└─────────────────────────────────────────────────────────────────┘

User Input              Processing                 Output
──────────            ──────────────            ─────────────

Volume Button         AccessibilityService      Vibration
Press (Hardware)  →   Key Event Detection   →   Feedback
                                                 ↓
                      Duration Measurement       Broadcast
                      (5 second timer)      →   Intent
                                                 ↓
                      ERSRecordingService        Notification
                      Broadcast Receiver    →   Update
                                                 ↓
                      MediaRecorder              Audio File
Microphone       →    Audio Recording       →   .m4a file
(Hardware)                                       ↓
                      LocationManager            GPS Data
GPS              →    Get Coordinates       →   Maps Link
(Hardware)                                       ↓
                      File Provider              Shareable
Audio File       →    Create URI            →   URI
                                                 ↓
                      Intent Launcher            WhatsApp/
Contact Info     →    Pre-fill Data         →   Gmail Compose
                                                 ↓
User Action           Send Button                Message
(Press Send)     →    in WhatsApp/Gmail     →   Delivered!


┌─────────────────────────────────────────────────────────────────┐
│                  PERMISSIONS & SETUP                             │
└─────────────────────────────────────────────────────────────────┘

Runtime Permissions (Requested in MainActivity):
├─ ✅ RECORD_AUDIO         - For recording emergency audio
├─ ✅ ACCESS_FINE_LOCATION - For GPS coordinates
├─ ✅ ACCESS_COARSE_LOCATION - Fallback location
├─ ✅ POST_NOTIFICATIONS   - For service notifications (Android 13+)
└─ ✅ WRITE_EXTERNAL_STORAGE - For saving files (Android 12 and below)

Special Permissions (User must enable manually):
├─ 🔐 BIND_ACCESSIBILITY_SERVICE ⚠️ CRITICAL
│     User must enable in: Settings → Accessibility → ERS Emergency Monitor
│     Why: Only way to detect volume buttons with screen OFF
│     Privacy: We ONLY monitor volume buttons, nothing else
│
└─ 🔋 Battery Optimization Exemption
      User should disable battery optimization for ERS
      Why: Prevents Android from killing the service
      Path: Settings → Apps → ERS → Battery → Unrestricted


┌─────────────────────────────────────────────────────────────────┐
│                  STATE MACHINE                                   │
└─────────────────────────────────────────────────────────────────┘

[SERVICE OFF]
     │
     │ User toggles ON
     ↓
[IDLE - Monitoring]
 │  │  Notification: "ERS Active - Volume Down - HOLD 5s"
 │  │  
 │  │ Volume button pressed
 │  ↓
 │ [HOLDING TO START]
 │  │  Timer running...
 │  │  Notification: "HOLD 5s to START - Keep holding..."
 │  │
 │  ├─ Released early → Back to IDLE
 │  │
 │  └─ 5 seconds elapsed
 │     ↓
 │   [RECORDING STARTED]
 │    │  MediaRecorder active
 │    │  Notification: "🔴 RECORDING - Wait 5s before stopping..."
 │    │  Minimum recording timer: 5s
 │    │
 │    │ Timer complete
 │    ↓
 │   [RECORDING - Can Stop]
 │    │  Notification: "🔴 RECORDING - HOLD 5s to STOP"
 │    │
 │    │ Volume button pressed
 │    ↓
 │   [HOLDING TO STOP]
 │    │  Timer running...
 │    │  Notification: "HOLD 5s to STOP - Keep holding..."
 │    │
 │    ├─ Released early → Back to [RECORDING - Can Stop]
 │    │
 │    └─ 5 seconds elapsed
 │       ↓
 │     [RECORDING STOPPED]
 │      │  MediaRecorder stopped
 │      │  File saved
 │      │  Notification: "⏸️ Stopped - Auto-sending in 5s..."
 │      │
 │      │ Wait 5 seconds
 │      ↓
 │     [SENDING]
 │      │  Open WhatsApp/Gmail
 │      │  Pre-fill everything
 │      │  Notification: "📱 WhatsApp Opened / 📧 Gmail Opened"
 │      │
 │      │ User presses Send
 │      ↓
 └──→ [IDLE - Monitoring]
       Loop continues...


┌─────────────────────────────────────────────────────────────────┐
│                  ERROR HANDLING                                  │
└─────────────────────────────────────────────────────────────────┘

Error Condition              Response                Recovery
────────────────            ──────────              ─────────────

Accessibility Service       Show warning            Guide to settings
Not Enabled            →    notification        →   Open accessibility
                                                    settings

Permission Denied           Show dialog with        Request again or
(Microphone)           →    explanation        →    allow skip

MediaRecorder Fails         Catch IOException       Show error
(Out of storage)       →    & IllegalState     →    notification

Location Unavailable        Use fallback text       "Location unavailable
(GPS off)              →    "Location unavailable" → (check GPS)"

WhatsApp Not Installed      Catch exception         Fallback to Gmail
                       →    in try-catch       →    or any email app

Gmail Not Installed         Catch exception         Show email chooser
                       →    in try-catch       →    for any email app

No Contacts Set             Recording saved         Notification:
                       →    locally only       →    "Recording saved
                                                    Setup contacts"

Service Killed by           START_STICKY            Android restarts
Android                →    flag in service    →    service automatically

Battery Optimization        Prompt user to          User disables in
Enabled                →    disable            →    system settings


┌─────────────────────────────────────────────────────────────────┐
│                  SUCCESS METRICS                                 │
└─────────────────────────────────────────────────────────────────┘

✅ Works with screen OFF               (AccessibilityService)
✅ Works with phone locked             (System-level key events)
✅ Works in pocket/purse               (No screen needed)
✅ Reliable hold detection             (100ms polling)
✅ Cannot be killed easily             (Foreground + WakeLock)
✅ Pre-fills everything for sending    (Intent extras)
✅ Clear user feedback                 (Vibrations + Notifications)
✅ Survives app removal                (stopWithTask=false)
✅ Battery efficient                   (Wake lock only when recording)
✅ Privacy respecting                  (Volume buttons only)


END OF FLOW DIAGRAM
