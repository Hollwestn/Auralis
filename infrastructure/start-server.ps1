param([switch]$Https)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Установите и запустите Docker Desktop.' }
if (-not (Test-Path -LiteralPath '.env')) { Copy-Item -LiteralPath '.env.example' -Destination '.env'; throw 'Создан infrastructure/.env. Замените пароль PostgreSQL, затем запустите скрипт снова.' }
$serverJar = Join-Path $PSScriptRoot '../backend/server/target/auralis-server-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $serverJar)) { throw 'Сначала в корне проекта: mvn -pl backend/server -am package' }
New-Item -ItemType Directory -Force -Path 'import','backups' | Out-Null
if ($Https) { & docker compose --profile https up --build -d } else { & docker compose up --build -d db api }
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose завершился с ошибкой.' }
Write-Host 'Auralis запускается. Проверка: http://localhost:8080/api/v1/health'
