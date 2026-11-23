param([string]$RobotHost = '')
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$configuration = Get-Content -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json') -Raw | ConvertFrom-Json
if (-not $RobotHost) { $RobotHost = $configuration.rony_host }
$robotUser = if ($configuration.rony_user) { $configuration.rony_user } else { 'hiwonder' }
if ($RobotHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $robotUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') {
    throw 'Invalid robot address or user.'
}
$scriptPath = Join-Path $PSScriptRoot 'robot_agent/inspect_runtime.py'
Write-Host 'Read-only inventory. No motion, ROS parameter writes, package installs or service restarts.'
& scp $scriptPath "${robotUser}@${RobotHost}:rony-inspect-runtime.py"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy inventory script.' }
$reportDirectory = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
& ssh "${robotUser}@${RobotHost}" 'bash -ic ''python3 ~/rony-inspect-runtime.py''' | Tee-Object -FilePath (Join-Path $reportDirectory 'robot-runtime-report.txt')
if ($LASTEXITCODE -ne 0) { throw 'Could not read robot runtime.' }
Write-Host 'Report saved in data/robot-runtime-report.txt'
