param(
    [string]$Python = "C:\Python38-32\python.exe",
    [switch]$SkipInstall
)

# Windows 7 / 32-bit build: Python 3.8 x86 + PySide2 (Qt 5) + Xray 1.8.3.
# The x64 build (build.ps1) stays the default download for Windows 10/11.

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path $Python)) {
    throw "Python 3.8 (32-bit) not found at $Python. Pass -Python <path to python.exe>."
}
& $Python -c "import struct, sys; assert struct.calcsize('P') == 4, 'need 32-bit Python'; assert sys.version_info[:2] == (3, 8), 'need Python 3.8'; print(sys.version)"

if (-not (Test-Path "vendor/xray-win7/xray.exe")) {
    Write-Host "Fetching Xray 1.8.3 (Windows x86)..."
    New-Item -ItemType Directory -Force -Path "vendor/xray-win7" | Out-Null
    $zip = Join-Path $env:TEMP "Xray-windows-32-v1.8.3.zip"
    Invoke-WebRequest `
        -Uri "https://github.com/XTLS/Xray-core/releases/download/v1.8.3/Xray-windows-32.zip" `
        -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath "vendor/xray-win7" -Force
}

if (-not $SkipInstall) {
    & $Python -m pip install -r requirements-win7.txt
}

& $Python -m PyInstaller schnellvpn-win7.spec --noconfirm --clean

$exe = Join-Path $PSScriptRoot "dist/SchnellVPN-Win7.exe"
& $Python tools/check_pe.py $exe x86 6.1
if ($LASTEXITCODE -ne 0) { throw "PE check failed: $exe is not an x86/6.1 build" }

$hash = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
Write-Host ""
Write-Host "Built $exe"
Write-Host "SHA-256: $hash"
