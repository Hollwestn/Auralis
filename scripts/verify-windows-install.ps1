param([string]$Installer='release/desktop/Auralis-0.3.1.msi')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $projectRoot
$installerPath=[IO.Path]::GetFullPath((Join-Path $projectRoot $Installer))
if(-not(Test-Path -LiteralPath $installerPath)){throw 'Сначала соберите MSI: scripts/build-desktop.ps1 -Type msi'}
$previous=Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object {$_.DisplayName -eq 'Auralis'}
if($previous){throw 'Auralis уже установлен. Тест не будет изменять существующую установку.'}
$testRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '.runtime/install-verification'))
$installPath=[IO.Path]::GetFullPath((Join-Path $testRoot 'application'))
if(-not$installPath.StartsWith($testRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Некорректный путь тестовой установки'}
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$checks=[ordered]@{}
$installed=$false
try{
 $installLog=Join-Path $testRoot 'install.log'
 $msiProcess=Start-Process msiexec.exe -ArgumentList @('/i',('"'+$installerPath+'"'),'/qn','/norestart',('INSTALLDIR="'+$installPath+'"'),'/L*v',('"'+$installLog+'"')) -WindowStyle Hidden -Wait -PassThru
 if($msiProcess.ExitCode -notin @(0,3010)){throw "MSI install: код $($msiProcess.ExitCode), лог $installLog"}
 $installed=$true
 $exe=Join-Path $installPath 'Auralis.exe'
 if(-not(Test-Path -LiteralPath $exe)){throw 'После установки отсутствует Auralis.exe'}
 $checks.install='PASS'
 $expected=(Get-FileHash -LiteralPath 'apps/desktop/target/auralis-desktop-0.1.0-SNAPSHOT.jar').Hash
 $actual=(Get-FileHash -LiteralPath (Join-Path $installPath 'app/auralis-desktop-0.1.0-SNAPSHOT.jar')).Hash
 if($expected -ne $actual){throw 'Хеш установленного JAR отличается от проверенной сборки'}
 $checks.installedJarMatchesVerifiedBuild='PASS'
 $data=Join-Path $testRoot 'data'
 $env:JAVA_TOOL_OPTIONS='-Dauralis.data="'+$data+'"'
 $env:PATH=($env:PATH.Split(';') | Where-Object {$_ -notlike '*FFmpeg*' -and $_ -notlike '*Java*'}) -join ';'
 $out=Join-Path $testRoot 'stdout.log'
 $appProcess=Start-Process -FilePath $exe -ArgumentList '--smoke-test' -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $out -RedirectStandardError (Join-Path $testRoot 'stderr.log')
 if($appProcess.ExitCode -ne 0 -or -not((Get-Content -LiteralPath $out -Raw).Contains('AURALIS_UI_SMOKE_OK'))){throw 'Установленное приложение не прошло UI smoke'}
 $checks.installedApplicationLaunchWithoutSystemJava='PASS'
 $checks.screenshots='PASS'
}finally{
 if($installed){
  $uninstallLog=Join-Path $testRoot 'uninstall.log'
  $removeProcess=Start-Process msiexec.exe -ArgumentList @('/x',('"'+$installerPath+'"'),'/qn','/norestart','/L*v',('"'+$uninstallLog+'"')) -WindowStyle Hidden -Wait -PassThru
  if($removeProcess.ExitCode -notin @(0,3010)){throw "MSI uninstall: код $($removeProcess.ExitCode), лог $uninstallLog"}
  if(Test-Path -LiteralPath (Join-Path $installPath 'Auralis.exe')){throw 'После удаления остался исполняемый файл приложения'}
  $checks.uninstall='PASS'
  $checks | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'result.json') -Encoding UTF8
 }
}
$checks | ConvertTo-Json
