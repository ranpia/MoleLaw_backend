param([Parameter(Mandatory=$true)][string]$SeriesPath)
$ErrorActionPreference = 'Stop'
$output = Join-Path $SeriesPath 'docker-observations.jsonl'
for ($sample = 0; $sample -lt 240; $sample++) {
    if (Test-Path (Join-Path $SeriesPath 'result.json')) { break }
    $lines = docker stats --no-stream --format '{{json .}}'
    foreach ($line in $lines) {
        $item = $line | ConvertFrom-Json
        if ($item.Name -like 'molelaw-loadtest-*') {
            @{ atUtc=[DateTime]::UtcNow.ToString('o'); name=$item.Name; cpu=$item.CPUPerc; memory=$item.MemUsage; network=$item.NetIO; blockIO=$item.BlockIO } |
                ConvertTo-Json -Compress | Add-Content -Encoding UTF8 $output
        }
    }
    Start-Sleep -Seconds 5
}
