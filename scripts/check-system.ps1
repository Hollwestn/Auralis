$ErrorActionPreference='Stop'
$missing=@()
foreach($name in @('java','mvn','ffmpeg','ffprobe','node','npm','docker','adb','jpackage','candle','light')) {
 $tool=Get-Command $name -ErrorAction SilentlyContinue
 if($tool){Write-Host "[OK] $name : $($tool.Source)"} else {Write-Host "[--] $name : не найден"; $missing+=$name}
}
Write-Host 'Для Desktop нужны JDK 25, Maven, FFmpeg. Для установщика EXE — WiX 3.'
Write-Host 'Для Android нужны Node, JDK 17, Android SDK. Для сервера в Docker нужен Docker Desktop.'
if($missing.Count){Write-Host "Не обнаружены: $($missing -join ', ')"}
