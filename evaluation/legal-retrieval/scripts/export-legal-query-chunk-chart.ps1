$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
Add-Type -AssemblyName System.Windows.Forms.DataVisualization
Add-Type -AssemblyName System.Drawing
$dir=Get-ReportPath 'legal-query-chunks-v1/kure-v1'
$rows=@(Import-Csv (Join-Path $dir 'comparison.csv')|Where-Object cohort -eq 'in_corpus')
if($rows.Count -ne 9){throw 'Expected nine positive summaries'}
$chart=[System.Windows.Forms.DataVisualization.Charting.Chart]::new()
try{
 $chart.Width=1600;$chart.Height=740;$chart.BackColor=[Drawing.Color]::White;$chart.AntiAliasing='All'
 $title=$chart.Titles.Add('KURE · 청크 크기와 질의 변환 비교');$title.Font=[Drawing.Font]::new('Malgun Gothic',24)
 $sub=$chart.Titles.Add('동일 원문 6,527개 단위 · 코퍼스 내 질문 36개 · 중복 64자 · 고정 정답 기준');$sub.Font=[Drawing.Font]::new('Malgun Gothic',13)
 $legend=$chart.Legends.Add('modes');$legend.Docking='Bottom';$legend.Font=[Drawing.Font]::new('Malgun Gothic',11)
 $labels=@{original='원본';rewritten='기존 의미 보존 정제';legal_transformed='법률 쟁점 중심 변환'};$colors=@{original='SlateGray';rewritten='RoyalBlue';legal_transformed='SeaGreen'}
 foreach($spec in @(@{key='hitAt5';title='정답을 하나 이상 찾은 질문 (Hit@5)';max=100;factor=100;format="0.0'%'"},@{key='completeAt5';title='필요 근거를 전부 찾은 질문 (Complete@5)';max=100;factor=100;format="0.0'%'"})){
  $area=$chart.ChartAreas.Add($spec.key);$area.Position=[System.Windows.Forms.DataVisualization.Charting.ElementPosition]::new($(if($spec.key -eq 'hitAt5'){3}else{52}),20,46,61)
  $area.AxisY.Minimum=0;$area.AxisY.Maximum=$spec.max;$area.AxisY.MajorGrid.LineColor=[Drawing.Color]::LightGray;$area.AxisX.MajorGrid.Enabled=$false;$area.AxisX.Interval=1;$area.AxisX.LabelStyle.Font=[Drawing.Font]::new('Malgun Gothic',13)
  $area.AxisY.LabelStyle.Format=$(if($spec.factor -eq 100){"0'%'"}else{'0.0'})
  $caption=$chart.Titles.Add($spec.title);$caption.DockedToChartArea=$spec.key;$caption.Font=[Drawing.Font]::new('Malgun Gothic',16)
  foreach($mode in @('original','rewritten','legal_transformed')){
   $series=$chart.Series.Add($spec.key+'-'+$mode);$series.ChartArea=$spec.key;$series.ChartType='Column';$series.Color=[Drawing.Color]::FromName($colors[$mode]);$series.LegendText=$labels[$mode];$series.IsVisibleInLegend=$spec.key -eq 'hitAt5';$series.IsValueShownAsLabel=$true;$series.SmartLabelStyle.Enabled=$false;$series.LabelFormat=$spec.format;$series.Font=[Drawing.Font]::new('Malgun Gothic',11)
   foreach($size in @(300,500,700)){$matches=@($rows|Where-Object {$_.queryMode -eq $mode -and [int]$_.maxChars -eq $size});if($matches.Count -ne 1){throw 'Missing chart configuration'};$null=$series.Points.AddXY("${size}자",$spec.factor*[double]$matches[0].($spec.key))}
  }
 }
 $footer=$chart.Titles.Add('법률 쟁점 변환의 Complete@5: 300자 3/36 · 500자 4/36 · 700자 3/36 | 탐색 평가이며 실제 답변 정확도가 아님');$footer.Docking='Bottom';$footer.Font=[Drawing.Font]::new('Malgun Gothic',11)
 $chart.SaveImage((Join-Path $dir 'query-chunk-comparison.png'),'Png')
}finally{$chart.Dispose()}
$queries=@(Import-Csv (Join-Path $dir 'queries.csv')|Where-Object {$_.cohort -eq 'in_corpus' -and $_.queryMode -eq 'legal_transformed'})
$pairs=@(foreach($size in @(500,700)){foreach($old in $queries|Where-Object maxChars -eq '300'){$new=@($queries|Where-Object {$_.queryId -eq $old.queryId -and [int]$_.maxChars -eq $size});if($new.Count -ne 1){throw 'Paired identity mismatch'};[pscustomobject]@{maxChars=$size;queryId=$old.queryId;recall300=[double]$old.recallAt5;recallNew=[double]$new[0].recallAt5;delta=[double]$new[0].recallAt5-[double]$old.recallAt5}}})
if($pairs.Count -ne 72){throw 'Expected 72 paired rows'}
$pairs|Export-Csv (Join-Path $dir 'paired-recall-changes.csv') -NoTypeInformation -Encoding UTF8
Write-Host 'Saved query-chunk-comparison.png and 72 paired recall changes'
