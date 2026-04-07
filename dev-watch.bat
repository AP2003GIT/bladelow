@echo off
title Bladelow Auto-Build Watcher
echo ============================================
echo  Bladelow Auto-Build + Deploy to Lunar Client
echo ============================================
echo.
echo Watching for file changes...
echo Every time you save a file, it will:
echo   1. Rebuild the mod
echo   2. Copy it to Lunar Client mods folder
echo.
echo Press Ctrl+C to stop.
echo.
cd /d "%~dp0"
gradlew.bat build --continuous
