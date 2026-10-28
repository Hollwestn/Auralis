param([switch]$MigrateOnly)
$ErrorActionPreference='Stop'
$releaseRoot=$PSScriptRoot
if(-not(Test-Path -LiteralPath (Join-Path $releaseRoot 'Auralis/Auralis.exe'))){$releaseRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../release/desktop'))}
$portable=Join-Path $releaseRoot 'Auralis/Auralis.exe'
if(-not(Test-Path -LiteralPath $portable)){throw 'Нужна полная новая portable-папка release/desktop/Auralis рядом с установщиком.'}
$running=Get-Process Auralis,java,javaw -ErrorAction SilentlyContinue | Where-Object {$_.ProcessName -eq 'Auralis' -or $_.MainWindowTitle -like 'Auralis*'}
if($running){throw 'Закройте все окна Auralis, включая запуск из исходников, затем повторите обновление. Процессы автоматически не завершаются.'}
if(-not$env:LOCALAPPDATA){throw 'Не определён LOCALAPPDATA. Запустите скрипт из пользовательской сессии Windows.'}
$legacy=Join-Path $env:LOCALAPPDATA 'Auralis'
$data=Join-Path $env:LOCALAPPDATA 'AuralisData'
$backupBase=Join-Path $env:LOCALAPPDATA 'AuralisBackups'
$stamp=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$backup=Join-Path $backupBase $stamp
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$migration=Start-Process -FilePath $portable -ArgumentList '--migrate-data' -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $backup 'migration.stdout.log') -RedirectStandardError (Join-Path $backup 'migration.stderr.log')
$output=Get-Content -LiteralPath (Join-Path $backup 'migration.stdout.log') -Raw
if($migration.ExitCode -ne 0 -or -not$output.Contains('AURALIS_DATA_MIGRATION_OK')){throw "Перенос остановлен, установщик не запущен. Лог: $backup/migration.stderr.log"}
if((Test-Path -LiteralPath (Join-Path $legacy 'library.db')) -and -not(Test-Path -LiteralPath (Join-Path $data 'library.db'))){throw 'Исходная библиотека обнаружена, но новая копия не появилась. Установщик не запущен.'}
if(Test-Path -LiteralPath $data){
 if(((Get-Item -LiteralPath $data -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Папка данных является ссылкой. Сначала сделайте ручную резервную копию.'}
 $links=Get-ChildItem -LiteralPath $data -Recurse -Force | Where-Object {($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0}
 if($links){throw 'В папке данных обнаружены ссылки. Сначала сделайте ручную резервную копию.'}
 Copy-Item -LiteralPath $data -Destination (Join-Path $backup 'AuralisData') -Recurse -Force
 $database=Join-Path $data 'library.db'
 if(Test-Path -LiteralPath $database){if((Get-FileHash -LiteralPath $database).Hash -ne (Get-FileHash -LiteralPath (Join-Path $backup 'AuralisData/library.db')).Hash){throw 'Проверка резервной копии не прошла. Установщик не запущен.'}}
}
Write-Host "Данные подготовлены: $data"
Write-Host "Резервная копия: $backup"
if($MigrateOnly){Write-Host 'AURALIS_SAFE_UPDATE_PREFLIGHT_OK';return}
$installer=Join-Path $releaseRoot 'Auralis-Setup.exe'
if(-not(Test-Path -LiteralPath $installer)){$installer=Join-Path $releaseRoot 'Auralis-0.3.1.msi'}
if(-not(Test-Path -LiteralPath $installer)){throw 'Установщик не найден. Данные уже сохранены; запустите установщик вручную после сборки.'}
Write-Host 'Открывается мастер обновления Auralis 0.2. Выберите отдельную папку установки AuralisApp.'
Start-Process -FilePath $installer -WindowStyle Normal -Wait
