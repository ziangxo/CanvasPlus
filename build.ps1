$ErrorActionPreference = 'Stop'
$root = 'c:\Users\ziang\CodeBuddy\20261007161444\canvasplus'
$sdk = 'c:\Users\ziang\CodeBuddy\20261007161444\toolchain\android-sdk'
$jdk = 'c:\Users\ziang\CodeBuddy\20261007161444\toolchain\jdk\jdk-21.0.12.1+1'
$env:JAVA_HOME = $jdk
$bt = "$sdk\build-tools\36.0.0"
$platform = "$sdk\platforms\android-36\android.jar"
$aapt2 = "$bt\aapt2.exe"
$d8 = "$bt\d8.bat"
$zipalign = "$bt\zipalign.exe"
$apksigner = "$bt\apksigner.bat"
$javac = "$jdk\bin\javac.exe"
$jar = "$jdk\bin\jar.exe"
$ks = "$env:USERPROFILE\.android\debug.keystore"

Set-Location $root
if (Test-Path build) { Remove-Item -Recurse -Force build }
if (Test-Path dist) { Remove-Item -Recurse -Force dist }
New-Item -ItemType Directory -Force -Path build\classes, build\dex, dist | Out-Null

Write-Host '== aapt2 compile =='
# 应用图标是 mipmap PNG + 自适应图标 XML + values/colors.xml，
# 先编译成一个 .flat 包，再交给 link；没有 res 目录时跳过（保持能只带 assets 构建）。
if (Test-Path app\src\main\res) {
    & $aapt2 compile --dir app\src\main\res -o build\res.zip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed $LASTEXITCODE" }
}

Write-Host '== aapt2 link =='
$resArg = @()
if (Test-Path build\res.zip) { $resArg += 'build\res.zip' }
& $aapt2 link -o build\base.apk -I $platform --manifest app\src\main\AndroidManifest.xml -A app\src\main\assets --min-sdk-version 35 --target-sdk-version 36 @resArg
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed $LASTEXITCODE" }

Write-Host '== javac =='
$srcs = Get-ChildItem -Recurse app\src\main\java -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -nowarn --release 17 -classpath "$platform;libs\xposed-api-82.jar" -d build\classes @srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed $LASTEXITCODE" }

Write-Host '== d8 =='
# 2026-11-21 坑：直接把 *.class 路径列表传给 d8，类一多（>20 个类）命令行就超长，
# 报 "The input line is too long. The syntax of the command is incorrect." d8 failed 255。
# 改成先用 jar 把 build\classes 打成一个 classes.jar，再把单个 jar 传给 d8。
if (Test-Path build\classes.jar) { Remove-Item build\classes.jar -Force }
Push-Location build\classes
& $jar cf ..\classes.jar .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar classes failed $LASTEXITCODE" }
if (-not (Test-Path build\dex)) { New-Item -ItemType Directory -Force -Path build\dex | Out-Null }
& $d8 --release --min-api 35 --lib $platform --output build\dex build\classes.jar
if ($LASTEXITCODE -ne 0) { throw "d8 failed $LASTEXITCODE" }

Write-Host '== package =='
Copy-Item build\base.apk build\unsigned.apk -Force
Push-Location build
& $jar uf unsigned.apk -C dex classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar failed $LASTEXITCODE" }
Pop-Location
& $zipalign -f 4 build\unsigned.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed $LASTEXITCODE" }

Write-Host '== sign =='
& $apksigner sign --ks $ks --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out dist\CanvasPlus.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed $LASTEXITCODE" }

Get-Item dist\CanvasPlus.apk | Select-Object FullName, Length
Write-Host 'done'
