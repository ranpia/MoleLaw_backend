[CmdletBinding()]
param(
    [ValidateSet('bge-m3', 'qwen3-embedding:0.6b', 'embeddinggemma')]
    [string[]]$Models = @('bge-m3', 'qwen3-embedding:0.6b', 'embeddinggemma'),
    [ValidateRange(3, 100)][int]$Iterations = 5,
    [ValidateRange(1, 8)][int]$Threads = 6,
    [ValidateRange(1, 65535)][int]$Port = 11435,
    [switch]$Pull
)

$ErrorActionPreference = 'Stop'
$baseUri = "http://127.0.0.1:$Port"
$expectedDimensions = @{ 'bge-m3' = 1024; 'qwen3-embedding:0.6b' = 1024; 'embeddinggemma' = 768 }
# Synthetic input for runtime checks, not a statement of actual law.
$document = '임베딩 실행 검사용 가상 조문입니다. 계약 당사자는 정해진 기한에 대금을 지급하고 분쟁이 발생하면 합의한 절차에 따라 해결합니다.'
$query = '계약 대금을 지급하지 않은 경우 어떤 절차를 확인해야 하나요?'

function Invoke-LabPost([string]$Path, [hashtable]$Payload, [int]$Timeout = 300) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 8 -Compress))
    Invoke-RestMethod -Method Post -Uri "$baseUri$Path" -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec $Timeout
}

function Assert-Embeddings($Response, [int]$Count, [int]$Dimensions) {
    if ($Response.embeddings.Count -ne $Count) { throw 'Unexpected embedding count.' }
    foreach ($vector in $Response.embeddings) {
        if ($vector.Count -ne $Dimensions) { throw "Unexpected dimensions: $($vector.Count), expected $Dimensions." }
        $norm = 0.0
        foreach ($value in $vector) {
            $number = [double]$value
            if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) { throw 'Non-finite embedding value.' }
            $norm += $number * $number
        }
        if ($norm -le 0) { throw 'Zero embedding vector.' }
    }
}

$version = Invoke-RestMethod -Uri "$baseUri/api/version" -TimeoutSec 10
$results = @()
foreach ($model in $Models) {
    if ($Pull) {
        Write-Host "Downloading $model (cached layers are reused)..."
        $download = Invoke-LabPost '/api/pull' @{ model = $model; stream = $false } 1800
        if ($download.status -ne 'success') { throw "Download failed: $model" }
    }
    $tags = Invoke-RestMethod -Uri "$baseUri/api/tags" -TimeoutSec 10
    $tag = @($tags.models | Where-Object { $_.name -eq $model -or $_.name -eq "${model}:latest" })
    if ($tag.Count -ne 1) { throw "Model missing: $model. Run this script with -Pull first." }
    $digest = $tag[0].digest

    # Explicitly unload this model before measuring the first request.
    $null = Invoke-LabPost '/api/embed' @{ model = $model; input = @(); keep_alive = 0 }
    $running = Invoke-RestMethod -Uri "$baseUri/api/ps" -TimeoutSec 10
    if (@($running.models).Count -gt 0) { throw 'Another model is loaded. Stop it before benchmarking.' }
    $payload = @{
        model = $model; input = @($document, $query); truncate = $false; keep_alive = '5m'
        options = @{ num_thread = $Threads; num_gpu = 0 }
    }
    Write-Host "Testing $model sequentially ($Threads threads)..."
    try {
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $cold = Invoke-LabPost '/api/embed' $payload
        $watch.Stop()
        $coldMs = $watch.Elapsed.TotalMilliseconds
        Assert-Embeddings $cold 2 $expectedDimensions[$model]
        $samples = @()
        for ($index = 0; $index -lt $Iterations; $index++) {
            $watch.Restart()
            $response = Invoke-LabPost '/api/embed' $payload
            $watch.Stop()
            Assert-Embeddings $response 2 $expectedDimensions[$model]
            $samples += [pscustomobject]@{
                wallMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2)
                serverMs = [math]::Round($response.total_duration / 1e6, 2)
                loadMs = [math]::Round($response.load_duration / 1e6, 2)
                inputTokens = $response.prompt_eval_count
            }
        }
        $sorted = @($samples.wallMs | Sort-Object)
        $middle = [int][math]::Floor($sorted.Count / 2)
        $median = $sorted[$middle]
        if ($sorted.Count % 2 -eq 0) { $median = ($sorted[$middle - 1] + $sorted[$middle]) / 2 }
        $results += [pscustomobject]@{
            model = $model; digest = $digest; dimensions = $expectedDimensions[$model]
            firstRequestMs = [math]::Round($coldMs, 2)
            medianWarmMs = [math]::Round($median, 2)
            p95WarmMs = $sorted[[math]::Ceiling($sorted.Count * 0.95) - 1]
            samples = $samples
        }
    } finally {
        $null = Invoke-LabPost '/api/embed' @{ model = $model; input = @(); keep_alive = 0 }
    }
}

$outputDirectory = Join-Path $PSScriptRoot 'results'
$null = New-Item -ItemType Directory -Force -Path $outputDirectory
$outputPath = Join-Path $outputDirectory ("runtime-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$report = @{
    timestampUtc = [DateTime]::UtcNow.ToString('o'); ollamaVersion = $version.version
    threads = $Threads; cpuLimit = 6; memoryLimitGiB = 8; batchSize = 2
    hostProcessor = $env:PROCESSOR_IDENTIFIER; hostLogicalProcessors = $env:NUMBER_OF_PROCESSORS
    fixture = 'synthetic-contract-v1'; inputMode = 'raw-runtime-smoke-only'; results = $results
}
[System.IO.File]::WriteAllText($outputPath, ($report | ConvertTo-Json -Depth 10), [System.Text.UTF8Encoding]::new($false))
$results | Select-Object model, dimensions, firstRequestMs, medianWarmMs, p95WarmMs | Format-Table -AutoSize
Write-Host "Report: $outputPath"
