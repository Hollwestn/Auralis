param(
    [switch]$TestApk,
    [switch]$Store,
    [ValidateSet('apk','aab')][string]$Format = 'apk',
    [switch]$AllowLocalHttp,
    [ValidateSet('arm64-v8a','x86_64','armeabi-v7a','all')][string]$Architecture = 'arm64-v8a',
    [switch]$SkipInstall,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$mobile = Split-Path $PSScriptRoot -Parent
$repository = [IO.Path]::GetFullPath((Join-Path $mobile '..\..'))
$previousLocation = Get-Location
$previousJava = $env:JAVA_HOME
$previousHttp = $env:AURALIS_ALLOW_HTTP
$previousTest = $env:AURALIS_TEST_APK
$previousStore = $env:AURALIS_STORE_RELEASE
function Check-Exit([string]$action) { if ($LASTEXITCODE -ne 0) { throw "$action завершилось с кодом $LASTEXITCODE" } }
try {
    if ($Store -and ($TestApk -or $AllowLocalHttp)) { throw 'Магазинная сборка запрещает тестовую подпись и HTTP.' }
    if ($Format -eq 'aab' -and $TestApk) { throw 'AAB требует production-подпись; -TestApk создаёт только APK.' }
    $env:AURALIS_STORE_RELEASE = if ($Store) { '1' } else { '0' }
    foreach ($command in @('node','npm')) { if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Установите ${command}: https://nodejs.org/en/download" } }
    $java17 = Join-Path $env:ProgramFiles 'Java\jdk-17'
    if (Test-Path -LiteralPath $java17) { $env:JAVA_HOME = $java17 }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'Укажите JAVA_HOME на JDK 17 или 21. JDK 25 предназначен для Desktop.' }
    if (-not $env:ANDROID_HOME) {
        $localSdk = Join-Path $repository '.runtime\android-sdk'
        if (Test-Path -LiteralPath (Join-Path $localSdk 'platform-tools')) { $env:ANDROID_HOME = $localSdk }
        else { throw 'Установите Android SDK по ANDROID_INSTALLATION.md и укажите ANDROID_HOME.' }
    }
    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        if ($mobile -match '[^\x00-\x7F]') { throw 'Windows CMake требует короткий путь без кириллицы. Соберите копию проекта в каталоге с латинским путём по docs/ANDROID_INSTALLATION.md; исходный проект перемещать не нужно.' }
        & (Join-Path $PSScriptRoot 'install-windows-ninja.ps1') -SdkRoot $env:ANDROID_HOME
        $env:CMAKE_BUILD_PARALLEL_LEVEL = '2'
    }
    if (-not $TestApk) {
        foreach ($key in @('AURALIS_UPLOAD_STORE_FILE','AURALIS_UPLOAD_STORE_PASSWORD','AURALIS_UPLOAD_KEY_ALIAS','AURALIS_UPLOAD_KEY_PASSWORD')) { if (-not [Environment]::GetEnvironmentVariable($key)) { throw "Для подписанного release нужен $key. Для локальной тестовой сборки укажите -TestApk." } }
    }
    $env:AURALIS_TEST_APK = if ($TestApk) { '1' } else { '0' }
    $env:AURALIS_ALLOW_HTTP = if ($AllowLocalHttp) { '1' } else { '0' }
    Set-Location -LiteralPath $mobile
    if (-not $SkipInstall) { & npm ci; Check-Exit 'npm ci' }
    & node scripts/patch-expo-audio.cjs; Check-Exit 'Нативное расширение аудио'
    if (-not $SkipTests) { foreach ($check in @('typecheck','lint','test')) { & npm run $check; Check-Exit $check } }
    & npm exec -- expo prebuild --no-install --no-clean --platform android; Check-Exit 'Expo prebuild'
    Set-Location -LiteralPath (Join-Path $mobile 'android')
    $architectures = if ($Architecture -eq 'all') { 'arm64-v8a,armeabi-v7a,x86_64' } else { $Architecture }
    $task = if ($Format -eq 'aab') { 'bundleRelease' } else { 'assembleRelease' }
    & .\gradlew.bat $task "-PreactNativeArchitectures=$architectures" --console=plain --no-daemon --max-workers=2; Check-Exit 'Gradle APK'
    $relative = if ($Format -eq 'aab') { 'android/app/build/outputs/bundle/release/app-release.aab' } else { 'android/app/build/outputs/apk/release/app-release.apk' }
    $built = Join-Path $mobile $relative
    if (-not (Test-Path -LiteralPath $built) -or (Get-Item -LiteralPath $built).Length -lt 100000) { throw 'Gradle не создал пакет приложения.' }
    if ($Format -eq 'apk') { & (Join-Path $PSScriptRoot 'verify-apk.ps1') -Path $built }
    else {
        & (Join-Path $env:JAVA_HOME 'bin/jarsigner.exe') -verify $built; Check-Exit 'Проверка подписи AAB'
        $entries = & (Join-Path $env:JAVA_HOME 'bin/jar.exe') tf $built; Check-Exit 'Проверка содержимого AAB'
        if (-not ($entries -contains 'BundleConfig.pb') -or -not ($entries -contains 'base/manifest/AndroidManifest.xml') -or -not ($entries | Where-Object { $_ -match '^META-INF/.+\.(RSA|EC|DSA)$' })) { throw 'Некорректный или неподписанный AAB.' }
    }
    $output = Join-Path $repository 'release\android'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $name = if ($TestApk) { 'Auralis-Android-Test.apk' } elseif ($Format -eq 'aab') { 'Auralis-GooglePlay.aab' } elseif ($Store) { 'Auralis-RuStore.apk' } else { 'Auralis-Android.apk' }
    $destination = Join-Path $output $name
    Copy-Item -LiteralPath $built -Destination $destination -Force
    Get-FileHash -LiteralPath $destination -Algorithm SHA256
    Write-Host "Готово: $destination"
    if ($TestApk) { Write-Host 'Тестовая подпись Android debug. JS включён в APK; Metro не требуется. Для публикации используйте собственный release-ключ.' }
} finally {
    Set-Location -LiteralPath $previousLocation
    $env:JAVA_HOME = $previousJava
    $env:AURALIS_ALLOW_HTTP = $previousHttp
    $env:AURALIS_TEST_APK = $previousTest
    $env:AURALIS_STORE_RELEASE = $previousStore
}
