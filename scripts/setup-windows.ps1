param([switch]$Build)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'check-system.ps1')
Write-Host 'Для готовой установки откройте release/desktop/Auralis-Setup.exe.'
Write-Host 'Приложение сохраняет данные в %LOCALAPPDATA%/Auralis.'
Write-Host 'При сборке исходников установите зависимости по docs/DEVELOPMENT_GUIDE.md.'
if($Build){& (Join-Path $PSScriptRoot 'build-desktop.ps1')}
