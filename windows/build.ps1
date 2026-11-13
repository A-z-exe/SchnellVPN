param(
    [switch]$SkipInstall,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$python = "python"

if (-not (Test-Path "vendor/xray/xray.exe")) {
    Write-Host "Fetching the Xray Windows core..."
    New-Item -ItemType Directory -Force -Path "vendor/xray" | Out-Null
    $zip = Join-Path $env:TEMP "Xray-windows-64.zip"
    Invoke-WebRequest `
        -Uri "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-64.zip" `
        -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath "vendor/xray" -Force
}

if (-not $SkipInstall) {
    & $python -m pip install -r requirements-dev.txt
}

if (-not $SkipTests) {
    & $python -m pytest tests -q
}

& $python tools/make_icon.py
& $python -m PyInstaller schnellvpn.spec --noconfirm --clean

$exe = Join-Path $PSScriptRoot "dist/SchnellVPN.exe"
if (-not (Test-Path $exe)) {
    throw "Build failed: $exe not found"
}
$hash = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
Write-Host ""
Write-Host "Built $exe"
Write-Host "SHA-256: $hash"
