param([switch]$Https)
$ErrorActionPreference='Stop'
$arguments=@{}
if($Https){$arguments.Https=$true}
& (Join-Path $PSScriptRoot '../infrastructure/start-server.ps1') @arguments
