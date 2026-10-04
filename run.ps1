param([ValidateSet('Simulation','Live','Physical')][string]$Mode = 'Simulation', [int]$Port = 8000)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:RONY_PORT = [string]$Port
$env:RONY_MODE = if ($Mode -eq 'Simulation') { 'simulation' } else { 'live' }
$env:RONY_ENABLE_ROBOT_MOTION = if ($Mode -eq 'Physical') { 'true' } else { 'false' }
$env:RONY_PYTHON = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\Entrenamiento\.venv\Scripts\python.exe'))
if (-not $env:RONY_DB_PASSWORD -and -not (Test-Path -LiteralPath 'application-local.properties')) {
    $dbSecret = Read-Host 'Contraseña de MySQL local' -AsSecureString
    $dbCredential = New-Object System.Management.Automation.PSCredential('local', $dbSecret)
    $env:RONY_DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
}
Write-Host ('Spring API en http://127.0.0.1:' + $Port + ' | modo ' + $Mode)
Write-Host 'El arranque físico solo conecta y lee. Angular activa los movimientos.'
& .\mvnw.cmd spring-boot:run
exit $LASTEXITCODE
