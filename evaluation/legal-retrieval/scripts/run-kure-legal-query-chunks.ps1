param([switch]$ValidateOnly,[switch]$CheckQdrantOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$repo=Split-Path (Split-Path $EvaluationRoot -Parent) -Parent;Set-Location $repo
$dir=Join-Path $DataRoot 'legal-query-chunks-v1';$target=Join-Path $ReportsRoot 'legal-query-chunks-v1/kure-v1'
$stop=Join-Path $ArtifactsRoot 'control/stop-kure-legal-query-chunks-v1.flag'
function Check-Stop {if(Test-Path -LiteralPath $stop){throw 'User stop requested; caches and completed size reports retained'}}
function Save-Json($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 32)+"`n",[Text.UTF8Encoding]::new($false))}
function Assert-Owned($Name,$Metadata){$info=(Invoke-QdrantLab GET "collections/$Name").result;foreach($k in $Metadata.Keys){if($info.config.metadata.$k -ne $Metadata[$k]){throw "Collection identity mismatch: $Name/$k"}};if($info.config.params.vectors.size -ne 1024 -or $info.config.params.vectors.distance -ne 'Cosine'){throw 'Collection vector schema mismatch'}}
function Assert-Frozen {
 if((Get-FileHash (Join-Path $dir 'manifest.json')).Hash -ne $freeze){throw 'Chunk manifest changed'}
 foreach($entry in $frozenFiles.GetEnumerator()){if((Get-FileHash $entry.Key).Hash -ne $entry.Value){throw "Frozen input changed: $($entry.Key)"}}
}
function Convert-Row($r,[int]$Size){
 $v=[ordered]@{maxChars=$Size};foreach($p in $r.PSObject.Properties){$v[$p.Name]=$p.Value}
 foreach($k in @('accepted','top1Relevant')){if($v[$k] -isnot [bool]){$v[$k]=[bool]::Parse([string]$v[$k])}}
 foreach($k in @('top1Similarity','threshold','mrrAt10','recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')){$v[$k]=$(if([string]::IsNullOrWhiteSpace([string]$v[$k])){$null}else{[double]::Parse([string]$v[$k],[Globalization.CultureInfo]::InvariantCulture)})}
 [pscustomobject]$v
}
function Summarize($Rows){foreach($size in @($Rows.maxChars|Sort-Object -Unique)){foreach($mode in @('original','rewritten','legal_transformed')){foreach($cohort in @('in_corpus','missing_corpus','unsupported','needs_clarification')){$selected=@($Rows|Where-Object {$_.maxChars -eq $size -and $_.queryMode -eq $mode -and $_.cohort -eq $cohort});if(!$selected.Count){continue};$r=[ordered]@{maxChars=$size;queryMode=$mode;cohort=$cohort;count=$selected.Count;scoreAcceptanceRate=@($selected|Where-Object accepted).Count/[double]$selected.Count};foreach($k in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10','mrrAt10')){$r[$k]=$(if($cohort -eq 'in_corpus'){($selected|Measure-Object $k -Average).Average}else{$null})};[pscustomobject]$r}}}}
function Read-Queries($Name,$Metadata,$Modes){
 Assert-Owned $Name $Metadata
 $expected=$Modes.Count*100;if((Invoke-QdrantLab POST "collections/$Name/points/count" @{exact=$true}).result.count -ne $expected){throw 'Source query count mismatch'}
 $offset=$null;$seen=@{}
 do{$body=@{limit=100;with_payload=$true;with_vector=$true};if($null -ne $offset){$body.offset=$offset};$page=(Invoke-QdrantLab POST "collections/$Name/points/scroll" $body).result
 foreach($p in $page.points){$mode=$p.payload.query_mode;$id=$p.payload.query_id;$key="$id/$mode";if($mode -notin $Modes -or !$baselineMap.ContainsKey($key) -or $seen.ContainsKey($key)){throw 'Source query identity mismatch'};$seen[$key]=$true;$row=$baselineMap[$key]
 if($p.payload.freeze_sha256 -ne $Metadata.freeze_sha256 -or $p.payload.retrieval_text -cne $row.retrievalText -or (@($p.payload.gold_unit_ids|Sort-Object) -join '|') -cne (@($questionMap[$id].goldUnitIds|Sort-Object) -join '|')){throw 'Source query payload mismatch'}
 $queryVectors.Add($key,[single[]]$p.vector)
 };$offset=$page.next_page_offset}while($null -ne $offset)
 if($seen.Count -ne $expected){throw 'Incomplete source query vectors'}
}
& (Join-Path $PSScriptRoot 'prepare-legal-query-chunks.ps1') -ValidateOnly
if(!$?){throw 'Chunk dataset validation failed'}
$m=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'manifest.json')|ConvertFrom-Json;$freeze=(Get-FileHash (Join-Path $dir 'manifest.json')).Hash
$base=Get-Content -Raw -Encoding UTF8 (Join-Path $DataRoot 'final-validation-v1/manifest.json')|ConvertFrom-Json
$transformManifestPath=Join-Path $DataRoot 'legal-query-transform-v1/manifest.json';$tm=Get-Content -Raw -Encoding UTF8 $transformManifestPath|ConvertFrom-Json
$questionPath=Join-Path $DataRoot 'independent-validation-v1/questions.json';$data=Get-Content -Raw -Encoding UTF8 $questionPath|ConvertFrom-Json
$baselineDir=Join-Path $ReportsRoot 'legal-query-transform-v1/kure-v1';$br=Get-Content -Raw -Encoding UTF8 (Join-Path $baselineDir 'report.json')|ConvertFrom-Json
$context=Get-QdrantLabContext 'kure-v1' 300
foreach($field in @('tag','dimensions','inputMode','documentFormat','queryFormat','revision')){if($context.profile.$field -ne $base.model.$field){throw "Pinned model profile mismatch: $field"}}
$indManifestPath=Join-Path $DataRoot 'independent-validation-v1/manifest.json'
if((Get-FileHash $indManifestPath).Hash -ne $tm.baselineManifestSha256){throw 'Original query manifest changed'}
if($m.modelRevision -ne $context.profile.revision -or $m.dimensions -ne 1024 -or $m.overlapChars -ne 64 -or $m.questionCount -ne 100 -or (@($m.sizes.maxChars|Sort-Object) -join ',') -ne '500,700'){throw 'Experiment model/sizes mismatch'}
$frozenFiles=@{};foreach($p in @((Join-Path $dir 'manifest.json'),$transformManifestPath,$questionPath,(Join-Path $ReviewRoot 'legal-query-transform-v1/transforms.json'),(Join-Path $ReviewRoot 'legal-query-transform-v1/audit.json'),(Join-Path $baselineDir 'report.json'),(Join-Path $baselineDir 'queries.csv'),(Join-Path $baselineDir 'top10.csv'))){$frozenFiles[$p]=(Get-FileHash $p).Hash}
foreach($s in $m.sizes){$p=Join-Path $dir "char$($s.maxChars)/corpus.jsonl";if((Get-FileHash $p).Hash -ne $s.corpusSha256){throw 'Size corpus hash mismatch'};$frozenFiles[$p]=$s.corpusSha256}
$questionMap=@{};foreach($q in $data.questions){$questionMap[$q.id]=$q}
$baselineRows=@(Import-Csv (Join-Path $baselineDir 'queries.csv')|ForEach-Object {Convert-Row $_ 300})
$baselineTops=@(Import-Csv (Join-Path $baselineDir 'top10.csv')|ForEach-Object {$_|Add-Member -NotePropertyName maxChars -NotePropertyValue 300 -PassThru})
if($baselineRows.Count -ne 300 -or $baselineTops.Count -ne 3000 -or $br.freezeSha256 -ne (Get-FileHash $transformManifestPath).Hash){throw 'Baseline 300 provenance mismatch'}
$baselineMap=@{};foreach($r in $baselineRows){$key="$($r.queryId)/$($r.queryMode)";if($baselineMap.ContainsKey($key) -or !$questionMap.ContainsKey($r.queryId) -or $r.originalText -cne $questionMap[$r.queryId].text -or $r.cohort -ne $questionMap[$r.queryId].cohort){throw 'Baseline question mismatch'};if($r.accepted -ne ($r.top1Similarity -ge $r.threshold)){throw 'Baseline score acceptance mismatch'};$baselineMap[$key]=$r}
$recomputed=@(Summarize $baselineRows);foreach($r in $recomputed){$old=@($br.summary|Where-Object {$_.queryMode -eq $r.queryMode -and $_.cohort -eq $r.cohort});if($old.Count -ne 1 -or $old[0].count -ne $r.count){throw 'Baseline summary counts mismatch'};foreach($k in @('scoreAcceptanceRate','recallAt5','completeAt5','hitAt5','mrrAt10')){if([math]::Abs([double]$old[0].$k-[double]$r.$k) -gt 1e-12){throw 'Baseline summary metrics mismatch'}}}
Assert-Frozen
if($ValidateOnly){Write-Host 'PASS: frozen 500/700 corpus, same questions/transforms/gold and 300 baseline; no model calls';return}
$logs=Join-Path $ArtifactsRoot 'logs';foreach($p in @($target,$logs)){$null=New-Item -ItemType Directory -Force $p}
Start-Transcript -Path (Join-Path $logs ('kure-legal-query-chunks-v1-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log'))
$started=$false
try{
 Check-Stop;Initialize-QdrantLab 6333
 $queryVectors=[LegalDenseIndex]::new(1024)
 $frozenFiles[$indManifestPath]=$tm.baselineManifestSha256
 $frozenFiles[(Join-Path $DataRoot 'final-validation-v1/corpus.jsonl')]=$m.sourceCorpusSha256
 $ir=Get-Content -Raw -Encoding UTF8 (Join-Path $ReportsRoot 'independent-validation-v1/kure-v1/report.json')|ConvertFrom-Json
 if($ir.freezeSha256 -ne $tm.baselineManifestSha256 -or $ir.manifest.questionsSha256 -ne $m.questionsSha256 -or $ir.documentCollection -ne $br.documentCollection){throw 'Original query report provenance mismatch'}
 Read-Queries $ir.queryCollection @{owner=$script:LabOwner;kind='independent-validation-v1';model_digest=$m.modelRevision;questions_sha256=$m.questionsSha256;corpus_sha256=$m.sourceCorpusSha256;freeze_sha256=$ir.freezeSha256} @('original','rewritten')
 Read-Queries $br.queryCollection @{owner=$script:LabOwner;kind='legal-query-transform-v1';model_digest=$m.modelRevision;transforms_sha256=$m.transformsSha256;corpus_sha256=$m.sourceCorpusSha256;freeze_sha256=$br.freezeSha256} @('legal_transformed')
 if($queryVectors.Count -ne 300){throw 'Expected 300 reused query vectors'}
 $docs=Read-QdrantLabCache $context;$docCache=Join-Path $ResultsRoot ('shared-cache/kure-v1-'+$m.modelRevision.Substring(0,16)+'-'+$context.profile.inputMode)
 if($CheckQdrantOnly){
  foreach($s in $m.sizes){$unique=@{};foreach($line in [IO.File]::ReadLines((Join-Path $dir "char$($s.maxChars)/corpus.jsonl"))){$c=$line|ConvertFrom-Json;$key=Get-QdrantLabDocumentKey $context $c;$unique[$key]=$true};$missingCount=@($unique.Keys|Where-Object {!$docs.Contains($_)}).Count;Write-Host "char$($s.maxChars): unique document inputs $($unique.Count), missing cached vectors $missingCount"}
  Write-Host 'PASS: 300 Qdrant query vectors/provenance and shared document cache; no inference';return
 }
 $running=@(docker ps --format '{{.Names}}');if($LASTEXITCODE -ne 0){throw 'Docker unavailable'}
 if('molelaw-kure-lab-kure-1' -in $running -or 'molelaw-qwen3-lab' -in $running){throw 'Another inference session is active'}
 if('molelaw-embedding-lab-ollama-1' -in $running){$loaded=Invoke-RestMethod http://127.0.0.1:11435/api/ps -TimeoutSec 5;if(@($loaded.models).Count){throw 'Ollama has loaded models'}}
 $started=$true;docker compose -f docker/kure/compose.yml up -d --no-build;if($LASTEXITCODE -ne 0){throw 'KURE startup failed'}
 $deadline=[datetime]::UtcNow.AddMinutes(15);while($true){Check-Stop;try{$runtime=Invoke-RestMethod http://127.0.0.1:11436/api/version -TimeoutSec 3;break}catch{if([datetime]::UtcNow -gt $deadline){throw 'KURE startup timeout'};Start-Sleep -Seconds 5}}
 Assert-QdrantLabRuntime $context $runtime;foreach($k in @('version','dtype','revision')){if($runtime.$k -ne $br.runtime.$k){throw 'Runtime changed'}}
 $allRows=@($baselineRows);$allTop=@($baselineTops);$completed=@(300)
 foreach($s in $m.sizes|Sort-Object maxChars){
  Check-Stop;Assert-Frozen;$size=[int]$s.maxChars;$chunks=@(foreach($line in [IO.File]::ReadLines((Join-Path $dir "char$size/corpus.jsonl"))){$line|ConvertFrom-Json})
  $texts=@{};$keysByChunk=@{};foreach($c in $chunks){$text=$context.profile.documentFormat.Replace('{title}',$c.title).Replace('{text}',$c.text);$key=[LegalDenseIndex]::InputKey($text);$texts[$key]=$text;$keysByChunk[$c.id]=$key}
  $missing=@($texts.Keys|Where-Object {!$docs.Contains($_)}|Sort-Object @{e={$texts[$_].Length}},@{e={$_}})
  Write-Host "char$size : $($chunks.Count) document chunks; new embeddings $($missing.Count); reused query vectors 300. CPU6/RAM8GB."
  for($offset=0;$offset -lt $missing.Count;$offset+=16){Check-Stop;Assert-Frozen;$end=[math]::Min($offset+16,$missing.Count);$keys=[string[]]@($missing[$offset..($end-1)]);$body=@{model='kure-v1:official';input=@($keys|ForEach-Object {$texts[$_]});truncate=$false;keep_alive='5m';options=@{num_thread=6;num_gpu=0}}|ConvertTo-Json -Depth 6 -Compress
   $response=Invoke-RestMethod -Method Post http://127.0.0.1:11436/api/embed -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 300
   if(@($response.embeddings).Count -ne $keys.Count){throw 'Embedding batch count mismatch'}
   $docs.WriteBatch((Join-Path $docCache ("legal-chunks-$size-"+[guid]::NewGuid().ToString('N')+'.bin')),$keys,[single[][]]@($response.embeddings|ForEach-Object {,[single[]]$_}));Write-Host "char$size saved vectors $end/$($missing.Count)"
  }
  $collection=$s.collection;$qc=$collection+'_queries';$metadata=@{owner=$script:LabOwner;kind='legal-query-chunks-v1';model_digest=$m.modelRevision;corpus_sha256=$s.corpusSha256;source_units_sha256=$m.sourceUnitsSha256;freeze_sha256=$freeze;max_chars=$size}
  foreach($name in @($collection,$qc)){if(@((Invoke-QdrantLab GET 'collections').result.collections|Where-Object name -eq $name).Count -eq 0){$null=Invoke-QdrantLab PUT "collections/$name" @{vectors=@{size=1024;distance='Cosine'};metadata=$metadata}};Assert-Owned $name $metadata}
  foreach($field in @('unit_id','law_id')){$null=Invoke-QdrantLab PUT "collections/$collection/index?wait=true" @{field_name=$field;field_schema='keyword'}}
  for($offset=0;$offset -lt $chunks.Count;$offset+=64){Check-Stop;$end=[math]::Min($offset+64,$chunks.Count);$points=@(foreach($c in $chunks[$offset..($end-1)]){@{id=(Get-QdrantLabPointId $c.id);vector=@($docs.GetVector($keysByChunk[$c.id]));payload=@{lab_owner=$script:LabOwner;chunk_id=$c.id;unit_id=$c.unitId;law_id=$c.lawId;title=$c.title;text=$c.text;start_char=$c.startChar;end_char=$c.endChar;source_url=$c.sourceUrl;source_sha256=$c.sourceSha256;corpus_sha256=$s.corpusSha256}}});$null=Invoke-QdrantLab PUT "collections/$collection/points?wait=true" @{points=$points};if($offset%640 -eq 0){Write-Host "char$size Qdrant docs $end/$($chunks.Count)"}}
  if((Invoke-QdrantLab POST "collections/$collection/points/count" @{exact=$true}).result.count -ne $s.chunkCount){throw 'Document count mismatch'}
  $rows=@();$top=@();$pending=@();$context.collection=$collection;$i=0
  foreach($old in $baselineRows){Check-Stop;$q=$questionMap[$old.queryId];$vector=$queryVectors.GetVector("$($old.queryId)/$($old.queryMode)");$groups=@(Invoke-QdrantLabGroups $context $vector 10 $true);if($groups.Count -ne 10 -or @($groups.id|Select-Object -Unique).Count -ne 10){throw 'Invalid grouped ranking'}
   $hits=@();for($j=0;$j -lt 10;$j++){$h=[pscustomobject]@{rank=$j+1;unitId=$groups[$j].id;similarity=$groups[$j].hits[0].score;relevant=($groups[$j].id -in @($q.goldUnitIds))};$hits+=$h;$top+=[pscustomobject]@{maxChars=$size;queryId=$q.id;queryMode=$old.queryMode;cohort=$q.cohort;rank=$h.rank;unitId=$h.unitId;similarity=$h.similarity;relevant=$h.relevant;title=$groups[$j].hits[0].payload.title}}
   $metric=$null;$rr=$null;if($q.cohort -eq 'in_corpus'){$metric=Get-RankingMetrics $hits $q.goldUnitIds;$first=@($hits|Where-Object relevant|Select-Object -First 1);$rr=$(if($first.Count){1.0/$first[0].rank}else{0.0})}
   $row=[ordered]@{maxChars=$size;queryId=$q.id;queryMode=$old.queryMode;cohort=$q.cohort;originalText=$q.text;retrievalText=$old.retrievalText;top1Similarity=$hits[0].similarity;top1Relevant=$hits[0].relevant;accepted=($hits[0].similarity -ge $old.threshold);threshold=$old.threshold;mrrAt10=$rr};foreach($k in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')){$row[$k]=$metric.$k};$rows+=[pscustomobject]$row
   $pending+=@{id=(Get-QdrantLabPointId "$qc/$($q.id)/$($old.queryMode)");vector=@($vector);payload=@{query_id=$q.id;query_mode=$old.queryMode;max_chars=$size;cohort=$q.cohort;question=$q;retrieval_text=$old.retrievalText;gold_unit_ids=@($q.goldUnitIds);top10=$hits;metrics=$row;freeze_sha256=$freeze;document_collection=$collection}}
   if($pending.Count -eq 16){$null=Invoke-QdrantLab PUT "collections/$qc/points?wait=true" @{points=$pending};$pending=@()};$i++;if($i%30 -eq 0){Write-Host "char$size queries $i/300"}
  }
  if($pending.Count){$null=Invoke-QdrantLab PUT "collections/$qc/points?wait=true" @{points=$pending}}
  if($rows.Count -ne 300 -or $top.Count -ne 3000 -or (Invoke-QdrantLab POST "collections/$qc/points/count" @{exact=$true}).result.count -ne 300){throw 'Incomplete size evaluation'}
  Assert-Frozen;$sum=@(Summarize $rows);$out=Join-Path $target "char$size";$null=New-Item -ItemType Directory -Force $out
  $rows|Export-Csv (Join-Path $out 'queries.csv') -NoTypeInformation -Encoding UTF8;$top|Export-Csv (Join-Path $out 'top10.csv') -NoTypeInformation -Encoding UTF8;$sum|Export-Csv (Join-Path $out 'summary.csv') -NoTypeInformation -Encoding UTF8
  Save-Json (Join-Path $out 'report.json') @{completedAtUtc=[datetime]::UtcNow.ToString('o');freezeSha256=$freeze;manifest=$m;maxChars=$size;runtime=$runtime;documentCollection=$collection;queryCollection=$qc;newDocumentInputs=$missing.Count;summary=$sum;serviceReady=$false}
  $allRows+=@($rows);$allTop+=@($top);$completed+=@($size);Write-Host "CHAR$size COMPLETE; reports saved before next size." -ForegroundColor Green
 }
 if($allRows.Count -ne 900 -or $allTop.Count -ne 9000){throw 'Incomplete combined comparison'}
 $summary=@(Summarize $allRows);$summary|Export-Csv (Join-Path $target 'comparison.csv') -NoTypeInformation -Encoding UTF8;$allRows|Export-Csv (Join-Path $target 'queries.csv') -NoTypeInformation -Encoding UTF8;$allTop|Export-Csv (Join-Path $target 'top10.csv') -NoTypeInformation -Encoding UTF8
 Save-Json (Join-Path $target 'report.json') @{completedAtUtc=[datetime]::UtcNow.ToString('o');freezeSha256=$freeze;manifest=$m;completedSizes=$completed;runtime=$runtime;summary=$summary;serviceReady=$false;policy='Exploratory chunk-size comparison; same questions, gold, query vectors, source units and per-mode diagnostic thresholds; no service LLM or answer evaluation'}
 $lines=@('# KURE 300·500·700자와 질문 변환 비교','','같은 원문 6,527개 단위·질문 100개·세 가지 입력·질문 벡터를 고정했다. 기존 300자 결과는 재사용하고 500·700자를 순차 실행했다. 중복은 64자이며 기존/추가 원문 각각의 기존 분할 규칙을 유지했다.','','| 문자 | 질의 | Recall@5 | Hit@5 | Complete@5 | MRR@10 |','| ---: | --- | ---: | ---: | ---: | ---: |')
 foreach($r in $summary|Where-Object cohort -eq 'in_corpus'|Sort-Object maxChars,queryMode){$lines+=('| {0} | {1} | {2:F2}% | {3:F2}% | {4:F2}% | {5:F4} |' -f $r.maxChars,$r.queryMode,(100*$r.recallAt5),(100*$r.hitAt5),(100*$r.completeAt5),$r.mrrAt10)}
 $lines+=@('','코퍼스 내 36문항 기준이다. 그 외 64문항은 별도 점수 수용률로 comparison.csv에 기록했다. 기존 고정 정답 외 대체 근거가 있을 수 있다. 기존 결과를 참고한 탐색 평가이며 새 독립 검증·실제 답변 정확도·LLM 충분성 판단·서비스 적용 확정을 뜻하지 않는다. 임계값을 재조정하지 않았다.','','[고정 입력](../../../data/legal-query-chunks-v1/manifest.json) · [300자 비교](../../legal-query-transform-v1/kure-v1/interpretation.md)')
 [IO.File]::WriteAllText((Join-Path $target 'README.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
 $summary|Where-Object cohort -eq 'in_corpus'|Format-Table maxChars,queryMode,recallAt5,completeAt5,mrrAt10
 Write-Host 'LEGAL QUERY CHUNKS 500/700 COMPLETE. Reports and Qdrant saved.' -ForegroundColor Green
}finally{if($started){try{$body=@{model='kure-v1:official';input=@();keep_alive=0}|ConvertTo-Json;$null=Invoke-RestMethod -Method Post http://127.0.0.1:11436/api/embed -ContentType application/json -Body $body -TimeoutSec 10}catch{};docker compose -f docker/kure/compose.yml stop kure};Stop-Transcript}
