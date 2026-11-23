param([switch]$Robot, [string]$RobotHost = '')
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$visionPython = Join-Path $workspaceRoot 'rony-yolo-isaac/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $visionPython)) { throw 'Ejecuta primero prepare.ps1.' }
$checkArgs = @((Join-Path $PSScriptRoot 'check_ready.py'))
if ($Robot) { $checkArgs += '--robot' }
if ($RobotHost) { $checkArgs += @('--host', $RobotHost) }
$env:YOLO_CONFIG_DIR = Join-Path $workspaceRoot '.runtime/yolo-settings'
$env:YOLO_AUTOINSTALL = 'False'
& $visionPython @checkArgs
exit $LASTEXITCODE
