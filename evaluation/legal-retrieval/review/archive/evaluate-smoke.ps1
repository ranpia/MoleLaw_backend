[CmdletBinding()]
param(
    [string]$Fixture,
    [ValidateRange(1, 65535)][int]$Port = 11435,
    [ValidateRange(1, 8)][int]$Threads = 6
)

$ErrorActionPreference = 'Stop'
if (!$Fixture) { $Fixture = Join-Path $PSScriptRoot 'fixtures/retrieval-smoke.json' }
$model = 'embeddinggemma:latest'
$baseUri = "http://127.0.0.1:$Port"
$dataset = Get-Content -Raw -Encoding UTF8 -LiteralPath $Fixture | ConvertFrom-Json
$documents = @($dataset.documents)
$queries = @($dataset.queries)
if (!$dataset.id -or $documents.Count -lt 2 -or $queries.Count -lt 1) { throw 'Invalid fixture.' }
$ids = @($documents.id)
if (@($ids | Select-Object -Unique).Count -ne $ids.Count) { throw 'Duplicate document IDs.' }
if (@($queries.id | Select-Object -Unique).Count -ne $queries.Count) { throw 'Duplicate query IDs.' }
foreach ($doc in $documents) {
    if (!$doc.id -or !$doc.title -or !$doc.text) { throw 'Document requires id/title/text.' }
}
foreach ($query in $queries) {
    if (!$query.id -or !$query.text -or @($query.relevantIds).Count -eq 0) { throw 'Query requires id/text/relevantIds.' }
    if (@($query.relevantIds | Select-Object -Unique).Count -ne @($query.relevantIds).Count) { throw 'Duplicate relevance labels.' }
    foreach ($id in $query.relevantIds) {
        if ($id -notin $ids) { throw "Unknown relevance label: $id" }
    }
}

function Invoke-Embed([string[]]$Inputs, $KeepAlive = '5m') {
    $payload = @{ model = $model; input = @($Inputs); truncate = $false; keep_alive = $KeepAlive
        options = @{ num_thread = $Threads; num_gpu = 0 } }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 6 -Compress))
    Invoke-RestMethod -Method Post -Uri "$baseUri/api/embed" -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 300
}

function Assert-Vector($Vector) {
    if ($Vector.Count -ne 768) { throw "Expected 768 dimensions, got $($Vector.Count)." }
    $norm = 0.0
    foreach ($value in $Vector) {
        $number = [double]$value
        if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) { throw 'Non-finite vector.' }
        $norm += $number * $number
    }
    if ($norm -le 0) { throw 'Zero vector.' }
}

function Get-Cosine($A, $B) {
    $dot = 0.0; $aa = 0.0; $bb = 0.0
    for ($i = 0; $i -lt $A.Count; $i++) {
        $dot += $A[$i] * $B[$i]; $aa += $A[$i] * $A[$i]; $bb += $B[$i] * $B[$i]
    }
    $dot / ([math]::Sqrt($aa) * [math]::Sqrt($bb))
}

$version = Invoke-RestMethod "$baseUri/api/version" -TimeoutSec 10
$tags = Invoke-RestMethod "$baseUri/api/tags" -TimeoutSec 10
$tag = @($tags.models | Where-Object { $_.name -eq $model })
if ($tag.Count -ne 1) { throw 'EmbeddingGemma is not installed.' }
$running = Invoke-RestMethod "$baseUri/api/ps" -TimeoutSec 10
if (@($running.models | Where-Object { $_.name -ne $model }).Count -gt 0) { throw 'Another model is loaded.' }
$rows = @(); $metrics = @(); $vectors = @{}; $timings = @()
try {
    # Separate model loading from measured query requests.
    $null = Invoke-Embed @('task: search result | query: warmup')
    foreach ($doc in $documents) {
        Write-Host "Embedding document $($doc.id)..."
        $response = Invoke-Embed @("title: $($doc.title) | text: $($doc.text)")
        if ($response.embeddings.Count -ne 1) { throw 'Unexpected embedding count.' }
        Assert-Vector $response.embeddings[0]
        $vectors[$doc.id] = $response.embeddings[0]
    }
    foreach ($query in $queries) {
        Write-Host "Evaluating $($query.id)..."
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-Embed @("task: search result | query: $($query.text)")
        $watch.Stop()
        if ($response.embeddings.Count -ne 1) { throw 'Unexpected embedding count.' }
        Assert-Vector $response.embeddings[0]
        $timings += $watch.Elapsed.TotalMilliseconds
        $scored = @(foreach ($doc in $documents) {
            [pscustomobject]@{ documentId = $doc.id; title = $doc.title
                similarity = (Get-Cosine $response.embeddings[0] $vectors[$doc.id])
                relevant = ($doc.id -in $query.relevantIds) }
        })
        $sorted = @($scored | Sort-Object @{ Expression = 'similarity'; Descending = $true }, documentId)
        $rank = 0
        foreach ($item in $sorted) {
            $rank++
            $rows += [pscustomobject]@{ model = $model; dimensions = 768; queryId = $query.id
                query = $query.text; documentId = $item.documentId; title = $item.title
                rank = $rank; similarity = $item.similarity; relevant = $item.relevant }
        }
        $queryRows = @($rows | Where-Object { $_.queryId -eq $query.id })
        $first = @($queryRows | Where-Object relevant)[0].rank
        $metrics += [pscustomobject]@{ queryId = $query.id; firstRelevantRank = $first; reciprocalRank = 1.0 / $first
            recallAt1 = @($queryRows | Where-Object { $_.relevant -and $_.rank -le 1 }).Count / [double]@($query.relevantIds).Count
            recallAt3 = @($queryRows | Where-Object { $_.relevant -and $_.rank -le 3 }).Count / [double]@($query.relevantIds).Count
            recallAt5 = @($queryRows | Where-Object { $_.relevant -and $_.rank -le 5 }).Count / [double]@($query.relevantIds).Count }
    }
} finally {
    $null = Invoke-Embed @() 0
}

$summary = [pscustomobject]@{ model = $model; dimensions = 768; documents = $documents.Count; queries = $queries.Count
    recallAt1 = ($metrics | Measure-Object recallAt1 -Average).Average
    recallAt3 = ($metrics | Measure-Object recallAt3 -Average).Average
    recallAt5 = ($metrics | Measure-Object recallAt5 -Average).Average
    mrr = ($metrics | Measure-Object reciprocalRank -Average).Average
    meanWarmQueryMs = ($timings | Measure-Object -Average).Average }
$outputDirectory = Join-Path $PSScriptRoot ('results/retrieval-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$null = New-Item -ItemType Directory -Force -Path $outputDirectory
$rows | Export-Csv (Join-Path $outputDirectory 'similarities.csv') -NoTypeInformation -Encoding UTF8
$summary | Export-Csv (Join-Path $outputDirectory 'summary.csv') -NoTypeInformation -Encoding UTF8
$report = @{ timestampUtc = [DateTime]::UtcNow.ToString('o'); fixture = $dataset
    fixtureSha256 = (Get-FileHash -LiteralPath $Fixture -Algorithm SHA256).Hash
    ollamaVersion = $version.version; modelDigest = $tag[0].digest; modelDetails = $tag[0].details
    threads = $Threads; inputMode = 'embeddinggemma-retrieval-prefix-v1'; truncate = $false
    queryTimingsMs = $timings; metrics = $metrics; summary = $summary }
[IO.File]::WriteAllText((Join-Path $outputDirectory 'report.json'), ($report | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
$summary | Format-List
$rows | Where-Object { $_.rank -eq 1 } | Format-Table queryId, documentId, similarity, relevant -AutoSize
Write-Host "Results: $outputDirectory"
