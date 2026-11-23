param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$frontendRoot = Join-Path $workspaceRoot 'front-web-rony'
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'C:/Program Files/Java/jdk-21')) { $env:JAVA_HOME = 'C:/Program Files/Java/jdk-21' }
Push-Location $frontendRoot
try {
    if (-not (Test-Path -LiteralPath 'node_modules/@angular/cli')) {
        & npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'No se pudieron instalar las dependencias de Angular.' }
    }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación Angular.' }
} finally { Pop-Location }
Push-Location $PSScriptRoot
try {
    # Clean only this project's generated target before compiling; removed adapters
    # and old Angular bundles must not survive inside the deliverable JAR.
    $expectedTarget = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'target'))
    if (Test-Path -LiteralPath $expectedTarget) {
        $resolvedTarget = (Resolve-Path -LiteralPath $expectedTarget).Path
        if ($resolvedTarget -ne $expectedTarget) { throw 'La carpeta target no corresponde a este proyecto.' }
    }
    & ./mvnw.cmd clean compile -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación limpia del backend.' }
    $staticRoot = Join-Path $PSScriptRoot 'target/classes/static'
    New-Item -ItemType Directory -Force -Path $staticRoot | Out-Null
    $browserRoot = Join-Path $frontendRoot 'dist/Web-Angular/browser'
    Get-ChildItem -LiteralPath $browserRoot | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $staticRoot -Recurse -Force }
    $mavenArgs = @('verify')
    if ($SkipTests) { $mavenArgs += '-DskipTests' }
    & ./mvnw.cmd @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Falló el empaquetado del backend.' }
    Write-Host 'Aplicación completa: target/back-rony-1.0.0-SNAPSHOT.jar'
} finally { Pop-Location }
