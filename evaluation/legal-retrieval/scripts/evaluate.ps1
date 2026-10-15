[CmdletBinding()]
param([ValidateRange(1,65535)][int]$Port = 11435, [ValidateRange(1,8)][int]$Threads = 6,
    [ValidateRange(1,32)][int]$BatchSize = 16, [Alias('Model')][ValidateSet('embeddinggemma','bge-m3')][string]$ModelChoice = 'embeddinggemma', [string]$DatasetDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
if ($DatasetDirectory) { $DataRoot = [IO.Path]::GetFullPath($DatasetDirectory) }
& (Join-Path $PSScriptRoot 'validate-evaluation.ps1') -DatasetDirectory $DataRoot
Add-Type -Path (Join-Path $PSScriptRoot 'lib/DenseIndex.cs')
$dataPath = Join-Path $DataRoot 'questions.json'
$data = Get-Content -Raw -Encoding UTF8 $dataPath | ConvertFrom-Json
$chunks = @(foreach ($line in [IO.File]::ReadLines((Join-Path $DataRoot $data.corpus.path))) { $line | ConvertFrom-Json })
$byId = @{}; foreach ($chunk in $chunks) { $byId[$chunk.id] = $chunk }
$baseUri = "http://127.0.0.1:$Port"
$profiles = Get-Content -Raw -Encoding UTF8 (Join-Path $ReviewRoot 'models.json') | ConvertFrom-Json
$profile = $profiles.$ModelChoice
$model = $profile.tag
$dimensions = [int]$profile.dimensions
function Format-Document($Chunk) { $profile.documentFormat.Replace('{title}',$Chunk.title).Replace('{text}',$Chunk.text) }
function Format-Query([string]$Text) { $profile.queryFormat.Replace('{text}',$Text) }
function Invoke-Embedding([string[]]$Inputs, $KeepAlive = '5m') {
    $payload = @{ model = $model; input = @($Inputs); truncate = $false; keep_alive = $KeepAlive
        options = @{ num_thread = $Threads; num_gpu = 0 } }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 6 -Compress))
    Invoke-RestMethod -Method Post -Uri "$baseUri/api/embed" -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 300
}
$version = Invoke-RestMethod "$baseUri/api/version" -TimeoutSec 10
$tags = Invoke-RestMethod "$baseUri/api/tags" -TimeoutSec 10
$tag = @($tags.models | Where-Object name -eq $model)
if ($tag.Count -ne 1) { throw "Model not installed: $model" }
$digest = $tag[0].digest
$running = Invoke-RestMethod "$baseUri/api/ps" -TimeoutSec 10
if (@($running.models | Where-Object name -ne $model).Count -gt 0) { throw 'Another model is loaded.' }
$cacheKey = $ModelChoice + '-' + $digest.Substring(0,16) + '-' + $profile.inputMode
$cacheDir = Join-Path $ResultsRoot ('shared-cache/' + $cacheKey)
$null = New-Item -ItemType Directory -Force $cacheDir
$shared = [LegalDenseIndex]::new($dimensions)
foreach ($file in Get-ChildItem -LiteralPath $cacheDir -Filter '*.bin' | Sort-Object Name) { $shared.ReadBatch($file.FullName) }
$chunkKeys = @{}; $inputsByKey = @{}
foreach ($chunk in $chunks) {
    $inputText = Format-Document $chunk
    $key = [LegalDenseIndex]::InputKey($inputText)
    $chunkKeys[$chunk.id] = $key; $inputsByKey[$key] = $inputText
}
$metadata = [ordered]@{ corpusSha256 = $data.corpus.sha256; model = $model; modelDigest = $digest
    dimensions = $dimensions; inputMode = $profile.inputMode; ollamaVersion = $version.version
    documentFormat = $profile.documentFormat; queryFormat = $profile.queryFormat }
$cacheMetadataPath = Join-Path $cacheDir 'metadata.json'
if (Test-Path -LiteralPath $cacheMetadataPath) {
    $previous = Get-Content -Raw -Encoding UTF8 $cacheMetadataPath | ConvertFrom-Json
    if ($previous.modelDigest -ne $digest -or $previous.dimensions -ne $dimensions -or $previous.inputMode -ne $profile.inputMode) { throw 'Shared cache model metadata mismatch.' }
}
[IO.File]::WriteAllText($cacheMetadataPath,($metadata | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
# Reuse the original Gemma vectors with exactly the same document input.
if ($ModelChoice -eq 'embeddinggemma') {
    $baselineDirectory = Join-Path $EvaluationRoot 'data'
    $baseline = Get-Content -Raw -Encoding UTF8 (Join-Path $baselineDirectory 'questions.json') | ConvertFrom-Json
    $legacyKey = $baseline.corpus.sha256.Substring(0,16) + '-' + $digest.Substring(0,16) + '-gemma-retrieval-v1'
    $legacyDir = Join-Path $ResultsRoot ('cache/' + $legacyKey)
    if (Test-Path -LiteralPath $legacyDir) {
        $legacy = [LegalDenseIndex]::new($dimensions)
        foreach ($file in Get-ChildItem -LiteralPath $legacyDir -Filter '*.bin') { $legacy.ReadBatch($file.FullName) }
        $migration = @{}
        foreach ($line in [IO.File]::ReadLines((Join-Path $baselineDirectory $baseline.corpus.path))) {
            $chunk = $line | ConvertFrom-Json
            $key = [LegalDenseIndex]::InputKey((Format-Document $chunk))
            if (!$shared.Contains($key) -and $legacy.Contains($chunk.id)) { $migration[$key] = $legacy.GetVector($chunk.id) }
            if ($migration.Count -ge 32) {
                $keys = [string[]]@($migration.Keys)
                $shared.WriteBatch((Join-Path $cacheDir ([guid]::NewGuid().ToString('N')+'.bin')),$keys,[single[][]]@($keys | ForEach-Object { ,$migration[$_] }))
                $migration.Clear()
            }
        }
        if ($migration.Count -gt 0) {
            $keys = [string[]]@($migration.Keys)
            $shared.WriteBatch((Join-Path $cacheDir ([guid]::NewGuid().ToString('N')+'.bin')),$keys,[single[][]]@($keys | ForEach-Object { ,$migration[$_] }))
        }
    }
}
$missing = @($inputsByKey.Keys | Where-Object { !$shared.Contains($_) } | Sort-Object)
$index = [LegalDenseIndex]::new($dimensions)
$indexWatch = [Diagnostics.Stopwatch]::StartNew(); $newVectors = 0; $queryRows = @(); $resultRows = @()
try {
    $null = Invoke-Embedding @((Format-Query 'warmup'))
    for ($offset = 0; $offset -lt $missing.Count; $offset += $BatchSize) {
        $last = [math]::Min($offset + $BatchSize, $missing.Count)
        $batch = @($missing[$offset..($last - 1)])
        $inputs = @($batch | ForEach-Object { $inputsByKey[$_] })
        $response = Invoke-Embedding $inputs
        if ($response.embeddings.Count -ne $batch.Count) { throw 'Embedding count mismatch.' }
        $vectors = [single[][]]@($response.embeddings | ForEach-Object { ,([single[]]$_) })
        $batchPath = Join-Path $cacheDir ('batch-' + [guid]::NewGuid().ToString('N') + '.bin')
        $shared.WriteBatch($batchPath, [string[]]$batch, $vectors)
        $newVectors += $batch.Count
        if ($offset % (16 * $BatchSize) -eq 0 -or $last -eq $missing.Count) {
            Write-Host "Embedded $last/$($missing.Count) new inputs; elapsed $([math]::Round($indexWatch.Elapsed.TotalSeconds))s"
        }
    }
    $indexWatch.Stop()

    foreach ($chunk in $chunks) { $index.Add($chunk.id,$shared.GetVector($chunkKeys[$chunk.id])); $index.SetUnit($chunk.id,$chunk.unitId) }; if ($index.Count -ne $chunks.Count) { throw 'Index count mismatch.' }
    foreach ($q in $data.questions) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-Embedding @((Format-Query $q.text))
        $watch.Stop()
        if ($response.embeddings.Count -ne 1) { throw 'Query vector count mismatch.' }
        $searchWatch = [Diagnostics.Stopwatch]::StartNew()
        $ranked = @($index.Rank([single[]]$response.embeddings[0], $data.corpus.unitCount))
        $searchWatch.Stop()
        $goldRanks = @()
        for ($i = 0; $i -lt $ranked.Count; $i++) {
            $hit = $ranked[$i]; $relevant = $hit.UnitId -in $q.relevantUnitIds
            if ($relevant) { $goldRanks += ($i + 1) }
            if ($i -lt 10) {
                $chunk = $byId[$hit.ChunkId]
                $resultRows += [pscustomobject]@{ queryId = $q.id; split = $q.split; query = $q.text
                    answerability = $q.answerability; rank = $i + 1; unitId = $hit.UnitId; chunkId = $hit.ChunkId
                    title = $chunk.title; similarity = $hit.Score; relevant = $relevant }
            }
        }
        $positive = $q.answerability -eq 'in_corpus'
        $queryRows += [pscustomobject]@{ queryId = $q.id; split = $q.split; answerability = $q.answerability
            top1Similarity = $ranked[0].Score; top1Relevant = ($ranked[0].UnitId -in $q.relevantUnitIds)
            firstRelevantRank = $(if ($positive) { $goldRanks[0] } else { $null })
            reciprocalRank = $(if ($positive) { 1.0 / $goldRanks[0] } else { $null })
            recallAt5 = $(if ($positive) { @($goldRanks | Where-Object { $_ -le 5 }).Count / [double]$q.relevantUnitIds.Count } else { $null })
            recallAt10 = $(if ($positive) { @($goldRanks | Where-Object { $_ -le 10 }).Count / [double]$q.relevantUnitIds.Count } else { $null })
            embeddingMs = $watch.Elapsed.TotalMilliseconds; searchMs = $searchWatch.Elapsed.TotalMilliseconds }
        Write-Host "Evaluated $($q.id)"
    }
    $after = Invoke-RestMethod "$baseUri/api/tags" -TimeoutSec 10
    if (@($after.models | Where-Object name -eq $model)[0].digest -ne $digest) { throw 'Model digest changed during evaluation.' }
} finally { $null = Invoke-Embedding @() 0 }
# Fit an answerability score threshold using development only, then freeze it for held-out.
$development = @($queryRows | Where-Object split -eq 'development')
$scores = @($development.top1Similarity | Sort-Object -Unique)
$thresholds = @(($scores[0] - 0.000001), ($scores[-1] + 0.000001))
for ($i = 1; $i -lt $scores.Count; $i++) { $thresholds += ($scores[$i-1] + $scores[$i]) / 2 }
$calibration = @(foreach ($threshold in $thresholds) {
    $positive = @($development | Where-Object answerability -eq 'in_corpus')
    $negative = @($development | Where-Object answerability -eq 'out_of_corpus')
    $tpr = @($positive | Where-Object top1Similarity -ge $threshold).Count / [double]$positive.Count
    $tnr = @($negative | Where-Object top1Similarity -lt $threshold).Count / [double]$negative.Count
    [pscustomobject]@{ threshold = $threshold; balancedAccuracy = ($tpr + $tnr) / 2 }
})
$selected = @($calibration | Sort-Object @{ Expression = 'balancedAccuracy'; Descending = $true }, @{ Expression = 'threshold'; Descending = $true })[0]
$summary = @(foreach ($split in @('development','held_out')) {
    $positive = @($queryRows | Where-Object { $_.split -eq $split -and $_.answerability -eq 'in_corpus' })
    $negative = @($queryRows | Where-Object { $_.split -eq $split -and $_.answerability -eq 'out_of_corpus' })
    [pscustomobject]@{ model = $model; dimensions = $dimensions; split = $split; positiveQueries = $positive.Count; negativeQueries = $negative.Count
        top1HitRate = @($positive | Where-Object top1Relevant).Count / [double]$positive.Count
        recallAt5 = ($positive | Measure-Object recallAt5 -Average).Average
        recallAt10 = ($positive | Measure-Object recallAt10 -Average).Average
        mrr = ($positive | Measure-Object reciprocalRank -Average).Average
        noAnswerFalseAcceptanceRate = @($negative | Where-Object top1Similarity -ge $selected.threshold).Count / [double]$negative.Count
        inCorpusAcceptanceRate = @($positive | Where-Object top1Similarity -ge $selected.threshold).Count / [double]$positive.Count
        threshold = $selected.threshold; meanQueryEmbeddingMs = ($positive | Measure-Object embeddingMs -Average).Average }
})
$outputDir = Join-Path $ArtifactsRoot ('results/' + $ModelChoice + '-' + $data.corpus.chunkingVersion + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$null = New-Item -ItemType Directory -Force $outputDir
$summary | Export-Csv (Join-Path $outputDir 'summary.csv') -NoTypeInformation -Encoding UTF8
$queryRows | Export-Csv (Join-Path $outputDir 'queries.csv') -NoTypeInformation -Encoding UTF8
$resultRows | Export-Csv (Join-Path $outputDir 'top10.csv') -NoTypeInformation -Encoding UTF8
$report = [ordered]@{ timestampUtc = [datetime]::UtcNow.ToString('o'); datasetId = $data.id
    datasetSha256 = (Get-FileHash $dataPath).Hash; corpusSha256 = $data.corpus.sha256; chunkingVersion = $data.corpus.chunkingVersion
    model = $metadata; threads = $Threads; batchSize = $BatchSize; newVectors = $newVectors
    reusedVectors = $chunks.Count - $newVectors; indexingWallMsThisRun = $indexWatch.Elapsed.TotalMilliseconds
    thresholdCalibration = @{ split = 'development_only'; objective = 'balanced_answerability_accuracy'; selected = $selected }
    metricScope = 'distinct article/annex units ranked by maximum child-chunk cosine; positives only for Recall/MRR'
    noAnswerScope = 'out-of-frozen-corpus topic checks, not answer generation'; summary = $summary }
[IO.File]::WriteAllText((Join-Path $outputDir 'report.json'),($report | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $ResultsRoot ("latest-$ModelChoice-$($data.corpus.chunkingVersion).json")),(@{ directory = $outputDir; corpusSha256 = $data.corpus.sha256 } | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$summary | Format-Table -AutoSize
Write-Host "Results: $outputDir"
