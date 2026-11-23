param()
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
$workspaceRoot = Split-Path $backendRoot -Parent
$configuration = Get-Content -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json') -Raw | ConvertFrom-Json
$api = $null
if (-not [Uri]::TryCreate($configuration.cloud_api_url,[UriKind]::Absolute,[ref]$api) -or $api.Scheme -ne 'https' -or $api.UserInfo -or $api.Query -or $api.Fragment -or $api.Host.EndsWith('.invalid')) {
    throw 'Configure the real HTTPS API with prepare-cloud.ps1 first.'
}
if ($configuration.device_token -notmatch '^[a-fA-F0-9]{64}$') { throw 'Missing private device credential. Run prepare-cloud.ps1.' }
$bundle = Join-Path $PSScriptRoot 'dist/autonomous'
New-Item -ItemType Directory -Force -Path (Join-Path $bundle 'robot_agent'),(Join-Path $bundle 'adapters') | Out-Null
foreach ($name in @('run_edge.py','controller.py','plans.py','outbox.py','yolo_tensorrt.py','start.sh','install.sh','rony-edge.service')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $bundle -Force
}
Copy-Item -LiteralPath (Join-Path $backendRoot 'robot_agent/agent.py') -Destination (Join-Path $bundle 'robot_agent/agent.py') -Force
foreach ($name in @('mira_vision.py','runtime_profile.py','worker_support.py')) {
    Copy-Item -LiteralPath (Join-Path $backendRoot ('adapters/' + $name)) -Destination (Join-Path $bundle 'adapters') -Force
}
Copy-Item -LiteralPath (Join-Path $workspaceRoot 'rony-yolo-isaac/runtime/rony_profile.json') -Destination (Join-Path $bundle 'rony_profile.json') -Force
$utf8 = [Text.UTF8Encoding]::new($false)
$envText = 'RONY_API_URL=' + $configuration.cloud_api_url + "`nRONY_DEVICE_ID=jetmax-01`nRONY_DEVICE_TOKEN=" + $configuration.device_token + "`n"
[IO.File]::WriteAllText((Join-Path $bundle 'edge.env'), $envText, $utf8)
foreach ($name in @('start.sh','install.sh','rony-edge.service')) {
    $file = Join-Path $bundle $name
    [IO.File]::WriteAllText($file, ([IO.File]::ReadAllText($file).Replace("`r`n", "`n")), $utf8)
}
$archive = Join-Path $PSScriptRoot 'dist/rony-edge.tar.gz'
& tar -czf $archive -C $bundle .
if ($LASTEXITCODE -ne 0) { throw 'Could not package autonomous runtime.' }
Write-Host ('Private autonomous runtime: ' + $archive)
