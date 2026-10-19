[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('embeddinggemma','bge-m3','kure-v1','qwen3-0.6b')][string]$Model, [int]$Port=0,[ValidateSet(300,500,700)][int]$Size=700)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
if (!$Port) { $Port=$(if ($Model -eq 'qwen3-0.6b') {11437} elseif ($Model -eq 'kure-v1') {11436} else {11435}) }
$version = "char$Size-overlap64-article-v1"
$dataset = $(if ($Size -eq 500) { $DataRoot } else { Join-Path $DataRoot "variants/$version" })
$rewrites = Join-Path $ReviewRoot 'query-rewrites-v1.json'
$rewriteHash = (Get-FileHash $rewrites).Hash
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'evaluate.ps1') -Model $Model -DatasetDirectory $dataset -QueryRewritesPath $rewrites -Port $Port
if ($LASTEXITCODE -ne 0) { throw 'Rewritten-query evaluation failed.' }
if ((Get-FileHash $rewrites).Hash -ne $rewriteHash) { throw 'Rewrites changed during evaluation.' }
$pointer = Get-Content -Raw -Encoding UTF8 (Join-Path $ResultsRoot "latest-$Model-$version-llm-rewrite-v1.json") | ConvertFrom-Json
$report = Get-Content -Raw -Encoding UTF8 (Join-Path $pointer.directory 'report.json') | ConvertFrom-Json
$baseline = Get-Content -Raw -Encoding UTF8 (Join-Path $ReportsRoot "$Model/$version/summary.json") | ConvertFrom-Json
if ($report.queryRewritesSha256 -ne $rewriteHash -or $report.queryMode -ne 'llm-rewrite-v1' -or $report.newVectors -ne 0) { throw 'Invalid rewrite run provenance.' }
foreach ($field in @('datasetSha256','corpusSha256','chunkingVersion','threads','batchSize')) { if ($report.$field -ne $baseline.$field) { throw "Baseline mismatch: $field" } }
foreach ($field in @('modelDigest','dimensions','inputMode','documentFormat','queryFormat','ollamaVersion','inferenceRuntime','inferenceRuntimeVersion','dtype','device')) { if ($report.model.$field -ne $baseline.model.$field) { throw "Baseline model mismatch: $field" } }
$queries = @(Import-Csv (Join-Path $pointer.directory 'queries.csv'))
$hits = @(Import-Csv (Join-Path $pointer.directory 'top10.csv'))
if ($queries.Count -ne 110 -or @($queries.queryId | Select-Object -Unique).Count -ne 110 -or $hits.Count -ne 1100) { throw 'Incomplete rewritten query results.' }
$target = Join-Path $ReportsRoot "query-rewrite-v1/$Model"
if ($Size -ne 700) { $target=Join-Path $ReportsRoot "query-rewrite-v1/char$Size/$Model" }
$null = New-Item -ItemType Directory -Force $target
foreach ($file in @('report.json','summary.csv','queries.csv','top10.csv')) { Copy-Item -LiteralPath (Join-Path $pointer.directory $file) -Destination (Join-Path $target $file) }
Write-Host "Verified frozen vectors and published: $target"
