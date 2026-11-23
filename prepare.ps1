param([string]$RobotHost = '', [switch]$SkipPython, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$yoloRoot = Join-Path $workspaceRoot 'rony-yolo-isaac'
$runtimeRoot = Join-Path $workspaceRoot '.runtime'
$localPath = Join-Path $yoloRoot 'config.local.json'
if (Test-Path -LiteralPath $localPath) {
    $settings = Get-Content -LiteralPath $localPath -Raw | ConvertFrom-Json
} else {
    $settings = Get-Content -LiteralPath (Join-Path $yoloRoot 'config.example.json') -Raw | ConvertFrom-Json
}
function Set-LocalValue([string]$Name, [string]$Value) {
    $settings | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
}
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
}
if ($RobotHost) { Set-LocalValue 'rony_host' $RobotHost }
if (-not $settings.robot_agent_token) { Set-LocalValue 'robot_agent_token' (New-LocalSecret) }
if (-not $settings.web_access_token) { Set-LocalValue 'web_access_token' (New-LocalSecret) }
if (-not ($settings.PSObject.Properties.Name -contains 'robot_agent_url')) { Set-LocalValue 'robot_agent_url' '' }
[IO.File]::WriteAllText($localPath, ($settings | ConvertTo-Json -Depth 20) + "`n", [Text.UTF8Encoding]::new($false))
Write-Host ('Configuración privada preparada: ' + $localPath)
if (-not $SkipPython) {
    $env:UV_CACHE_DIR = Join-Path $runtimeRoot 'cache'
    $env:UV_PYTHON_INSTALL_DIR = Join-Path $runtimeRoot 'python'
    $env:UV_PYTHON_BIN_DIR = Join-Path $runtimeRoot 'bin'
    $uv = Join-Path $runtimeRoot 'tools/bin/uv.exe'
    if (-not (Test-Path -LiteralPath $uv)) {
        & python -m pip install --target (Join-Path $runtimeRoot 'tools') --no-cache-dir uv==0.13.9
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar uv.' }
    }
    $visionPython = Join-Path $yoloRoot '.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $visionPython)) {
        & $uv venv --python 3.11 (Join-Path $yoloRoot '.venv')
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar Python 3.11.' }
    }
    & $uv pip install --python $visionPython torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cu128
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar PyTorch CUDA.' }
    & $uv pip install --python $visionPython -r (Join-Path $yoloRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar las dependencias del notebook.' }
    & $visionPython (Join-Path $yoloRoot 'runtime/export_notebook.py') --check
    if ($LASTEXITCODE -ne 0) { throw 'Revisa la sincronización con el notebook.' }
}
& (Join-Path $PSScriptRoot 'robot_agent/package.ps1')
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'package.ps1') }
Write-Host 'Preparación terminada. Instala el paquete del agente una vez en el JetMax y usa run.ps1.'
