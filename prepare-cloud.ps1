param([Parameter(Mandatory=$true)][string]$Domain)
$ErrorActionPreference = 'Stop'
if ($Domain -notmatch '^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$' -or $Domain.EndsWith('.invalid')) {
    throw 'Supply the real DNS domain only, without protocol, paths or credentials.'
}
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$path = Join-Path $workspaceRoot 'rony-yolo-isaac/config.local.json'
$configuration = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
function New-PrivateToken {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
foreach ($name in @('device_token','cloud_db_password','cloud_db_root_password')) {
    if (-not $configuration.$name) { $configuration | Add-Member -NotePropertyName $name -NotePropertyValue (New-PrivateToken) -Force }
}
$configuration | Add-Member -NotePropertyName cloud_api_url -NotePropertyValue ('https://' + $Domain.ToLowerInvariant()) -Force
$configuration | Add-Member -NotePropertyName device_id -NotePropertyValue 'jetmax-01' -Force
if (-not $configuration.web_access_token -or $configuration.web_access_token.Length -lt 24) { throw 'Prepare the existing private web credential first.' }
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($path, ($configuration | ConvertTo-Json -Depth 10), $utf8)
$lines = @('RONY_DOMAIN=' + $Domain.ToLowerInvariant(), 'RONY_WEB_ACCESS_TOKEN=' + $configuration.web_access_token,
    'RONY_DEVICE_TOKEN=' + $configuration.device_token, 'RONY_DB_PASSWORD=' + $configuration.cloud_db_password,
    'RONY_DB_ROOT_PASSWORD=' + $configuration.cloud_db_root_password)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'deploy/.env'), ($lines -join "`n") + "`n", $utf8)
Write-Host 'Private deployment settings prepared. No server was created and nothing was published.'
Write-Host 'The generated deploy/.env and config.local.json contain secrets; do not publish them.'
