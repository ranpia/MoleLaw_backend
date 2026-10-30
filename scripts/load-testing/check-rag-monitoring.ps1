$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$settings=@{}
foreach($line in [IO.File]::ReadAllLines((Join-Path $repoPath 'docker/loadtest/.env'))){
    if($line -match '^([A-Z0-9_]+)=(.*)$'){$settings[$Matches[1]]=$Matches[2]}
}
$qdrant='http://127.0.0.1:'+$(if($settings.LT_QDRANT_HTTP_PORT){$settings.LT_QDRANT_HTTP_PORT}else{'16333'})
$kure='http://127.0.0.1:'+$(if($settings.LT_KURE_PORT){$settings.LT_KURE_PORT}else{'21437'})
$headers=@{'api-key'=$settings.LT_QDRANT_API_KEY}
if(-not $headers.'api-key'){throw 'Qdrant key is missing.'}
try {
    $null=Invoke-WebRequest "$qdrant/metrics" -UseBasicParsing
    throw 'Unauthenticated metrics unexpectedly accessible.'
} catch {
    if(-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -notin @(401,403)){throw}
}
'Qdrant metrics authentication enforced.'
$probe='molelaw_monitoring_probe_v1'
$created=$false
try {
    $collections=Invoke-RestMethod "$qdrant/collections" -Headers $headers
    if($collections.result.collections.name -contains $probe){throw 'Probe collection already exists; no existing collection is overwritten.'}
    $null=Invoke-RestMethod "$qdrant/collections/$probe" -Method Put -Headers $headers -ContentType application/json -Body '{"vectors":{"size":4,"distance":"Cosine"}}'
    $created=$true
    $null=Invoke-RestMethod "$qdrant/collections/$probe/points?wait=true" -Method Put -Headers $headers -ContentType application/json -Body '{"points":[{"id":1,"vector":[1,0,0,0]}]}'
    $result=Invoke-RestMethod "$qdrant/collections/$probe/points/query" -Method Post -Headers $headers -ContentType application/json -Body '{"query":[1,0,0,0],"limit":1}'
    if($result.result.points.Count -ne 1 -or $result.result.points[0].id -ne 1){throw 'Qdrant query probe failed.'}
    'Qdrant synthetic vector insert/query passed; no application collection changed.'
} finally {
    if($created){$null=Invoke-RestMethod "$qdrant/collections/$probe" -Method Delete -Headers $headers}
}
$ready=$false
for($attempt=0;$attempt -lt 120;$attempt++) {
    try {$version=Invoke-RestMethod "$kure/api/version" -TimeoutSec 5;$ready=$true;break} catch {}
    Start-Sleep -Seconds 2
}
if(-not $ready){throw 'KURE models are not ready; check model_ready and container status.'}
$threads=if($settings.LT_KURE_THREADS){[int]$settings.LT_KURE_THREADS}else{4}
$payload=@{model='kure-v1:official';input=@('Synthetic monitoring probe.');truncate=$false;options=@{num_thread=$threads;num_gpu=0}} | ConvertTo-Json -Depth 5 -Compress
$result=Invoke-RestMethod "$kure/api/embed" -Method Post -ContentType application/json -Body $payload -TimeoutSec 120
$vector=$result.embeddings[0]
if($vector.Count -ne 1024){throw 'Embedding dimension mismatch.'}
$norm=0;foreach($value in $vector){$norm+=$value*$value}
if([math]::Abs([math]::Sqrt($norm)-1) -gt .01){throw 'Embedding norm mismatch.'}
'Real KURE embedding passed: 1024 dimensions, unit norm.'
$payload=@{model='BAAI/bge-reranker-v2-m3';revision='953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e';query='contract';documents=@(@{id='probe-1';text='A contract between parties.'},@{id='probe-2';text='A weather report.'})} | ConvertTo-Json -Depth 5 -Compress
$result=Invoke-RestMethod "$kure/api/rerank" -Method Post -ContentType application/json -Body $payload -TimeoutSec 120
if($result.results.Count -ne 2){throw 'Rerank result count mismatch.'}
foreach($item in $result.results){if([double]::IsNaN($item.score) -or [double]::IsInfinity($item.score)){throw 'Non-finite rerank score.'}}
'Real local reranker passed: 2 finite scores. No answer generation performed.'
Start-Sleep -Seconds 7
$queries=@('up{job=~"molelaw-(qdrant|kure)"}','kure_model_ready','kure_requests_total{status="200"}','rest_responses_total{job="molelaw-qdrant"}')
foreach($query in $queries){
    $reply=Invoke-RestMethod ('http://localhost:19090/api/v1/query?query='+[uri]::EscapeDataString($query))
    if($reply.status -ne 'success' -or $reply.data.result.Count -eq 0){throw 'Required monitoring series missing.'}
    if($query -eq 'kure_model_ready' -or $query -like 'up*'){
        if(@($reply.data.result | Where-Object {$_.value[1] -ne '1'}).Count){throw 'Target or model is not ready.'}
    }
}
'Qdrant/KURE scrape, model readiness and request series present.'
