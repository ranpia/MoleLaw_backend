$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$dir=Get-ReportPath 'recall50-v1/kure-v1';$logs=Join-Path $ArtifactsRoot 'logs'
foreach($p in @($dir,$logs)){$null=New-Item -ItemType Directory -Force $p}
Start-Transcript -Path (Join-Path $logs ('kure-recall50-v1-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log'))
try{
 $questionPath=Get-DataPath 'independent-validation-v1/questions.json'
 $qhash=(Get-FileHash $questionPath).Hash;$data=Get-Content -Raw -Encoding UTF8 $questionPath|ConvertFrom-Json
 $map=@{};foreach($q in $data.questions){$map[$q.id]=$q}
 $oldPath=Get-ReportPath 'legal-query-chunks-v1/kure-v1/queries.csv';$oldHash=(Get-FileHash $oldPath).Hash
 $old=@{};foreach($r in Import-Csv $oldPath){$old["$($r.maxChars)/$($r.queryMode)/$($r.queryId)"]=$r}
 $ir=Get-Content -Raw -Encoding UTF8 (Get-ReportPath 'independent-validation-v1/kure-v1/report.json')|ConvertFrom-Json
 $br=Get-Content -Raw -Encoding UTF8 (Get-ReportPath 'legal-query-transform-v1/kure-v1/report.json')|ConvertFrom-Json
 $m=Get-Content -Raw -Encoding UTF8 (Get-DataPath 'legal-query-chunks-v1/manifest.json')|ConvertFrom-Json
 $sources=@([pscustomobject]@{size=300;query=$ir.queryCollection;document=$ir.documentCollection;freeze=$ir.freezeSha256;modes=@('original','rewritten');corpus=$m.sourceCorpusSha256},[pscustomobject]@{size=300;query=$br.queryCollection;document=$br.documentCollection;freeze=$br.freezeSha256;modes=@('legal_transformed');corpus=$m.sourceCorpusSha256})
 foreach($size in @(500,700)){$r=Get-Content -Raw -Encoding UTF8 (Get-ReportPath "legal-query-chunks-v1/kure-v1/char$size/report.json")|ConvertFrom-Json;$s=@($m.sizes|Where-Object maxChars -eq $size)[0];$sources+=[pscustomobject]@{size=$size;query=$r.queryCollection;document=$r.documentCollection;freeze=$r.freezeSha256;modes=@('original','rewritten','legal_transformed');corpus=$s.corpusSha256}}
 $manifest=[ordered]@{id='recall50-v1';goldQuestionsSha256=$qhash;baselineQueriesSha256=$oldHash;modelRevision=$m.modelRevision;maxRank=50;positiveQuestions=36;queryModes=@('original','rewritten','legal_transformed');sizes=@(300,500,700);sources=$sources;policy='Exploratory candidate coverage; same vectors and gold; no model inference or threshold tuning'}
 $freeze=[LegalDenseIndex]::InputKey(($manifest|ConvertTo-Json -Depth 12 -Compress))
 function Save-Json($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 24)+"`n",[Text.UTF8Encoding]::new($false))}
 Save-Json (Join-Path $dir 'manifest.json') $manifest
 Initialize-QdrantLab 6333;$context=Get-QdrantLabContext 'kure-v1' 300
 $rows=@();$top=@();$seen=@{}
 foreach($source in $sources){
  $info=(Invoke-QdrantLab GET "collections/$($source.query)").result
  if($info.config.metadata.owner -ne $script:LabOwner -or $info.config.metadata.freeze_sha256 -ne $source.freeze -or $info.config.metadata.model_digest -ne $m.modelRevision -or $info.config.metadata.corpus_sha256 -ne $source.corpus -or $info.config.params.vectors.size -ne 1024){throw 'Source query provenance mismatch'}
  $doc=(Invoke-QdrantLab GET "collections/$($source.document)").result
  if($doc.config.metadata.owner -ne $script:LabOwner -or $doc.config.metadata.corpus_sha256 -ne $source.corpus -or $doc.config.metadata.model_digest -ne $m.modelRevision -or $doc.config.params.vectors.size -ne 1024 -or $doc.config.params.vectors.distance -ne 'Cosine'){throw 'Source document provenance mismatch'}
  $qc="molelaw_recall50_kure_v1_c$($source.size)_$($freeze.Substring(0,8))"
  $metadata=@{owner=$script:LabOwner;kind='recall50-v1';freeze_sha256=$freeze;model_digest=$m.modelRevision;max_chars=$source.size;corpus_sha256=$source.corpus;max_rank=50}
  if(@((Invoke-QdrantLab GET 'collections').result.collections|Where-Object name -eq $qc).Count -eq 0){$null=Invoke-QdrantLab PUT "collections/$qc" @{vectors=@{size=1024;distance='Cosine'};metadata=$metadata}}
  $info=(Invoke-QdrantLab GET "collections/$qc").result;foreach($k in $metadata.Keys){if($info.config.metadata.$k -ne $metadata[$k]){throw 'Recall50 archive ownership mismatch'}}
  $offset=$null;$buffer=@();$context.collection=$source.document;$sourceCount=0
  do{$body=@{limit=100;with_payload=$true;with_vector=$true};if($null -ne $offset){$body.offset=$offset};$page=(Invoke-QdrantLab POST "collections/$($source.query)/points/scroll" $body).result
   foreach($point in $page.points){$p=$point.payload;$id=$p.query_id;$mode=$p.query_mode
    if(!$map.ContainsKey($id) -or $mode -notin $source.modes){throw 'Source point identity mismatch'}
    $q=$map[$id];if($q.cohort -ne 'in_corpus'){continue}
    $key="$($source.size)/$mode/$id";if($seen.ContainsKey($key) -or !$old.ContainsKey($key)){throw 'Duplicate or unknown positive query'};$seen[$key]=$true;$previous=$old[$key]
    if($p.freeze_sha256 -ne $source.freeze -or $p.retrieval_text -cne $previous.retrievalText -or (@($p.gold_unit_ids|Sort-Object) -join '|') -cne (@($q.goldUnitIds|Sort-Object) -join '|')){throw 'Source query/gold mismatch'}
    $vector=[single[]]$point.vector;if($vector.Length -ne 1024){throw 'Source vector dimensions mismatch'}
    $groups=@(Invoke-QdrantLabGroups $context $vector 50 $true)
    if($groups.Count -ne 50 -or @($groups.id|Select-Object -Unique).Count -ne 50){throw 'Expected 50 unique source units'}
    $hits=@();for($i=0;$i -lt 50;$i++){$h=[pscustomobject]@{rank=$i+1;unitId=$groups[$i].id;similarity=$groups[$i].hits[0].score;relevant=($groups[$i].id -in @($q.goldUnitIds))};$hits+=$h;$top+=[pscustomobject]@{maxChars=$source.size;queryMode=$mode;queryId=$id;rank=$h.rank;unitId=$h.unitId;similarity=$h.similarity;relevant=$h.relevant;title=$groups[$i].hits[0].payload.title}}
    $metrics=Get-RankingMetrics $hits $q.goldUnitIds
    foreach($k in @('recallAt5','recallAt10','hitAt5','completeAt5')){if([math]::Abs([double]$metrics.$k-[double]$previous.$k) -gt 1e-12){throw "Top50 prefix changed frozen metric: $key/$k"}}
    $found=@($hits|Where-Object relevant).Count;$gold=@($q.goldUnitIds|Select-Object -Unique).Count
    $row=[ordered]@{maxChars=$source.size;queryMode=$mode;queryId=$id;goldCount=$gold;foundAt50=$found;hitAt5=$metrics.hitAt5;completeAt5=$metrics.completeAt5;recallAt5=$metrics.recallAt5;recallAt10=$metrics.recallAt10;recallAt50=$found/[double]$gold;hitAt50=[double]($found -gt 0);completeAt50=[double]($found -eq $gold)}
    $rows+=[pscustomobject]$row;$sourceCount++
    $buffer+=@{id=(Get-QdrantLabPointId "$qc/$id/$mode");vector=@($vector);payload=@{query_id=$id;query_mode=$mode;max_chars=$source.size;retrieval_text=$previous.retrievalText;gold_unit_ids=@($q.goldUnitIds);metrics=$row;top50=$hits;freeze_sha256=$freeze;document_collection=$source.document;source_query_collection=$source.query}}
    if($buffer.Count -eq 16){$null=Invoke-QdrantLab PUT "collections/$qc/points?wait=true" @{points=$buffer};$buffer=@()}
    if($rows.Count%36 -eq 0){Write-Host "Evaluated $($rows.Count)/324 positive query variants (no embedding calls)"}
   };$offset=$page.next_page_offset
  }while($null -ne $offset)
  if($buffer.Count){$null=Invoke-QdrantLab PUT "collections/$qc/points?wait=true" @{points=$buffer}}
  if($sourceCount -ne 36*$source.modes.Count){throw 'Source positive count mismatch'}
 }
 if($rows.Count -ne 324 -or $top.Count -ne 16200){throw 'Incomplete Recall50 results'}
 foreach($size in @(300,500,700)){$qc="molelaw_recall50_kure_v1_c${size}_$($freeze.Substring(0,8))";if((Invoke-QdrantLab POST "collections/$qc/points/count" @{exact=$true}).result.count -ne 108){throw 'Recall50 archive count mismatch'}}
 if((Get-FileHash $questionPath).Hash -ne $qhash -or (Get-FileHash $oldPath).Hash -ne $oldHash){throw 'Source files changed'}
 $summary=@(foreach($size in @(300,500,700)){foreach($mode in @('original','rewritten','legal_transformed')){$selected=@($rows|Where-Object {$_.maxChars -eq $size -and $_.queryMode -eq $mode});if($selected.Count -ne 36){throw 'Summary sample mismatch'};$r=[ordered]@{maxChars=$size;queryMode=$mode;count=36};foreach($k in @('hitAt5','completeAt5','recallAt5','recallAt10','recallAt50','hitAt50','completeAt50')){$r[$k]=($selected|Measure-Object $k -Average).Average};[pscustomobject]$r}})
 $rows|Export-Csv (Join-Path $dir 'queries.csv') -NoTypeInformation -Encoding UTF8;$top|Export-Csv (Join-Path $dir 'top50.csv') -NoTypeInformation -Encoding UTF8;$summary|Export-Csv (Join-Path $dir 'summary.csv') -NoTypeInformation -Encoding UTF8
 Save-Json (Join-Path $dir 'report.json') @{completedAtUtc=[datetime]::UtcNow.ToString('o');freezeSha256=$freeze;manifest=$manifest;summary=$summary;additionalInference=$false;serviceReady=$false}
 $lines=@('# Recall@50 후보 근거 회수 검증','','동일한 코퍼스 내 36문항 × 세 입력 × 세 청크 = 324회, 각 상위 50개 조문을 Qdrant 전수 검색했다. 기존 질문 벡터를 재사용해 모델 추론은 하지 않았다.','','| 문자 | 질의 | Hit@5 | Complete@5 | Recall@5 | Recall@50 | Hit@50 | Complete@50 |','| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |')
 foreach($r in $summary){$lines+=('| {0} | {1} | {2:F2}% | {3:F2}% | {4:F2}% | {5:F2}% | {6:F2}% | {7:F2}% |' -f $r.maxChars,$r.queryMode,(100*$r.hitAt5),(100*$r.completeAt5),(100*$r.recallAt5),(100*$r.recallAt50),(100*$r.hitAt50),(100*$r.completeAt50))}
 $lines+=@('','Hit@5와 Complete@5를 우선 품질 지표로 유지하고 Recall@50은 재정렬 전 후보 50개에 필요한 근거가 얼마나 포함되는지 확인하는 보조 지표로 사용한다. 상위 50개를 조회해도 최종 상위 5개의 품질이 자동으로 좋아지는 것은 아니다.','','기존 상위 5/10개 지표와 재조회 결과가 같은지 확인했다. 임계값·질문·정답·변환을 수정하지 않았다. 고정 정답 외 대체 근거 누락, 작은 표본, 이미 탐색한 튜닝셋이라는 한계를 유지한다. 실제 답변 정확도·새 독립 검증·재정렬 효과를 평가한 결과는 아니다.','','[상위 50개 순위와 유사도](top50.csv) · [기존 청크 비교](../../../chunk-sizes-v1/reports/kure-v1/interpretation.md)')
 [IO.File]::WriteAllText((Join-Path $dir 'README.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
 $summary|Where-Object queryMode -eq 'legal_transformed'|Format-Table maxChars,hitAt5,completeAt5,recallAt50,hitAt50,completeAt50
 Write-Host 'RECALL50 COMPLETE. No model inference. Qdrant and reports saved.' -ForegroundColor Green
}finally{Stop-Transcript}
