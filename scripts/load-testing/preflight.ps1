param()

# Read-only inventory: no application requests, secrets, restarts or data writes.
$ErrorActionPreference = 'Stop'
$serviceNames = @(
    'backend-service-backend-1',
    'ai-service-kure-1',
    'database-service-mysql-1',
    'database-service-redis-1',
    'database-service-qdrant-1'
)

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is missing.'
}
$dockerSummary = docker info --format 'Docker CPUs={{.NCPU}} MemoryBytes={{.MemTotal}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable.' }
$dockerSummary

$systemInfo = Get-CimInstance Win32_ComputerSystem
[pscustomobject]@{
    HostLogicalCPUs = $systemInfo.NumberOfLogicalProcessors
    HostMemoryGiB = [math]::Round($systemInfo.TotalPhysicalMemory / 1GB, 1)
} | Format-List

$inventory = foreach ($serviceName in $serviceNames) {
    $inspection = docker inspect $serviceName 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Expected container missing: $serviceName" }
    $containerInfo = ($inspection | ConvertFrom-Json)[0]
    [pscustomobject]@{
        Name = $serviceName
        State = $containerInfo.State.Status
        Health = $containerInfo.State.Health.Status
        Image = $containerInfo.Config.Image
        CPUQuota = $containerInfo.HostConfig.NanoCpus / 1e9
        MemoryMiB = $containerInfo.HostConfig.Memory / 1MB
    }
}
$inventory | Format-Table -AutoSize

$configuredMiB = ($inventory | Measure-Object -Property MemoryMiB -Sum).Sum
"Main service memory limits total: $configuredMiB MiB (limits are not reservations)."
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
if ($LASTEXITCODE -ne 0) { throw 'Container metrics could not be read.' }

docker image inspect locustio/locust:2.47.0 --format 'Locust image={{index .RepoDigests 0}}'
if ($LASTEXITCODE -ne 0) { throw 'Locust image missing. Follow docs/testing/environment-readiness.md.' }
'Inventory complete. This does not prove isolated test data, API correctness or load readiness.'
