$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$source=Join-Path $ReportsRoot 'independent-validation-v1/kure-v1/queries.csv'
$target=Join-Path $ReportsRoot 'threshold-sweep-v1';$null=New-Item -ItemType Directory -Force $target
$rows=@(Import-Csv $source);if($rows.Count -ne 200){throw 'Expected 200 frozen query results'}
function Get-Point($Mode,$Selected,[double]$Threshold,$Kind){
 $pos=@($Selected|Where-Object cohort -eq 'in_corpus');$neg=@($Selected|Where-Object cohort -ne 'in_corpus')
 $accepted=@($Selected|Where-Object {[double]$_.top1Similarity -ge $Threshold})
 $tp=@($accepted|Where-Object cohort -eq 'in_corpus').Count;$fp=@($accepted|Where-Object cohort -ne 'in_corpus').Count
 $correct=@($accepted|Where-Object {$_.cohort -eq 'in_corpus' -and $_.top1Relevant -eq 'True'}).Count
 $hit=@($accepted|Where-Object {$_.cohort -eq 'in_corpus' -and [double]$_.hitAt5 -gt 0}).Count
 $r=[ordered]@{queryMode=$Mode;threshold=$Threshold;kind=$Kind;inCorpusAccepted=$tp;inCorpusCount=$pos.Count;otherAccepted=$fp;otherCount=$neg.Count;scopeSensitivity=$tp/[double]$pos.Count;scopeFalseAcceptance=$fp/[double]$neg.Count;scopeBalancedAccuracy=0.5*($tp/[double]$pos.Count+1-$fp/[double]$neg.Count);acceptedTotal=$accepted.Count;acceptedTop1GoldCount=$correct;acceptedTop1GoldPrecision=$(if($accepted.Count){$correct/[double]$accepted.Count}else{$null});acceptedHitAt5Count=$hit;positiveHitAndAcceptedRate=$hit/[double]$pos.Count}
 foreach($c in @('missing_corpus','unsupported','needs_clarification')){$r[$c+'Accepted']=@($accepted|Where-Object cohort -eq $c).Count}
 [pscustomobject]$r
}
$output=@();$choices=@()
foreach($mode in @('original','rewritten')){
 $selected=@($rows|Where-Object queryMode -eq $mode)
 $grid=@(30..85|ForEach-Object {$_/100.0})
 $thresholds=@($grid)+@($selected|ForEach-Object {[double]$_.top1Similarity})+@([double]$selected[0].threshold,-1.000001,1.000001)
 $points=@(foreach($t in $thresholds|Sort-Object -Unique){Get-Point $mode $selected $t $(if($t -in $grid){'grid'}else{'observed_or_reference'})})
 $output+=$points
 $best=@($points|Sort-Object @{e='scopeBalancedAccuracy';Descending=$true},@{e='threshold';Descending=$true})[0]
 $coverage=@($points|Where-Object {$_.scopeSensitivity -ge 0.70}|Sort-Object scopeFalseAcceptance,@{e='threshold';Descending=$true})[0]
 $conservative=@($points|Where-Object {$_.scopeFalseAcceptance -le 0.10}|Sort-Object @{e='scopeSensitivity';Descending=$true},@{e='threshold';Descending=$true})[0]
 foreach($entry in @(@{name='existing';row=(Get-Point $mode $selected ([double]$selected[0].threshold) 'reference')},@{name='scope_balanced_diagnostic';row=$best},@{name='coverage_at_least_70pct';row=$coverage},@{name='other_acceptance_at_most_10pct';row=$conservative})){
  $r=[ordered]@{candidate=$entry.name};foreach($p in $entry.row.PSObject.Properties){$r[$p.Name]=$p.Value};$choices+=[pscustomobject]$r
 }
}
$output|Export-Csv (Join-Path $target 'sweep.csv') -NoTypeInformation -Encoding UTF8
$choices|Export-Csv (Join-Path $target 'candidates.csv') -NoTypeInformation -Encoding UTF8
$meta=[ordered]@{createdAtUtc=[datetime]::UtcNow.ToString('o');source='reports/independent-validation-v1/kure-v1/queries.csv';sourceSha256=(Get-FileHash $source).Hash;queryCount=200;inCorpusCount=36;otherCount=64;policy='Post-hoc tuning on independent-validation-v1. No longer untouched held-out evidence for future tuned configurations. Original frozen results unchanged.';appliedToService=$false;additionalInference=$false;limitations=@('Scope labels are not answer-correctness labels; missing/unsupported/clarification merged only as a diagnostic proxy.','Strict gold can omit alternative relevant provisions.','Threshold gates queries by top1 score; ranking and ungated Recall@5 do not change.','Candidates selected and measured on same small sample; require new untouched validation.')}
[IO.File]::WriteAllText((Join-Path $target 'report.json'),($meta|ConvertTo-Json -Depth 8)+"`n",[Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.Windows.Forms.DataVisualization
Add-Type -AssemblyName System.Drawing
$chart=[System.Windows.Forms.DataVisualization.Charting.Chart]::new()
try{
 $chart.Width=1500;$chart.Height=700;$chart.BackColor=[Drawing.Color]::White
 $title=$chart.Titles.Add('임계값 조정 · 범위 내 질문 수용과 범위 밖 수용의 교환');$title.Font=[Drawing.Font]::new('Malgun Gothic',20)
 $footer=$chart.Titles.Add('사후 튜닝 진단 · 범위 판정은 답변 정답 판정이 아님 · 검색 순위와 Recall@5는 변하지 않음');$footer.Docking='Bottom';$footer.Font=[Drawing.Font]::new('Malgun Gothic',11)
 $legend=$chart.Legends.Add('legend');$legend.Docking='Bottom';$legend.Font=[Drawing.Font]::new('Malgun Gothic',11)
 foreach($mode in @('original','rewritten')){
  $area=$chart.ChartAreas.Add($mode);$area.Position=[System.Windows.Forms.DataVisualization.Charting.ElementPosition]::new($(if($mode -eq 'original'){3}else{52}),15,46,68)
  $area.AxisX.Minimum=0.3;$area.AxisX.Maximum=0.85;$area.AxisX.Interval=0.1;$area.AxisX.Title='임계값';$area.AxisY.Minimum=0;$area.AxisY.Maximum=100;$area.AxisY.LabelStyle.Format="0'%'";$area.AxisY.MajorGrid.LineColor=[Drawing.Color]::LightGray
  $caption=$chart.Titles.Add($(if($mode -eq 'original'){'원본 질문'}else{'정제 질문'}));$caption.DockedToChartArea=$mode;$caption.Font=[Drawing.Font]::new('Malgun Gothic',15)
  foreach($spec in @(@{key='scopeSensitivity';title='코퍼스 내 수용률 (36개)';color='RoyalBlue'},@{key='scopeFalseAcceptance';title='그 외 수용률 (64개)';color='Firebrick'},@{key='positiveHitAndAcceptedRate';title='정답@5 확보 + 수용 (36개)';color='ForestGreen'})){
   $series=$chart.Series.Add($mode+'-'+$spec.key);$series.ChartArea=$mode;$series.ChartType='Line';$series.BorderWidth=3;$series.Color=[Drawing.Color]::FromName($spec.color);$series.LegendText=$spec.title;$series.IsVisibleInLegend=$mode -eq 'original'
   foreach($r in $output|Where-Object {$_.queryMode -eq $mode -and $_.kind -eq 'grid'}){$null=$series.Points.AddXY($r.threshold,100*$r.($spec.key))}
  }
 }
 $chart.SaveImage((Join-Path $target 'threshold-tradeoff.png'),'Png')
}finally{$chart.Dispose()}
$lines=@('# KURE 임계값 사후 튜닝 진단','','![임계값별 수용률](threshold-tradeoff.png)','','새 추론 없이 독립 질문 100개 × 원본/정제의 저장된 최고 유사도를 재사용했다. 기준값 이상이면 질문을 수용하는 방식이다. 임계값을 낮추면 많은 질문을 받아들이지만 필요한 정답을 새로 찾지는 않는다. 검색 순위와 기존 Recall@5는 변하지 않는다.','','| 방식 | 후보 목적 | 임계값 | 코퍼스 내 수용 | 그 외 수용 | 수용 중 1위가 고정 정답인 비율 | 정답@5 확보+수용 |','| --- | --- | ---: | ---: | ---: | ---: | ---: |')
$labels=@{existing='기존';scope_balanced_diagnostic='범위 구분 균형(사후)';coverage_at_least_70pct='범위 내 70% 이상 수용';other_acceptance_at_most_10pct='그 외 수용 10% 이하'}
foreach($r in $choices){$precision=$(if($null -eq $r.acceptedTop1GoldPrecision){'-'}else{'{0:F2}%' -f (100*$r.acceptedTop1GoldPrecision)});$lines+=('| {0} | {1} | {2:F8} | {3}/36 | {4}/64 | {5} | {6}/36 |' -f $r.queryMode,$labels[$r.candidate],$r.threshold,$r.inCorpusAccepted,$r.otherAccepted,$precision,$r.acceptedHitAt5Count)}
$lines+=@('','existing은 원래 기준값, scope_balanced_diagnostic은 현재 표본에서 범위 구분 balanced accuracy가 가장 높은 지점, coverage_at_least_70pct는 코퍼스 내 70% 이상 수용 조건에서 그 외 수용을 줄인 지점, other_acceptance_at_most_10pct는 그 외 수용 10% 이하 조건에서 코퍼스 내 수용을 높인 지점이다. 추천 확정값이 아닌 상충관계를 보는 사후 후보이며 운영에는 적용하지 않았다.','','코퍼스 내 36개를 양성, 추가 자료 필요 44개·지원 범위 밖 15개·사실 부족 5개를 나머지 64개로 묶은 것은 범위 구분의 대리 지표다. 실제 답변 정답/오답 라벨이 아니며 사실 부족 질문도 일반 법적 설명은 가능할 수 있다. 1위가 고정 정답인 비율 역시 정답 집합 외 대체 근거 누락의 영향을 받는다.','','이 질문셋을 보고 임계값을 선택하므로 앞으로 이 자료는 튜닝용으로 취급한다. 기존 독립 실행과 고정 입력은 그대로 보존하되, 여기서 고른 기준값의 독립 검증 성능이라고 주장하지 않는다. 별도 새 질문으로 확인하기 전까지 서비스 준비 상태는 false다.','','[원래 독립 검증](../independent-validation-v1/kure-v1/interpretation.md) · [모든 임계값 CSV](sweep.csv) · [후보 CSV](candidates.csv)')
[IO.File]::WriteAllText((Join-Path $target 'README.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$choices|Format-Table queryMode,candidate,threshold,inCorpusAccepted,otherAccepted,acceptedTop1GoldCount,acceptedHitAt5Count
