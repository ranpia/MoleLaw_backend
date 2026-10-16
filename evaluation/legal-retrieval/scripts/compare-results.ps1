[CmdletBinding()]
param([string]$ReportDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
if ($ReportDirectory) { $ReportsRoot = [IO.Path]::GetFullPath($ReportDirectory) }
$identities = @{}; $commonIdentity = $null; $configurations = @{}; $executionConditions = $null
$records = @()
$rankingRows = @()
$similarityRows = @()
foreach ($file in Get-ChildItem -LiteralPath $ReportsRoot -Recurse -Filter summary.json) {
    $r = Get-Content -Raw -Encoding UTF8 $file.FullName | ConvertFrom-Json
    if (!$r.chunkingVersion) { continue }
    if ($r.chunkingVersion -notmatch '^char(300|500|700)-overlap64-article-v1$') { throw 'Unsupported report chunk version.' }
    $directory = $(if ($r.chunkingVersion -eq 'char500-overlap64-article-v1') { $DataRoot } else { Join-Path $DataRoot "variants/$($r.chunkingVersion)" })
    $data = Get-Content -Raw -Encoding UTF8 (Join-Path $directory 'questions.json') | ConvertFrom-Json
    if ($r.datasetSha256 -ne (Get-FileHash (Join-Path $directory 'questions.json')).Hash -or $r.corpusSha256 -ne $data.corpus.sha256) { throw 'Report input hashes mismatch.' }
    if (!$identities.ContainsKey($directory)) {
        & (Join-Path $PSScriptRoot 'validate-evaluation.ps1') -DatasetDirectory $directory
        $identities[$directory] = Get-ComparisonIdentity $directory
    }
    $identity = $identities[$directory]
    # Legacy reports remain unchanged; derive identity only after their exact input hashes match.
    if ($r.comparisonIdentitySha256 -and ($r.comparisonIdentityVersion -ne 1 -or $r.comparisonIdentitySha256 -ne $identity)) { throw 'Report canonical identity mismatch.' }
    if ($commonIdentity -and $commonIdentity -ne $identity) { throw 'Incompatible source/question/gold/split identities.' }
    $commonIdentity = $identity
    $conditions = "$($r.threads)/$($r.batchSize)"
    if ($executionConditions -and $executionConditions -ne $conditions) { throw 'Report thread/batch conditions differ.' }
    $executionConditions = $conditions
    $configuration = "$($r.model.model)/$($r.chunkingVersion)"
    if ($configurations.ContainsKey($configuration)) { throw 'Duplicate report configuration.' }; $configurations[$configuration] = $true
    if (@($r.summary).Count -ne 2) { throw 'Incomplete report splits.' }
    foreach ($split in @('development','held_out')) {
        $rows = @($r.summary | Where-Object split -eq $split)
        $positiveCount = $(if ($split -eq 'development') { 30 } else { 70 })
        if ($rows.Count -ne 1 -or $rows[0].positiveQueries -ne $positiveCount -or $rows[0].negativeQueries -ne 5) { throw 'Report split counts mismatch.' }
    }
    foreach ($name in @('summary.csv','queries.csv','top10.csv')) { if (!(Test-Path -LiteralPath (Join-Path $file.DirectoryName $name))) { throw 'Missing report result file.' } }
    $queries = @(Import-Csv (Join-Path $file.DirectoryName 'queries.csv'))
    $top = @(Import-Csv (Join-Path $file.DirectoryName 'top10.csv'))
    if ($queries.Count -ne 110 -or @($queries.queryId | Select-Object -Unique).Count -ne 110 -or $top.Count -ne 1100) { throw 'Incomplete query results.' }
    foreach ($q in $data.questions) {
        $query = @($queries | Where-Object queryId -eq $q.id)
        $hits = @($top | Where-Object queryId -eq $q.id)
        $invalidHits = @($hits | Where-Object { $_.split -ne $q.split -or $_.answerability -ne $q.answerability -or [int]$_.rank -lt 1 -or [int]$_.rank -gt 10 })
        if ($query.Count -ne 1 -or $query[0].split -ne $q.split -or $query[0].answerability -ne $q.answerability -or $hits.Count -ne 10 -or @($hits | ForEach-Object { $_.rank } | Select-Object -Unique).Count -ne 10 -or $invalidHits.Count -gt 0) { throw "Query result identity mismatch: $($file.FullName), $($q.id)" }
        foreach ($hit in $hits) {
            if (($hit.relevant -eq 'True') -ne ($hit.unitId -in @($q.relevantUnitIds))) { throw 'Hit relevance differs from frozen gold.' }
        }
        if (@($q.relevantUnitIds).Count -gt 0) {
            $metrics = Get-RankingMetrics $hits $q.relevantUnitIds
            foreach ($k in @(5,10)) {
                if ([math]::Abs($metrics."recallAt$k" - [double]$query[0]."recallAt$k") -gt 1e-6) { throw 'Recomputed recall mismatch.' }
            }
            $rankingRows += [pscustomobject]@{model=$r.model.model; chunkingVersion=$r.chunkingVersion; queryId=$q.id; split=$q.split
                hitAt5=$metrics.hitAt5; completeAt5=$metrics.completeAt5; precisionAt5=$metrics.precisionAt5; ndcgAt5=$metrics.ndcgAt5; ndcgAt10=$metrics.ndcgAt10}
        }
        if ($r.chunkingVersion -eq 'char700-overlap64-article-v1' -and $q.id -in @('civil-2','housing-3','labor-3','privacy-3')) {
            $first = @($hits | Where-Object { [int]$_.rank -eq 1 })[0]
            $similarityRows += [pscustomobject]@{model=$r.model.model; queryId=$q.id; question=$first.query; title=$first.title; similarity=[double]$first.similarity; relevant=$first.relevant; firstRelevantRank=$query[0].firstRelevantRank}
        }
    }
    $dev = @($r.summary | Where-Object split -eq 'development')[0]
    $held = @($r.summary | Where-Object split -eq 'held_out')[0]
    $size = [int]([regex]::Match($r.chunkingVersion,'char(\d+)').Groups[1].Value)
    $records += [pscustomobject]@{ model = $r.model.model; dimensions = $r.model.dimensions; maxChars = $size
        overlapChars = 64; devTop1HitRate = $dev.top1HitRate; devRecallAt5 = $dev.recallAt5; devMrr = $dev.mrr
        devRecallAt10 = $dev.recallAt10; devInCorpusAcceptanceRate = $dev.inCorpusAcceptanceRate
        devNoAnswerFalseAcceptanceRate = $dev.noAnswerFalseAcceptanceRate
        heldOutTop1HitRate = $held.top1HitRate; heldOutRecallAt5 = $held.recallAt5
        heldOutRecallAt10 = $held.recallAt10; heldOutMrr = $held.mrr
        heldOutNoAnswerFalseAcceptanceRate = $held.noAnswerFalseAcceptanceRate
        heldOutInCorpusAcceptanceRate = $held.inCorpusAcceptanceRate
        heldOutMeanQueryEmbeddingMs = $held.meanQueryEmbeddingMs; corpusSha256 = $r.corpusSha256
        comparisonIdentitySha256 = $identity; threads = $r.threads; batchSize = $r.batchSize
        inferenceRuntime = $(if ($r.model.inferenceRuntime) { $r.model.inferenceRuntime } else { 'ollama' })
        inferenceRuntimeVersion = $(if ($r.model.inferenceRuntimeVersion) { $r.model.inferenceRuntimeVersion } else { $r.model.ollamaVersion })
        dtype = $(if ($r.model.dtype) { $r.model.dtype } else { 'not_recorded_in_legacy_report' })
        rawFloat32VectorMiB = $data.corpus.chunkCount * $r.model.dimensions * 4.0 / 1MB }
}
$records = @($records | Sort-Object model,maxChars)
$records | Export-Csv (Join-Path $ReportsRoot 'comparison.csv') -NoTypeInformation -Encoding UTF8
$rankingRows | Export-Csv (Join-Path $ReportsRoot 'ranking-queries.csv') -NoTypeInformation -Encoding UTF8
$extra = @(foreach ($group in ($rankingRows | Group-Object model,chunkingVersion,split)) {
    $row = $group.Group[0]
    $aggregate = [ordered]@{model=$row.model; chunkingVersion=$row.chunkingVersion; split=$row.split; positiveQueries=$group.Count}
    foreach ($metric in @('hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')) { $aggregate[$metric] = ($group.Group | Measure-Object -Property $metric -Average).Average }
    [pscustomobject]$aggregate
})
$extra | Export-Csv (Join-Path $ReportsRoot 'ranking-comparison.csv') -NoTypeInformation -Encoding UTF8
$similarityRows | Export-Csv (Join-Path $ReportsRoot 'similarity-examples.csv') -NoTypeInformation -Encoding UTF8
$lines = @('# 법령 임베딩 모델·청크 비교', '', '2026-10-16 기준 3개 모델 × 300 / 500 / 700문자, 총 9개 구성 완료. 중복은 64문자이며 동일 원문 5,486개 단위·질문 110개·정답·분할을 사용했다. 차원 축소는 아직 평가하지 않았다.', '',
    '처음 읽는다면 Recall@5 → Complete@5 → MRR → nDCG 순서로 보면 된다. 각각 필요한 근거를 얼마나 찾았는지, 전부 찾았는지, 첫 정답이 앞에 있는지, 여러 정답이 앞에 모였는지를 뜻한다. [숫자 예시로 보는 지표 설명](similarity-and-metrics.md)을 함께 참고한다.')
foreach ($split in @('development','held_out')) {
    $label = $(if ($split -eq 'development') { '개발용: 설정 비교에 사용한 양성 질문 30개' } else { '검증용: 별도로 남겨 둔 양성 질문 70개' })
    $prefix = $(if ($split -eq 'development') { 'dev' } else { 'heldOut' })
    $lines += @('', "## $label", '', '| 모델 | 차원 | 문자 | 1위 적중률 | Recall@5 | Recall@10 | Hit@5 | Complete@5 | Precision@5 | MRR | nDCG@5 | nDCG@10 |', '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
    foreach ($r in $records) {
        $row = @($extra | Where-Object { $_.model -eq $r.model -and $_.chunkingVersion -eq "char$($r.maxChars)-overlap64-article-v1" -and $_.split -eq $split })[0]
        $cells = @($r.model,$r.dimensions,$r.maxChars)
        foreach ($metric in @('Top1HitRate','RecallAt5','RecallAt10')) { $cells += ([math]::Round(100*$r."$prefix$metric",2)).ToString()+'%' }
        foreach ($metric in @('hitAt5','completeAt5','precisionAt5')) { $cells += ([math]::Round(100*$row.$metric,2)).ToString()+'%' }
        $cells += [math]::Round($r."${prefix}Mrr",4)
        foreach ($metric in @('ndcgAt5','ndcgAt10')) { $cells += [math]::Round($row.$metric,4) }
        $lines += '| '+($cells -join ' | ')+' |'
    }
}
$lines += @('', '## 결과 해석', '',
    '- 미리 정한 development Recall@5 기준 우선 후보는 BGE-M3 700문자(65.72%)다. Gemma 700문자(64.22%)와 차이는 1.50%p이며, 작은 개발 표본만으로 일반적인 우열을 확정하지 않는다.',
    '- 첫 정답 순위의 MRR은 KURE 500문자(0.6245), nDCG@5는 Gemma 700문자(0.5543), nDCG@10은 KURE 700문자(0.6021)가 가장 높다. 여러 근거의 회수와 앞쪽 배치에서 지표별 장점이 다르다.',
    '- Complete@5의 개발 최고값은 50%다. 정답 하나를 찾는 Hit@5가 높아도 필요한 모든 조문을 확보한 것은 아니다.',
    '- 모델 선택은 미리 정한 개발용 Recall@5를 중심으로 하고 다른 지표를 함께 본다. 공개된 검증용 결과로 설정을 반복 조정하지 않는다.', '',
    '## 유사도 점수 읽기', '',
    '700문자 조건의 휴게시간 질문에서 BGE-M3는 근로기준법 제50조(근로시간)를 0.5869로, KURE는 정답 제54조(휴게)를 0.5602로 검색했다. 유사도는 정답 확률이 아니며 모델별 원시 점수로 우열을 비교하지 않는다. 조문 점수는 자식 청크의 최대 코사인 유사도다.', '',
    '예시 전체와 검색 조문명: [유사도 CSV](similarity-examples.csv). 질문별 순위 지표: [CSV](ranking-queries.csv).', '', '## 평가 조건과 한계')
$lines += @('', '지표를 읽는 방법: [설명 문서](similarity-and-metrics.md). 전체 순위 지표는 위 두 표에서 함께 비교한다.', '',
    '각 질문의 점수를 먼저 구한 뒤 평균했다. 예를 들어 Recall@5 65.72%는 필요한 근거 중 평균 65.72%를 찾았다는 뜻이며, 질문의 65.72%에 완벽하게 답했다는 뜻은 아니다.', '',
    '정답이 없는 질문은 순위 지표에서 뺐다. 범위 안 수용률과 범위 밖 오수용률은 [실행 조건 CSV](comparison.csv)에서 확인한다. 수용은 검색 점수가 기준값 이상이라는 뜻으로, 실제 답변의 정답 여부가 아니다. 음성 질문은 분할마다 5개뿐이다.', '',
    '유사도 기준값은 모델·청크 구성마다 개발용 질문으로 따로 정했다. 정답 목록 밖의 조문은 계산상 정답이 아닌 것으로 처리하지만, 모든 조문이 무관하다고 검증한 것은 아니다.', '',
    '질문 임베딩 시간은 모델이 준비된 상태에서 질문마다 한 번 측정했다. 반복 부하 시험이 아니며, 청크 길이도 토큰 수가 아닌 문자 수다.', '',
    '벡터 용량은 청크 수 × 차원 × 4바이트로 계산한 숫자 자체의 크기다. 문서 정보와 검색 색인 등은 제외하므로 실제 저장 공간은 더 필요하다. 캐시를 재사용한 색인 시간은 처음부터 전체 자료를 처리하는 속도로 비교하지 않는다.', '',
    'KURE는 Sentence Transformers/PyTorch float32, Gemma와 BGE는 Ollama BF16/F16으로 실행했다. 실행 도구와 숫자 정밀도가 달라 시간 차이를 모델 자체의 속도 차이로 단정하지 않는다. 원본 보고서는 보존했고 기록되지 않은 정밀도는 CSV에 명시했다.')
[IO.File]::WriteAllText((Join-Path $ReportsRoot 'comparison.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$extraLines = @('# 순위 지표 상세 집계', '', '전체 지표의 통합 비교는 [모델 비교표](comparison.md), 쉬운 설명은 [지표 안내](similarity-and-metrics.md)를 참고한다. 이 파일은 CSV에 대응하는 상세 집계다.', '', 'Positive questions only; macro averages over frozen gold source units. Binary relevance, log2(rank+1) discount; IDCG uses min(k, total gold units), including gold outside retrieved top ten.', '',
    '| Model | Chunk | Split | N | Hit@5 | Complete@5 | Precision@5 | nDCG@5 | nDCG@10 |',
    '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $extra) {
    $extraLines += '| '+$row.model+' | '+$row.chunkingVersion+' | '+$row.split+' | '+$row.positiveQueries+' | '+([math]::Round($row.hitAt5,4))+' | '+([math]::Round($row.completeAt5,4))+' | '+([math]::Round($row.precisionAt5,4))+' | '+([math]::Round($row.ndcgAt5,4))+' | '+([math]::Round($row.ndcgAt10,4))+' |'
}
$extraLines += @('', '모델 선택은 개발용 Recall@5를 중심으로 다른 지표를 함께 본다. 정답 목록 밖의 조문은 계산상 정답이 아닌 것으로 처리하며, 모두 무관하다고 검증한 것은 아니다.', '', '지표 정의와 예시: [안내](similarity-and-metrics.md).')
[IO.File]::WriteAllText((Join-Path $ReportsRoot 'ranking-comparison.md'),($extraLines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$records | Format-Table model,maxChars,devRecallAt5,heldOutRecallAt5,heldOutMrr -AutoSize
