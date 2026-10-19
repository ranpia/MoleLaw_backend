[CmdletBinding()]
param([ValidateSet('embeddinggemma','bge-m3','kure-v1')][string[]]$Models=@('embeddinggemma','bge-m3','kure-v1'),[ValidateSet(300,500,700)][int]$Size=700)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
function Get-NearestRankPercentile($Values,[double]$Percentile) {
    $ordered=@($Values | Sort-Object)
    if (!$ordered.Count) { throw 'No latency samples.' }
    return $ordered[[math]::Max(0,[math]::Ceiling($Percentile*$ordered.Count)-1)]
}
$root=Join-Path $ReportsRoot 'qdrant-lab/benchmark'
if ($Size -ne 700) { $root=Join-Path $root "char$Size" }
$chunkVersion="char$Size-overlap64-article-v1"
$quality=@();$latency=@();$agreement=@()
foreach ($model in $Models) {
    $directory=Join-Path $root $model
    $report=Get-Content -Raw -Encoding UTF8 (Join-Path $directory 'report.json') | ConvertFrom-Json
    $queries=@(Import-Csv (Join-Path $directory 'queries.csv'))
    $samples=@(Import-Csv (Join-Path $directory 'latency.csv'))
    $top=@(Import-Csv (Join-Path $directory 'top10.csv'))
    $datasetPath=$(if ($Size -eq 500) { Join-Path $DataRoot 'questions.json' } else { Join-Path $DataRoot "variants/$chunkVersion/questions.json" })
    $data=Get-Content -Raw -Encoding UTF8 $datasetPath | ConvertFrom-Json
    $baseline=Get-Content -Raw -Encoding UTF8 (Join-Path $ReportsRoot "$model/$chunkVersion/summary.json") | ConvertFrom-Json
    if ($report.questionDatasetSha256 -ne (Get-FileHash $datasetPath).Hash -or $report.queryRewritesSha256 -ne (Get-FileHash (Join-Path $ReviewRoot 'query-rewrites-v1.json')).Hash -or $report.metadata.corpus_sha256 -ne $baseline.corpusSha256 -or $report.metadata.model_digest -ne $baseline.model.modelDigest) { throw 'Benchmark input identity mismatch.' }
    if ($queries.Count -ne 660 -or $samples.Count -ne 440*$report.repeats -or $top.Count -ne 6600) { throw 'Incomplete benchmark outputs.' }
    $queryIndex=@{};$hitIndex=@{};$sampleIndex=@{}
    foreach ($row in $queries) { $key="$($row.queryMode)/$($row.engine)/$($row.queryId)"; $queryIndex[$key]=@($queryIndex[$key] | Where-Object { $null -ne $_ })+@($row) }
    foreach ($row in $top) { $key="$($row.queryMode)/$($row.engine)/$($row.queryId)"; $hitIndex[$key]=@($hitIndex[$key] | Where-Object { $null -ne $_ })+@($row) }
    foreach ($row in $samples) { $key="$($row.queryMode)/$($row.engine)/$($row.queryId)"; $sampleIndex[$key]=@($sampleIndex[$key] | Where-Object { $null -ne $_ })+@($row) }
    foreach ($mode in @('original','rewritten')) {
        $historicalDirectory=$(if ($mode -eq 'original') { Join-Path $ReportsRoot "$model/$chunkVersion" } elseif ($Size -eq 700) { Join-Path $ReportsRoot "query-rewrite-v1/$model" } else { Join-Path $ReportsRoot "query-rewrite-v1/char$Size/$model" })
        $historical=@(Import-Csv (Join-Path $historicalDirectory 'queries.csv'))
        foreach ($q in $data.questions) {
            foreach ($engine in @('local','exact','ann')) {
                $row=@($queryIndex["$mode/$engine/$($q.id)"])
                if ($row.Count -ne 1 -or $row[0].model -ne $model -or $row[0].split -ne $q.split -or $row[0].answerability -ne $q.answerability) { throw 'Query identity mismatch.' }
                $hits=@($hitIndex["$mode/$engine/$($q.id)"] | Sort-Object { [int]$_.rank })
                if ($hits.Count -ne 10 -or @($hits.unitId | Select-Object -Unique).Count -ne 10) { throw 'Invalid top ten.' }
                for ($i=0;$i -lt 10;$i++) {
                    if ([int]$hits[$i].rank -ne $i+1 -or (($hits[$i].relevant -eq 'True') -ne ($hits[$i].unitId -in @($q.relevantUnitIds)))) { throw 'Wrong hit rank or gold label.' }
                }
                if ($engine -ne 'local') {
                    $measurements=@($sampleIndex["$mode/$engine/$($q.id)"])
                    if ($measurements.Count -ne $report.repeats -or @($measurements | ForEach-Object { $_.repeat } | Select-Object -Unique).Count -ne $report.repeats) { throw 'Missing repeated samples.' }
                }
            }
        }
        foreach ($split in @('development','held_out')) {
            foreach ($engine in @('local','exact','ann')) {
                $positive=@($queries | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq $engine -and $_.split -eq $split -and $_.answerability -eq 'in_corpus' })
                if ($positive.Count -ne $(if ($split -eq 'development') {30} else {70})) { throw 'Invalid positive split count.' }
                $row=[ordered]@{model=$model;queryMode=$mode;split=$split;engine=$engine;positiveQueries=$positive.Count}
                foreach ($metric in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','mrrAt10','ndcgAt5','ndcgAt10')) {
                    $row[$metric]=($positive | ForEach-Object { [double]$_.$metric } | Measure-Object -Average).Average
                }
                $row.top1HitRate=@($positive | Where-Object top1Relevant -eq 'True').Count/[double]$positive.Count
                $quality+=[pscustomobject]$row
            }
        }
        foreach ($engine in @('exact','ann')) {
            $selected=@($samples | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq $engine })
            $client=@($selected | ForEach-Object { [double]$_.clientMs });$server=@($selected | ForEach-Object { [double]$_.serverMs })
            if (@($client | Where-Object { $_ -lt 0 -or [double]::IsNaN($_) -or [double]::IsInfinity($_) }).Count -gt 0) { throw 'Invalid latency.' }
            $latency+=[pscustomobject]@{model=$model;queryMode=$mode;engine=$engine;samples=$selected.Count
                clientMedianMs=(Get-NearestRankPercentile $client 0.5);clientP95Ms=(Get-NearestRankPercentile $client 0.95)
                serverMedianMs=(Get-NearestRankPercentile $server 0.5);serverP95Ms=(Get-NearestRankPercentile $server 0.95)}
        }
        $current=@($queries | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq 'local' })
        $changed=0;$annRecallChanged=0;$annNdcgChanged=0;$changedTop10=0
        foreach ($row in @($current | Where-Object answerability -eq 'in_corpus')) {
            $old=@($historical | Where-Object queryId -eq $row.queryId)[0]
            if ([math]::Abs([double]$row.recallAt5-[double]$old.recallAt5) -gt 1e-6) { $changed++ }
        }
        foreach ($row in $current) {
            $exactRow=$queryIndex["$mode/exact/$($row.queryId)"][0]
            $annRow=$queryIndex["$mode/ann/$($row.queryId)"][0]
            if ([double]$row.annExactTop10Overlap -lt 1) { $changedTop10++ }
            if ($row.answerability -eq 'in_corpus') {
                if ([math]::Abs([double]$exactRow.recallAt5-[double]$annRow.recallAt5) -gt 1e-8) { $annRecallChanged++ }
                if ([math]::Abs([double]$exactRow.ndcgAt10-[double]$annRow.ndcgAt10) -gt 1e-8) { $annNdcgChanged++ }
            }
        }
        $agreement+=[pscustomobject]@{model=$model;queryMode=$mode;questions=$current.Count
            meanAnnExactTop10Overlap=($current | ForEach-Object { [double]$_.annExactTop10Overlap } | Measure-Object -Average).Average
            meanLocalExactTop10Overlap=($current | ForEach-Object { [double]$_.localExactTop10Overlap } | Measure-Object -Average).Average
            maxExactScoreError=($current | ForEach-Object { [double]$_.exactMaxScoreError } | Measure-Object -Maximum).Maximum
            historicalRecallAt5ChangedQueries=$changed;annTop10ChangedQueries=$changedTop10;annRecallAt5ChangedQueries=$annRecallChanged;annNdcgAt10ChangedQueries=$annNdcgChanged}
    }
}
$quality | Export-Csv (Join-Path $root 'comparison.csv') -NoTypeInformation -Encoding UTF8
$latency | Export-Csv (Join-Path $root 'latency-summary.csv') -NoTypeInformation -Encoding UTF8
$agreement | Export-Csv (Join-Path $root 'agreement.csv') -NoTypeInformation -Encoding UTF8
$lines=@('# Qdrant 전체 질문 검색 비교', '', '700문자 청크에서 모델별 원본 질문 110개와 고정 정제 질문 110개를 실행했다. 같은 질문 벡터로 local(기존 C# 전수 계산), exact(Qdrant 전수), ann(Qdrant 근사 설정)을 비교했다. 컬렉션과 문서 벡터는 수정하지 않았다.', '',
    'original은 원본 질문, rewritten은 정제 질문이다. development는 개발용 양성 30개, held_out은 검증용 양성 70개다. 정답 없는 질문은 품질 지표에서 제외하고 시간·검색 일치율에는 포함했다.', '',
    '## 검색 품질', '', '| 모델 | 질문 | 분할 | 방식 | Recall@5 | Recall@10 | 1위 적중률 | Complete@5 | MRR@10 | nDCG@5 | nDCG@10 |', '| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $quality) {
    $cells=@($row.model,$row.queryMode,$row.split,$row.engine)
    foreach ($key in @('recallAt5','recallAt10','top1HitRate','completeAt5')) { $cells+=([math]::Round(100*$row.$key,2)).ToString()+'%' }
    foreach ($key in @('mrrAt10','ndcgAt5','ndcgAt10')) { $cells+=[math]::Round($row.$key,4) }
    $lines+='| '+($cells -join ' | ')+' |'
}
$lines+=@('', 'MRR@10은 첫 정답이 10위 밖이면 0으로 처리한다. 이전 보고서의 전체 순위 MRR과 정의가 다르므로 직접 같은 수치로 비교하지 않는다. Hit@5·Precision@5도 [CSV](comparison.csv)에 보존했다.', '',
    '## 검색 결과가 얼마나 같은가?', '', '| 모델 | 질문 | ANN·전수 상위 10개 겹침 | 기존 계산·Qdrant 전수 겹침 | 과거 Recall@5가 달라진 질문 수 |', '| --- | --- | ---: | ---: | ---: |')
foreach ($row in $agreement) { $lines+='| '+$row.model+' | '+$row.queryMode+' | '+([math]::Round(100*$row.meanAnnExactTop10Overlap,2))+'% | '+([math]::Round(100*$row.meanLocalExactTop10Overlap,2))+'% | '+$row.historicalRecallAt5ChangedQueries+' |' }
$lines+=@('', '겹침 100%는 검색된 조문 집합이 같다는 뜻이다. 순서가 같거나 모든 조문이 정답이라는 뜻은 아니다. 전수 검색 점수는 기존 계산과 오차 0.00001 이내인지도 질문마다 검사했다. 동점에서는 조문 순서나 마지막 포함 조문이 달라질 수 있다.', '',
    '| 모델 | 질문 | 상위 10개가 달라진 질문 수 / 110 | Recall@5가 달라진 양성 질문 수 / 100 | nDCG@10이 달라진 양성 질문 수 / 100 |', '| --- | --- | ---: | ---: | ---: |')
foreach ($row in $agreement) { $lines+='| '+$row.model+' | '+$row.queryMode+' | '+$row.annTop10ChangedQueries+' | '+$row.annRecallAt5ChangedQueries+' | '+$row.annNdcgAt10ChangedQueries+' |' }
$changedRecallTotal=($agreement | Measure-Object annRecallAt5ChangedQueries -Sum).Sum
$recallInterpretation=$(if ($changedRecallTotal -eq 0) { '이번 측정에서는 모든 구성의 질문별 Recall@5가 유지됐다.' } else { "이번 측정에서는 근사 설정으로 Recall@5가 달라진 양성 질문 변형이 $changedRecallTotal 개 있었다. 모델·질문별 차이를 표와 원본 CSV에서 확인해야 한다." })
$lines+=@('', '상위 조문 일부가 바뀌어도 정답 근거가 그대로 포함되면 Recall은 유지될 수 있다. 반대로 평균이 같아도 질문별 개선·악화가 상쇄될 수 있으므로 질문별 변화 수도 함께 계산했다.', '',
    $recallInterpretation, '', '근사 검색이 상위의 비정답 조문을 놓치면 정답이 상대적으로 앞에 나와 지표가 좋아질 수도 있으므로, 근사 검색이 본질적으로 더 정확하다는 근거로 해석하지 않는다.', '',
    '## 검색 시간', '', '| 모델 | 질문 | 방식 | 측정 수 | 클라이언트 중앙값 ms | 클라이언트 p95 ms | 서버 중앙값 ms | 서버 p95 ms |', '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $latency) {
    $cells=@($row.model,$row.queryMode,$row.engine,$row.samples)
    foreach ($key in @('clientMedianMs','clientP95Ms','serverMedianMs','serverP95Ms')) { $cells+=[math]::Round($row.$key,3) }
    $lines+='| '+($cells -join ' | ')+' |'
}
$lines+=@('', '중앙값은 측정값의 가운데, p95는 약 95%의 요청이 그 시간 안에 끝났다는 뜻이다. nearest-rank 방식으로 계산했다. 클라이언트 시간에는 PowerShell의 JSON 변환·HTTP·응답 해석이 포함된다. 서버 시간은 Qdrant 응답의 time 필드다. 두 값 모두 질문 임베딩·LLM 정제·답변 생성 시간을 포함하지 않는다.', '',
    '기본 3회씩 순차 측정했으며 질문마다 exact와 ann의 실행 순서를 번갈아 적용했다. 처음 각 방식 한 번은 준비 요청으로 제외했다. 동시 사용자 부하 시험이 아니며 이전에 한 번 측정했던 임베딩 지연과 비교하지 않는다. 기존 C# 계산은 프로세스 내부에서 실행되므로 HTTP 검색 시간과 같은 조건의 속도 비교가 아니다.', '',
    'ann은 exact=false 및 hnsw_ef=128 설정을 뜻한다. Qdrant가 실제로 모든 요청에 HNSW를 선택했다고 확인한 것은 아니다. 작은 데이터에서는 전수 탐색을 선택할 수도 있으므로 결과·시간이 비슷해도 이상이 아니다.', '',
    '정제본은 대화형 어시스턴트가 작성한 고정 질문이며 블라인드 실험은 아니다. 이번 결과를 보고 질문이나 모델을 다시 조정하지 않았다. 정제 전후 범위 밖 오수용률은 [별도 비교](../../query-rewrite-v1/comparison.md)를 참고한다. 이번 실험은 임계값을 새로 학습하지 않았다.', '',
    '세부 자료: 모델별 queries.csv(질문별 지표), top10.csv(상위 조문), latency.csv(반복 측정 원본), report.json(환경·입력 정보).')
[IO.File]::WriteAllText((Join-Path $root 'comparison.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
if ($Size -ne 700) {
    $path=Join-Path $root 'comparison.md'
    $text=[IO.File]::ReadAllText($path).Replace('700문자 청크',"$Size 문자 청크")
    $text=$text.Replace('Gemma 정제본의 급여 압류 질문(execution-5)은 nDCG@10이 전수 0.7039에서 근사 0.8396으로 달라졌다. ','')
    $text=$text.Replace('../../query-rewrite-v1/comparison.md','../../../chunk-experiments/comparison.md')
    [IO.File]::WriteAllText($path,$text,[Text.UTF8Encoding]::new($false))
}
$agreement | Format-Table -AutoSize
$latency | Format-Table -AutoSize
