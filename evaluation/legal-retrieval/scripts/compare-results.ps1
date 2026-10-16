[CmdletBinding()]
param([string]$ReportDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
if ($ReportDirectory) { $ReportsRoot = [IO.Path]::GetFullPath($ReportDirectory) }
$identities = @{}; $commonIdentity = $null; $configurations = @{}; $executionConditions = $null
$records = @()
$rankingRows = @()
$similarityRows = @()
foreach ($file in Get-ChildItem -LiteralPath $ReportsRoot -Recurse -Filter summary.json) {
    $r = Get-Content -Raw -Encoding UTF8 $file.FullName | ConvertFrom-Json
    if (!$r.chunkingVersion) { continue }
    if ($r.chunkingVersion -notmatch '^char(300|500|700)-overlap64-article-v1$') { throw 'Unsupported report chunk version.' }
    $directory = $(if ($r.chunkingVersion -eq 'char500-overlap64-article-v1') { $DataRoot } else { Join-Path $DataRoot "variants/$($r.chunkingVersion)" })
    $data = Get-Content -Raw -Encoding UTF8 (Join-Path $directory 'questions.json') | ConvertFrom-Json
    if ($r.datasetSha256 -ne (Get-FileHash (Join-Path $directory 'questions.json')).Hash -or $r.corpusSha256 -ne $data.corpus.sha256) { throw 'Report input hashes mismatch.' }
    if (!$identities.ContainsKey($directory)) {
        & (Join-Path $PSScriptRoot 'validate-evaluation.ps1') -DatasetDirectory $directory
        $identities[$directory] = Get-ComparisonIdentity $directory
    }
    $identity = $identities[$directory]
    # Legacy reports remain unchanged; derive identity only after their exact input hashes match.
    if ($r.comparisonIdentitySha256 -and ($r.comparisonIdentityVersion -ne 1 -or $r.comparisonIdentitySha256 -ne $identity)) { throw 'Report canonical identity mismatch.' }
    if ($commonIdentity -and $commonIdentity -ne $identity) { throw 'Incompatible source/question/gold/split identities.' }
    $commonIdentity = $identity
    $conditions = "$($r.threads)/$($r.batchSize)"
    if ($executionConditions -and $executionConditions -ne $conditions) { throw 'Report thread/batch conditions differ.' }
    $executionConditions = $conditions
    $configuration = "$($r.model.model)/$($r.chunkingVersion)"
    if ($configurations.ContainsKey($configuration)) { throw 'Duplicate report configuration.' }; $configurations[$configuration] = $true
    if (@($r.summary).Count -ne 2) { throw 'Incomplete report splits.' }
    foreach ($split in @('development','held_out')) {
        $rows = @($r.summary | Where-Object split -eq $split)
        $positiveCount = $(if ($split -eq 'development') { 30 } else { 70 })
        if ($rows.Count -ne 1 -or $rows[0].positiveQueries -ne $positiveCount -or $rows[0].negativeQueries -ne 5) { throw 'Report split counts mismatch.' }
    }
    foreach ($name in @('summary.csv','queries.csv','top10.csv')) { if (!(Test-Path -LiteralPath (Join-Path $file.DirectoryName $name))) { throw 'Missing report result file.' } }
    $queries = @(Import-Csv (Join-Path $file.DirectoryName 'queries.csv'))
    $top = @(Import-Csv (Join-Path $file.DirectoryName 'top10.csv'))
    if ($queries.Count -ne 110 -or @($queries.queryId | Select-Object -Unique).Count -ne 110 -or $top.Count -ne 1100) { throw 'Incomplete query results.' }
    foreach ($q in $data.questions) {
        $query = @($queries | Where-Object queryId -eq $q.id)
        $hits = @($top | Where-Object queryId -eq $q.id)
        $invalidHits = @($hits | Where-Object { $_.split -ne $q.split -or $_.answerability -ne $q.answerability -or [int]$_.rank -lt 1 -or [int]$_.rank -gt 10 })
        if ($query.Count -ne 1 -or $query[0].split -ne $q.split -or $query[0].answerability -ne $q.answerability -or $hits.Count -ne 10 -or @($hits | ForEach-Object { $_.rank } | Select-Object -Unique).Count -ne 10 -or $invalidHits.Count -gt 0) { throw "Query result identity mismatch: $($file.FullName), $($q.id)" }
        foreach ($hit in $hits) {
            if (($hit.relevant -eq 'True') -ne ($hit.unitId -in @($q.relevantUnitIds))) { throw 'Hit relevance differs from frozen gold.' }
        }
        if (@($q.relevantUnitIds).Count -gt 0) {
            $metrics = Get-RankingMetrics $hits $q.relevantUnitIds
            foreach ($k in @(5,10)) {
                if ([math]::Abs($metrics."recallAt$k" - [double]$query[0]."recallAt$k") -gt 1e-6) { throw 'Recomputed recall mismatch.' }
            }
            $rankingRows += [pscustomobject]@{model=$r.model.model; chunkingVersion=$r.chunkingVersion; queryId=$q.id; split=$q.split
                hitAt5=$metrics.hitAt5; completeAt5=$metrics.completeAt5; precisionAt5=$metrics.precisionAt5; ndcgAt5=$metrics.ndcgAt5; ndcgAt10=$metrics.ndcgAt10}
        }
        if ($r.chunkingVersion -eq 'char700-overlap64-article-v1' -and $q.id -in @('civil-2','housing-3','labor-3','privacy-3')) {
            $first = @($hits | Where-Object { [int]$_.rank -eq 1 })[0]
            $similarityRows += [pscustomobject]@{model=$r.model.model; queryId=$q.id; question=$first.query; title=$first.title; similarity=[double]$first.similarity; relevant=$first.relevant; firstRelevantRank=$query[0].firstRelevantRank}
        }
    }
    $dev = @($r.summary | Where-Object split -eq 'development')[0]
    $held = @($r.summary | Where-Object split -eq 'held_out')[0]
    $size = [int]([regex]::Match($r.chunkingVersion,'char(\d+)').Groups[1].Value)
    $records += [pscustomobject]@{ model = $r.model.model; dimensions = $r.model.dimensions; maxChars = $size
        overlapChars = 64; devRecallAt5 = $dev.recallAt5; devMrr = $dev.mrr
        devRecallAt10 = $dev.recallAt10; devInCorpusAcceptanceRate = $dev.inCorpusAcceptanceRate
        devNoAnswerFalseAcceptanceRate = $dev.noAnswerFalseAcceptanceRate
        heldOutTop1HitRate = $held.top1HitRate; heldOutRecallAt5 = $held.recallAt5
        heldOutRecallAt10 = $held.recallAt10; heldOutMrr = $held.mrr
        heldOutNoAnswerFalseAcceptanceRate = $held.noAnswerFalseAcceptanceRate
        heldOutInCorpusAcceptanceRate = $held.inCorpusAcceptanceRate
        heldOutMeanQueryEmbeddingMs = $held.meanQueryEmbeddingMs; corpusSha256 = $r.corpusSha256
        comparisonIdentitySha256 = $identity; threads = $r.threads; batchSize = $r.batchSize
        inferenceRuntime = $(if ($r.model.inferenceRuntime) { $r.model.inferenceRuntime } else { 'ollama' })
        inferenceRuntimeVersion = $(if ($r.model.inferenceRuntimeVersion) { $r.model.inferenceRuntimeVersion } else { $r.model.ollamaVersion })
        dtype = $(if ($r.model.dtype) { $r.model.dtype } else { 'not_recorded_in_legacy_report' })
        rawFloat32VectorMiB = $data.corpus.chunkCount * $r.model.dimensions * 4.0 / 1MB }
}
$records = @($records | Sort-Object model,maxChars)
$records | Export-Csv (Join-Path $ReportsRoot 'comparison.csv') -NoTypeInformation -Encoding UTF8
$rankingRows | Export-Csv (Join-Path $ReportsRoot 'ranking-queries.csv') -NoTypeInformation -Encoding UTF8
$extra = @(foreach ($group in ($rankingRows | Group-Object model,chunkingVersion,split)) {
    $row = $group.Group[0]
    $aggregate = [ordered]@{model=$row.model; chunkingVersion=$row.chunkingVersion; split=$row.split; positiveQueries=$group.Count}
    foreach ($metric in @('hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')) { $aggregate[$metric] = ($group.Group | Measure-Object -Property $metric -Average).Average }
    [pscustomobject]$aggregate
})
$extra | Export-Csv (Join-Path $ReportsRoot 'ranking-comparison.csv') -NoTypeInformation -Encoding UTF8
$similarityRows | Export-Csv (Join-Path $ReportsRoot 'similarity-examples.csv') -NoTypeInformation -Encoding UTF8
$lines = @('# Model and chunk comparison', '', 'Character-based chunks with 64-character overlap; identical source units, questions and splits.', '',
    '| Model | Dimensions | Characters | Dev Recall@5 | Dev MRR | Held-out top-1 | Held-out Recall@5 | Held-out MRR |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($r in $records) {
    $lines += '| '+$r.model+' | '+$r.dimensions+' | '+$r.maxChars+' | '+([math]::Round(100*$r.devRecallAt5,2))+'% | '+([math]::Round($r.devMrr,4))+' | '+([math]::Round(100*$r.heldOutTop1HitRate,2))+'% | '+([math]::Round(100*$r.heldOutRecallAt5,2))+'% | '+([math]::Round($r.heldOutMrr,4))+' |'
}
$lines += @('', 'Similarity examples and metric definitions: [guide](similarity-and-metrics.md). Additional Hit@5, Complete@5, Precision@5 and nDCG@5/10: [ranking comparison](ranking-comparison.md).', '', 'Use development results to choose chunk/model settings. Held-out rows are descriptive final checks, not a repeated tuning objective.', '',
    'Primary selection metric: development Recall@5. Supporting metrics: MRR, Recall@10, in-corpus acceptance and out-of-corpus false acceptance; see CSV.', '',
    'Recall ranks distinct source articles/annexes by maximum child-chunk score. No-answer rates use only five held-out negative questions and score-based thresholds, not generated answers.', '',
    'Raw similarity thresholds are fitted independently per configuration; do not compare absolute cosine scores across models.', '',
    'Query latency is one warm request per question, not a repeated load test. Character limits are not tokenizer-exact token limits.', '',
    'CSV vector size is chunk count x dimension x 4 bytes for float32 values only; excludes IDs, metadata, cache overhead and any ANN index. Cached indexing times are not model throughput measurements.', '',
    'KURE uses official Sentence Transformers/PyTorch float32; the existing Gemma/BGE runs use Ollama BF16/F16. Runtime and precision differ, so latency is not a pure model comparison. Legacy report JSON remains unchanged; unrecorded dtype is explicitly marked in CSV.')
[IO.File]::WriteAllText((Join-Path $ReportsRoot 'comparison.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$extraLines = @('# Additional ranking metrics', '', 'Positive questions only; macro averages over frozen gold source units. Binary relevance, log2(rank+1) discount; IDCG uses min(k, total gold units), including gold outside retrieved top ten.', '',
    '| Model | Chunk | Split | N | Hit@5 | Complete@5 | Precision@5 | nDCG@5 | nDCG@10 |',
    '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $extra) {
    $extraLines += '| '+$row.model+' | '+$row.chunkingVersion+' | '+$row.split+' | '+$row.positiveQueries+' | '+([math]::Round($row.hitAt5,4))+' | '+([math]::Round($row.completeAt5,4))+' | '+([math]::Round($row.precisionAt5,4))+' | '+([math]::Round($row.ndcgAt5,4))+' | '+([math]::Round($row.ndcgAt10,4))+' |'
}
$extraLines += @('', 'These are post-hoc supporting metrics, not a replacement for the predeclared development Recall@5 selection rule. Unlabelled units count as non-gold, not as independently proven irrelevant.', '', 'Definitions, similarity examples and deferred metrics: [evaluation guide](similarity-and-metrics.md).')
[IO.File]::WriteAllText((Join-Path $ReportsRoot 'ranking-comparison.md'),($extraLines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$records | Format-Table model,maxChars,devRecallAt5,heldOutRecallAt5,heldOutMrr -AutoSize
