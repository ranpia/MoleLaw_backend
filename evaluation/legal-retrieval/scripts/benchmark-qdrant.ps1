[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('embeddinggemma','bge-m3','kure-v1')][string]$Model,
    [ValidateRange(3,10)][int]$Repeats=3,[int]$QdrantPort=6333,[int]$OllamaPort=0)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
. (Join-Path $PSScriptRoot 'lib/QueryRewrites.ps1')
Initialize-QdrantLab $QdrantPort
$context=Get-QdrantLabContext $Model
if (!$OllamaPort) { $OllamaPort=$(if ($Model -eq 'kure-v1') {11436} else {11435}) }
$info=(Invoke-QdrantLab GET "collections/$($context.collection)").result
Assert-QdrantLabCollection $context $info
$count=(Invoke-QdrantLab POST "collections/$($context.collection)/points/count" @{exact=$true}).result.count
if ($count -ne 6031 -or $info.status -ne 'green' -or $info.indexed_vectors_count -ne $count) { throw 'Collection must be fully indexed and green.' }
$datasetPath=Join-Path $context.directory 'questions.json'
$rewritePath=Join-Path $ReviewRoot 'query-rewrites-v1.json'
$rewriteHash=(Get-FileHash $rewritePath).Hash
$rewrites=Read-QueryRewrites $rewritePath $context.data (Get-FileHash $datasetPath).Hash (Get-ComparisonIdentity $context.directory)
$version=Invoke-RestMethod "http://127.0.0.1:$OllamaPort/api/version" -TimeoutSec 10
Assert-QdrantLabRuntime $context $version
$queries=@(foreach ($mode in @('original','rewritten')) {
    foreach ($q in $context.data.questions) {
        $text=$(if ($mode -eq 'original') { $q.text } else { $rewrites[$q.id].rewrittenText })
        [pscustomobject]@{mode=$mode;question=$q;text=$text;key=[LegalDenseIndex]::InputKey($context.profile.queryFormat.Replace('{text}',$text))}
    }
})
$cacheDirectory=Join-Path $ArtifactsRoot ('qdrant-query-cache/'+$Model+'-'+$context.baseline.model.modelDigest.Substring(0,16)+'-'+$context.profile.inputMode)
$null=New-Item -ItemType Directory -Force $cacheDirectory
$cacheMetadataPath=Join-Path $cacheDirectory 'metadata.json'
$cacheMetadata=@{modelDigest=$context.baseline.model.modelDigest;dimensions=$context.profile.dimensions;queryFormat=$context.profile.queryFormat;ollamaVersion=$version.version}
if ($context.profile.runtime -eq 'sentence-transformers') {
    $cacheMetadata.Remove('ollamaVersion')
    $cacheMetadata.inferenceRuntimeVersion=$version.version;$cacheMetadata.dtype=$version.dtype;$cacheMetadata.device=$version.device
}
if (Test-Path -LiteralPath $cacheMetadataPath) {
    $previous=Get-Content -Raw -Encoding UTF8 $cacheMetadataPath | ConvertFrom-Json
    foreach ($field in $cacheMetadata.Keys) { if ($previous.$field -ne $cacheMetadata[$field]) { throw 'Query vector cache metadata mismatch.' } }
}
[IO.File]::WriteAllText($cacheMetadataPath,($cacheMetadata | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$queryCache=[LegalDenseIndex]::new([int]$context.profile.dimensions)
foreach ($file in Get-ChildItem -LiteralPath $cacheDirectory -Filter '*.bin' | Sort-Object Name) { $queryCache.ReadBatch($file.FullName) }
$pending=@{}; $generated=0
try {
    foreach ($query in $queries) {
        if ($queryCache.Contains($query.key) -or $pending.ContainsKey($query.key)) { continue }
        $pending[$query.key]=Get-QdrantLabQueryVector $context $query.text $OllamaPort '5m'
        $generated++
        if ($pending.Count -eq 16) {
            $keys=[string[]]@($pending.Keys)
            $queryCache.WriteBatch((Join-Path $cacheDirectory ([guid]::NewGuid().ToString('N')+'.bin')),$keys,[single[][]]@($keys | ForEach-Object { ,$pending[$_] }))
            $pending.Clear()
            Write-Host "Cached $generated new query vectors: $Model"
        }
    }
    if ($pending.Count -gt 0) {
        $keys=[string[]]@($pending.Keys)
        $queryCache.WriteBatch((Join-Path $cacheDirectory ([guid]::NewGuid().ToString('N')+'.bin')),$keys,[single[][]]@($keys | ForEach-Object { ,$pending[$_] }))
    }
} finally {
    $body=@{model=$context.profile.tag;input=@();keep_alive=0}
    $null=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$OllamaPort/api/embed" -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 30
}
$documents=Read-QdrantLabCache $context
$local=[LegalDenseIndex]::new([int]$context.profile.dimensions)
foreach ($chunk in (Get-QdrantLabChunks $context)) { $local.Add($chunk.id,$documents.GetVector((Get-QdrantLabDocumentKey $context $chunk)));$local.SetUnit($chunk.id,$chunk.unitId) }
$samples=@();$queryRows=@();$topRows=@()
$first=$queryCache.GetVector($queries[0].key)
$null=Invoke-QdrantLabGroups $context $first 10 $true
$null=Invoke-QdrantLabGroups $context $first 10 $false
$index=0
foreach ($query in $queries) {
    $q=$query.question;$vector=$queryCache.GetVector($query.key)
    $clock=[Diagnostics.Stopwatch]::StartNew();$ranked=@($local.Rank($vector,10));$clock.Stop()
    $localMs=$clock.Elapsed.TotalMilliseconds
    $outputs=@{}
    for ($repeat=0;$repeat -lt $Repeats;$repeat++) {
        $order=$(if (($index+$repeat)%2 -eq 0) { @('exact','ann') } else { @('ann','exact') })
        foreach ($engine in $order) {
            $clock=[Diagnostics.Stopwatch]::StartNew()
            $response=Invoke-QdrantLabGroups $context $vector 10 ($engine -eq 'exact') '' $true
            $clock.Stop()
            $groups=@($response.groups)
            if ($groups.Count -ne 10 -or @($groups.id | Select-Object -Unique).Count -ne 10) { throw 'Invalid grouped query response.' }
            $samples+=[pscustomobject]@{model=$Model;queryMode=$query.mode;queryId=$q.id;split=$q.split;engine=$engine;repeat=$repeat+1;clientMs=$clock.Elapsed.TotalMilliseconds;serverMs=$response.serverMs}
            if ($repeat -eq 0) { $outputs[$engine]=$groups }
        }
    }
    $exact=$outputs.exact;$ann=$outputs.ann
    $scoreError=0.0
    for ($i=0;$i -lt 10;$i++) { $scoreError=[math]::Max($scoreError,[math]::Abs($exact[$i].hits[0].score-$ranked[$i].Score)) }
    if ($scoreError -gt 1e-5) { throw "Qdrant exact differs from local cosine: $($q.id)" }
    $overlap=@($ann | Where-Object { $_.id -in @($exact.id) }).Count/10.0
    $localOverlap=@($exact | Where-Object { $_.id -in @($ranked.UnitId) }).Count/10.0
    foreach ($engine in @('local','exact','ann')) {
        $hits=@()
        for ($i=0;$i -lt 10;$i++) {
            if ($engine -eq 'local') { $unit=$ranked[$i].UnitId;$score=$ranked[$i].Score } else { $unit=$outputs[$engine][$i].id;$score=$outputs[$engine][$i].hits[0].score }
            $hit=[pscustomobject]@{rank=$i+1;unitId=$unit;similarity=$score;relevant=($unit -in $q.relevantUnitIds)}
            $hits+=$hit
            $topRows+=[pscustomobject]@{model=$Model;queryMode=$query.mode;queryId=$q.id;engine=$engine;rank=$hit.rank;unitId=$unit;similarity=$score;relevant=$hit.relevant}
        }
        $metric=$null;$rr=$null
        if ($q.answerability -eq 'in_corpus') {
            $metric=Get-RankingMetrics $hits $q.relevantUnitIds
            $gold=@($hits | Where-Object relevant)
            $rr=$(if ($gold.Count) { 1.0/$gold[0].rank } else { 0.0 })
        }
        $queryRows+=[pscustomobject]@{model=$Model;queryMode=$query.mode;queryId=$q.id;split=$q.split;answerability=$q.answerability;engine=$engine
            top1Similarity=$hits[0].similarity;top1Relevant=$hits[0].relevant;recallAt5=$metric.recallAt5;recallAt10=$metric.recallAt10
            hitAt5=$metric.hitAt5;completeAt5=$metric.completeAt5;precisionAt5=$metric.precisionAt5;ndcgAt5=$metric.ndcgAt5;ndcgAt10=$metric.ndcgAt10;mrrAt10=$rr
            annExactTop10Overlap=$overlap;localExactTop10Overlap=$localOverlap;exactMaxScoreError=$scoreError;localInProcessMs=$localMs}
    }
    $index++
    if ($index%20 -eq 0 -or $index -eq $queries.Count) { Write-Host "Benchmarked $index/$($queries.Count) questions: $Model" }
}
if ((Get-FileHash $rewritePath).Hash -ne $rewriteHash) { throw 'Rewrites changed during benchmark.' }
$after=(Invoke-QdrantLab GET "collections/$($context.collection)").result
Assert-QdrantLabCollection $context $after
if ((Invoke-QdrantLab POST "collections/$($context.collection)/points/count" @{exact=$true}).result.count -ne $count) { throw 'Point count changed.' }
$directory=Join-Path $ReportsRoot "qdrant-lab/benchmark/$Model"
$null=New-Item -ItemType Directory -Force $directory
$samples | Export-Csv (Join-Path $directory 'latency.csv') -NoTypeInformation -Encoding UTF8
$queryRows | Export-Csv (Join-Path $directory 'queries.csv') -NoTypeInformation -Encoding UTF8
$topRows | Export-Csv (Join-Path $directory 'top10.csv') -NoTypeInformation -Encoding UTF8
$report=@{timestampUtc=[datetime]::UtcNow.ToString('o');model=$Model;metadata=$context.metadata;collection=$context.collection;points=$count
    qdrantVersion=(Invoke-RestMethod $script:LabUri -TimeoutSec 10).version;ollamaVersion=$version.version;queryRewritesSha256=$rewriteHash
    questionDatasetSha256=(Get-FileHash $datasetPath).Hash;repeats=$Repeats;hnswEf=128;newQueryVectors=$generated
    latencyScope='Sequential warm HTTP grouped queries; embedding excluded; 3+ repeated samples per question. Local scan is in-process and not HTTP-comparable.'
    qualityScope='First repetition only; positive-query macro averages; MRR@10 is truncated and differs from historical full MRR. ANN means exact=false, not proof HNSW was selected.'}
if ($context.profile.runtime -eq 'sentence-transformers') {
    $report.Remove('ollamaVersion')
    $report.inferenceRuntimeVersion=$version.version;$report.dtype=$version.dtype;$report.device=$version.device
}
[IO.File]::WriteAllText((Join-Path $directory 'report.json'),($report | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
Write-Host "PASS: $Model, 220 query variants, 660 quality rows, $($samples.Count) latency samples; collection unchanged."
