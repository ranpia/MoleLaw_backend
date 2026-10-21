param([switch]$ValidateOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$repo=Split-Path (Split-Path $EvaluationRoot -Parent) -Parent
Set-Location $repo
$dir=Get-DataPath 'independent-validation-v1'
$baseDir=Get-DataPath 'final-validation-v1'
$work=Join-Path $ArtifactsRoot 'independent-validation-v1'
$target=Get-ReportPath 'independent-validation-v1/kure-v1'
$logs=Join-Path $ArtifactsRoot 'logs'
foreach($path in @($work,$target,$logs)){$null=New-Item -ItemType Directory -Force $path}
$stop=Join-Path $ArtifactsRoot 'control/stop-kure-independent-validation-v1.flag'
$started=$false
Start-Transcript -Path (Join-Path $logs ('kure-independent-validation-v1-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log'))
function Check-Stop {if(Test-Path -LiteralPath $stop){throw 'User stop flag found; caches retained'}}
function Save-Json($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 32)+"`n",[Text.UTF8Encoding]::new($false))}
function Assert-ReviewHashes($Manifest){
    $reviewDir=Get-ReviewPath 'independent-validation-v1'
    if($null -eq $Manifest.reviewHashes -or @($Manifest.reviewHashes.PSObject.Properties).Count -eq 0){throw 'Review hashes required'}
    foreach($entry in $Manifest.reviewHashes.PSObject.Properties){
        if($entry.Name -ne [IO.Path]::GetFileName($entry.Name) -or $entry.Name -match '[\\/]'){throw 'Review filename must be local'}
        $reviewPath=Join-Path $reviewDir $entry.Name
        if(!(Test-Path -LiteralPath $reviewPath) -or (Get-FileHash -LiteralPath $reviewPath).Hash -ne $entry.Value){throw "Frozen review hash mismatch: $($entry.Name)"}
    }
    if($null -eq $Manifest.reviewHashes.'author-freeze.json'){throw 'Author freeze hash required'}
    $authorFreeze=Get-Content -Raw -Encoding UTF8 (Join-Path $reviewDir 'author-freeze.json')|ConvertFrom-Json
    if([string]::IsNullOrWhiteSpace($authorFreeze.authoredSha256)){throw 'Authored questions SHA required'}
    $authoredPath=Join-Path $reviewDir 'authored-questions.json'
    if(!(Test-Path -LiteralPath $authoredPath) -or (Get-FileHash -LiteralPath $authoredPath).Hash -ne $authorFreeze.authoredSha256){throw 'Authored questions changed after author freeze'}
}
function Assert-Owned($Name,$Expected){
    $info=(Invoke-QdrantLab GET "collections/$Name").result
    foreach($key in $Expected.Keys){if($info.config.metadata.$key -ne $Expected[$key]){throw "Collection identity mismatch: $key"}}
    if($info.config.params.vectors.size -ne 1024 -or $info.config.params.vectors.distance -ne 'Cosine'){throw 'Vector schema mismatch'}
}
try {
    Check-Stop
    $manifestPath=Join-Path $dir 'manifest.json';$questionPath=Join-Path $dir 'questions.json'
    $manifest=Get-Content -Raw -Encoding UTF8 $manifestPath|ConvertFrom-Json
    $freezeHash=(Get-FileHash $manifestPath).Hash
    Assert-ReviewHashes $manifest
    $data=Get-Content -Raw -Encoding UTF8 $questionPath|ConvertFrom-Json
    $base=Get-Content -Raw -Encoding UTF8 (Join-Path $baseDir 'manifest.json')|ConvertFrom-Json
    $context=Get-QdrantLabContext 'kure-v1' 300
    if((Get-FileHash $questionPath).Hash -ne $manifest.questionsSha256){throw 'Frozen questions hash mismatch'}
    if((Get-FileHash (Join-Path $baseDir 'corpus.jsonl')).Hash -ne $base.corpusSha256 -or $manifest.corpusSha256 -ne $base.corpusSha256){throw 'Frozen corpus hash mismatch'}
    if($manifest.modelRevision -ne $base.model.revision -or $context.profile.revision -ne $base.model.revision){throw 'Model revision mismatch'}
    foreach($field in @('tag','dimensions','inputMode','documentFormat','queryFormat')){if($base.model.$field -ne $context.profile.$field){throw "Model profile mismatch: $field"}}
    foreach($mode in @('original','rewritten')){if($null -eq $manifest.thresholds.$mode -or [math]::Abs([double]$manifest.thresholds.$mode-[double]$base.thresholds.$mode) -gt 1e-12){throw 'Frozen threshold mismatch'}}
    $collection='molelaw_validation_kure_v1_c300_'+$base.corpusSha256.Substring(0,8).ToLowerInvariant()+'_'+$base.model.revision.Substring(0,8)
    if($manifest.collection -ne $collection){throw 'Unexpected document collection'}
    $chunks=@(foreach($line in [IO.File]::ReadLines((Join-Path $baseDir 'corpus.jsonl'))){$line|ConvertFrom-Json})
    $units=@{};foreach($chunk in $chunks){$units[$chunk.unitId]=$true}
    if(@($data.questions).Count -ne 100 -or @($data.questions.id|Select-Object -Unique).Count -ne 100){throw 'Expected 100 unique questions'}
    if([string]::IsNullOrWhiteSpace($data.id) -or [string]::IsNullOrWhiteSpace($data.referenceDate)){throw 'Dataset id/reference date required'}
    $cohorts=@('in_corpus','missing_corpus','unsupported','needs_clarification')
    foreach($q in $data.questions){
        if([string]::IsNullOrWhiteSpace($q.id) -or [string]::IsNullOrWhiteSpace($q.text) -or [string]::IsNullOrWhiteSpace($q.rewrittenText) -or [string]::IsNullOrWhiteSpace($q.reason) -or $q.cohort -notin $cohorts){throw 'Invalid question schema'}
        if($q.cohort -eq 'in_corpus'){
            if(@($q.goldUnitIds).Count -eq 0 -or @($q.goldUnitIds|Select-Object -Unique).Count -ne @($q.goldUnitIds).Count){throw 'Positive question requires unique gold units'}
            foreach($id in $q.goldUnitIds){if(!$units.ContainsKey($id)){throw "Unknown gold unit: $id"}}
        }elseif(@($q.goldUnitIds).Count -ne 0){throw 'Only in-corpus questions may have gold units'}
        if($q.cohort -eq 'missing_corpus' -and @($q.sourceUrls).Count -eq 0){throw 'Missing corpus question requires source evidence'}
    }
    if(@($data.questions|Where-Object cohort -eq 'in_corpus').Count -eq 0){throw 'No positive questions'}
    if($manifest.questionCount -ne 100 -or $data.questionCount -ne 100 -or $data.id -ne 'independent-validation-v1' -or $data.referenceDate -ne '2026-10-19'){throw 'Dataset metadata mismatch'}
    if(@($manifest.cohorts).Count -ne 4 -or @($manifest.cohorts.cohort|Select-Object -Unique).Count -ne 4){throw 'Invalid manifest cohort summary'}
    foreach($name in $cohorts){$declared=@($manifest.cohorts|Where-Object cohort -eq $name);$actual=@($data.questions|Where-Object cohort -eq $name).Count;if($declared.Count -ne 1 -or $declared[0].count -ne $actual){throw 'Manifest cohort count mismatch'}}
    if($ValidateOnly){Write-Host 'PASS: question, corpus, model, gold and threshold integrity';return}
    Initialize-QdrantLab 6333
    $docMetadata=@{owner=$script:LabOwner;kind='final-validation-v1';model_digest=$base.model.revision;dataset_sha256=$base.datasetSha256;corpus_sha256=$base.corpusSha256;freeze_sha256=(Get-FileHash (Join-Path $baseDir 'manifest.json')).Hash}
    Assert-Owned $collection $docMetadata
    if((Invoke-QdrantLab POST "collections/$collection/points/count" @{exact=$true}).result.count -ne $chunks.Count){throw 'Document collection count mismatch'}
    $running=docker ps --format '{{.Names}}'
    if($LASTEXITCODE -ne 0){throw 'Docker unavailable'}
    if('molelaw-kure-lab-kure-1' -in @($running)){throw 'KURE already running; avoid stopping another session'}
    if('molelaw-qwen3-lab' -in @($running)){throw 'Qwen running; finish other inference first'}
    if('molelaw-embedding-lab-ollama-1' -in @($running)){
        $loaded=Invoke-RestMethod http://127.0.0.1:11435/api/ps -TimeoutSec 5
        if(@($loaded.models).Count){throw 'Ollama has loaded models; unload before KURE'}
    }
    $started=$true
    docker compose -f docker/kure/compose.yml up -d --no-build
    if($LASTEXITCODE -ne 0){throw 'KURE startup failed'}
    $deadline=[datetime]::UtcNow.AddMinutes(15)
    while($true){Check-Stop;try{$runtime=Invoke-RestMethod http://127.0.0.1:11436/api/version -TimeoutSec 3;break}catch{if([datetime]::UtcNow -gt $deadline){throw 'KURE startup timeout'};Start-Sleep -Seconds 5}}
    Assert-QdrantLabRuntime $context $runtime
    $tags=Invoke-RestMethod http://127.0.0.1:11436/api/tags
    if(@($tags.models|Where-Object{$_.name -eq 'kure-v1:official' -and $_.digest -eq $base.model.revision}).Count -ne 1){throw 'Checkpoint mismatch'}
    $queryCollection='molelaw_independent_kure_v1_'+$manifest.questionsSha256.Substring(0,8).ToLowerInvariant()
    $metadata=@{owner=$script:LabOwner;kind='independent-validation-v1';model_digest=$base.model.revision;questions_sha256=$manifest.questionsSha256;corpus_sha256=$base.corpusSha256;freeze_sha256=$freezeHash}
    if(@((Invoke-QdrantLab GET 'collections').result.collections|Where-Object name -eq $queryCollection).Count -eq 0){$null=Invoke-QdrantLab PUT "collections/$queryCollection" @{vectors=@{size=1024;distance='Cosine'};metadata=$metadata}}
    Assert-Owned $queryCollection $metadata
    $cacheDir=Join-Path $work ('query-cache/'+$manifest.questionsSha256.Substring(0,16));$null=New-Item -ItemType Directory -Force $cacheDir
    $cache=[LegalDenseIndex]::new(1024)
    $cacheMeta=@{modelRevision=$base.model.revision;runtime=$runtime.version;dtype=$runtime.dtype;inputMode=$base.model.inputMode;questionsSha256=$manifest.questionsSha256}
    $metaPath=Join-Path $cacheDir 'metadata.json'
    if(Test-Path -LiteralPath $metaPath){$previous=Get-Content -Raw -Encoding UTF8 $metaPath|ConvertFrom-Json;foreach($key in $cacheMeta.Keys){if($previous.$key -ne $cacheMeta[$key]){throw 'Query cache identity mismatch'}}}
    Save-Json $metaPath $cacheMeta
    foreach($file in Get-ChildItem -LiteralPath $cacheDir -Filter '*.bin'){$cache.ReadBatch($file.FullName)}
    $queries=@(foreach($mode in @('original','rewritten')){foreach($q in $data.questions){$text=$(if($mode -eq 'original'){$q.text}else{$q.rewrittenText});$inputText=$base.model.queryFormat.Replace('{text}',$text);[pscustomobject]@{mode=$mode;question=$q;text=$text;inputText=$inputText;key=[LegalDenseIndex]::InputKey($inputText)}}})
    $pending=@{};foreach($q in $queries){if(!$cache.Contains($q.key)){$pending[$q.key]=$q.inputText}}
    $keys=@($pending.Keys|Sort-Object)
    for($offset=0;$offset -lt $keys.Count;$offset+=16){
        Check-Stop;$end=[math]::Min($offset+16,$keys.Count);$batch=[string[]]@($keys[$offset..($end-1)])
        $body=@{model='kure-v1:official';input=@($batch|ForEach-Object{$pending[$_]});truncate=$false;keep_alive='5m';options=@{num_thread=6;num_gpu=0}}|ConvertTo-Json -Depth 5 -Compress
        $response=Invoke-RestMethod -Method Post http://127.0.0.1:11436/api/embed -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 300
        if(@($response.embeddings).Count -ne $batch.Count){throw 'Embedding count mismatch'}
        $cache.WriteBatch((Join-Path $cacheDir ([guid]::NewGuid().ToString('N')+'.bin')),$batch,[single[][]]@($response.embeddings|ForEach-Object{,[single[]]$_}))
        Write-Host "Cached query vectors $end/$($keys.Count)"
    }
    $results=@();$topRows=@();$stored=@();$context.collection=$collection;$index=0
    foreach($query in $queries){
        Check-Stop;$q=$query.question;$vector=$cache.GetVector($query.key)
        $groups=@(Invoke-QdrantLabGroups $context $vector 10 $true)
        if($groups.Count -ne 10 -or @($groups.id|Select-Object -Unique).Count -ne 10){throw 'Invalid grouped results'}
        $hits=@();for($i=0;$i -lt 10;$i++){
            $hit=[pscustomobject]@{rank=$i+1;unitId=$groups[$i].id;similarity=$groups[$i].hits[0].score;relevant=($groups[$i].id -in @($q.goldUnitIds))};$hits+=$hit
            $topRows+=[pscustomobject]@{queryId=$q.id;queryMode=$query.mode;cohort=$q.cohort;rank=$hit.rank;unitId=$hit.unitId;similarity=$hit.similarity;relevant=$hit.relevant;title=$groups[$i].hits[0].payload.title}
        }
        $metrics=$null;$rr=$null
        if($q.cohort -eq 'in_corpus'){$metrics=Get-RankingMetrics $hits $q.goldUnitIds;$first=@($hits|Where-Object relevant|Select-Object -First 1);$rr=$(if($first.Count){1.0/$first[0].rank}else{0.0})}
        $threshold=[double]$manifest.thresholds.($query.mode)
        $row=[ordered]@{queryId=$q.id;queryMode=$query.mode;cohort=$q.cohort;originalText=$q.text;retrievalText=$query.text;top1Similarity=$hits[0].similarity;top1Relevant=$hits[0].relevant;accepted=($hits[0].similarity -ge $threshold);threshold=$threshold;mrrAt10=$rr}
        foreach($key in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')){$row[$key]=$metrics.$key}
        $results+=[pscustomobject]$row
        $stored+=@{id=(Get-QdrantLabPointId "$queryCollection/$($q.id)/$($query.mode)");vector=@($vector);payload=@{lab_owner=$script:LabOwner;query_id=$q.id;query_mode=$query.mode;cohort=$q.cohort;question=$q;retrieval_text=$query.text;gold_unit_ids=@($q.goldUnitIds);metrics=$row;top10=$hits;freeze_sha256=$freezeHash;document_collection=$collection}}
        if($stored.Count -eq 16){$null=Invoke-QdrantLab PUT "collections/$queryCollection/points?wait=true" @{points=$stored};$stored=@()}
        $index++;if($index%20 -eq 0){Write-Host "Evaluated $index/200 query variants"}
    }
    if($stored.Count){$null=Invoke-QdrantLab PUT "collections/$queryCollection/points?wait=true" @{points=$stored}}
    if($results.Count -ne 200 -or $topRows.Count -ne 2000 -or (Invoke-QdrantLab POST "collections/$queryCollection/points/count" @{exact=$true}).result.count -ne 200){throw 'Incomplete query results'}
    $summary=@(foreach($mode in @('original','rewritten')){foreach($cohort in $cohorts){
        $selected=@($results|Where-Object{$_.queryMode -eq $mode -and $_.cohort -eq $cohort});if(!$selected.Count){continue}
        $row=[ordered]@{queryMode=$mode;cohort=$cohort;count=$selected.Count;scoreAcceptanceRate=@($selected|Where-Object accepted).Count/[double]$selected.Count}
        foreach($key in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10','mrrAt10')){$row[$key]=$(if($cohort -eq 'in_corpus'){($selected|Measure-Object $key -Average).Average}else{$null})};[pscustomobject]$row
    }})
    if((Get-FileHash $manifestPath).Hash -ne $freezeHash -or (Get-FileHash $questionPath).Hash -ne $manifest.questionsSha256 -or (Get-FileHash (Join-Path $baseDir 'corpus.jsonl')).Hash -ne $manifest.corpusSha256){throw 'Frozen inputs changed'}
    Assert-ReviewHashes $manifest
    $probe=@((Invoke-QdrantLab POST "collections/$queryCollection/points/scroll" @{limit=1;with_payload=$true;with_vector=$true}).result.points)
    if($probe.Count -ne 1 -or $probe[0].payload.freeze_sha256 -ne $freezeHash -or @($probe[0].vector).Count -ne 1024){throw 'Query archive roundtrip failed'}
    $report=@{completedAtUtc=[datetime]::UtcNow.ToString('o');freezeSha256=$freezeHash;manifest=$manifest;runtime=$runtime;documentCollection=$collection;queryCollection=$queryCollection;summary=$summary;serviceReady=$false;scope='Independent retrieval only. No service LLM routing, answer generation or automatic acquisition evaluated. MRR@10 and frozen development thresholds.'}
    $results|Export-Csv (Join-Path $target 'queries.csv') -NoTypeInformation -Encoding UTF8
    $topRows|Export-Csv (Join-Path $target 'top10.csv') -NoTypeInformation -Encoding UTF8
    $summary|Export-Csv (Join-Path $target 'summary.csv') -NoTypeInformation -Encoding UTF8
    Save-Json (Join-Path $target 'report.json') $report
    $lines=@('# KURE-v1 독립 질문 검증','','KURE 1024차원·300자·중복 64자와 기존 문서 컬렉션을 고정했다. 독립 작성과 별도 정답 검토를 거친 100문항의 원본·정제본을 비교한다. 문서 재색인은 수행하지 않는다.','','| 질문 | 분류 | 문항 | Recall@5 | Complete@5 | MRR@10 | 점수 수용률 |','| --- | --- | ---: | ---: | ---: | ---: | ---: |')
    foreach($r in $summary){
        $recall='-';$complete='-';$mrr='-'
        if($r.cohort -eq 'in_corpus'){$recall=('{0:F2}%' -f (100*$r.recallAt5));$complete=('{0:F2}%' -f (100*$r.completeAt5));$mrr=('{0:F4}' -f $r.mrrAt10)}
        $lines+=('| {0} | {1} | {2} | {3} | {4} | {5} | {6:F2}% |' -f $r.queryMode,$r.cohort,$r.count,$recall,$complete,$mrr,(100*$r.scoreAcceptanceRate))
    }
    $lines+=@('','Recall은 정답 근거 중 찾은 비율, Complete는 근거를 모두 찾은 문항 비율이다. MRR@10은 첫 정답 순위의 역수이며 10위 밖은 0이다. in_corpus 이외에는 정답 순위 지표를 계산하지 않는다.','','점수 수용률은 기존 개발 데이터의 고정 임계값을 상위 검색 점수가 넘은 비율이다. 누락 법령·지원 범위 밖·사실관계 부족을 판단하는 서비스 LLM 정확도가 아니다. 자동 추가 수집과 답변 생성도 평가하지 않았다. 에이전트 역할 분리는 사람 또는 실제 사용자 평가와 같은 독립성을 보장하지 않는다.','','[고정 입력](../../data/manifest.json) · [선정 근거](../../../../model-comparison/review/model-selection.md)')
    [IO.File]::WriteAllText((Join-Path $target 'README.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    $summary|Format-Table queryMode,cohort,count,recallAt5,completeAt5,mrrAt10,scoreAcceptanceRate
    Write-Host 'INDEPENDENT VALIDATION COMPLETE. See reports/independent-validation-v1/kure-v1/README.md' -ForegroundColor Green
} finally {
    if($started){
        try{$body=@{model='kure-v1:official';input=@();keep_alive=0}|ConvertTo-Json;$null=Invoke-RestMethod -Method Post http://127.0.0.1:11436/api/embed -ContentType application/json -Body $body -TimeoutSec 10}catch{}
        docker compose -f docker/kure/compose.yml stop kure
    }
    Stop-Transcript
}