$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& docker compose --profile https down
if ($LASTEXITCODE -ne 0) { throw 'Не удалось остановить Compose.' }
Write-Host 'Сервер остановлен. Данные и музыка сохранены в Docker volumes.'
