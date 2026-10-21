[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('bge-m3','qwen3-0.6b')][string]$Model,[switch]$ValidateOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$context=Get-QdrantLabContext $Model 300
$profile=$context.profile;$digest=$context.baseline.model.modelDigest
$questionsPath=Get-DataPath 'independent-validation-v1/questions.json'
$transformsPath=Get-ReviewPath 'legal-query-transform-v1/transforms.json'
$chunkManifestPath=Get-DataPath 'legal-query-chunks-v1/manifest.json'
$chunkManifest=Get-Content -Raw -Encoding UTF8 $chunkManifestPath|ConvertFrom-Json
$questions=Get-Content -Raw -Encoding UTF8 $questionsPath|ConvertFrom-Json
$transforms=Get-Content -Raw -Encoding UTF8 $transformsPath|ConvertFrom-Json
if((Get-FileHash $questionsPath).Hash -ne $chunkManifest.questionsSha256 -or (Get-FileHash $transformsPath).Hash -ne $chunkManifest.transformsSha256){throw 'Frozen questions/transforms mismatch'}
$transformMap=@{};foreach($t in $transforms){$transformMap[$t.id]=$t}
$positive=@($questions.questions|Where-Object cohort -eq 'in_corpus')
if($positive.Count -ne 36 -or @($transforms).Count -ne 100){throw 'Frozen sample count mismatch'}
$inputs=@{};$queries=@()
foreach($q in $positive){
 if($transformMap[$q.id].originalText -cne $q.text){throw 'Transform original text mismatch'}
 foreach($mode in @('original','rewritten','legal_transformed')){
  $text=$(if($mode -eq 'original'){$q.text}elseif($mode -eq 'rewritten'){$q.rewrittenText}else{$transformMap[$q.id].searchText})
  if([string]::IsNullOrWhiteSpace($text)){throw 'Missing query text'}
  $inputText=$profile.queryFormat.Replace('{text}',$text);$key=[LegalDenseIndex]::InputKey($inputText);$inputs[$key]=$inputText
  $queries+=[pscustomobject]@{id=$q.id;mode=$mode;text=$text;key=$key;gold=@($q.goldUnitIds)}
 }
}
$sources=@();foreach($size in @(300,500,700)){
 $path=$(if($size -eq 300){Get-DataPath 'final-validation-v1/corpus.jsonl'}else{Get-DataPath "legal-query-chunks-v1/char$size/corpus.jsonl"})
 if(!(Test-Path $path)){throw "Missing corpus: $path"}
 $hash=(Get-FileHash $path).Hash
 $expected=$(if($size -eq 300){$chunkManifest.sourceCorpusSha256}else{($chunkManifest.sizes|Where-Object maxChars -eq $size).corpusSha256})
 if($hash -ne $expected){throw 'Corpus hash mismatch'}
 $chunks=@(foreach($line in [IO.File]::ReadLines($path)){$c=$line|ConvertFrom-Json;$inputText=$profile.documentFormat.Replace('{title}',$c.title).Replace('{text}',$c.text);$key=[LegalDenseIndex]::InputKey($inputText);$inputs[$key]=$inputText;[pscustomobject]@{chunk=$c;key=$key}})
 foreach($q in $positive){foreach($gold in $q.goldUnitIds){if($gold -notin $chunks.chunk.unitId){throw "Missing gold source: $gold"}}}
 $sources+=[pscustomobject]@{size=$size;path=$path;hash=$hash;chunks=$chunks}
}
$manifest=[ordered]@{id='recall50-models-v1';model=$Model;modelDigest=$digest;dimensions=$profile.dimensions;inputMode=$profile.inputMode;documentFormat=$profile.documentFormat;queryFormat=$profile.queryFormat;questionsSha256=(Get-FileHash $questionsPath).Hash;transformsSha256=(Get-FileHash $transformsPath).Hash;chunkManifestSha256=(Get-FileHash $chunkManifestPath).Hash;sources=@($sources|Select-Object size,hash);positiveQuestions=36;modes=@('original','rewritten','legal_transformed');maxRank=50;thresholdPolicy='none; no score gating or tuning';serviceReady=$false}
$freeze=[LegalDenseIndex]::InputKey(($manifest|ConvertTo-Json -Depth 12 -Compress))
$cache=Read-QdrantLabCache $context
$cacheDir=Join-Path $ResultsRoot ("shared-cache/$Model-"+$digest.Substring(0,16)+'-'+$profile.inputMode)
$missing=@($inputs.Keys|Where-Object {!$cache.Contains($_)}|Sort-Object)
Write-Host "$Model : $($sources.chunks.Count) chunks, 108 positive query variants, 3 sizes; $($missing.Count) new unique embeddings; threshold disabled."
if($ValidateOnly){Write-Host 'PASS: frozen sources, question texts, gold, model identity and cache contract; no inference';return}
$target=Get-ReportPath "recall50-v1/$Model";$null=New-Item -ItemType Directory -Force $target
function Save-Json($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 30)+"`n",[Text.UTF8Encoding]::new($false))}
$manifestPath=Join-Path $target 'manifest.json'
if(Test-Path $manifestPath){$existing=Get-Content -Raw -Encoding UTF8 $manifestPath|ConvertFrom-Json;if($existing.freezeSha256 -ne $freeze){throw 'Existing experiment differs; refusing overwrite'}}
Save-Json $manifestPath @{freezeSha256=$freeze;manifest=$manifest}
$port=$(if($Model -eq 'bge-m3'){11435}else{11437})
$container=$(if($Model -eq 'bge-m3'){'molelaw-embedding-lab-ollama-1'}else{'molelaw-qwen3-lab'})
$flag=Join-Path $ArtifactsRoot 'control/stop-recall50-models-v1.flag'
function Check-Stop {if(Test-Path -LiteralPath $flag){throw 'Stop requested; completed vector batches retained'}}
function Embed([string[]]$Batch){
 Check-Stop
 $body=@{model=$profile.tag;input=@($Batch);truncate=$false;keep_alive='5m';options=@{num_thread=6;num_gpu=0}}|ConvertTo-Json -Depth 8 -Compress
 Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port/api/embed" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 300
}
try{
 Check-Stop
 $other=@(docker ps --format '{{.Names}}'|Where-Object {$_ -in @('molelaw-embedding-lab-ollama-1','molelaw-qwen3-lab','molelaw-kure-lab-kure-1') -and $_ -ne $container})
 if($other.Count){throw "Other embedding container running: $other"}
 docker start $container;if($LASTEXITCODE -ne 0){throw 'Model container start failed'}
 $deadline=[datetime]::UtcNow.AddMinutes(15)
 do{Check-Stop;try{$version=Invoke-RestMethod "http://127.0.0.1:$port/api/version" -TimeoutSec 3;break}catch{if([datetime]::UtcNow -gt $deadline){throw 'Model startup timeout'};Start-Sleep -Seconds 5}}while($true)
 $tags=Invoke-RestMethod "http://127.0.0.1:$port/api/tags" -TimeoutSec 10
 if(@($tags.models|Where-Object {$_.name -eq $profile.tag -and $_.digest -eq $digest}).Count -ne 1){throw 'Pinned model digest mismatch'}
 if($profile.runtime -eq 'sentence-transformers'){
  foreach($k in @('dtype','device')){if($version.$k -ne $context.baseline.model.$k){throw 'Runtime device/dtype mismatch'}}
  if($version.version -ne $context.baseline.model.inferenceRuntimeVersion){throw 'Pinned runtime version mismatch'}
 } elseif($version.version -ne $context.baseline.model.ollamaVersion){throw 'Pinned Ollama runtime mismatch'}
 for($offset=0;$offset -lt $missing.Count;$offset+=16){
  $last=[math]::Min($offset+16,$missing.Count);$keys=[string[]]@($missing[$offset..($last-1)])
  $response=Embed @($keys|ForEach-Object {$inputs[$_]})
  if(@($response.embeddings).Count -ne $keys.Count){throw 'Embedding batch count mismatch'}
  $cache.WriteBatch((Join-Path $cacheDir ('recall50-'+[guid]::NewGuid().ToString('N')+'.bin')),$keys,[single[][]]@($response.embeddings|ForEach-Object {,([single[]]$_)}))
  Write-Host "$Model embedded $last/$($missing.Count) new inputs (checkpoint saved)"
 }
 Initialize-QdrantLab 6333
 $rows=@();$top=@();$summary=@();$archives=@()
 foreach($source in $sources){
  Check-Stop
  $modelName=$Model.Replace('-','_').Replace('.','_')
  $docs="molelaw_candidates_${modelName}_c$($source.size)_$($freeze.Substring(0,8))"
  $qc=$docs+'_queries';$archives+=@{size=$source.size;documentCollection=$docs;queryCollection=$qc}
  $metadata=@{owner=$script:LabOwner;kind='recall50-models-v1';model=$Model;model_digest=$digest;freeze_sha256=$freeze;corpus_sha256=$source.hash;max_chars=$source.size;threshold_policy='none'}
  foreach($name in @($docs,$qc)){
   if(@((Invoke-QdrantLab GET 'collections').result.collections|Where-Object name -eq $name).Count -eq 0){$null=Invoke-QdrantLab PUT "collections/$name" @{vectors=@{size=[int]$profile.dimensions;distance='Cosine'};metadata=$metadata}}
   $info=(Invoke-QdrantLab GET "collections/$name").result
   foreach($k in $metadata.Keys){if($info.config.metadata.$k -ne $metadata[$k]){throw "Collection provenance mismatch: $name/$k"}}
   if($info.config.params.vectors.size -ne $profile.dimensions -or $info.config.params.vectors.distance -ne 'Cosine'){throw 'Collection schema mismatch'}
  }
  $null=Invoke-QdrantLab PUT "collections/$docs/index?wait=true" @{field_name='unit_id';field_schema='keyword'}
  for($offset=0;$offset -lt $source.chunks.Count;$offset+=32){
   Check-Stop;$last=[math]::Min($offset+32,$source.chunks.Count)
   $points=@(foreach($entry in $source.chunks[$offset..($last-1)]){$c=$entry.chunk;@{id=(Get-QdrantLabPointId $c.id);vector=@($cache.GetVector($entry.key));payload=@{unit_id=$c.unitId;chunk_id=$c.id;title=$c.title;text=$c.text;law_id=$c.lawId;corpus_sha256=$source.hash;lab_owner=$script:LabOwner}}})
   $null=Invoke-QdrantLab PUT "collections/$docs/points?wait=true" @{points=$points}
  }
  if((Invoke-QdrantLab POST "collections/$docs/points/count" @{exact=$true}).result.count -ne $source.chunks.Count){throw 'Document count mismatch'}
  $context.collection=$docs
  foreach($q in $queries){
   Check-Stop;$vector=$cache.GetVector($q.key);$groups=@(Invoke-QdrantLabGroups $context $vector 50 $true)
   if($groups.Count -ne 50 -or @($groups.id|Select-Object -Unique).Count -ne 50){throw 'Expected 50 unique units'}
   $hits=@(for($i=0;$i -lt 50;$i++){[pscustomobject]@{rank=$i+1;unitId=$groups[$i].id;similarity=$groups[$i].hits[0].score;relevant=($groups[$i].id -in $q.gold);title=$groups[$i].hits[0].payload.title}})
   $metrics=Get-RankingMetrics $hits $q.gold;$found=@($hits|Where-Object relevant).Count;$gold=@($q.gold|Select-Object -Unique).Count
   $row=[ordered]@{maxChars=$source.size;queryMode=$q.mode;queryId=$q.id;goldCount=$gold;foundAt50=$found}
   foreach($p in $metrics.PSObject.Properties){$row[$p.Name]=$p.Value}
   $row.recallAt50=$found/[double]$gold;$row.hitAt50=[double]($found -gt 0);$row.completeAt50=[double]($found -eq $gold)
   $row.mrrAt10=0.0;$first=@($hits|Where-Object {$_.relevant -and $_.rank -le 10}|Select-Object -First 1);if($first.Count){$row.mrrAt10=1.0/$first[0].rank}
   $rows+=[pscustomobject]$row
   foreach($h in $hits){$r=[ordered]@{maxChars=$source.size;queryMode=$q.mode;queryId=$q.id};foreach($p in $h.PSObject.Properties){$r[$p.Name]=$p.Value};$top+=[pscustomobject]$r}
   $null=Invoke-QdrantLab PUT "collections/$qc/points?wait=true" @{points=@(@{id=(Get-QdrantLabPointId "$qc/$($q.id)/$($q.mode)");vector=@($vector);payload=@{query_id=$q.id;query_mode=$q.mode;retrieval_text=$q.text;gold_unit_ids=$q.gold;metrics=$row;top50=$hits;freeze_sha256=$freeze;document_collection=$docs}})}
  }
  if((Invoke-QdrantLab POST "collections/$qc/points/count" @{exact=$true}).result.count -ne 108){throw 'Query archive count mismatch'}
  $rows|Export-Csv (Join-Path $target 'queries.csv') -NoTypeInformation -Encoding UTF8
  $top|Export-Csv (Join-Path $target 'top50.csv') -NoTypeInformation -Encoding UTF8
  foreach($mode in @('original','rewritten','legal_transformed')){$selected=@($rows|Where-Object {$_.maxChars -eq $source.size -and $_.queryMode -eq $mode});if($selected.Count -ne 36){throw 'Summary sample mismatch'};$r=[ordered]@{model=$Model;maxChars=$source.size;queryMode=$mode;count=36};foreach($k in @('hitAt5','completeAt5','recallAt5','recallAt10','recallAt50','hitAt50','completeAt50','mrrAt10')){$r[$k]=($selected|Measure-Object $k -Average).Average};$summary+=[pscustomobject]$r}
  $summary|Export-Csv (Join-Path $target 'summary.csv') -NoTypeInformation -Encoding UTF8
  Write-Host "$Model char$($source.size) complete. Saved checkpoint." -ForegroundColor Green
  $summary|Where-Object {$_.maxChars -eq $source.size}|Format-Table
 }
 if($rows.Count -ne 324 -or $top.Count -ne 16200){throw 'Incomplete results'}
 if((Get-FileHash $questionsPath).Hash -ne $manifest.questionsSha256 -or (Get-FileHash $transformsPath).Hash -ne $manifest.transformsSha256){throw 'Frozen questions/transforms changed during run'}
 foreach($source in $sources){if((Get-FileHash $source.path).Hash -ne $source.hash){throw 'Corpus changed during run'}}
 Save-Json (Join-Path $target 'report.json') @{completedAtUtc=[datetime]::UtcNow.ToString('o');freezeSha256=$freeze;manifest=$manifest;summary=$summary;collections=$archives;additionalEmbeddings=$missing.Count;serviceReady=$false}
 & (Join-Path $PSScriptRoot 'manage-qdrant-lab.ps1') -Action ApplyAliases
 Write-Host "$Model RECALL50 COMPLETE. Threshold filtering disabled. Reports and Qdrant saved." -ForegroundColor Green
}finally{docker stop $container}
