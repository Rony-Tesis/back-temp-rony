param([string]$RobotHost = '')
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$configuration = Get-Content -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json') -Raw | ConvertFrom-Json
if (-not $RobotHost) { $RobotHost = $configuration.rony_host }
$robotUser = if ($configuration.rony_user) { $configuration.rony_user } else { 'hiwonder' }
if ($RobotHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $robotUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') {
    throw 'Invalid robot address or user.'
}
$bundle = Join-Path $PSScriptRoot 'edge_runtime/dist/model-check'
New-Item -ItemType Directory -Force -Path $bundle | Out-Null
foreach ($name in @('build_engine.py','benchmark_model.py','yolo_tensorrt.py')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('edge_runtime/' + $name)) -Destination $bundle -Force
}
foreach ($name in @('yolo11n.onnx','model.json')) {
    $source = Join-Path $PSScriptRoot ('edge_runtime/dist/model/' + $name)
    if (-not (Test-Path -LiteralPath $source)) { throw 'Run edge_runtime/export_model.py on the PC first.' }
    Copy-Item -LiteralPath $source -Destination $bundle -Force
}
$archive = Join-Path $PSScriptRoot 'edge_runtime/dist/rony-model-check.tar.gz'
$agentUrl = if ($configuration.robot_agent_url) { $configuration.robot_agent_url } else { 'http://' + $RobotHost + ':8090' }
$agentHeaders = @{ Authorization = 'Bearer ' + $configuration.robot_agent_token }
$agentState = Invoke-RestMethod -Uri ($agentUrl.TrimEnd('/') + '/v1/state') -Headers $agentHeaders -TimeoutSec 5
if ($agentState.job -and $agentState.job.state -eq 'running') { throw 'Stop the active classification before building the model.' }
try {
    $webState = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/api/v1/monitor/status' -Headers @{ Authorization = 'Bearer ' + $configuration.web_access_token } -TimeoutSec 3
} catch { $webState = $null }
if ($webState -and ($webState.robotState.autoEnabled -or $webState.robotState.cycleActive)) { throw 'Cancel automatic classification before building the model.' }
& tar -czf $archive -C $bundle .
if ($LASTEXITCODE -ne 0) { throw 'Could not package model check.' }
Write-Host 'This builds and benchmarks YOLO on the JetMax GPU. No service changes, ROS writes or movements.'
& scp $archive "${robotUser}@${RobotHost}:rony-model-check.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy model.' }
$reportDirectory = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
& ssh "${robotUser}@${RobotHost}" 'mkdir -p ~/rony-model-check && tar -xzf ~/rony-model-check.tar.gz -C ~/rony-model-check && cd ~/rony-model-check && python3 build_engine.py --model-dir . && python3 benchmark_model.py --model-dir .' | Tee-Object -FilePath (Join-Path $reportDirectory 'jetson-model-build.txt')
if ($LASTEXITCODE -ne 0) { throw 'Jetson model check failed. Existing services and calibration were preserved.' }
& scp "${robotUser}@${RobotHost}:rony-model-check/benchmark.json" (Join-Path $reportDirectory 'jetson-model-benchmark.json')
if ($LASTEXITCODE -ne 0) { throw 'Could not copy benchmark result.' }
Write-Host 'Jetson inference report saved in data/jetson-model-benchmark.json'
