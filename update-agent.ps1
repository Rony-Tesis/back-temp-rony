param([string]$RobotHost = '')
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$configuration = Get-Content -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json') -Raw | ConvertFrom-Json
if (-not $RobotHost) { $RobotHost = $configuration.rony_host }
$robotUser = if ($configuration.rony_user) { $configuration.rony_user } else { 'hiwonder' }
if ($RobotHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $robotUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') {
    throw 'Dirección o usuario del brazo inválidos.'
}
& (Join-Path $PSScriptRoot 'robot_agent/package.ps1')
$archive = Join-Path $PSScriptRoot 'robot_agent/dist/rony-agent.tar.gz'
Write-Host 'Se actualiza únicamente nuestro agente. No se escriben parámetros ni archivos de calibración.'
Write-Host 'Introduce la contraseña Linux del brazo cuando SSH/SCP la solicite.'
& scp $archive "${robotUser}@${RobotHost}:rony-agent.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'No se pudo copiar el paquete.' }
& ssh -t "${robotUser}@${RobotHost}" 'mkdir -p ~/rony-install && tar -xzf ~/rony-agent.tar.gz -C ~/rony-install && cd ~/rony-install && sudo bash install.sh && systemctl is-active rony-agent'
if ($LASTEXITCODE -ne 0) { throw 'No se pudo confirmar la actualización del agente.' }
Write-Host 'Agente actualizado. Ejecuta check-ready.ps1 -Robot antes de iniciar otra prueba.'
