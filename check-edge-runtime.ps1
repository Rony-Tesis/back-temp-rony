param([string]$RobotHost = '')
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$configuration = Get-Content -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json') -Raw | ConvertFrom-Json
if (-not $RobotHost) { $RobotHost = $configuration.rony_host }
$robotUser = if ($configuration.rony_user) { $configuration.rony_user } else { 'hiwonder' }
if ($RobotHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $robotUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') {
    throw 'Invalid robot address or user.'
}
$bundle = Join-Path $PSScriptRoot 'edge_runtime/dist/runtime-check'
New-Item -ItemType Directory -Force -Path (Join-Path $bundle 'robot_agent'),(Join-Path $bundle 'adapters') | Out-Null
foreach ($name in @('run_edge.py','controller.py','plans.py','outbox.py','yolo_tensorrt.py')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('edge_runtime/' + $name)) -Destination $bundle -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'robot_agent/agent.py') -Destination (Join-Path $bundle 'robot_agent/agent.py') -Force
foreach ($name in @('mira_vision.py','runtime_profile.py','worker_support.py')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('adapters/' + $name)) -Destination (Join-Path $bundle 'adapters') -Force
}
Copy-Item -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/runtime/rony_profile.json') -Destination (Join-Path $bundle 'rony_profile.json') -Force
$archive = Join-Path $PSScriptRoot 'edge_runtime/dist/rony-runtime-check.tar.gz'
& tar -czf $archive -C $bundle .
if ($LASTEXITCODE -ne 0) { throw 'Could not package runtime check.' }
Write-Host 'Read-only complete runtime check. No service changes, motion or ROS parameter writes.'
& scp $archive "${robotUser}@${RobotHost}:rony-runtime-check.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy runtime check.' }
$reportDirectory = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
& ssh "${robotUser}@${RobotHost}" 'mkdir -p ~/rony-edge-check && tar -xzf ~/rony-runtime-check.tar.gz -C ~/rony-edge-check && cd ~/rony-edge-check && bash -ic ''python3 run_edge.py --check --profile rony_profile.json --model-dir /home/hiwonder/rony-model-check''' | Tee-Object -FilePath (Join-Path $reportDirectory 'jetson-runtime-check.txt')
if ($LASTEXITCODE -ne 0) { throw 'Runtime check failed; original services and calibration were preserved.' }
Write-Host 'Native runtime report saved in data/jetson-runtime-check.txt'
