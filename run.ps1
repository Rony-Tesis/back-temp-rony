param(
    [ValidateSet('Simulation','Live','Physical')][string]$Mode = 'Simulation',
    [ValidateSet('H2','MySQL')][string]$Database = 'H2',
    [int]$Port = 8000,
    [string]$RobotHost = '',
    [switch]$Rebuild
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$yoloRoot = Join-Path $workspaceRoot 'rony-yolo-isaac'
if (-not $env:YOLO_CONFIG_DIR) { $env:YOLO_CONFIG_DIR = Join-Path $workspaceRoot '.runtime/yolo-settings' }
$env:YOLO_AUTOINSTALL = 'False'
$localPath = Join-Path $yoloRoot 'config.local.json'
$local = if (Test-Path -LiteralPath $localPath) { Get-Content -LiteralPath $localPath -Raw | ConvertFrom-Json } else { $null }
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'C:/Program Files/Java/jdk-21')) { $env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' }
if (-not $env:JAVA_HOME) { throw 'Configura JAVA_HOME con Java 21.' }
if ($RobotHost) { $env:RONY_HOST = $RobotHost }
elseif (-not $env:RONY_HOST -and $local.rony_host) { $env:RONY_HOST = $local.rony_host }
if (-not $env:RONY_HOST) { $env:RONY_HOST = '192.168.149.1' }
if (-not $env:RONY_PYTHON) { $env:RONY_PYTHON = Join-Path $yoloRoot '.venv/Scripts/python.exe' }
if (-not $env:RONY_MODEL_PATH) { $env:RONY_MODEL_PATH = Join-Path $yoloRoot 'modelo/yolo11n_300ep_20261028_211758_best.pt' }
if (-not $env:RONY_RUNTIME_PROFILE) { $env:RONY_RUNTIME_PROFILE = Join-Path $yoloRoot 'runtime/rony_profile.json' }
if (-not $env:RONY_CAMERA_URL -and $local.camera_url) { $env:RONY_CAMERA_URL = $local.camera_url }
if (-not $env:RONY_ROBOT_AGENT_URL -and $local.robot_agent_url) { $env:RONY_ROBOT_AGENT_URL = $local.robot_agent_url }
if (-not $env:RONY_ROBOT_AGENT_TOKEN -and $local.robot_agent_token) { $env:RONY_ROBOT_AGENT_TOKEN = $local.robot_agent_token }
if (-not $env:RONY_ACCESS_TOKEN -and $local.web_access_token) { $env:RONY_ACCESS_TOKEN = $local.web_access_token }
$env:RONY_MODE = if ($Mode -eq 'Simulation') { 'simulation' } else { 'live' }
$env:RONY_ENABLE_ROBOT_MOTION = if ($Mode -eq 'Physical') { 'true' } else { 'false' }
$env:RONY_PORT = [string]$Port
if ($Mode -ne 'Simulation' -and -not (Test-Path -LiteralPath $env:RONY_PYTHON)) { throw 'Falta Python de visión. Ejecuta prepare.ps1.' }
if ($Mode -eq 'Physical' -and -not $env:RONY_ROBOT_AGENT_TOKEN) { throw 'Falta el token del agente. Ejecuta prepare.ps1 e instala el paquete en JetMax.' }
if ($Database -eq 'MySQL' -and $Mode -ne 'Simulation' -and -not $env:RONY_DB_PASSWORD -and -not (Test-Path -LiteralPath 'application-local.properties')) { throw 'Configura las credenciales MySQL en application-local.properties o RONY_DB_PASSWORD.' }
$jar = Join-Path $PSScriptRoot 'target/back-rony-1.0.0-SNAPSHOT.jar'
if ($Rebuild -or -not (Test-Path -LiteralPath $jar)) { & ./package.ps1 }
$launchArgs = @('-jar', $jar, '--rony.web-portal=true')
if ($Mode -eq 'Simulation') { $launchArgs += '--spring.profiles.active=demo' }
elseif ($Database -eq 'H2') { $launchArgs += '--spring.profiles.active=bench' }
Write-Host ('Aplicación completa: http://127.0.0.1:' + $Port + '/monitoring | modo ' + $Mode)
Write-Host 'El arranque solo observa. Iniciar clasificación continua es un comando explícito desde la web.'
Write-Host 'La clave web está en config.local.json; no se imprime ni se almacena en el navegador.'
& (Join-Path $env:JAVA_HOME 'bin/java.exe') @launchArgs
exit $LASTEXITCODE
