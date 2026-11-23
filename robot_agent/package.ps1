param()
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
$yoloRoot = Join-Path (Split-Path $backendRoot -Parent) 'rony-yolo-isaac'
$localPath = Join-Path $yoloRoot 'config.local.json'
if (-not (Test-Path -LiteralPath $localPath)) { throw 'Ejecuta primero prepare.ps1.' }
$local = Get-Content -LiteralPath $localPath -Raw | ConvertFrom-Json
if (-not $local.robot_agent_token -or $local.robot_agent_token.Length -lt 32) { throw 'Falta el token del agente. Ejecuta prepare.ps1.' }
$bundle = Join-Path $PSScriptRoot 'dist/bundle'
New-Item -ItemType Directory -Force -Path $bundle | Out-Null
foreach ($name in @('agent.py','start.sh','install.sh','rony-agent.service')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $bundle $name) -Force
}
Copy-Item -LiteralPath (Join-Path $yoloRoot 'runtime/rony_profile.json') -Destination (Join-Path $bundle 'rony_profile.json') -Force
$agentEnv = 'RONY_AGENT_TOKEN=' + $local.robot_agent_token + "`nRONY_AGENT_BIND=0.0.0.0`n"
[IO.File]::WriteAllText((Join-Path $bundle 'agent.env'), $agentEnv, [Text.UTF8Encoding]::new($false))
# Shell files must retain LF after a Windows Git checkout.
foreach ($name in @('start.sh','install.sh','rony-agent.service')) {
    $file = Join-Path $bundle $name
    [IO.File]::WriteAllText($file, ([IO.File]::ReadAllText($file).Replace("`r`n", "`n")), [Text.UTF8Encoding]::new($false))
}
$archive = Join-Path $PSScriptRoot 'dist/rony-agent.tar.gz'
& tar -czf $archive -C $bundle .
if ($LASTEXITCODE -ne 0) { throw 'No se pudo empaquetar el agente.' }
Write-Host ('Paquete privado para copiar al JetMax: ' + $archive)
Write-Host 'Contiene un token local; no lo subas a Git ni lo publiques.'
