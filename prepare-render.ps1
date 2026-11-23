param([string]$ApiUrl)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$aivenPath = Join-Path $PSScriptRoot 'deploy/.env.aiven'
$localPath = Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json'
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines($aivenPath)) {
    if ($line -match '^([A-Z][A-Z0-9_]+)=(.*)$') { $settings[$matches[1]] = $matches[2] }
}
foreach ($key in @('RONY_DB_HOST','RONY_DB_PORT','RONY_DB_NAME','RONY_DB_USER','RONY_DB_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace($settings[$key])) { throw ('Completa ' + $key + ' en deploy/.env.aiven.') }
}
if ($settings.RONY_DB_HOST -notmatch '^[a-zA-Z0-9.-]+$' -or
    $settings.RONY_DB_NAME -notmatch '^[a-zA-Z0-9_-]+$' -or
    $settings.RONY_DB_PORT -notmatch '^\d{1,5}$' -or
    [int]$settings.RONY_DB_PORT -lt 1 -or [int]$settings.RONY_DB_PORT -gt 65535) {
    throw 'Revisa host, puerto y nombre de la base Aiven.'
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'deploy/aiven-ca.pem'))) {
    throw 'Falta deploy/aiven-ca.pem; descarga el certificado de Aiven.'
}
$local = Get-Content -LiteralPath $localPath -Raw | ConvertFrom-Json
if (-not $local.web_access_token -or $local.web_access_token.Length -lt 24) { throw 'Falta la clave web privada existente.' }
if (-not $local.device_token) {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    $local | Add-Member -NotePropertyName device_token -NotePropertyValue (-join ($bytes | ForEach-Object { $_.ToString('x2') })) -Force
}
if ($local.device_token.Length -lt 32) { throw 'La clave privada del dispositivo es demasiado corta.' }
if (-not $local.frontend_url) { $local | Add-Member -NotePropertyName frontend_url -NotePropertyValue 'https://rony-brazo.netlify.app' -Force }
$frontend = [uri]$local.frontend_url
if ($frontend.Scheme -ne 'https' -or $frontend.UserInfo -or $frontend.Query -or $frontend.Fragment -or $frontend.AbsolutePath -ne '/') { throw 'La web requiere un origen HTTPS válido.' }
if ($ApiUrl) {
    $api = [uri]$ApiUrl
    if ($api.Scheme -ne 'https' -or -not $api.Host -or $api.UserInfo -or $api.Query -or $api.Fragment -or $api.AbsolutePath -ne '/') { throw 'Usa la URL HTTPS real de Render sin rutas ni credenciales.' }
    $local | Add-Member -NotePropertyName cloud_api_url -NotePropertyValue $api.GetLeftPart([UriPartial]::Authority) -Force
}
$local | Add-Member -NotePropertyName device_id -NotePropertyValue 'jetmax-01' -Force
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($localPath, (($local | ConvertTo-Json -Depth 10) + "`n"), $utf8)
$lines = @(
    'RONY_DB_HOST=' + $settings.RONY_DB_HOST
    'RONY_DB_PORT=' + $settings.RONY_DB_PORT
    'RONY_DB_NAME=' + $settings.RONY_DB_NAME
    'RONY_DB_USER=' + $settings.RONY_DB_USER
    'RONY_DB_PASSWORD=' + $settings.RONY_DB_PASSWORD
    'RONY_ACCESS_TOKEN=' + $local.web_access_token
    'RONY_DEVICE_TOKEN=' + $local.device_token
    'RONY_DEVICE_ID=jetmax-01'
    'RONY_FRONTEND_URL=' + $frontend.GetLeftPart([UriPartial]::Authority)
    'RONY_DB_CA_FILE=/etc/secrets/aiven-ca.pem'
)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'deploy/.env.render'), (($lines -join "`n") + "`n"), $utf8)
$profileText = [IO.File]::ReadAllText((Join-Path $workspaceRoot 'rony-yolo-isaac/runtime/rony_profile.json')).Replace("`r`n", "`n")
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'deploy/rony_profile.json'), $profileText, $utf8)
Write-Host 'Render: importa deploy/.env.render como variables privadas y aiven-ca.pem como Secret File.'
Write-Host 'La clave web se conserva. No se publico ningun recurso ni se modifico ROS.'
