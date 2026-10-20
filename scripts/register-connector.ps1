$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot 'infra\debezium\product-connector.json'
$config = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json

$localEnvironment = @{}
$environmentPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $environmentPath) {
    Get-Content -LiteralPath $environmentPath | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
            $localEnvironment[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
}

function Get-Setting([string] $name, [string] $fallback) {
    $processValue = [Environment]::GetEnvironmentVariable($name)
    if ($processValue) { return $processValue }
    if ($localEnvironment.ContainsKey($name)) { return $localEnvironment[$name] }
    return $fallback
}

$config.'database.dbname' = Get-Setting 'POSTGRES_DB' 'inventory_db'
$config.'database.user' = Get-Setting 'DEBEZIUM_USER' 'debezium'
$config.'database.password' = Get-Setting 'DEBEZIUM_PASSWORD' 'debezium'
$body = $config | ConvertTo-Json -Depth 20 -Compress
Invoke-RestMethod `
    -Method Put `
    -Uri 'http://localhost:8083/connectors/product-cdc-connector/config' `
    -ContentType 'application/json' `
    -Body $body
Invoke-RestMethod -Uri 'http://localhost:8083/connectors/product-cdc-connector/status'

