param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference='Stop'
$apk=[IO.Path]::GetFullPath($Path)
if(-not(Test-Path -LiteralPath $apk)){throw "APK не найден: $apk"}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($apk)
try{
    foreach($required in @('AndroidManifest.xml','classes.dex','assets/index.android.bundle')){
        if(-not $archive.GetEntry($required)){throw "APK не содержит $required"}
    }
    $pcmClass=$false; $spectrumMethod=$false; $glContext=$false; $glView=$false
    foreach($entry in $archive.Entries | Where-Object {$_.FullName -match '^classes\d*\.dex$'}){
        $stream=$entry.Open(); $bytes=New-Object IO.MemoryStream
        try{$stream.CopyTo($bytes);$symbols=[Text.Encoding]::GetEncoding(28591).GetString($bytes.ToArray())}
        finally{$stream.Dispose();$bytes.Dispose()}
        $pcmClass=$pcmClass -or $symbols.Contains('Lexpo/modules/audio/AuralisPcmTap;')
        $spectrumMethod=$spectrumMethod -or $symbols.Contains('setSpectrumEnabled')
        $glContext=$glContext -or $symbols.Contains('Lexpo/modules/gl/GLContext;')
        $glView=$glView -or $symbols.Contains('Lexpo/modules/gl/GLView;')
    }
    if(-not $pcmClass -or -not $spectrumMethod){throw 'В DEX отсутствует нативное PCM-расширение. Проверьте expo.autolinking.android.buildFromSource: expo-audio.'}
    if(-not $glContext){throw 'R8 удалил/переименовал GLContext. Проверьте AURALIS_EXGL_JNI в ProGuard: GLView может аварийно завершить приложение.'}
    if(-not $glView){throw 'R8 удалил/переименовал GLView. Проверьте AURALIS_EXPO_VIEW_CONSTRUCTORS: Expo создаёт его конструктор через reflection.'}
    [PSCustomObject]@{apk=$apk;sizeBytes=(Get-Item -LiteralPath $apk).Length;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash;embeddedBundleBytes=$archive.GetEntry('assets/index.android.bundle').Length;pcmClassInDex=$pcmClass;spectrumMethodInDex=$spectrumMethod;glContextInDex=$glContext}
}finally{$archive.Dispose()}
