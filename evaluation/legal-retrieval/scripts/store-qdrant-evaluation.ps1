[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Model,[int]$QdrantPort=6333,[ValidateSet(300,500,700)][int[]]$Sizes)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/QueryRewrites.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
Initialize-QdrantLab $QdrantPort
if(!$Sizes){
    $profile=(Get-Content -Raw -Encoding UTF8 (Join-Path $ReviewRoot 'models.json') | ConvertFrom-Json).$Model
    $Sizes=$(if($profile.experimentSizes){@($profile.experimentSizes)}else{@(300,500,700)})
}
$anchor=$(if($Model -eq 'qwen3-0.6b' -and 500 -in $Sizes){500}elseif(700 -in $Sizes){700}else{$Sizes[0]})
$context=Get-QdrantLabContext $Model $anchor
$rewritePath=Join-Path $ReviewRoot 'query-rewrites-v1.json'
$rewriteHash=(Get-FileHash $rewritePath).Hash
$datasetHash=(Get-FileHash (Join-Path $context.directory 'questions.json')).Hash
$rewriteSourceHash=(Get-FileHash (Join-Path $DataRoot 'variants/char700-overlap64-article-v1/questions.json')).Hash
$rewrites=Read-QueryRewrites $rewritePath $context.data $datasetHash (Get-ComparisonIdentity $context.directory) $rewriteSourceHash
$name='molelaw_eval_queries_'+$Model.Replace('-','_').Replace('.','_')+'_'+$context.baseline.model.modelDigest.Substring(0,8)+'_'+$rewriteHash.Substring(0,8).ToLowerInvariant()
$metadata=@{owner=$script:LabOwner;kind='evaluation-queries-v1';model=$Model;model_digest=$context.baseline.model.modelDigest;rewrite_sha256=$rewriteHash;dataset_sha256=$datasetHash;input_mode=$context.profile.inputMode}
$exists=@((Invoke-QdrantLab GET 'collections').result.collections | Where-Object name -eq $name).Count -eq 1
if (!$exists) {
    $null=Invoke-QdrantLab PUT "collections/$name" @{vectors=@{size=[int]$context.profile.dimensions;distance='Cosine'};metadata=$metadata}
}
$info=(Invoke-QdrantLab GET "collections/$name").result
foreach($field in $metadata.Keys) { if($info.config.metadata.$field -ne $metadata[$field]) {throw "Query collection identity mismatch: $field"} }
if($info.config.params.vectors.size -ne $context.profile.dimensions -or $info.config.params.vectors.distance -ne 'Cosine'){throw 'Query vector schema mismatch'}
foreach($field in @('query_id','query_mode','split','answerability')){
    $null=Invoke-QdrantLab PUT "collections/$name/index?wait=true" @{field_name=$field;field_schema='keyword'}
}
$cacheDirectory=Join-Path $ArtifactsRoot ('qdrant-query-cache/'+$Model+'-'+$context.baseline.model.modelDigest.Substring(0,16)+'-'+$context.profile.inputMode)
$cacheMetadata=Get-Content -Raw -Encoding UTF8 (Join-Path $cacheDirectory 'metadata.json') | ConvertFrom-Json
foreach($field in @('modelDigest','dimensions','queryFormat')) {if($cacheMetadata.$field -ne $context.baseline.model.$field){throw "Query cache identity mismatch: $field"}}
if($context.profile.runtime -eq 'sentence-transformers'){
    foreach($field in @('inferenceRuntimeVersion','dtype','device')){if($cacheMetadata.$field -ne $context.baseline.model.$field){throw "Query runtime identity mismatch: $field"}}
} elseif($cacheMetadata.ollamaVersion -ne $context.baseline.model.ollamaVersion){throw 'Query Ollama runtime mismatch'}
$cache=[LegalDenseIndex]::new([int]$context.profile.dimensions)
foreach($file in Get-ChildItem -LiteralPath $cacheDirectory -Filter '*.bin'){$cache.ReadBatch($file.FullName)}
$results=@{}
foreach($size in $Sizes) {
    $dir=$(if($size -eq 700){Join-Path $ReportsRoot "qdrant-lab/benchmark/$Model"}else{Join-Path $ReportsRoot "qdrant-lab/benchmark/char$size/$Model"})
    $report=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'report.json') | ConvertFrom-Json
    $c=Get-QdrantLabContext $Model $size
    if($report.questionDatasetSha256 -ne (Get-FileHash (Join-Path $c.directory 'questions.json')).Hash -or $report.queryRewritesSha256 -ne $rewriteHash -or $report.metadata.model_digest -ne $metadata.model_digest){throw 'Result provenance mismatch'}
    $results[$size]=@{metrics=@(Import-Csv (Join-Path $dir 'queries.csv') | Where-Object engine -eq 'exact');hits=@(Import-Csv (Join-Path $dir 'top10.csv') | Where-Object engine -eq 'exact');collection=$c.collection}
    if($results[$size].metrics.Count -ne 220 -or $results[$size].hits.Count -ne 2200){throw 'Incomplete Qdrant results'}
    $results[$size].metricIndex=@{};$results[$size].hitIndex=@{}
    foreach($row in $results[$size].metrics){
        $key="$($row.queryId)/$($row.queryMode)"
        if($results[$size].metricIndex.ContainsKey($key)){throw 'Duplicate query metrics'}
        $results[$size].metricIndex[$key]=$row
    }
    foreach($row in $results[$size].hits){
        $key="$($row.queryId)/$($row.queryMode)"
        $results[$size].hitIndex[$key]=@($results[$size].hitIndex[$key] | Where-Object {$null -ne $_})+@($row)
    }
    $results[$size].fullIndex=@{}
    foreach($mode in @('original','rewritten')){
        $fullDirectory=$(if($mode -eq 'original'){Join-Path $ReportsRoot "$Model/char$size-overlap64-article-v1"}elseif($size -eq 700){Join-Path $ReportsRoot "query-rewrite-v1/$Model"}else{Join-Path $ReportsRoot "query-rewrite-v1/char$size/$Model"})
        $filename=$(if($mode -eq 'original'){'summary.json'}else{'report.json'})
        $fullReport=Get-Content -Raw -Encoding UTF8 (Join-Path $fullDirectory $filename) | ConvertFrom-Json
        if($fullReport.datasetSha256 -ne $report.questionDatasetSha256 -or $fullReport.model.modelDigest -ne $metadata.model_digest){throw 'Full-ranking provenance mismatch'}
        foreach($row in @(Import-Csv (Join-Path $fullDirectory 'queries.csv'))){
            $results[$size].fullIndex["$($row.queryId)/$mode"]=$row
        }
    }
}
$points=@()
foreach($q in $context.data.questions){
    foreach($mode in @('original','rewritten')){
        $text=$(if($mode -eq 'original'){$q.text}else{$rewrites[$q.id].rewrittenText})
        $key=[LegalDenseIndex]::InputKey($context.profile.queryFormat.Replace('{text}',$text))
        if(!$cache.Contains($key)){throw 'Missing frozen query vector'}
        $bySize=@{}
        foreach($size in $Sizes){
            $r=$results[$size]
            $resultKey="$($q.id)/$mode"
            $metrics=@($r.metricIndex[$resultKey])
            $hits=@($r.hitIndex[$resultKey] | Sort-Object {[int]$_.rank})
            if($metrics.Count -ne 1 -or $hits.Count -ne 10){throw 'Incomplete per-question result'}
            if($metrics[0].split -ne $q.split -or $metrics[0].answerability -ne $q.answerability){throw 'Result question identity mismatch'}
            for($i=0;$i -lt 10;$i++){
                if([int]$hits[$i].rank -ne $i+1 -or (($hits[$i].relevant -eq 'True') -ne ($hits[$i].unitId -in @($q.relevantUnitIds)))){throw 'Invalid result rank/gold label'}
            }
            $numericMetrics=@{top1Relevant=($metrics[0].top1Relevant -eq 'True')}
            foreach($field in @('top1Similarity','recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10','mrrAt10')){
                $numericMetrics[$field]=$(if([string]::IsNullOrEmpty($metrics[0].$field)){$null}else{[double]$metrics[0].$field})
            }
            $full=$r.fullIndex[$resultKey]
            if(!$full -or $full.split -ne $q.split -or $full.answerability -ne $q.answerability){throw 'Full-ranking question mismatch'}
            $numericMetrics.fullReciprocalRank=$(if($q.answerability -eq 'in_corpus'){[double]$full.reciprocalRank}else{$null})
            $numericHits=@(foreach($hit in $hits){@{rank=[int]$hit.rank;unit_id=$hit.unitId;similarity=[double]$hit.similarity;relevant=($hit.relevant -eq 'True')}})
            $bySize["char$size"]=@{collection=$r.collection;engine='exact';metrics=$numericMetrics;top10=$numericHits}
        }
        $points+=@{id=(Get-QdrantLabPointId "$name/$($q.id)/$mode");vector=@($cache.GetVector($key));payload=@{owner=$script:LabOwner;model=$Model;query_id=$q.id;query_mode=$mode;split=$q.split;answerability=$q.answerability;original_text=$q.text;retrieval_text=$text;formatted_query=$context.profile.queryFormat.Replace('{text}',$text);input_sha256=$key;relevant_unit_ids=@($q.relevantUnitIds);rewrite_sha256=$rewriteHash;results=$bySize}}
        if($points.Count -eq 16){$null=Invoke-QdrantLab PUT "collections/$name/points?wait=true" @{points=$points};$points=@()}
    }
}
if($points.Count){$null=Invoke-QdrantLab PUT "collections/$name/points?wait=true" @{points=$points}}
$count=(Invoke-QdrantLab POST "collections/$name/points/count" @{exact=$true}).result.count
if($count -ne 220){throw 'Stored query count mismatch'}
foreach($q in @($context.data.questions[0],$context.data.questions[-1])){
    $id=Get-QdrantLabPointId "$name/$($q.id)/rewritten"
    $stored=@((Invoke-QdrantLab POST "collections/$name/points" @{ids=@($id);with_payload=$true;with_vector=$true}).result)
    if($stored.Count -ne 1 -or $stored[0].vector.Count -ne $context.profile.dimensions -or $stored[0].payload.original_text -cne $q.text -or $stored[0].payload.retrieval_text -cne $rewrites[$q.id].rewrittenText){throw 'Query payload/vector roundtrip failed'}
    foreach($size in $Sizes){if(@($stored[0].payload.results."char$size".top10).Count -ne 10){throw 'Stored result roundtrip failed'}}
}
Write-Host "PASS: $name; 220 query vectors, gold labels and $(220*$Sizes.Count) Qdrant exact search results."
