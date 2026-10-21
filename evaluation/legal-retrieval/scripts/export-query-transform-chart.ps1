$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
Add-Type -AssemblyName System.Windows.Forms.DataVisualization
Add-Type -AssemblyName System.Drawing
$dir=Get-ReportPath 'legal-query-transform-v1/kure-v1'
$rows=@(Import-Csv (Join-Path $dir 'summary.csv')|Where-Object cohort -eq 'in_corpus')
if($rows.Count -ne 3 -or @($rows.queryMode|Select-Object -Unique).Count -ne 3){throw 'Three strategies required'}
$chart=[System.Windows.Forms.DataVisualization.Charting.Chart]::new()
try{
 $chart.Width=1400;$chart.Height=690;$chart.BackColor=[Drawing.Color]::White;$chart.AntiAliasing='All'
 $title=$chart.Titles.Add('단일 dense 검색 · 질문 변환 방식 비교');$title.Font=[Drawing.Font]::new('Malgun Gothic',23)
 $subtitle=$chart.Titles.Add('동일한 코퍼스 내 36문항 · KURE 1024차원 / 300자 · 고정 정답 기준');$subtitle.Font=[Drawing.Font]::new('Malgun Gothic',13)
 $legend=$chart.Legends.Add('modes');$legend.Docking='Bottom';$legend.Font=[Drawing.Font]::new('Malgun Gothic',13)
 $area=$chart.ChartAreas.Add('quality');$area.AxisY.Minimum=0;$area.AxisY.Maximum=100;$area.AxisY.Interval=20;$area.AxisY.LabelStyle.Format="0'%'";$area.AxisY.MajorGrid.LineColor=[Drawing.Color]::LightGray;$area.AxisX.MajorGrid.Enabled=$false;$area.AxisX.LabelStyle.Font=[Drawing.Font]::new('Malgun Gothic',13)
 $labels=@{original='원본';rewritten='기존 의미 보존 정제';legal_transformed='법률 쟁점 중심 변환'};$colors=@{original='SlateGray';rewritten='RoyalBlue';legal_transformed='SeaGreen'}
 foreach($mode in @('original','rewritten','legal_transformed')){
  $r=@($rows|Where-Object queryMode -eq $mode)[0];$series=$chart.Series.Add($mode);$series.ChartArea='quality';$series.ChartType='Column';$series.LegendText=$labels[$mode];$series.Color=[Drawing.Color]::FromName($colors[$mode]);$series.IsValueShownAsLabel=$true;$series.LabelFormat="0.00'%'";$series.Font=[Drawing.Font]::new('Malgun Gothic',13)
  foreach($metric in @(@{key='recallAt5';label='필요 근거 회수율 (Recall@5)'},@{key='hitAt5';label='하나 이상 찾은 질문 (Hit@5)'},@{key='completeAt5';label='전부 찾은 질문 (Complete@5)'})){$null=$series.Points.AddXY($metric.label,100*[double]$r.($metric.key))}
 }
 $footer=$chart.Titles.Add('기존 결과를 참고해 설계한 탐색 비교 · 실제 답변 정확도나 새 독립 검증이 아님');$footer.Docking='Bottom';$footer.Font=[Drawing.Font]::new('Malgun Gothic',11)
 $chart.SaveImage((Join-Path $dir 'query-strategy-comparison.png'),'Png')
}finally{$chart.Dispose()}
Write-Host 'Saved query-strategy-comparison.png'
$queries=@(Import-Csv (Join-Path $dir 'queries.csv')|Where-Object cohort -eq 'in_corpus')
$pairs=@(foreach($old in $queries|Where-Object queryMode -eq 'rewritten'){
 $new=@($queries|Where-Object {$_.queryId -eq $old.queryId -and $_.queryMode -eq 'legal_transformed'})
 if($new.Count -ne 1){throw 'Paired query identity mismatch'}
 [pscustomobject]@{queryId=$old.queryId;oldRecall=[double]$old.recallAt5;newRecall=[double]$new[0].recallAt5;delta=[double]$new[0].recallAt5-[double]$old.recallAt5}
})
if($pairs.Count -ne 36 -or @($pairs.queryId|Select-Object -Unique).Count -ne 36){throw 'Expected 36 paired questions'}
$pairs|Export-Csv (Join-Path $dir 'paired-recall-changes.csv') -NoTypeInformation -Encoding UTF8
