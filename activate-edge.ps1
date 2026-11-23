param([string]$RobotHost = '')
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$configuration = Get-Content -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json') -Raw | ConvertFrom-Json
if (-not $RobotHost) { $RobotHost = $configuration.rony_host }
$robotUser = if ($configuration.rony_user) { $configuration.rony_user } else { 'hiwonder' }
if ($RobotHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $robotUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') {
    throw 'Invalid robot address or user.'
}
& (Join-Path $PSScriptRoot 'edge_runtime/package.ps1')
$headers = @{ Authorization = 'Bearer ' + $configuration.web_access_token }
$status = Invoke-RestMethod -Uri ($configuration.cloud_api_url.TrimEnd('/') + '/api/v1/monitor/status') -Headers $headers -TimeoutSec 8
if ($status.executionLocation -ne 'jetmax') { throw 'The HTTPS API is not running in cloud mode.' }
if ($status.robotState.autoEnabled -or $status.robotState.cycleActive) { throw 'Cancel classification and wait for the current segment before updating.' }
$agentUrl = if ($configuration.robot_agent_url) { $configuration.robot_agent_url } else { 'http://' + $RobotHost + ':8090' }
try { $agent = Invoke-RestMethod -Uri ($agentUrl.TrimEnd('/') + '/v1/state') -Headers @{ Authorization = 'Bearer ' + $configuration.robot_agent_token } -TimeoutSec 4 }
catch { $agent = $null }
if ($agent -and $agent.job -and $agent.job.state -eq 'running') { throw 'The bench executor still has an active trajectory.' }
Write-Host 'Installs only our autonomous service. YOLO and all classification will run on the JetMax.'
Write-Host 'Vendor ROS/calibration stay unchanged. Startup observes; enable motion from the HTTPS web.'
$archive = Join-Path $PSScriptRoot 'edge_runtime/dist/rony-edge.tar.gz'
& scp $archive "${robotUser}@${RobotHost}:rony-edge.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy autonomous service.' }
& ssh -t "${robotUser}@${RobotHost}" 'umask 077; chmod 600 ~/rony-edge.tar.gz && mkdir -p ~/rony-edge-install && tar -xzf ~/rony-edge.tar.gz -C ~/rony-edge-install && cd ~/rony-edge-install && bash -ic ''python3 run_edge.py --check --profile rony_profile.json --model-dir /home/hiwonder/rony-model-check'' && sudo bash install.sh && systemctl is-active rony-edge'
if ($LASTEXITCODE -ne 0) { throw 'Autonomous service installation did not complete. Inspect the report before retrying.' }
Write-Host 'Autonomous service installed. Open the deployed HTTPS web to inspect state and start classification.'
