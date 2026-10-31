[CmdletBinding()]
param([string]$DatasetDirectory,[string]$RuntimeSnapshotPath,[string]$OutputDirectory,
    [ValidateRange(1,36)][int]$Limit=36,[int]$RerankerPort=11437,
    [string]$Model='BAAI/bge-reranker-v2-m3',
    [string]$Revision='953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
. (Join-Path $PSScriptRoot 'lib/RerankerComparison.ps1')
if(!$DatasetDirectory){$DatasetDirectory=Get-DataPath 'reranker-v1/c500-legal_transformed'}
if(!$RuntimeSnapshotPath){$RuntimeSnapshotPath=Join-Path $DatasetDirectory 'service-runtime.json'}
$manifest=Read-RerankerJson (Join-Path $DatasetDirectory 'manifest.json')
$candidatePath=Join-Path $DatasetDirectory 'candidates.jsonl'
if((Get-FileHash $candidatePath).Hash -ne $manifest.candidatesSha256){throw 'Frozen candidate bytes changed.'}
$runtime=Read-RerankerJson $RuntimeSnapshotPath
if($runtime.reranker.model -cne $Model -or $runtime.reranker.revision -cne $Revision -or !$runtime.reranker.enabled){throw 'Current-service reranker snapshot does not match requested model.'}
$current=Invoke-RerankerJson "http://127.0.0.1:$RerankerPort/api/version"
if(($current | ConvertTo-Json -Compress) -cne ($runtime.apiVersion | ConvertTo-Json -Compress)){throw 'Runtime changed after snapshot.'}
$runtimeHash=(Get-FileHash $RuntimeSnapshotPath).Hash
$runId=[datetime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
if(!$OutputDirectory){$OutputDirectory=Get-ReportPath "reranker-v1/$runId"}
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $OutputDirectory){throw 'Use a fresh report directory; previous results are preserved.'}
$null=New-Item -ItemType Directory -Path $OutputDirectory
$cacheDir=Join-Path $ArtifactsRoot ("reranker-dense50-v1/"+$manifest.candidatesSha256.Substring(0,16)+'/'+$runtimeHash.Substring(0,16))
$null=New-Item -ItemType Directory -Force -Path $cacheDir
$rows=@(foreach($line in [IO.File]::ReadLines($candidatePath)){$line | ConvertFrom-Json})
if($rows.Count -ne 36 -or @($rows.queryId | Select-Object -Unique).Count -ne 36){throw 'Expected 36 unique frozen queries.'}
$rows=@($rows | Sort-Object queryId | Select-Object -First $Limit)
$baseline=@();$reranked=@();$comparisons=@();$ranking=@();$cacheHits=0;$requests=0
$run=[ordered]@{id='reranker-dense50-v1';runId=$runId;completed=$false;datasetManifest=$manifest;
    candidatesSha256=$manifest.candidatesSha256;runtimeSha256=$runtimeHash;runtime=$runtime;model=$Model;revision=$Revision;
    candidateCount=50;expectedQueries=36;requestedQueries=$Limit;completedQueries=0;cacheHits=0;inferenceRequests=0;
    latencyPolicy='Per-query reranker HTTP wall time only; includes queue/network; baseline retrieval time not measured. Offline request deadline is 300 seconds, not a claim about Spring request SLA.';
    documentFormat='title + newline + representative dense-search chunk';thresholdApplied=$false;serviceReady=$false}
Write-RerankerJson (Join-Path $OutputDirectory 'run.json') $run
try {
    foreach($q in $rows) {
        if(@($q.candidates).Count -ne 50 -or @($q.goldUnitIds).Count -eq 0){throw 'Invalid candidate/gold count.'}
        $payload=[ordered]@{model=$Model;revision=$Revision;query=$q.retrievalText;documents=@($q.candidates | ForEach-Object {
            @{id=$_.unitId;text=([string]$_.title+"`n"+[string]$_.text)}})}
        $requestJson=$payload | ConvertTo-Json -Depth 10 -Compress
        $requestHash=[Convert]::ToBase64String([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($requestJson)))
        $cacheFile=Join-Path $cacheDir ($q.queryId+'.json')
        $cached=$false
        if(Test-Path -LiteralPath $cacheFile) {
            $saved=Read-RerankerJson $cacheFile
            if($saved.requestHash -cne $requestHash -or $saved.runtimeSha256 -ne $runtimeHash){throw 'Cached request/runtime identity mismatch.'}
            $response=$saved.response;$elapsed=[double]$saved.elapsedMs;$cacheHits++;$cached=$true
        } else {
            $watch=[Diagnostics.Stopwatch]::StartNew()
            $response=Invoke-RerankerJson "http://127.0.0.1:$RerankerPort/api/rerank" $payload
            $watch.Stop();$elapsed=$watch.Elapsed.TotalMilliseconds;$requests++
            $null=@(Get-RerankerOrder $q.candidates $response $Model $Revision)
            Write-RerankerJson $cacheFile @{requestHash=$requestHash;runtimeSha256=$runtimeHash;response=$response;elapsedMs=$elapsed}
        }
        $ordered=@(Get-RerankerOrder $q.candidates $response $Model $Revision)
        $before=Get-RerankerMetrics $q.candidates $q.goldUnitIds
        $after=Get-RerankerMetrics $ordered $q.goldUnitIds
        if($before.recallAt50 -ne $after.recallAt50 -or $before.completeAt50 -ne $after.completeAt50){throw 'Reranking changed candidate membership.'}
        $before | Add-Member NoteProperty queryId $q.queryId
        $after | Add-Member NoteProperty queryId $q.queryId
        $baseline+=$before;$reranked+=$after
        $row=[ordered]@{queryId=$q.queryId;originalText=$q.originalText;retrievalText=$q.retrievalText;
            goldCount=@($q.goldUnitIds).Count;goldFoundAt50=@($q.candidates | Where-Object {$_.unitId -in $q.goldUnitIds}).Count;
            rerankerElapsedMs=$elapsed;cacheHit=$cached}
        foreach($metric in @('hitAt5','completeAt5','recallAt5','ndcgAt5','mrrAt10','recallAt10','ndcgAt10','recallAt50')) {
            $row["baseline_$metric"]=$before.$metric;$row["reranked_$metric"]=$after.$metric;$row["delta_$metric"]=$after.$metric-$before.$metric
        }
        $comparisons+=[pscustomobject]$row
        foreach($pair in @(@{name='baseline';hits=$q.candidates},@{name='reranked';hits=$ordered})) {
            foreach($hit in $pair.hits) {
                $original=@($q.candidates | Where-Object unitId -CEQ $hit.unitId)[0]
                $ranking+=[pscustomobject]@{queryId=$q.queryId;condition=$pair.name;rank=$hit.rank;unitId=$hit.unitId;
                    relevant=($hit.unitId -in $q.goldUnitIds);denseSimilarity=$original.similarity;
                    rerankScore=$(if($pair.name -eq 'reranked'){$hit.rerankScore}else{$null});title=$original.title}
            }
        }
        $comparisons | Export-Csv (Join-Path $OutputDirectory 'queries.csv') -NoTypeInformation -Encoding UTF8
        $run.completedQueries=$comparisons.Count;$run.cacheHits=$cacheHits;$run.inferenceRequests=$requests
        Write-RerankerJson (Join-Path $OutputDirectory 'run.json') $run
        Write-Host ("[{0}/{1}] {2}: Hit@5 {3}->{4}, Complete@5 {5}->{6}; {7:N0} ms; cache={8}" -f $comparisons.Count,$Limit,$q.queryId,$before.hitAt5,$after.hitAt5,$before.completeAt5,$after.completeAt5,$elapsed,$cached)
    }
    $summary=@(foreach($pair in @(@{name='baseline';rows=$baseline},@{name='reranked';rows=$reranked})) {
        $s=[ordered]@{condition=$pair.name;count=$pair.rows.Count}
        foreach($metric in @('hitAt5','completeAt5','recallAt5','ndcgAt5','mrrAt10','recallAt10','ndcgAt10','recallAt50','hitAt50','completeAt50')) {
            $s[$metric]=($pair.rows | Measure-Object $metric -Average).Average
        }
        [pscustomobject]$s
    })
    $summary | Export-Csv (Join-Path $OutputDirectory 'summary.csv') -NoTypeInformation -Encoding UTF8
    $ranking | Export-Csv (Join-Path $OutputDirectory 'rankings.csv') -NoTypeInformation -Encoding UTF8
    $run.completed=($Limit -eq 36 -and $comparisons.Count -eq 36);$run.finishedAtUtc=[datetime]::UtcNow.ToString('o');$run.summary=$summary
    $times=@($comparisons.rerankerElapsedMs | Sort-Object)
    $run.latencyMs=@{mean=($times | Measure-Object -Average).Average;p50=$times[[math]::Ceiling($times.Count*0.5)-1];
        p95=$times[[math]::Ceiling($times.Count*0.95)-1];max=($times | Measure-Object -Maximum).Maximum}
    Write-RerankerJson (Join-Path $OutputDirectory 'run.json') $run
    $lines=@('# Dense top-50 vs current-service reranker','','Same frozen candidates, query text and gold. No embeddings, Qdrant writes, threshold filtering, or tuning.','',
        "- Completed full dataset: $($run.completed)","- Reranker: $Model / $Revision",'- Latency is reranker HTTP time, not end-to-end retrieval latency. Offline HTTP deadline is 300 seconds; production SLA is not evaluated.',
        ("- Reranker latency: mean {0:N0} ms, p50 {1:N0} ms, p95 {2:N0} ms; cached responses {3}." -f $run.latencyMs.mean,$run.latencyMs.p50,$run.latencyMs.p95,$cacheHits),'',
        '| Condition | N | Hit@5 | Complete@5 | Recall@5 | nDCG@5 | MRR@10 | Recall@50 |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
    foreach($s in $summary){$lines+=('| {0} | {1} | {2:P2} | {3:P2} | {4:P2} | {5:F4} | {6:F4} | {7:P2} |' -f $s.condition,$s.count,$s.hitAt5,$s.completeAt5,$s.recallAt5,$s.ndcgAt5,$s.mrrAt10,$s.recallAt50)}
    $lines+=@('','## Paired changes','', '| Metric | Improved queries | Regressed queries | Unchanged |','| --- | ---: | ---: | ---: |')
    foreach($m in @('hitAt5','completeAt5','recallAt5','ndcgAt5')) {
        $up=@($comparisons | Where-Object {$_.$("delta_$m") -gt 1e-12}).Count
        $down=@($comparisons | Where-Object {$_.$("delta_$m") -lt -1e-12}).Count
        $lines+="| $m | $up | $down | $($comparisons.Count-$up-$down) |"
    }
    $lines+=@('','Exploratory results on the previously explored 36 in-corpus questions, not an independent holdout or answer-quality evaluation. Gold coverage may be incomplete. Recall@50 cannot improve when reranking the same 50 candidates. Inspect per-query regressions before changing the service.',
        '', '[Per-query changes](queries.csv) | [All rankings](rankings.csv) | [Identity and runtime](run.json)')
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'README.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    $summary | Format-Table condition,count,hitAt5,completeAt5,recallAt5,ndcgAt5,mrrAt10,recallAt50
    Write-Host "RESULTS: $OutputDirectory"
} catch {
    $run.failureType=$_.Exception.GetType().Name
    Write-RerankerJson (Join-Path $OutputDirectory 'run.json') $run
    throw
}
