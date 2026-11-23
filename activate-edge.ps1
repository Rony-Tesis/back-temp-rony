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
Write-Host 'The robot must have internet. Its UTC clock is synchronized for TLS before installation.'
$archive = Join-Path $PSScriptRoot 'edge_runtime/dist/rony-edge.tar.gz'
& scp $archive "${robotUser}@${RobotHost}:rony-edge.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy autonomous service.' }
$installUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
$remoteCommand = 'umask 077; chmod 600 ~/rony-edge.tar.gz && mkdir -p ~/rony-edge-install && tar -xzf ~/rony-edge.tar.gz -C ~/rony-edge-install && cd ~/rony-edge-install && bash activate.sh ' + $installUtc
& ssh -t "${robotUser}@${RobotHost}" $remoteCommand
if ($LASTEXITCODE -ne 0) { throw 'Autonomous service installation did not complete. Inspect the report before retrying.' }
$confirmationDeadline = [DateTime]::UtcNow.AddSeconds(50)
$onlineState = $null
do {
    try {
        $onlineState = Invoke-RestMethod -Uri ($configuration.cloud_api_url.TrimEnd('/') + '/api/v1/monitor/status') -Headers $headers -TimeoutSec 8
        if ($onlineState.robotState.connected) { break }
    } catch { $onlineState = $null }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $confirmationDeadline)
if (-not $onlineState -or -not $onlineState.robotState.connected) {
    throw 'Service installed but robot telemetry is not confirmed in Render. Inspect journalctl -u rony-edge before enabling motion.'
}
$receiptPath = Join-Path $PSScriptRoot 'data/jetson-online-status.json'
New-Item -ItemType Directory -Force -Path (Split-Path $receiptPath -Parent) | Out-Null
[IO.File]::WriteAllText($receiptPath, (($onlineState | ConvertTo-Json -Depth 12) + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host 'JetMax telemetry confirmed in the public HTTPS API. Startup sent no motion commands.'
Write-Host ('Open ' + $configuration.frontend_url + '/monitoring to inspect state before enabling classification.')
