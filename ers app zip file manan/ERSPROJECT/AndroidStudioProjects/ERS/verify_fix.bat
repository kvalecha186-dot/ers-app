@echo off
echo ============================================
echo ERS - Emergency Recording System
echo ROBUST FIX VERIFICATION
echo ============================================
echo.

echo [1/7] Checking Gradle Build...
call gradlew clean
if errorlevel 1 (
    echo ERROR: Clean failed!
    pause
    exit /b 1
)
echo ✓ Clean successful
echo.

echo [2/7] Building APK...
call gradlew assembleDebug
if errorlevel 1 (
    echo ERROR: Build failed!
    pause
    exit /b 1
)
echo ✓ Build successful
echo.

echo [3/7] Finding connected devices...
adb devices
echo.

echo [4/7] Installing APK...
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
    echo ERROR: Installation failed!
    echo Make sure USB debugging is enabled
    pause
    exit /b 1
)
echo ✓ Installation successful
echo.

echo [5/7] Granting RECORD_AUDIO permission...
adb shell pm grant com.ers android.permission.RECORD_AUDIO
echo ✓ RECORD_AUDIO granted
echo.

echo [6/7] Granting WAKE_LOCK permission...
adb shell pm grant com.ers android.permission.WAKE_LOCK
echo ✓ WAKE_LOCK granted
echo.

echo [7/7] Starting logcat monitoring...
echo.
echo ============================================
echo READY TO TEST!
echo ============================================
echo.
echo MANUAL STEPS:
echo 1. Enable Accessibility Service in phone settings
echo 2. Disable battery optimization for ERS
echo 3. Test scenarios:
echo    a. Screen OFF - Hold Volume Down 5s
echo    b. Screen ON + Locked - Hold Volume Down 5s  
echo    c. Screen ON + Unlocked - Hold Volume Down 5s
echo.
echo Watching logs for: VolumeButtonService and ERSRecordingService
echo Press Ctrl+C to stop
echo.
echo ============================================
echo.

adb logcat -v time | findstr /C:"VolumeButtonService" /C:"ERSRecordingService"

pause
