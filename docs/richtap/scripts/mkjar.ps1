param([string]$Class, [string]$Jar)
$d8 = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\d8.bat"
$aj = "$env:LOCALAPPDATA\Android\Sdk\platforms\android-36\android.jar"
$src = "$env:TEMP\opencode\haptic\$Class.java"
$wd = "$env:TEMP\opencode\haptic\out_$Class"
Remove-Item -Recurse -Force $wd -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $wd | Out-Null
javac --release 8 -nowarn -cp "$aj" -d $wd $src
# 注意：`javac` 在 JDK 9+ 会向 stderr 打印 “源值 8 已过时” 警告并令 `$?` 为 false，
# 但编译其实成功；因此不要用 `if (-not $?) { exit }` 判失败，改为检查产物。
$cls = Get-ChildItem "$wd\*.class" | ForEach-Object { $_.FullName }
if (-not $cls) { Write-Error "javac failed"; exit 1 }
# 必须把**全部** .class（含匿名内部类 `Foo$1.class`）交给 d8，否则运行时报
# `NoClassDefFoundError: LFoo$1`。
& $d8 --release --lib "$aj" --output $wd $cls
if (-not (Test-Path "$wd\classes.dex")) { Write-Error "dex build failed"; exit 1 }
$jarPath = "$wd\$Jar"
Add-Type -AssemblyName System.IO.Compression
$fs = [System.IO.File]::Open($jarPath, [System.IO.FileMode]::CreateNew)
$zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
$en = $zip.CreateEntry("classes.dex"); $es = $en.Open()
$b = [System.IO.File]::ReadAllBytes("$wd\classes.dex"); $es.Write($b, 0, $b.Length)
$es.Close(); $zip.Dispose(); $fs.Close()
adb -s 879a0d6f push $jarPath /data/local/tmp/$Jar 2>$null | Out-Null
Write-Output "built $jarPath"
