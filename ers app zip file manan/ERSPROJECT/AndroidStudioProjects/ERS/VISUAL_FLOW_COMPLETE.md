# 🎨 VISUAL FLOW DIAGRAM - Complete Fix

## 📱 Overall System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         ANDROID SYSTEM                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────┐         ┌──────────────────┐             │
│  │  Accessibility   │         │  Audio Manager   │             │
│  │     System       │         │   (Focus API)    │             │
│  └────────┬─────────┘         └────────┬─────────┘             │
│           │                             │                        │
│           │ Key Events                  │ Audio Focus           │
│           ▼                             ▼                        │
│  ┌──────────────────────────────────────────────────┐          │
│  │  VolumeButtonAccessibilityService                │          │
│  │  • Detects volume button holds                   │          │
│  │  • Manages wake locks (CPU + Screen)             │          │
│  │  • Wakes & unlocks screen                        │          │
│  │  • Sends actions to recording service            │          │
│  └──────────────────┬───────────────────────────────┘          │
│                     │                                            │
│                     │ Intents with Actions                       │
│                     ▼                                            │
│  ┌──────────────────────────────────────────────────┐          │
│  │  ERSRecordingService                             │          │
│  │  • Manages recording lifecycle                    │          │
│  │  • Requests audio focus                          │          │
│  │  • Controls MediaRecorder                        │          │
│  │  • Manages wake locks during recording           │          │
│  └──────────────────┬───────────────────────────────┘          │
│                     │                                            │
│                     │ Uses                                       │
│                     ▼                                            │
│  ┌──────────────────────────────────────────────────┐          │
│  │  MediaRecorder (Android Framework)               │          │
│  │  • Captures audio from microphone                │          │
│  │  • Encodes to AAC/M4A format                     │          │
│  │  • Writes to file                                │          │
│  └──────────────────────────────────────────────────┘          │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔄 State Machine - Recording Lifecycle

```
                    ┌─────────────┐
                    │   IDLE      │ ◄─────────────────┐
                    │ (Waiting)   │                    │
                    └──────┬──────┘                    │
                           │                           │
                  Volume Button Held                   │
                           │                           │
                           ▼                           │
                    ┌─────────────┐                    │
                    │  HOLDING    │                    │
                    │ (0-5 sec)   │                    │
                    └──────┬──────┘                    │
                           │                           │
               Hold >= 5 seconds                       │
                           │                           │
                           ▼                           │
        ┌──────────────────────────────────┐          │
        │  Is Currently Recording?          │          │
        └────┬─────────────────────────┬────┘          │
             │ NO                     │ YES            │
             │                         │                │
             ▼                         ▼                │
    ┌─────────────┐          ┌─────────────┐          │
    │  START      │          │ Can Stop?   │          │
    │ RECORDING   │          │ (>5s since  │          │
    └──────┬──────┘          │  start?)    │          │
           │                 └──────┬──────┘          │
           │                        │                  │
           │                  ┌─────┴─────┐           │
           │                  │ YES  │ NO │           │
           │                  ▼      │    │           │
           │         ┌─────────────┐ │    │           │
           │         │    STOP     │ │    │           │
           │         │  RECORDING  │ │    │           │
           │         └──────┬──────┘ │    │           │
           │                │        │    │           │
           │                ▼        │    ▼           │
           │         ┌─────────────┐ │ ┌──────────┐  │
           │         │AUTO-SEND IN │ │ │  WAIT    │  │
           │         │  5 SECONDS  │ │ │  MORE    │  │
           │         └──────┬──────┘ │ └──────────┘  │
           │                │        │      │         │
           │                │        └──────┘         │
           │                │                         │
           └────────────────┴─────────────────────────┘
```

---

## 🎯 Scenario 1: Screen OFF → Recording

```
┌─────────────────────────────────────────────────────────────┐
│ USER ACTION: Hold Volume Down for 5 seconds (Screen OFF)   │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────────┐
│ VolumeButtonAccessibilityService                            │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 1. onKeyEvent() receives KEYCODE_VOLUME_DOWN            │ │
│ │    └─> Check: Matches trigger? ✓                        │ │
│ │    └─> Check: Service enabled? ✓                        │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 2. ensureWakeLocksAcquired()                            │ │
│ │    └─> Partial wake lock: ✓ CPU stays ON                │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 3. wakeUpAndUnlockScreen()                              │ │
│ │    └─> Screen wake lock: ✓ Screen turns ON              │ │
│ │    └─> KeyguardManager: Attempt unlock                  │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 4. vibrateShort() → User feels feedback                 │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 5. Start checking hold duration (every 50ms)            │ │
│ │    └─> At 5000ms: onHoldComplete() called               │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 6. vibrateLong() → Success feedback                     │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 7. startRecordingService("ACTION_START_RECORDING")      │ │
│ └──────────────────────────┬──────────────────────────────┘ │
└────────────────────────────┼────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│ ERSRecordingService                                         │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 8. onStartCommand() receives ACTION_START_RECORDING     │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 9. startRecording()                                      │ │
│ │    a. ensureScreenOnAndUnlocked()                        │ │
│ │       └─> Acquire screen wake lock for 30s              │ │
│ │    b. requestAudioFocus()                                │ │
│ │       └─> Request EXCLUSIVE audio focus ✓                │ │
│ │    c. prepareOutputFile()                                │ │
│ │       └─> Create: ERS_20241128_143022.m4a               │ │
│ │    d. initializeMediaRecorder()                          │ │
│ │       └─> Try MIC source ✓                               │ │
│ │       └─> Set format: MPEG_4 / AAC                       │ │
│ │       └─> Set quality: 192kbps @ 48kHz                   │ │
│ │       └─> prepare() ✓                                    │ │
│ │    e. mediaRecorder.start() ✓                            │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 10. Update notification: "🔴 RECORDING"                 │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 11. Schedule: Enable stop after 5 seconds               │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  🎙️ RECORDING   │
                    │   IN PROGRESS   │
                    └─────────────────┘
```

---

## 🛡️ Scenario 2: Audio Recording - Success Path

```
┌──────────────────────────────────────────────────────────────┐
│ AUDIO RECORDING FLOW (When Screen is ON)                    │
└────────────────────┬─────────────────────────────────────────┘
                     │
                     ▼
        ┌────────────────────────┐
        │ 1. Request Audio Focus │
        └────────┬───────────────┘
                 │
                 ├─> AudioManager.requestAudioFocus()
                 │   └─> AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                 │
                 ▼
        ┌────────────────────────┐
        │ 2. Audio Focus Granted │ ✅
        └────────┬───────────────┘
                 │
                 ▼
        ┌────────────────────────────────┐
        │ 3. Initialize MediaRecorder    │
        └────────┬───────────────────────┘
                 │
                 ├─> new MediaRecorder()
                 ├─> setAudioSource(MIC)
                 │   └─> If fails: Try VOICE_RECOGNITION
                 ├─> setOutputFormat(MPEG_4)
                 ├─> setAudioEncoder(AAC)
                 ├─> setAudioEncodingBitRate(192000)
                 ├─> setAudioSamplingRate(48000)
                 ├─> setOutputFile(path)
                 │
                 ▼
        ┌────────────────────────┐
        │ 4. Prepare Recorder    │
        └────────┬───────────────┘
                 │
                 ├─> mediaRecorder.prepare()
                 │   └─> Success ✅
                 │
                 ▼
        ┌────────────────────────┐
        │ 5. Start Recording     │
        └────────┬───────────────┘
                 │
                 ├─> mediaRecorder.start()
                 │   └─> Success ✅
                 │
                 ▼
        ┌────────────────────────┐
        │ 6. Recording Active    │ 🎙️
        │ Audio being captured   │
        └────────┬───────────────┘
                 │
                 │ (User holds button again after 5s+)
                 │
                 ▼
        ┌────────────────────────┐
        │ 7. Stop Recording      │
        └────────┬───────────────┘
                 │
                 ├─> mediaRecorder.stop()
                 ├─> mediaRecorder.release()
                 ├─> Release audio focus
                 │
                 ▼
        ┌────────────────────────────────┐
        │ 8. File Saved                  │
        │ /storage/.../ERS_xxx.m4a       │
        └────────┬───────────────────────┘
                 │
                 ▼
        ┌────────────────────────┐
        │ 9. Auto-Send in 5s     │
        └────────────────────────┘
```

---

## ⚠️ Scenario 3: Error Handling

```
┌───────────────────────────────────────────────────────────┐
│ ERROR SCENARIOS & RECOVERY                                │
└────────────────┬──────────────────────────────────────────┘
                 │
    ┌────────────┼────────────┐
    │            │            │
    ▼            ▼            ▼
┌────────┐  ┌────────┐  ┌──────────┐
│ Audio  │  │Screen  │  │MediaRec  │
│ Focus  │  │ Mgmt   │  │  Errors  │
│ Failed │  │Errors  │  │          │
└───┬────┘  └───┬────┘  └────┬─────┘
    │           │            │
    │           │            │
    ▼           ▼            ▼
┌──────────────────────────────────┐
│ 1. Audio Focus Failed            │
│    └─> Another app has mic       │
│    └─> Show: "❌ Audio busy"     │
│    └─> Cleanup resources         │
│    └─> Don't attempt recording   │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│ 2. Wake Lock Failed              │
│    └─> Log warning               │
│    └─> Continue anyway           │
│    └─> May not work screen OFF   │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│ 3. MediaRecorder.prepare() Failed│
│    └─> Try alternative source    │
│    └─> If still fails:           │
│        └─> Show: "❌ Rec failed" │
│        └─> Release audio focus   │
│        └─> Cleanup recorder      │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│ 4. MediaRecorder.start() Failed  │
│    └─> IllegalStateException     │
│    └─> Show: "❌ Rec error"      │
│    └─> Reset recorder            │
│    └─> Release all resources     │
│    └─> User can try again        │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│ 5. Storage Error                 │
│    └─> Can't create file         │
│    └─> Show: "❌ Storage error"  │
│    └─> Check permissions         │
│    └─> Cleanup resources         │
└──────────────────────────────────┘
```

---

## 🔒 Wake Lock Strategy

```
┌─────────────────────────────────────────────────────────────┐
│ WAKE LOCK HIERARCHY                                         │
└──────────────────┬──────────────────────────────────────────┘
                   │
        ┌──────────┴──────────┐
        │                     │
        ▼                     ▼
┌─────────────────┐   ┌─────────────────┐
│  Partial Wake   │   │  Screen Wake    │
│      Lock       │   │      Lock       │
│                 │   │                 │
│ • Keeps CPU ON  │   │ • Turns screen  │
│ • Always held   │   │   ON            │
│ • Background    │   │ • Held on-      │
│   operation     │   │   demand        │
└────────┬────────┘   └────────┬────────┘
         │                     │
         │ VolumeButtonAccess  │ ERSRecording
         │ ibilityService      │ Service
         │                     │
         ▼                     ▼
┌─────────────────────────────────────┐
│ RESULT: Works with screen OFF! ✅    │
└─────────────────────────────────────┘
```

---

## 📊 Complete Flow Summary

```
USER INPUT (Hold Volume Down 5s)
          ↓
    [Accessibility Service Detects]
          ↓
    [Acquire Wake Locks]
          ↓
    [Wake & Unlock Screen]
          ↓
    [Vibrate (Short)]
          ↓
    [Send Intent to Recording Service]
          ↓
    [Request Audio Focus] ←─────┐
          ↓                     │
    [Initialize MediaRecorder]  │ RETRY
          ↓                     │ (if needed)
    [Try MIC source] ─────> Fail? ┘
          ↓
    [Prepare Recorder]
          ↓
    [Start Recording] ✅
          ↓
    [Update Notification: 🔴 RECORDING]
          ↓
    [Record Audio...]
          ↓
    [Hold Button Again After 5s+]
          ↓
    [Stop Recording]
          ↓
    [Release Audio Focus]
          ↓
    [Save File]
          ↓
    [Auto-Send After 5s]
          ↓
    [WhatsApp or Gmail?]
          ↓
    [SUCCESS] 🎉
```

---

## 🎯 Key Takeaways

### Why This Works

1. **Wake Locks** 
   - CPU stays running even with screen OFF
   - Screen turns ON for user feedback
   
2. **Audio Focus**
   - Exclusive access to microphone
   - Other apps pause automatically
   
3. **Multiple Fallbacks**
   - Try MIC, fallback to VOICE_RECOGNITION
   - Handle all error scenarios
   
4. **Comprehensive Management**
   - Screen, keyguard, audio, recorder
   - All synchronized properly

### Critical Success Factors

✅ Partial wake lock acquired BEFORE key detection
✅ Screen wake lock used to turn ON display
✅ Audio focus requested BEFORE MediaRecorder
✅ MediaRecorder state machine followed exactly
✅ All resources cleaned up on failure
✅ User feedback at every step

---

**This visual guide shows how all components work together to create a robust, reliable emergency recording system!** 🎉
