[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
. (Join-Path $PSScriptRoot 'lib/QueryRewrites.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$version = 'char700-overlap64-article-v1'
$dataset = Join-Path $DataRoot "variants/$version"
$dataPath = Join-Path $dataset 'questions.json'
$data = Get-Content -Raw -Encoding UTF8 $dataPath | ConvertFrom-Json
$identity = Get-ComparisonIdentity $dataset
$rewritesPath = Join-Path $ReviewRoot 'query-rewrites-v1.json'
$rewrites = Read-QueryRewrites $rewritesPath $data (Get-FileHash $dataPath).Hash $identity
$target = Join-Path $ReportsRoot 'query-rewrite-v1'
$summaryRows = @(); $pairedRows = @()
foreach ($model in @('embeddinggemma','bge-m3','kure-v1')) {
    $baselineDirectory = Join-Path $ReportsRoot "$model/$version"
    $baseline = Get-Content -Raw -Encoding UTF8 (Join-Path $baselineDirectory 'summary.json') | ConvertFrom-Json
    $maps = @{}
    foreach ($mode in @('original','rewritten')) {
        $directory = $(if ($mode -eq 'original') { $baselineDirectory } else { Join-Path $target $model })
        $reportFile = $(if ($mode -eq 'original') { 'summary.json' } else { 'report.json' })
        $report = Get-Content -Raw -Encoding UTF8 (Join-Path $directory $reportFile) | ConvertFrom-Json
        if ($report.datasetSha256 -ne (Get-FileHash $dataPath).Hash -or $report.comparisonIdentitySha256 -ne $identity -or $report.corpusSha256 -ne $data.corpus.sha256) { throw 'Incompatible input identity.' }
        foreach ($field in @('threads','batchSize','chunkingVersion')) { if ($report.$field -ne $baseline.$field) { throw "Changed run condition: $field" } }
        foreach ($field in @('modelDigest','dimensions','inputMode','queryFormat','documentFormat','ollamaVersion','inferenceRuntime','inferenceRuntimeVersion','dtype','device')) { if ($report.model.$field -ne $baseline.model.$field) { throw "Changed model condition: $field" } }
        if ($mode -eq 'rewritten' -and ($report.queryRewritesSha256 -ne (Get-FileHash $rewritesPath).Hash -or $report.queryMode -ne 'llm-rewrite-v1' -or $report.newVectors -ne 0)) { throw 'Invalid rewrite provenance.' }
        $queries = @(Import-Csv (Join-Path $directory 'queries.csv'))
        $hits = @(Import-Csv (Join-Path $directory 'top10.csv'))
        if ($queries.Count -ne 110 -or $hits.Count -ne 1100) { throw 'Incomplete report.' }
        $map = @{}
        foreach ($q in $data.questions) {
            $query = @($queries | Where-Object queryId -eq $q.id)
            $top = @($hits | Where-Object queryId -eq $q.id | Sort-Object { [int]$_.rank })
            if ($query.Count -ne 1 -or $query[0].split -ne $q.split -or $query[0].answerability -ne $q.answerability -or $top.Count -ne 10 -or @($top | ForEach-Object { $_.rank } | Select-Object -Unique).Count -ne 10) { throw 'Invalid query rows.' }
            for ($i=0; $i -lt 10; $i++) {
                if ([int]$top[$i].rank -ne ($i+1) -or $top[$i].query -cne $q.text -or $top[$i].split -ne $q.split -or $top[$i].answerability -ne $q.answerability -or (($top[$i].relevant -eq 'True') -ne ($top[$i].unitId -in @($q.relevantUnitIds)))) { throw 'Invalid ranked hit.' }
                if ($mode -eq 'rewritten' -and $top[$i].retrievalQuery -cne $rewrites[$q.id].rewrittenText) { throw 'Wrong retrieval query.' }
            }
            $metric = $null
            if ($q.answerability -eq 'in_corpus') {
                $metric = Get-RankingMetrics $top $q.relevantUnitIds
                foreach ($k in @(5,10)) { if ([math]::Abs($metric."recallAt$k" - [double]$query[0]."recallAt$k") -gt 1e-6) { throw 'Recomputed recall differs.' } }
            }
            $map[$q.id] = [pscustomobject]@{query=$query[0];top=$top[0];metric=$metric}
        }
        $maps[$mode] = $map
        foreach ($split in @('development','held_out')) {
            $positive = @($data.questions | Where-Object { $_.split -eq $split -and $_.answerability -eq 'in_corpus' } | ForEach-Object { $map[$_.id] })
            $negative = @($data.questions | Where-Object { $_.split -eq $split -and $_.answerability -eq 'out_of_corpus' } | ForEach-Object { $map[$_.id] })
            $stored = @($report.summary | Where-Object split -eq $split)[0]
            $originalThreshold = [double]@($baseline.summary | Where-Object split -eq 'development')[0].threshold
            $summaryRows += [pscustomobject]@{model=$model;queryMode=$mode;split=$split;positiveQueries=$positive.Count;negativeQueries=$negative.Count
                top1HitRate=@($positive | Where-Object { $_.query.top1Relevant -eq 'True' }).Count/[double]$positive.Count
                recallAt5=($positive | ForEach-Object { [double]$_.query.recallAt5 } | Measure-Object -Average).Average
                recallAt10=($positive | ForEach-Object { [double]$_.query.recallAt10 } | Measure-Object -Average).Average
                mrr=($positive | ForEach-Object { [double]$_.query.reciprocalRank } | Measure-Object -Average).Average
                hitAt5=($positive | ForEach-Object { $_.metric.hitAt5 } | Measure-Object -Average).Average
                completeAt5=($positive | ForEach-Object { $_.metric.completeAt5 } | Measure-Object -Average).Average
                precisionAt5=($positive | ForEach-Object { $_.metric.precisionAt5 } | Measure-Object -Average).Average
                ndcgAt5=($positive | ForEach-Object { $_.metric.ndcgAt5 } | Measure-Object -Average).Average
                ndcgAt10=($positive | ForEach-Object { $_.metric.ndcgAt10 } | Measure-Object -Average).Average
                originalThreshold=$originalThreshold;calibratedThreshold=$stored.threshold
                fixedThresholdInCorpusAcceptance=@($positive | Where-Object { [double]$_.query.top1Similarity -ge $originalThreshold }).Count/[double]$positive.Count
                fixedThresholdNoAnswerFalseAcceptance=@($negative | Where-Object { [double]$_.query.top1Similarity -ge $originalThreshold }).Count/[double]$negative.Count
                calibratedInCorpusAcceptance=$stored.inCorpusAcceptanceRate;calibratedNoAnswerFalseAcceptance=$stored.noAnswerFalseAcceptanceRate
                meanQueryEmbeddingMs=$stored.meanQueryEmbeddingMs}
        }
    }
    foreach ($q in $data.questions) {
        $a=$maps.original[$q.id]; $b=$maps.rewritten[$q.id]
        $pairedRows += [pscustomobject]@{model=$model;queryId=$q.id;split=$q.split;answerability=$q.answerability;originalQuery=$q.text;rewrittenQuery=$rewrites[$q.id].rewrittenText
            originalRecallAt5=$a.query.recallAt5;rewrittenRecallAt5=$b.query.recallAt5
            recallAt5Delta=$(if ($q.answerability -eq 'in_corpus') { [double]$b.query.recallAt5-[double]$a.query.recallAt5 } else {$null})
            originalFirstRelevantRank=$a.query.firstRelevantRank;rewrittenFirstRelevantRank=$b.query.firstRelevantRank
            originalTop1Similarity=$a.query.top1Similarity;rewrittenTop1Similarity=$b.query.top1Similarity
            originalTop1Title=$a.top.title;rewrittenTop1Title=$b.top.title;originalTop1Relevant=$a.top.relevant;rewrittenTop1Relevant=$b.top.relevant}
    }
}
$summaryRows | Export-Csv (Join-Path $target 'comparison.csv') -NoTypeInformation -Encoding UTF8
$pairedRows | Export-Csv (Join-Path $target 'paired-queries.csv') -NoTypeInformation -Encoding UTF8
$lines=@('# 질문 정제 전후 검색 성능', '', '기존 세 모델의 700자 기록이다. Qwen3를 포함한 최신 결과는 [통합 비교](../chunk-experiments/comparison.md)를 따른다.', '', '같은 원문·정답·질문 분할·700문자 청크·모델 버전·문서 벡터를 사용하고 검색 질문만 바꿨다. 정제본 110개를 고정해 Gemma·BGE-M3·KURE-v1에 동일하게 적용했다.', '', '정제 방식은 GPT-4 API 실행이 아니라 대화형 Codex 어시스턴트가 작성한 질문이다. 정제 시 원본 질문만 사용했으나 대화에 기존 결과가 있었으므로 독립적인 블라인드 검증은 아니다. 프롬프트를 결과에 맞춰 재작성하지 않았다.', '', '## 원본과 정제본 비교', '', '| 모델 | 질문 | 분할 | Recall@5 | Recall@10 | 1위 적중률 | Hit@5 | Complete@5 | Precision@5 | MRR | nDCG@5 | nDCG@10 |', '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $summaryRows) {
    $cells=@($row.model,$row.queryMode,$row.split)
    foreach ($metric in @('recallAt5','recallAt10','top1HitRate','hitAt5','completeAt5','precisionAt5')) { $cells+=([math]::Round(100*$row.$metric,2)).ToString()+'%' }
    foreach ($metric in @('mrr','ndcgAt5','ndcgAt10')) { $cells+=[math]::Round($row.$metric,4) }
    $lines+='| '+($cells -join ' | ')+' |'
}
$lines+=@('', 'original은 원본 질문, rewritten은 정제 질문이다. development는 개발용 양성 30개, held_out은 검증용 양성 70개다. 정답 없는 질문은 위 표에서 제외했다.', '', '## 얼마나 바뀌었나?', '', '| 모델 | 분할 | Recall@5 변화 | 개선된 질문 | 악화된 질문 | 동일한 질문 |', '| --- | --- | ---: | ---: | ---: | ---: |')
foreach ($model in @('embeddinggemma','bge-m3','kure-v1')) {
    foreach ($split in @('development','held_out')) {
        $rows=@($pairedRows | Where-Object { $_.model -eq $model -and $_.split -eq $split -and $_.answerability -eq 'in_corpus' })
        $delta=($rows | Measure-Object recallAt5Delta -Average).Average
        $up=@($rows | Where-Object { $_.recallAt5Delta -gt 1e-8 }).Count
        $down=@($rows | Where-Object { $_.recallAt5Delta -lt -1e-8 }).Count
        $lines+='| '+$model+' | '+$split+' | '+([math]::Round(100*$delta,2))+'%p | '+$up+' | '+$down+' | '+($rows.Count-$up-$down)+' |'
    }
}
$lines+=@('', '개선·악화는 질문별 Recall@5 변화 기준이다. 평균 점수만 높아져도 일부 질문은 나빠질 수 있으므로 [질문별 전후 결과](paired-queries.csv)를 함께 확인한다.', '',
    '## 쉽게 풀어 읽으면', '',
    '개발용 Recall@5는 Gemma 64.22% → 72.00%, BGE-M3 65.72% → 70.17%로 둘 다 올랐다. 별도로 남겨 둔 검증용에서는 Gemma 78.14% → 78.43%로 거의 같고, BGE-M3는 73.62% → 80.95%로 7.33%p 올랐다. 이번 정제본은 BGE-M3에서 더 큰 효과를 보였다.', '',
    '검증용 70문제에서 필요한 정답을 모두 상위 5개에 넣은 Complete@5는 Gemma 46문제 → 44문제로 줄고, BGE-M3는 42문제 → 48문제로 늘었다. 정제했다고 모든 질문이 좋아지는 것은 아니다. 이 두 모델끼리 비교하면 정제본의 검증용 Recall@5·MRR·nDCG@5는 BGE-M3가 높지만, 개발용에서는 Gemma가 높다.', '',
    'KURE-v1도 같은 정제본으로 평가했다. 개발용 Recall@5는 58.39% → 73.50%, 검증용은 76.48% → 81.12%다. 정제 후 검증용 Recall@5에서 BGE-M3 80.95%보다 약 0.17%p 높지만 작은 차이이므로 확정적인 우열로 보지 않는다. 각 모델은 원본 질문과 정제 질문에 다르게 반응하므로 모델과 질문 입력 방식을 함께 검토해야 한다.', '',
    '## 좋아진 질문과 나빠진 질문', '',
    '아래는 결과를 확인한 뒤 고른 설명용 사례다. 전체 성능을 대표하는 무작위 표본은 아니다.', '',
    '| 모델 | 질문 ID | Recall@5 원본 → 정제 | 검색 1위 원본 → 정제 |', '| --- | --- | --- | --- |')
foreach ($selection in @(@('embeddinggemma','criminal-2'),@('embeddinggemma','criminal-4'),@('bge-m3','privacy-1'),@('bge-m3','labor-1'))) {
    $row=@($pairedRows | Where-Object { $_.model -eq $selection[0] -and $_.queryId -eq $selection[1] })[0]
    $lines+='| '+$row.model+' | '+$row.queryId+' | '+([math]::Round(100*[double]$row.originalRecallAt5,2))+'% → '+([math]::Round(100*[double]$row.rewrittenRecallAt5,2))+'% | '+$row.originalTop1Title+' → '+$row.rewrittenTop1Title+' |'
}
$lines+=@('', '예를 들어 개인정보 질문은 “회원 탈퇴 후에도 회사가 제 개인정보를 계속 보관하고 있어요.”에서 “회원 탈퇴 후에도 회사가 개인정보를 계속 보유하는 경우 적용되는 보유 및 파기 규정은 무엇인가요?”로 바꿨다. BGE-M3는 정답을 상위 5개에서 찾지 못하던 상태에서 찾는 상태로 바뀌었다.', '',
    '반대로 두 달 임금 미지급 질문은 “사용자가 근로자에게 두 달간 임금을 지급하지 않은 경우 적용되는 임금 지급 규정은 무엇인가요?”로 정리했지만 BGE-M3의 정답은 상위 5개 밖으로 밀렸다. 검색 1위 조문이 같아도 나머지 순서가 바뀌면 Recall이 달라질 수 있다.', '',
    '의미 보존에도 주의가 필요하다. 맡긴 돈의 개인 사용 질문에 “범죄 요건”이라는 관점을 넣은 사례처럼 표현 정리가 법률적 관점을 좁힐 수 있다. 이번 결과에는 이러한 용어·관점 보강의 효과도 포함되므로 단순 맞춤법 교정의 효과로 보지 않는다.', '',
    '## 자료가 없는 질문을 잘 걸러내는가?', '', '검색 점수가 기준 이상이면 수용한 것으로 계산한다. 실제 답변의 정확성이나 거절 여부를 측정한 것은 아니다. 기준값을 바꾸는 영향과 질문 정제의 영향을 구분하기 위해 두 결과를 모두 보존했다.', '', '| 모델 | 질문 | 분할 | 원본 기준 고정: 범위 안 수용률 | 원본 기준 고정: 범위 밖 오수용률 | 개발용 재설정: 범위 안 수용률 | 개발용 재설정: 범위 밖 오수용률 |', '| --- | --- | --- | ---: | ---: | ---: | ---: |')
foreach ($row in $summaryRows) {
    $cells=@($row.model,$row.queryMode,$row.split)
    foreach ($metric in @('fixedThresholdInCorpusAcceptance','fixedThresholdNoAnswerFalseAcceptance','calibratedInCorpusAcceptance','calibratedNoAnswerFalseAcceptance')) { $cells+=([math]::Round(100*$row.$metric,2)).ToString()+'%' }
    $lines+='| '+($cells -join ' | ')+' |'
}
$lines+=@('', '범위 안 수용률은 높을수록, 범위 밖 오수용률은 낮을수록 좋다. 음성은 분할별 5개라 1문제 차이가 20%p다. 재설정 기준은 정제된 개발용 질문에서만 정하고 검증용에는 그대로 적용했다.', '', '## 해석 범위', '', '이 결과는 대화형 LLM이 작성한 고정 질문의 검색 효과다. 실제 자동 정제 API의 반복성·시간·비용은 검증하지 않았다. 기존 원본 질문 결과는 보존했으며 현재 정제 결과에 맞춰 질문·정답·문서 벡터를 수정하지 않았다. Qdrant와 답변 생성은 실행하지 않았다.', '', '원본과 정제본의 임베딩 지연은 서로 다른 시점에 질문당 한 번 측정한 값이다. 정제에 걸리는 시간을 포함한 서비스 전체 속도로 해석하지 않는다. 질문의 의미 보존은 자동으로 증명되지 않으며 [정제 질문](../../review/query-rewrites-v1.json)을 검토할 수 있다.', '', '지표가 익숙하지 않다면 [예시로 보는 지표 설명](../similarity-and-metrics.md)을 참고한다. [전체 수치 CSV](comparison.csv), [질문별 CSV](paired-queries.csv)를 보존했다.')
[IO.File]::WriteAllText((Join-Path $target 'comparison.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$summaryRows | Format-Table model,queryMode,split,recallAt5,mrr,ndcgAt5 -AutoSize
