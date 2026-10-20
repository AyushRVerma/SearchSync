$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

Push-Location $projectRoot
try {
    docker compose up -d --wait
    docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh `
        --bootstrap-server kafka:29092 --create --if-not-exists `
        --topic inventory.public.products --partitions 1 --replication-factor 1
    docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh `
        --bootstrap-server kafka:29092 --create --if-not-exists `
        --topic inventory.public.products.DLT --partitions 1 --replication-factor 1 `
        --config retention.ms=604800000
    & (Join-Path $PSScriptRoot 'register-connector.ps1')
    Write-Host 'Infrastructure and Debezium connector are ready.'
    Write-Host 'Start the API in another terminal with: .\mvnw.cmd spring-boot:run'
}
finally {
    Pop-Location
}

