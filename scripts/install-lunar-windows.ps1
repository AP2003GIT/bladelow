param(
    [string]$MinecraftProfileVersion = "1.21",
    [string]$FabricSubdir = "fabric-1.21.11",
    [string]$WindowsUser = $env:USERNAME
)

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

Write-Host "Building jar..."
& .\gradlew.bat clean build

$Jar = Get-ChildItem -Path ".\build\libs" -Filter "minecraft-bladelow-*.jar" `
    | Where-Object { $_.Name -notmatch "-sources\.jar$" } `
    | Sort-Object LastWriteTimeUtc -Descending `
    | Select-Object -First 1

if (-not $Jar) {
    throw "Build succeeded but no runtime jar was found in build\libs."
}

$TargetDir = "C:\Users\$WindowsUser\.lunarclient\profiles\vanilla\$MinecraftProfileVersion\mods\$FabricSubdir"
New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
Copy-Item -Path $Jar.FullName -Destination $TargetDir -Force

Write-Host "Installed:"
Write-Host "  $($Jar.FullName)"
Write-Host "to:"
Write-Host "  $TargetDir"
Write-Host ""
Write-Host "Restart Lunar Client to load the update."
