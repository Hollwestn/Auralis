$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$backupTag = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path 'backups' | Out-Null
# Stop writes BEFORE both snapshots so catalog records and music are consistent.
& docker compose stop api
if ($LASTEXITCODE -ne 0) { throw 'Не удалось остановить запись в каталог.' }
try {
    # Write within the bind mount, avoiding PowerShell binary redirection.
    & docker compose exec -T db pg_dump -U auralis -d auralis -Fc -f "/backups/database-$backupTag.dump"
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump завершился с ошибкой.' }
    & docker compose run --rm --no-deps --user 0 --entrypoint tar -v "${PSScriptRoot}/backups:/backups" api -czf "/backups/music-$backupTag.tar.gz" -C /app/data .
    if ($LASTEXITCODE -ne 0) { throw 'Архив музыки не создан.' }
} finally { & docker compose start api }
Write-Host "Резервная копия: $PSScriptRoot/backups/*-$backupTag.*. Скопируйте её на другой диск."
