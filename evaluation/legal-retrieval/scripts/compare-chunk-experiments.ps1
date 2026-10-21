[CmdletBinding()]
param([ValidateSet('embeddinggemma','bge-m3','kure-v1','qwen3-0.6b')][string[]]$Models=@('embeddinggemma','bge-m3','kure-v1','qwen3-0.6b'))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
Initialize-QdrantLab 6333
$rows=@();$timing=@();$agreement=@();$commonIdentity=$null;$identities=@{}
$rewriteHash=(Get-FileHash (Get-ReviewPath 'query-rewrites-v1.json')).Hash
$profiles=Get-Content -Raw -Encoding UTF8 (Get-ReviewPath 'models.json') | ConvertFrom-Json
$modelSizes=@{}
foreach ($model in $Models) {
    $sizes=$(if($profiles.$model.experimentSizes){@($profiles.$model.experimentSizes)}else{@(300,500,700)})
    $modelSizes[$model]=@($sizes)
    $anchor=$(if($model -eq 'qwen3-0.6b' -and 500 -in $sizes){500}elseif(700 -in $sizes){700}else{$sizes[0]})
    $queryContext=Get-QdrantLabContext $model $anchor
    $queryCollection='molelaw_eval_queries_'+$model.Replace('-','_').Replace('.','_')+'_'+$queryContext.baseline.model.modelDigest.Substring(0,8)+'_'+$rewriteHash.Substring(0,8).ToLowerInvariant()
    $queryInfo=(Invoke-QdrantLab GET "collections/$queryCollection").result
    if($queryInfo.config.metadata.owner -ne $script:LabOwner -or $queryInfo.config.metadata.kind -ne 'evaluation-queries-v1' -or $queryInfo.config.metadata.model -ne $model -or $queryInfo.config.metadata.dataset_sha256 -ne $queryContext.baseline.datasetSha256 -or $queryInfo.config.metadata.model_digest -ne $queryContext.baseline.model.modelDigest -or $queryInfo.config.metadata.rewrite_sha256 -ne $rewriteHash){throw 'Stored query collection identity mismatch'}
    $storedQueries=@((Invoke-QdrantLab POST "collections/$queryCollection/points/scroll" @{limit=256;with_payload=$true;with_vector=$false}).result.points)
    if($storedQueries.Count -ne 220 -or @($storedQueries | ForEach-Object {$_.payload.query_id+'/'+$_.payload.query_mode} | Select-Object -Unique).Count -ne 220){throw 'Incomplete Qdrant query records'}
    $questionMap=@{};foreach($q in $queryContext.data.questions){$questionMap[$q.id]=$q}
    foreach($point in $storedQueries){
        $p=$point.payload;$q=$questionMap[$p.query_id]
        if(!$q -or $p.query_mode -notin @('original','rewritten') -or $p.split -ne $q.split -or $p.answerability -ne $q.answerability -or $p.original_text -cne $q.text -or $p.model -ne $model){throw 'Stored question identity mismatch'}
    }
    foreach ($size in $sizes) {
        $version="char$size-overlap64-article-v1"
        $dataDirectory=$(if ($size -eq 500) { $DataRoot } else { Get-DataPath "variants/$version" })
        if (!$identities.ContainsKey($size)) { $identities[$size]=Get-ComparisonIdentity $dataDirectory }
        $identity=$identities[$size]
        if ($commonIdentity -and $identity -ne $commonIdentity) { throw 'Cross-chunk source/question/gold/split identity differs.' }
        $commonIdentity=$identity
        $dataHash=(Get-FileHash (Join-Path $dataDirectory 'questions.json')).Hash
        $benchmarkDirectory=$(if ($size -eq 700) { Get-ReportPath "qdrant-lab/benchmark/$model" } else { Get-ReportPath "qdrant-lab/benchmark/char$size/$model" })
        $benchmark=Get-Content -Raw -Encoding UTF8 (Join-Path $benchmarkDirectory 'report.json') | ConvertFrom-Json
        $qrows=@(Import-Csv (Join-Path $benchmarkDirectory 'queries.csv'))
        $samples=@(Import-Csv (Join-Path $benchmarkDirectory 'latency.csv'))
        if ($benchmark.questionDatasetSha256 -ne $dataHash -or $benchmark.queryRewritesSha256 -ne $rewriteHash -or $benchmark.metadata.chunking_version -ne $version -or $qrows.Count -ne 660 -or $samples.Count -ne 440*$benchmark.repeats) { throw 'Invalid benchmark input or count.' }
        foreach ($mode in @('original','rewritten')) {
            $directory=$(if ($mode -eq 'original') { Get-ReportPath "$model/$version" } elseif ($size -eq 700) { Get-ReportPath "query-rewrite-v1/$model" } else { Get-ReportPath "query-rewrite-v1/char$size/$model" })
            $filename=$(if ($mode -eq 'original') {'summary.json'} else {'report.json'})
            $report=Get-Content -Raw -Encoding UTF8 (Join-Path $directory $filename) | ConvertFrom-Json
            if ($report.datasetSha256 -ne $dataHash -or $report.corpusSha256 -ne $benchmark.metadata.corpus_sha256 -or $report.model.modelDigest -ne $benchmark.metadata.model_digest -or $report.chunkingVersion -ne $version) { throw 'Wrong full-ranking report.' }
            if ($mode -eq 'rewritten' -and ($report.queryRewritesSha256 -ne $rewriteHash -or $report.newVectors -ne 0 -or $report.queryMode -ne 'llm-rewrite-v1' -or $report.comparisonIdentitySha256 -ne $identity)) { throw 'Invalid rewritten report.' }
            $fullQueries=@(Import-Csv (Join-Path $directory 'queries.csv'))
            if ($fullQueries.Count -ne 110 -or @($fullQueries.queryId | Select-Object -Unique).Count -ne 110) { throw 'Incomplete full-ranking report.' }
            foreach ($split in @('development','held_out')) {
                $summary=@($report.summary | Where-Object split -eq $split)[0]
                $positive=@($storedQueries | Where-Object { $_.payload.query_mode -eq $mode -and $_.payload.split -eq $split -and $_.payload.answerability -eq 'in_corpus' } | ForEach-Object {
                    $result=$_.payload.results."char$size"
                    if($result.collection -ne $benchmark.collection -or $result.engine -ne 'exact'){throw 'Stored result collection mismatch'}
                    $result.metrics
                })
                $ann=@($qrows | Where-Object { $_.queryMode -eq $mode -and $_.engine -eq 'ann' -and $_.split -eq $split -and $_.answerability -eq 'in_corpus' })
                $expected=$(if ($split -eq 'development') {30} else {70})
                if ($positive.Count -ne $expected -or $ann.Count -ne $expected -or $summary.positiveQueries -ne $expected -or $summary.negativeQueries -ne 5) { throw 'Wrong split counts.' }
                $recall=($positive | ForEach-Object {[double]$_.recallAt5} | Measure-Object -Average).Average
                if ([math]::Abs($recall-$summary.recallAt5) -gt 1e-6) { throw 'Full ranking and Qdrant Recall@5 differ.' }
                $record=[ordered]@{model=$model;maxChars=$size;queryMode=$mode;split=$split;positiveQueries=$expected
                    dimensions=$report.model.dimensions;recallAt5=$recall;recallAt10=($positive | Measure-Object recallAt10 -Average).Average;top1HitRate=@($positive | Where-Object top1Relevant -eq $true).Count/[double]$expected;mrr=($positive | Measure-Object fullReciprocalRank -Average).Average
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
$target=Get-ReportPath 'chunk-experiments'
$null=New-Item -ItemType Directory -Force $target
$rows | Export-Csv (Join-Path $target 'comparison.csv') -NoTypeInformation -Encoding UTF8
$timing | Export-Csv (Join-Path $target 'latency.csv') -NoTypeInformation -Encoding UTF8
$agreement | Export-Csv (Join-Path $target 'agreement.csv') -NoTypeInformation -Encoding UTF8
$lines=@('# 모델·청크·질문 정제 통합 비교', '', '![정제 질문 Recall@5 그래프](recall-by-chunk.png)', '', '[그래프와 정렬된 요약표로 보기](overview.md)', '', '300·500·700문자 청크에서 같은 원문 5,486개 단위와 같은 질문·정답·분할을 사용했다. 정제 질문 110개는 처음 작성한 것을 그대로 재사용했고 결과에 맞춰 고치지 않았다. 네 모델의 300·500·700자 평가를 완료했다. 아래 표는 원본·정제 질문을 따로 집계한다.', '',
    'Recall@5는 필요한 근거 중 상위 5개에서 찾은 비율, Complete@5는 필요한 근거를 전부 찾은 질문의 비율이다. MRR은 첫 정답의 순위, nDCG는 여러 정답의 배치를 본다. 모두 높을수록 좋다. [예시를 이용한 설명](../similarity-and-metrics.md)을 참고한다.', '', '## 정제 후 핵심 결과', '',
    '| 모델 | 문자 | 개발용 Recall@5 원본 → 정제 | 검증용 Recall@5 원본 → 정제 | 검증용 정제 MRR |', '| --- | ---: | ---: | ---: | ---: |')
foreach ($model in $Models) {
    foreach ($size in $modelSizes[$model]) {
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
$lines+=@('', 'original은 원본, rewritten은 정제 질문이다. 모든 상위 10개 지표는 Qdrant 전수 검색 결과에서 계산하며, MRR만 기존 전체 순위 평가값이다. 저장된 전체 순위 Recall@5와 Qdrant 결과의 일치를 확인한다. 개발용 양성은 30개, 검증용은 70개다.', '', '데이터와 벡터는 Qdrant 전용 컬렉션에서 관리한다. 로컬·Qdrant 정확도 비교와 근사 검색 시간 표는 주요 결과에서 제외하고 기존 상세 기록에 보존한다.', '', '정제본은 대화형 어시스턴트가 작성한 탐색 자료이며 독립 블라인드 평가가 아니다. 음성은 분할마다 5개뿐이다.')
[IO.File]::WriteAllText((Join-Path $target 'comparison.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
& (Join-Path $PSScriptRoot 'export-experiment-charts.ps1')
Write-Host "PASS: $($Models.Count) models, $($rows.Count) quality summaries, canonical identity=$commonIdentity"
