param([switch]$Install, [switch]$Release, [switch]$Debug, [switch]$Lite, [switch]$Quick)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
$signed = Test-Path (Join-Path $root 'keystore.properties')
if ($Release -and -not $signed) { throw "keystore.properties not found; the release APK would be unsigned" }
$variants = if ($Lite) { @('lite') } elseif ($Release) { @('release') } elseif ($Debug -or -not $signed) { @('lite', 'debug') } else { @('lite', 'debug', 'release') }

Push-Location $root
try {
    New-Item -ItemType Directory -Force $dist | Out-Null
    if (-not $Quick) {
        & "$root\gradlew.bat" --stop
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -match 'KotlinCompileDaemon' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Remove-Item (Join-Path $root 'app\build') -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path (Join-Path $root 'app\build')) { Write-Host "Some files in app\build are in use (close Android Studio for a fully clean build); continuing" -ForegroundColor Yellow }
    }

    $outs = foreach ($variant in $variants) {
        $apk = Join-Path $root "app\build\outputs\apk\$variant\app-$variant.apk"
        $out = Join-Path $dist $(switch ($variant) { 'release' { 'PCLink-signed.apk' } 'lite' { 'PCLink-lite.apk' } default { 'PCLink.apk' } })
        Remove-Item $apk, $out -ErrorAction SilentlyContinue
        if ($variant -eq 'lite') {
            & "$root\gradlew.bat" :app:assembleLite
        } elseif ($variant -eq 'release') {
            & "$root\gradlew.bat" :app:assembleRelease
        } else {
            & "$root\gradlew.bat" :app:assembleDebug
        }
        if ($LASTEXITCODE -ne 0) { throw "Gradle $variant build failed (exit $LASTEXITCODE)" }
        if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
        Copy-Item $apk $out -Force
        Write-Host "Built $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB, $(Get-Date -Format 'yyyy-MM-dd HH:mm'))" -ForegroundColor Green
        $out
    }

    if ($Install) {
        $sdk = (Get-Content "$root\local.properties" | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', '' -replace '\\\\', '\' -replace '/', '\'
        $adb = Join-Path $sdk 'platform-tools\adb.exe'
        if (-not (Test-Path $adb)) { $adb = 'adb' }
        & $adb install -r @($outs)[-1]
        if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
    }
}
finally { Pop-Location }
