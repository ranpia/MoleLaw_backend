[CmdletBinding()]
param([ValidateSet('embeddinggemma','bge-m3','kure-v1')][string[]]$Models=@('embeddinggemma','bge-m3','kure-v1'))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
$rows=@();$timing=@();$agreement=@();$commonIdentity=$null;$identities=@{}
$rewriteHash=(Get-FileHash (Join-Path $ReviewRoot 'query-rewrites-v1.json')).Hash
foreach ($model in $Models) {
    foreach ($size in @(300,500,700)) {
        $version="char$size-overlap64-article-v1"
        $dataDirectory=$(if ($size -eq 500) { $DataRoot } else { Join-Path $DataRoot "variants/$version" })
        if (!$identities.ContainsKey($size)) { $identities[$size]=Get-ComparisonIdentity $dataDirectory }
        $identity=$identities[$size]
        if ($commonIdentity -and $identity -ne $commonIdentity) { throw 'Cross-chunk source/question/gold/split identity differs.' }
        $commonIdentity=$identity
        $dataHash=(Get-FileHash (Join-Path $dataDirectory 'questions.json')).Hash
        $benchmarkDirectory=$(if ($size -eq 700) { Join-Path $ReportsRoot "qdrant-lab/benchmark/$model" } else { Join-Path $ReportsRoot "qdrant-lab/benchmark/char$size/$model" })
        $benchmark=Get-Content -Raw -Encoding UTF8 (Join-Path $benchmarkDirectory 'report.json') | ConvertFrom-Json
        $qrows=@(Import-Csv (Join-Path $benchmarkDirectory 'queries.csv'))
        $samples=@(Import-Csv (Join-Path $benchmarkDirectory 'latency.csv'))
        if ($benchmark.questionDatasetSha256 -ne $dataHash -or $benchmark.queryRewritesSha256 -ne $rewriteHash -or $benchmark.metadata.chunking_version -ne $version -or $qrows.Count -ne 660 -or $samples.Count -ne 440*$benchmark.repeats) { throw 'Invalid benchmark input or count.' }
        foreach ($mode in @('original','rewritten')) {
            $directory=$(if ($mode -eq 'original') { Join-Path $ReportsRoot "$model/$version" } elseif ($size -eq 700) { Join-Path $ReportsRoot "query-rewrite-v1/$model" } else { Join-Path $ReportsRoot "query-rewrite-v1/char$size/$model" })
            $filename=$(if ($mode -eq 'original') {'summary.json'} else {'report.json'})
            $report=Get-Content -Raw -Encoding UTF8 (Join-Path $directory $filename) | ConvertFrom-Json
            if ($report.datasetSha256 -ne $dataHash -or $report.corpusSha256 -ne $benchmark.metadata.corpus_sha256 -or $report.model.modelDigest -ne $benchmark.metadata.model_digest -or $report.chunkingVersion -ne $version) { throw 'Wrong full-ranking report.' }
            if ($mode -eq 'rewritten' -and ($report.queryRewritesSha256 -ne $rewriteHash -or $report.newVectors -ne 0 -or $report.queryMode -ne 'llm-rewrite-v1' -or $report.comparisonIdentitySha256 -ne $identity)) { throw 'Invalid rewritten report.' }
            $fullQueries=@(Import-Csv (Join-Path $directory 'queries.csv'))
            if ($fullQueries.Count -ne 110 -or @($fullQueries.queryId | Select-Object -Unique).Count -ne 110) { throw 'Incomplete full-ranking report.' }
            foreach ($split in @('development','held_out')) {
                $summary=@($report.summary | Where-Object split -eq $split)[0]
                $positive=@($qrows | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq 'exact' -and $_.split -eq $split -and $_.answerability -eq 'in_corpus' })
                $ann=@($qrows | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq 'ann' -and $_.split -eq $split -and $_.answerability -eq 'in_corpus' })
                $expected=$(if ($split -eq 'development') {30} else {70})
                if ($positive.Count -ne $expected -or $ann.Count -ne $expected -or $summary.positiveQueries -ne $expected -or $summary.negativeQueries -ne 5) { throw 'Wrong split counts.' }
                $recall=($positive | ForEach-Object {[double]$_.recallAt5} | Measure-Object -Average).Average
                if ([math]::Abs($recall-$summary.recallAt5) -gt 1e-6) { throw 'Full ranking and Qdrant Recall@5 differ.' }
                $record=[ordered]@{model=$model;maxChars=$size;queryMode=$mode;split=$split;positiveQueries=$expected
                    dimensions=$report.model.dimensions;recallAt5=$summary.recallAt5;recallAt10=$summary.recallAt10;top1HitRate=$summary.top1HitRate;mrr=$summary.mrr
                    inCorpusAcceptanceRate=$summary.inCorpusAcceptanceRate;noAnswerFalseAcceptanceRate=$summary.noAnswerFalseAcceptanceRate;threshold=$summary.threshold}
                foreach ($metric in @('hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')) { $record[$metric]=($positive | ForEach-Object { [double]$_.$metric } | Measure-Object -Average).Average }
                $record.annRecallAt5=($ann | ForEach-Object { [double]$_.recallAt5 } | Measure-Object -Average).Average
                $record.annNdcgAt10=($ann | ForEach-Object { [double]$_.ndcgAt10 } | Measure-Object -Average).Average
                $rows+=[pscustomobject]$record
            }
            $comparison=@($qrows | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq 'exact' })
            $changed=0
            foreach ($query in @($comparison | Where-Object answerability -eq 'in_corpus')) {
                $annQuery=@($qrows | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq 'ann' -and $_.queryId -eq $query.queryId })[0]
                if ([math]::Abs([double]$annQuery.recallAt5-[double]$query.recallAt5) -gt 1e-8) { $changed++ }
            }
            $agreement+=[pscustomobject]@{model=$model;maxChars=$size;queryMode=$mode;annExactOverlap=($comparison | ForEach-Object { [double]$_.annExactTop10Overlap } | Measure-Object -Average).Average;annRecallAt5ChangedQueries=$changed;indexedVectors=$benchmark.indexedVectors;points=$benchmark.points}
            foreach ($engine in @('exact','ann')) {
                $selected=@($samples | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq $engine })
                if ($selected.Count -ne 110*$benchmark.repeats) { throw 'Incomplete timing samples.' }
                $record=[ordered]@{model=$model;maxChars=$size;queryMode=$mode;engine=$engine;samples=$selected.Count}
                foreach ($scope in @('client','server')) {
                    $values=@($selected | ForEach-Object { [double]$_."${scope}Ms" } | Sort-Object)
                    $record["${scope}MedianMs"]=$values[[math]::Ceiling(.5*$values.Count)-1]
                    $record["${scope}P95Ms"]=$values[[math]::Ceiling(.95*$values.Count)-1]
                }
                $timing+=[pscustomobject]$record
            }
        }
    }
}
$target=Join-Path $ReportsRoot 'chunk-experiments'
$null=New-Item -ItemType Directory -Force $target
$rows | Export-Csv (Join-Path $target 'comparison.csv') -NoTypeInformation -Encoding UTF8
$timing | Export-Csv (Join-Path $target 'latency.csv') -NoTypeInformation -Encoding UTF8
$agreement | Export-Csv (Join-Path $target 'agreement.csv') -NoTypeInformation -Encoding UTF8
$lines=@('# 모델·청크·질문 정제 통합 비교', '', '![정제 질문 Recall@5 그래프](recall-by-chunk.png)', '', '[그래프와 정렬된 요약표로 보기](overview.md)', '', '300·500·700문자 청크에서 같은 원문 5,486개 단위와 같은 질문·정답·분할을 사용했다. 정제 질문 110개는 처음 작성한 것을 그대로 재사용했고 결과에 맞춰 고치지 않았다. 완료된 모델만 아래 표에 포함한다.', '',
    'Recall@5는 필요한 근거 중 상위 5개에서 찾은 비율, Complete@5는 필요한 근거를 전부 찾은 질문의 비율이다. MRR은 첫 정답의 순위, nDCG는 여러 정답의 배치를 본다. 모두 높을수록 좋다. [예시를 이용한 설명](../similarity-and-metrics.md)을 참고한다.', '', '## 정제 후 핵심 결과', '',
    '| 모델 | 문자 | 개발용 Recall@5 원본 → 정제 | 검증용 Recall@5 원본 → 정제 | 검증용 정제 MRR |', '| --- | ---: | ---: | ---: | ---: |')
foreach ($model in $Models) {
    foreach ($size in @(300,500,700)) {
        $r=@($rows | Where-Object { $_.model -eq $model -and $_.maxChars -eq $size })
        $a=@($r | Where-Object { $_.queryMode -eq 'original' -and $_.split -eq 'development' })[0];$b=@($r | Where-Object { $_.queryMode -eq 'rewritten' -and $_.split -eq 'development' })[0]
        $c=@($r | Where-Object { $_.queryMode -eq 'original' -and $_.split -eq 'held_out' })[0];$d=@($r | Where-Object { $_.queryMode -eq 'rewritten' -and $_.split -eq 'held_out' })[0]
        $lines+='| '+$model+' | '+$size+' | '+([math]::Round(100*$a.recallAt5,2))+'% → '+([math]::Round(100*$b.recallAt5,2))+'% | '+([math]::Round(100*$c.recallAt5,2))+'% → '+([math]::Round(100*$d.recallAt5,2))+'% | '+([math]::Round($d.mrr,4))+' |'
    }
}
$dev=@($rows | Where-Object { $_.queryMode -eq 'rewritten' -and $_.split -eq 'development' })
$bestValue=($dev | Measure-Object recallAt5 -Maximum).Maximum
$best=@($dev | Where-Object { [math]::Abs($_.recallAt5-$bestValue) -lt 1e-8 })
$bestLabels=@($best | ForEach-Object { $_.model+' '+$_.maxChars+'문자' }) -join ', '
$bestSentence='완료된 구성 중 개발용 정제 Recall@5가 가장 높은 것은 '+$bestLabels+' ('+([math]::Round(100*$bestValue,2))+'%)다. 이것은 필요한 근거를 상위 5개 안에 얼마나 회수했는지에 따른 우선 후보다. 모델의 첫 정답 순위나 모든 근거 확보 여부까지 항상 최고라는 뜻은 아니다.'
$lines+=@('', '## 결과를 읽는 순서', '',
    $bestSentence, '',
    '청크를 작게 나누면 검색 대상 조각은 늘어나지만 질문에 필요한 문맥이 짧아진다. 크게 나누면 문맥은 늘어나지만 덜 관련된 문장이 함께 들어갈 수 있다. 따라서 300·500·700 중 어느 쪽이 항상 좋다고 가정하지 않고 같은 질문에서 측정했다.', '',
    '검증용 결과는 별도로 남겨 둔 질문에서의 확인값이다. 이미 공개된 작은 평가셋이므로 이 수치만 보고 반복 조정하거나 한국어 법령 전체의 우열로 확정하지 않는다. 첫 정답을 빨리 찾는 것이 중요하면 MRR, 여러 근거의 누락이 중요하면 Recall과 Complete를 함께 본다.')
foreach ($split in @('development','held_out')) {
    $lines+=@('', "## $split 전체 지표", '', '| 모델 | 문자 | 질문 | Recall@5 | Recall@10 | 1위 적중률 | Hit@5 | Complete@5 | Precision@5 | MRR | nDCG@5 | nDCG@10 |', '| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
    foreach ($row in @($rows | Where-Object split -eq $split)) {
        $cells=@($row.model,$row.maxChars,$row.queryMode)
        foreach ($key in @('recallAt5','recallAt10','top1HitRate','hitAt5','completeAt5','precisionAt5')) { $cells+=([math]::Round(100*$row.$key,2)).ToString()+'%' }
        foreach ($key in @('mrr','ndcgAt5','ndcgAt10')) { $cells+=[math]::Round($row.$key,4) }
        $lines+='| '+($cells -join ' | ')+' |'
    }
}
$lines+=@('', 'original은 원본, rewritten은 정제 질문이다. development는 개발용 양성 30개, held_out은 검증용 양성 70개다. MRR은 전체 순위 기준이며 Qdrant 전용 보고서의 MRR@10과 다르다. nDCG 등은 동일 질문 벡터의 Qdrant 전수 결과를 사용했고 Recall@5가 원본 평가 보고서와 일치하는지 검사했다.', '',
    '## Qdrant 근사 검색은 얼마나 달랐나?', '', '| 모델 | 문자 | 질문 | 전수·근사 상위 10개 겹침 | Recall@5가 달라진 질문 수 |', '| --- | ---: | --- | ---: | ---: |')
foreach ($row in $agreement) { $lines+='| '+$row.model+' | '+$row.maxChars+' | '+$row.queryMode+' | '+([math]::Round(100*$row.annExactOverlap,2))+'% | '+$row.annRecallAt5ChangedQueries+' |' }
$lines+=@('', '## 검색 시간', '', '| 모델 | 문자 | 질문 | 방식 | 클라이언트 중앙값 ms | 클라이언트 p95 ms | 서버 중앙값 ms | 서버 p95 ms |', '| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |')
foreach ($row in $timing) {
    $cells=@($row.model,$row.maxChars,$row.queryMode,$row.engine)
    foreach ($key in @('clientMedianMs','clientP95Ms','serverMedianMs','serverP95Ms')) { $cells+=[math]::Round($row.$key,3) }
    $lines+='| '+($cells -join ' | ')+' |'
}
$lines+=@('', '시간을 읽는 방법: 중앙값은 가운데 값, p95는 약 95%의 요청이 그 시간 안에 끝났다는 뜻이다. 질문별 3회 순차 측정했고 임베딩·LLM 정제·답변 생성은 제외했다. 클라이언트에는 JSON·HTTP 처리까지, 서버에는 Qdrant 처리 시간이 들어간다. 서로 다른 시점의 로컬 측정이므로 작은 시간 차이로 모델을 확정하지 않는다.', '',
    '근사 설정은 exact=false, hnsw_ef=128이고 색인 임계값은 동일하다. 작은 세그먼트는 HNSW가 아닌 전수 탐색 대상으로 남을 수 있으며 이는 미적재가 아니다. green·optimizer ok·정확한 포인트 수를 확인하고 실제 indexedVectors를 보고서에 남겼다. 인덱스 생성률이나 HNSW 호출 여부를 검색 정확도와 혼동하지 않는다.', '',
    '개발용 Recall@5가 원래 선택 기준이다. 공개된 검증용 결과로 질문·정답을 다시 조정하지 않으며, 정제본은 대화형 어시스턴트가 작성한 탐색 자료여서 독립 블라인드 검증은 아니다. 정제 후 검색 점수 분포가 달라질 수 있어 범위 안 수용률·범위 밖 오수용률·기준값은 CSV에 보존했다. 음성은 분할마다 5개뿐이다.', '',
    '상세 결과: [300문자 Qdrant](../qdrant-lab/benchmark/char300/comparison.md), [500문자 Qdrant](../qdrant-lab/benchmark/char500/comparison.md), [700문자 Qdrant](../qdrant-lab/benchmark/comparison.md). 현재 통합 표의 원본 검색 품질은 동일 벡터의 Qdrant 전수 기준이며 실제 근사 Recall@5·nDCG@10도 CSV에 함께 남겼다.')
[IO.File]::WriteAllText((Join-Path $target 'comparison.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
& (Join-Path $PSScriptRoot 'export-experiment-charts.ps1')
Write-Host "PASS: $($Models.Count) models, $($rows.Count) quality summaries, canonical identity=$commonIdentity"
