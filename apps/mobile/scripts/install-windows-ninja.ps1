param([Parameter(Mandatory=$true)][string]$SdkRoot)
$ErrorActionPreference='Stop'
$sdk=[IO.Path]::GetFullPath($SdkRoot)
$binary=Join-Path $sdk 'cmake/3.22.1/bin/ninja.exe'
if(-not(Test-Path -LiteralPath $binary)){throw 'Сначала установите Android SDK CMake 3.22.1.'}
$current=(& $binary --version).Trim()
if([version]$current -ge [version]'1.12.0'){Write-Host "Ninja $current уже поддерживает длинные пути.";return}
$directory=Join-Path $sdk '.auralis-tools/ninja-1.13.2'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$archive=Join-Path $directory 'ninja-win.zip'
$expected='07FC8261B42B20E71D1720B39068C2E14FFCEE6396B76FB7A795FB460B78DC65'
if(-not(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive).Hash -ne $expected){Invoke-WebRequest 'https://github.com/ninja-build/ninja/releases/download/v1.13.2/ninja-win.zip' -OutFile $archive}
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected){throw 'SHA-256 официального Ninja не совпал; SDK не изменён.'}
Expand-Archive -LiteralPath $archive -DestinationPath $directory -Force
$newBinary=Join-Path $directory 'ninja.exe'
if((& $newBinary --version).Trim() -ne '1.13.2'){throw 'Неверная версия загруженного Ninja; SDK не изменён.'}
$backup=Join-Path (Split-Path -Parent $binary) ('ninja-'+$current+'.original.exe')
if(-not(Test-Path -LiteralPath $backup)){Copy-Item -LiteralPath $binary -Destination $backup}
Copy-Item -LiteralPath $newBinary -Destination $binary -Force
Write-Host "Проектный CMake теперь использует Ninja 1.13.2. Резервная копия: $backup"
