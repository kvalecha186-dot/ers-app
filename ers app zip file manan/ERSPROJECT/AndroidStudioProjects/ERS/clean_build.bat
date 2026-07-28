@echo off
echo Cleaning build cache...
cd /d "D:\Work Station\New folder\AndroidStudioProjects\ERS"

echo Deleting build directories...
if exist app\build rmdir /s /q app\build
if exist build rmdir /s /q build
if exist .gradle rmdir /s /q .gradle

echo Clean complete! Now rebuild the project in Android Studio.
pause
