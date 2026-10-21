[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
Add-Type -AssemblyName System.Windows.Forms.DataVisualization
Add-Type -AssemblyName System.Drawing
$directory=Get-ReportPath 'chunk-experiments'
$rows=@(Import-Csv (Join-Path $directory 'comparison.csv') | Where-Object queryMode -eq 'rewritten')
$models=[ordered]@{embeddinggemma='Gemma';'bge-m3'='BGE-M3';'kure-v1'='KURE-v1';'qwen3-0.6b'='Qwen3 0.6B'}
$available=@($models.Keys | Where-Object { $_ -in $rows.model })
$profiles=Get-Content -Raw -Encoding UTF8 (Get-ReviewPath 'models.json') | ConvertFrom-Json
$colors=@{300='#3B82F6';500='#0D9488';700='#8B5CF6'}
$chart=[System.Windows.Forms.DataVisualization.Charting.Chart]::new()
try {
    $chart.Width=1600;$chart.Height=780
    $chart.BackColor=[Drawing.Color]::White
    $chart.AntiAliasing='All';$chart.TextAntiAliasingQuality='High'
    $font=[Drawing.Font]::new('Malgun Gothic',13)
    $title=$chart.Titles.Add('정제 질문 검색 성능 · 청크 크기 비교')
    $title.Font=[Drawing.Font]::new('Malgun Gothic',25,[Drawing.FontStyle]::Bold)
    $subtitle=$chart.Titles.Add('Recall@5: 필요한 정답 근거를 상위 5개에서 찾은 비율 · 높을수록 좋음')
    $subtitle.Font=[Drawing.Font]::new('Malgun Gothic',13)
    $legend=$chart.Legends.Add('sizes');$legend.Docking='Bottom';$legend.Alignment='Center';$legend.Font=$font
    $footer=$chart.Titles.Add('같은 질문·정답·정제본 | 빈 막대는 미실행 | 완료된 청크만 표시 | 수치는 정답 확률이 아님')
    $footer.Docking='Bottom';$footer.Font=[Drawing.Font]::new('Malgun Gothic',11)
    foreach ($split in @('development','held_out')) {
        $area=[System.Windows.Forms.DataVisualization.Charting.ChartArea]::new($split)
        $area.Position=[System.Windows.Forms.DataVisualization.Charting.ElementPosition]::new($(if ($split -eq 'development') {3} else {52}),20,46,65)
        $area.AxisY.Minimum=0;$area.AxisY.Maximum=100;$area.AxisY.Interval=20
        $area.AxisY.LabelStyle.Format="0'%'";$area.AxisY.LabelStyle.Font=$font
        $area.AxisY.MajorGrid.LineColor=[Drawing.Color]::FromArgb(225,230,236)
        $area.AxisY.LineColor=[Drawing.Color]::LightGray
        $area.AxisX.MajorGrid.Enabled=$false;$area.AxisX.Interval=1
        $area.AxisX.IsReversed=$true
        $area.AxisX.LabelStyle.Font=[Drawing.Font]::new('Malgun Gothic',15)
        $area.AxisX.LineColor=[Drawing.Color]::LightGray
        $area.AxisX.MajorTickMark.Enabled=$false
        $chart.ChartAreas.Add($area)
        $caption=$chart.Titles.Add($(if ($split -eq 'development') {'개발용 30문제 · 모델 선택 기준'} else {'검증용 70문제 · 별도 확인 결과'}))
        $caption.DockedToChartArea=$split;$caption.IsDockedInsideChartArea=$false
        $caption.Font=[Drawing.Font]::new('Malgun Gothic',17,[Drawing.FontStyle]::Bold)
        foreach ($size in @(300,500,700)) {
            $series=[System.Windows.Forms.DataVisualization.Charting.Series]::new("$split-$size")
            $series.ChartArea=$split;$series.ChartType='Bar';$series.Legend='sizes';$series.LegendText="$size 문자"
            $series.IsVisibleInLegend=$split -eq 'development'
            $series.Color=[Drawing.ColorTranslator]::FromHtml($colors[$size])
            $series.IsValueShownAsLabel=$true;$series.LabelFormat="0.00'%'"
            $series.Font=[Drawing.Font]::new('Malgun Gothic',12,[Drawing.FontStyle]::Bold)
            $series['PointWidth']='0.72';$series['BarLabelStyle']='Outside';$series.SmartLabelStyle.Enabled=$false
            foreach ($model in $available) {
                $match=@($rows | Where-Object { $_.model -eq $model -and [int]$_.maxChars -eq $size -and $_.split -eq $split })
                if ($match.Count -eq 0) {
                    $expectedSizes=$(if($profiles.$model.experimentSizes){@($profiles.$model.experimentSizes)}else{@(300,500,700)})
                    if($size -in $expectedSizes){throw "Missing chart input: $model/$size/$split"}
                    $index=$series.Points.AddXY($models[$model],0)
                    $series.Points[$index].IsEmpty=$true
                    $series.Points[$index].IsValueShownAsLabel=$false
                    continue
                }
                if ($match.Count -ne 1) { throw "Duplicate chart input: $model/$size/$split" }
                $index=$series.Points.AddXY($models[$model],100*[double]$match[0].recallAt5)
                $series.Points[$index].ToolTip=$models[$model]+" / $size / "+$match[0].recallAt5
            }
            $chart.Series.Add($series)
        }
    }
    $chart.SaveImage((Join-Path $directory 'recall-by-chunk.png'),'Png')
} finally { $chart.Dispose() }
$ranking=@($rows | Where-Object split -eq 'development' | Sort-Object @{Expression={[double]$_.recallAt5};Descending=$true},model,@{Expression={[int]$_.maxChars}})
$lines=@('# 한눈에 보는 모델·청크 비교','','![개발용과 검증용 정제 질문 Recall@5 비교](recall-by-chunk.png)','','모델별로 묶고 청크를 300 → 500 → 700문자 순서로 고정했다. 색깔은 두 그래프에서 같다. 비율 축은 모두 0~100%로 통일했고, 작은 차이는 막대 옆 숫자로 확인할 수 있다. 네 모델의 세 청크 평가 결과를 모두 표시했다.','','왼쪽은 설정 선택에 사용한 개발용 질문, 오른쪽은 따로 남겨 둔 검증용 질문이다. 같은 질문에 필요한 정답 근거를 얼마나 찾았는지를 측정한 값이며 실제 답변 정답률은 아니다.','','## 개발용 Recall@5가 높은 순서','','같은 Recall 값은 공동 수준으로 읽는다. 동률 안에서는 모델명·청크 크기 순서로 표시했으며 검증용 점수로 순서를 바꾸지 않았다.','','| 모델 | 문자 | 개발용 Recall@5 | 검증용 Recall@5 | 검증용 MRR |','| --- | ---: | ---: | ---: | ---: |')
foreach ($row in $ranking) {
    $held=@($rows | Where-Object { $_.model -eq $row.model -and $_.maxChars -eq $row.maxChars -and $_.split -eq 'held_out' })[0]
    $lines+='| '+$models[$row.model]+' | '+$row.maxChars+' | '+('{0:F2}' -f (100*[double]$row.recallAt5))+'% | '+('{0:F2}' -f (100*[double]$held.recallAt5))+'% | '+('{0:F4}' -f [double]$held.mrr)+' |'
}
$topValue=[double]$ranking[0].recallAt5
$best=@($ranking | Where-Object { [math]::Abs([double]$_.recallAt5-$topValue) -lt 1e-8 } | ForEach-Object { $models[$_.model]+' '+$_.maxChars+'문자' }) -join ', '
$lines+=@('',"표에 포함된 구성에서 개발용 점수가 가장 높은 구성은 $best 입니다. 첫 정답 순위나 필요한 근거 전체를 확보하는 지표까지 항상 최고라는 뜻은 아니다. 한 지표의 최고값을 모든 상황의 우승 모델로 해석하지 않는다.",'','[원본·정제 전체 지표](comparison.md) · [원본 수치 CSV](comparison.csv)')
[IO.File]::WriteAllText((Join-Path $directory 'overview.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
Write-Host 'Saved recall-by-chunk.png and overview.md from comparison.csv.'
