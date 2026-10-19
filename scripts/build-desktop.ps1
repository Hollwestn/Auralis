param([ValidateSet('app-image','exe','msi')][string]$Type='exe',[switch]$SkipTests)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $projectRoot
foreach($tool in @('java','mvn','jpackage')) { if(-not(Get-Command $tool -ErrorAction SilentlyContinue)){throw "Не найден $tool. Установите JDK 25 и Maven, см. docs/DEVELOPMENT_GUIDE.md"} }
$mavenArgs=@('-B','-pl','apps/desktop','-am','package',"-Dmaven.repo.local=$env:USERPROFILE/.m2/repository")
if($SkipTests){$mavenArgs+='-DskipTests'}
& mvn @mavenArgs
if($LASTEXITCODE -ne 0){throw 'Сборка Maven завершилась ошибкой'}
$inputDir=Join-Path $projectRoot 'apps/desktop/target/package-input'
New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'apps/desktop/target/auralis-desktop-0.1.0-SNAPSHOT.jar') -Destination $inputDir -Force
Copy-Item -Path (Join-Path $projectRoot 'apps/desktop/target/lib') -Destination $inputDir -Recurse -Force
$toolsDir=Join-Path $inputDir 'tools'
New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
foreach($binary in @('ffmpeg','ffprobe')){ $command=Get-Command $binary -ErrorAction Stop; Copy-Item -LiteralPath $command.Source -Destination (Join-Path $toolsDir "$binary.exe") -Force }
$ffmpegBin=Split-Path -Parent (Get-Command ffmpeg -ErrorAction Stop).Source
Get-ChildItem -LiteralPath $ffmpegBin -Filter '*.dll' -File | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $toolsDir -Force}
$ffmpegLicense='C:/Program Files/FFmpeg/LICENSE.txt'
if(Test-Path -LiteralPath $ffmpegLicense){Copy-Item -LiteralPath $ffmpegLicense -Destination (Join-Path $toolsDir 'FFMPEG-LICENSE.txt') -Force}
if(Test-Path -LiteralPath (Join-Path $projectRoot 'docs/THIRD_PARTY_NOTICES.md')){Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/THIRD_PARTY_NOTICES.md') -Destination $inputDir -Force}
$destination=Join-Path $projectRoot 'release/desktop'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
if($Type -eq 'app-image'){
 $oldImage=[IO.Path]::GetFullPath((Join-Path $destination 'Auralis'))
 if(Test-Path -LiteralPath $oldImage){
  if(-not$oldImage.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Путь существующей сборки находится вне проекта'}
  if(-not(Test-Path -LiteralPath (Join-Path $oldImage 'app/Auralis.cfg'))){throw 'В целевой папке нет ожидаемого jpackage образа. Переместите её вручную.'}
  $archiveRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '.runtime'))
  New-Item -ItemType Directory -Path $archiveRoot -Force | Out-Null
  $archivePath=[IO.Path]::GetFullPath((Join-Path $archiveRoot ('previous-desktop-'+[Guid]::NewGuid().ToString())))
  if(-not$archivePath.StartsWith($archiveRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Некорректный путь архива сборки'}
  Move-Item -LiteralPath $oldImage -Destination $archivePath
 }
}
# Use the installed JDK template and add a data-preservation condition before upgrades.
if($Type -ne 'app-image'){
 $jdkRoot=Split-Path (Split-Path (Get-Command jpackage).Source -Parent) -Parent
 $templateRoot=Join-Path $projectRoot 'apps/desktop/target/jpackage-template'
 $resourceRoot=Join-Path $projectRoot 'apps/desktop/target/jpackage-resources'
 New-Item -ItemType Directory -Path $templateRoot,$resourceRoot -Force | Out-Null
 Push-Location -LiteralPath $templateRoot
 try{& (Join-Path $jdkRoot 'bin/jar.exe') xf (Join-Path $jdkRoot 'jmods/jdk.jpackage.jmod') classes/jdk/jpackage/internal/resources/main.wxs;if($LASTEXITCODE -ne 0){throw 'Не удалось прочитать шаблон jpackage'}}finally{Pop-Location}
 $template=[IO.File]::ReadAllText((Join-Path $templateRoot 'classes/jdk/jpackage/internal/resources/main.wxs'))
 $anchor='<UIRef Id="JpUI"/>'
 if(-not$template.Contains($anchor)){throw 'Шаблон jpackage изменился: проверьте защиту миграции перед упаковкой'}
 $condition=@"
<Property Id="AURALIS_LEGACY_DB" Secure="yes">
 <DirectorySearch Id="AuralisFindLegacyData" Path="[LocalAppDataFolder]Auralis" Depth="0"><FileSearch Id="AuralisLegacyDatabase" Name="library.db"/></DirectorySearch>
</Property>
<Property Id="AURALIS_SAFE_DB" Secure="yes">
 <DirectorySearch Id="AuralisFindSafeData" Path="[LocalAppDataFolder]AuralisData" Depth="0"><FileSearch Id="AuralisSafeDatabase" Name="library.db"/></DirectorySearch>
</Property>
<Condition Message="Before updating Auralis 0.1, close Auralis and run Safe-Update.ps1 from this release folder. It preserves your music library in AuralisData. Setup has stopped without changing your installed application.">Installed OR NOT AURALIS_LEGACY_DB OR AURALIS_SAFE_DB</Condition>
"@
 [IO.File]::WriteAllText((Join-Path $resourceRoot 'main.wxs'),$template.Replace($anchor,$condition+$anchor))
}
# jpackage marks an EXE read-only and cannot replace it on a repeated build.
# Generate installers in a fresh task directory, then publish the completed file.
$packageDestination=$destination
if($Type -ne 'app-image'){
 $packageDestination=Join-Path $projectRoot ('.runtime/desktop-package-'+[Guid]::NewGuid().ToString())
 New-Item -ItemType Directory -Path $packageDestination | Out-Null
}
$packageArgs=@('--verbose','--type',$Type,'--name','Auralis','--app-version','0.6.1','--vendor','Auralis','--description','Your music. Your space.','--input',$inputDir,'--main-jar','auralis-desktop-0.1.0-SNAPSHOT.jar','--main-class','io.auralis.desktop.Launcher','--dest',$packageDestination,'--java-options','--enable-native-access=ALL-UNNAMED','--java-options','-Dauralis.home=$APPDIR','--java-options','-Dfile.encoding=UTF-8','--add-modules','java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.unsupported,jdk.zipfs')
$packageArgs+=@('--icon',(Join-Path $projectRoot 'assets/auralis.ico'))
if($Type -ne 'app-image'){$packageArgs+=@('--resource-dir',$resourceRoot,'--win-per-user-install','--install-dir','AuralisApp','--win-dir-chooser','--win-menu','--win-shortcut','--win-menu-group','Auralis','--win-upgrade-uuid','68fdc42f-8b2b-4d39-a50f-2db35c01bf25')}
& jpackage @packageArgs
if($LASTEXITCODE -ne 0){throw 'jpackage завершился ошибкой. Для EXE/MSI установите WiX Toolset 3.11+ и добавьте bin в PATH.'}
if($Type -ne 'app-image'){Copy-Item -LiteralPath (Join-Path $packageDestination "Auralis-0.6.1.$Type") -Destination (Join-Path $destination "Auralis-0.6.1.$Type") -Force}
if($Type -eq 'exe'){Copy-Item -LiteralPath (Join-Path $destination 'Auralis-0.6.1.exe') -Destination (Join-Path $destination 'Auralis-Setup.exe') -Force}
Copy-Item -LiteralPath (Join-Path $projectRoot "scripts/safe-update-desktop.ps1") -Destination (Join-Path $destination "Safe-Update.ps1") -Force
Write-Host "Готово: $destination"
