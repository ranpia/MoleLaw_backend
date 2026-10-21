$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$root=Get-ReportPath 'recall50-v1'
$records=@();$statuses=@()
foreach($model in @('kure-v1','bge-m3','qwen3-0.6b')){
 $dir=Join-Path $root $model
 if(!(Test-Path (Join-Path $dir 'report.json'))){$statuses+="$model : final report absent; excluded from comparison";continue}
 $report=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'report.json')|ConvertFrom-Json
 $rows=@(Import-Csv (Join-Path $dir 'queries.csv'));$top=@(Import-Csv (Join-Path $dir 'top50.csv'));$summary=@(Import-Csv (Join-Path $dir 'summary.csv'))
 if($rows.Count -ne 324 -or $top.Count -ne 16200 -or $summary.Count -ne 9){throw "Incomplete result set: $model"}
 $questionsPath=Get-DataPath 'independent-validation-v1/questions.json'
 $expected=$(if($model -eq 'kure-v1'){$report.manifest.goldQuestionsSha256}else{$report.manifest.questionsSha256})
 if((Get-FileHash $questionsPath).Hash -ne $expected){throw "Question hash mismatch: $model"}
 $qmap=@{};foreach($q in (Get-Content -Raw -Encoding UTF8 $questionsPath|ConvertFrom-Json).questions){$qmap[$q.id]=$q}
 $groups=@{};foreach($h in $top){$key="$($h.maxChars)/$($h.queryMode)/$($h.queryId)";if(!$groups.ContainsKey($key)){$groups[$key]=[Collections.Generic.List[object]]::new()};$groups[$key].Add($h)}
 $seen=@{}
 foreach($row in $rows){
  $key="$($row.maxChars)/$($row.queryMode)/$($row.queryId)"
  if($seen.ContainsKey($key) -or $qmap[$row.queryId].cohort -ne 'in_corpus'){throw 'Duplicate or non-positive row'};$seen[$key]=$true
  $hits=@($groups[$key]);if($hits.Count -ne 50){throw 'Missing ranks'}
  $gold=@($qmap[$row.queryId].goldUnitIds|Select-Object -Unique);$metrics=Get-RankingMetrics $hits $gold
  foreach($k in @('hitAt5','completeAt5','recallAt5','recallAt10')){if([math]::Abs([double]$row.$k-[double]$metrics.$k) -gt 1e-10){throw "Metric mismatch: $model/$key/$k"}}
  $found=@($hits|Where-Object {$_.unitId -in $gold}).Count
  if([math]::Abs([double]$row.recallAt50-$found/[double]$gold.Count) -gt 1e-10){throw 'Recall50 mismatch'}
 }
 foreach($s in $summary){
  $selected=@($rows|Where-Object {$_.maxChars -eq $s.maxChars -and $_.queryMode -eq $s.queryMode});if($selected.Count -ne 36){throw 'Summary sample mismatch'}
  $r=[ordered]@{model=$model;maxChars=[int]$s.maxChars;queryMode=$s.queryMode;count=36}
  foreach($k in @('hitAt5','completeAt5','recallAt5','recallAt10','recallAt50','hitAt50','completeAt50')){$avg=($selected|Measure-Object $k -Average).Average;if([math]::Abs($avg-[double]$s.$k) -gt 1e-10){throw 'Summary aggregate mismatch'};$r[$k]=$avg}
  $records+=[pscustomobject]$r
 }
 $statuses+="$model : completed; 324 queries / 16200 ranks; ranking metrics independently recalculated"
}
$records|Export-Csv (Join-Path $root 'model-comparison.csv') -NoTypeInformation -Encoding UTF8
$selected=@($records|Where-Object queryMode -eq 'legal_transformed'|Sort-Object maxChars,model)
$lines=@('# Recall@50 모델 비교','','법률 쟁점 변환 질의, 같은 36문항·정답·코퍼스를 비교한다. Hit@5와 Complete@5가 공동 우선이다. 점수 임계값을 적용하지 않았다.','','| 모델 | 문자 | Hit@5 | Complete@5 | Recall@5 | Recall@50 | Hit@50 | Complete@50 |','| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach($r in $selected){$lines+=('| {0} | {1} | {2}/36 | {3}/36 | {4:F2}% | {5:F2}% | {6}/36 | {7}/36 |' -f $r.model,$r.maxChars,[math]::Round(36*$r.hitAt5),[math]::Round(36*$r.completeAt5),(100*$r.recallAt5),(100*$r.recallAt50),[math]::Round(36*$r.hitAt50),[math]::Round(36*$r.completeAt50))}
$lines+=@('','![모델별 지표](model-comparison.svg)','','Hit@5는 필요한 근거를 하나 이상 찾은 문항 수, Complete@5는 필요한 근거 전부를 찾은 문항 수다. Recall@50은 정답 근거 중 상위 50개에 포함된 비율을 문항별로 평균한 값이다. 50개 후보를 확보해도 최종 상위 5개의 품질이 자동으로 높아지지는 않는다.','','[세 질의 버전 전체 CSV](model-comparison.csv) · [실험 조건과 상태](../README.md)','','## 완료 검증','')
$lines+=$statuses|ForEach-Object {'- '+$_}
$lines+=@('','최종 report.json이 없는 모델은 미완료로 표시하고 비교에 넣지 않는다. 이미 탐색한 작은 튜닝 표본이며 고정 정답 외 대체 근거 누락 가능성이 있다. 모델의 일반적 우열이나 서비스 준비 완료를 뜻하지 않는다.')
[IO.File]::WriteAllText((Join-Path $root 'model-comparison.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$height=100+$selected.Count*70
$svg=@("<svg xmlns='http://www.w3.org/2000/svg' width='900' height='$height' viewBox='0 0 900 $height'><rect width='100%' height='100%' fill='white'/><g font-family='Arial,sans-serif' font-size='13'><text x='20' y='25' font-size='18'>Legal transformed: Hit@5 / Complete@5 / Recall@50</text><text x='20' y='48'>Blue: Hit@5   Orange: Complete@5   Green: Recall@50 (percent, 0-100)</text>")
$y=80
foreach($r in $selected){$svg+="<text x='20' y='$($y+12)'>$($r.model) / $($r.maxChars)</text>";$i=0;foreach($k in @('hitAt5','completeAt5','recallAt50')){$v=100*$r.$k;$w=5*$v;$color=@('#2563eb','#ea580c','#15803d')[$i];$at=$y+$i*18;$label=$v.ToString('F2',[Globalization.CultureInfo]::InvariantCulture);$svg+="<rect x='190' y='$at' width='$($w.ToString([Globalization.CultureInfo]::InvariantCulture))' height='13' fill='$color'/><text x='$([math]::Round(198+$w))' y='$($at+11)'>$label%</text>";$i++};$y+=70}
$svg+='</g></svg>'
[IO.File]::WriteAllText((Join-Path $root 'model-comparison.svg'),($svg -join "`n"),[Text.UTF8Encoding]::new($false))
Write-Host "PASS: $($records.Count) verified summaries; comparison CSV, Markdown and SVG saved."
