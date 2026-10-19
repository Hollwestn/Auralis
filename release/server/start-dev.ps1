param(
    [switch]$Lan,
    [ValidateRange(1,65535)][int]$Port=8080
)
$ErrorActionPreference='Stop'
$javaCommand=Get-Command java -ErrorAction SilentlyContinue
if(-not $javaCommand){throw 'Установите Java 25 (JDK/JRE), затем откройте PowerShell заново.'}
$jarPath=Join-Path $PSScriptRoot 'auralis-server.jar'
if(-not(Test-Path -LiteralPath $jarPath)){throw 'Восстановите полный release/server: отсутствует auralis-server.jar.'}
$bindAddress=if($Lan){'0.0.0.0'}else{'127.0.0.1'}
Write-Host "Auralis: http://localhost:$Port — для остановки Ctrl+C. Нужна Java 25."
Write-Host "Данные сохраняются в $(Join-Path $PSScriptRoot 'data')."
if($Lan){Write-Host "LAN включён явно: телефон использует http://IP-компьютера:$Port в общей доверенной Wi-Fi сети. Тестовый Android APK должен разрешать HTTP. Брандмауэр скрипт не меняет."}
Push-Location -LiteralPath $PSScriptRoot
try{
    & $javaCommand.Source '-jar' $jarPath '--spring.profiles.active=dev' "--server.address=$bindAddress" "--server.port=$Port" '--spring.datasource.url=jdbc:h2:file:./data/auralis;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0' '--auralis.storage=./data/music'
    if($LASTEXITCODE -ne 0){throw "Сервер завершился с кодом $LASTEXITCODE. Проверьте Java 25, свободный порт и сообщения выше."}
}finally{Pop-Location}
