[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('embeddinggemma','bge-m3','kure-v1')][string]$Model,[ValidateSet(300,500)][int[]]$Sizes=@(500,300))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
foreach ($size in $Sizes) {
    $version="char$size-overlap64-article-v1"
    $context=Get-QdrantLabContext $Model $size
    $path=Join-Path $ReportsRoot "query-rewrite-v1/char$size/$Model/report.json"
    $complete=$false
    if (Test-Path -LiteralPath $path) {
        $r=Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
        $directory=Split-Path $path -Parent
        $complete=$r.queryMode -eq 'llm-rewrite-v1' -and $r.queryRewritesSha256 -eq (Get-FileHash (Join-Path $ReviewRoot 'query-rewrites-v1.json')).Hash -and $r.datasetSha256 -eq (Get-FileHash (Join-Path $context.directory 'questions.json')).Hash -and $r.model.modelDigest -eq $context.baseline.model.modelDigest -and $r.newVectors -eq 0 -and @(Import-Csv (Join-Path $directory 'queries.csv')).Count -eq 110 -and @(Import-Csv (Join-Path $directory 'top10.csv')).Count -eq 1100
    }
    if (!$complete) {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-query-rewrite.ps1') -Model $Model -Size $size
        if ($LASTEXITCODE -ne 0) { throw "Rewrite evaluation failed: $Model/$size" }
    } else { Write-Host "Reusing completed rewritten evaluation: $Model/$size" }
    Initialize-QdrantLab 6333
    $collectionComplete=$false
    if (@((Invoke-QdrantLab GET 'collections').result.collections | Where-Object name -eq $context.collection).Count -eq 1) {
        $existing=(Invoke-QdrantLab GET "collections/$($context.collection)").result
        Assert-QdrantLabCollection $context $existing
        $collectionComplete=(Invoke-QdrantLab POST "collections/$($context.collection)/points/count" @{exact=$true}).result.count -eq $context.data.corpus.chunkCount
    }
    if (!$collectionComplete) {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'qdrant-lab.ps1') -Action Index -Model $Model -Size $size
        if ($LASTEXITCODE -ne 0) { throw "Index failed: $Model/$size" }
    } else { Write-Host "Reusing complete experiment collection: $Model/$size" }
    $ready=$false
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        $info=(Invoke-QdrantLab GET "collections/$($context.collection)").result
        Assert-QdrantLabCollection $context $info
        if ($info.status -eq 'green' -and $info.optimizer_status -eq 'ok' -and $info.indexed_vectors_count -gt 0) { $ready=$true;break }
        Write-Host "Waiting for Qdrant index: $Model/$size"
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Collection index not ready.' }
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'qdrant-lab.ps1') -Action Verify -Model $Model -Size $size
    if ($LASTEXITCODE -ne 0) { throw "Collection verification failed: $Model/$size" }
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'benchmark-qdrant.ps1') -Model $Model -Size $size
    if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $Model/$size" }
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'compare-qdrant-benchmark.ps1') -Models $Model -Size $size
    if ($LASTEXITCODE -ne 0) { throw "Benchmark validation failed: $Model/$size" }
    Write-Host "COMPLETE: $Model/$size"
}
