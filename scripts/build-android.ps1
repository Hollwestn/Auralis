param(
 [switch]$Production,
 [switch]$Store,
 [ValidateSet('apk','aab')][string]$Format='apk',
 [switch]$AllowLocalHttp,
 [ValidateSet('arm64-v8a','x86_64','armeabi-v7a','all')][string]$Architecture='arm64-v8a',
 [switch]$SkipDependencies,
 [switch]$SkipTests
)
$ErrorActionPreference='Stop'
$mobileBuild=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../apps/mobile/scripts/build-android.ps1'))
$buildArgs=@{TestApk=(-not $Production);Store=$Store;Format=$Format;AllowLocalHttp=$AllowLocalHttp;Architecture=$Architecture;SkipInstall=$SkipDependencies;SkipTests=$SkipTests}
& $mobileBuild @buildArgs
