param([switch]$SmokeTest)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $projectRoot
& mvn -B -pl apps/desktop -am package '-DskipTests' "-Dmaven.repo.local=$env:USERPROFILE/.m2/repository"
if($LASTEXITCODE -ne 0){throw 'Сборка не прошла'}
$classpath=(Join-Path $projectRoot 'apps/desktop/target/auralis-desktop-0.1.0-SNAPSHOT.jar')+';'+(Join-Path $projectRoot 'apps/desktop/target/lib/*')
$arguments=@('--enable-native-access=ALL-UNNAMED','-cp',$classpath,'io.auralis.desktop.Launcher')
if($SmokeTest){$arguments+='--smoke-test'}
& java @arguments
if($LASTEXITCODE -ne 0){throw 'Приложение завершилось с ошибкой'}
