[CmdletBinding()]
param([ValidateSet('embeddinggemma','bge-m3','kure-v1')][string]$Model, [int[]]$Sizes = @(500,300,700), [int]$Port = 11435)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
foreach ($size in $Sizes) {
    if (Test-Path -LiteralPath (Join-Path $ArtifactsRoot "control/stop-$Model.flag")) { Write-Host "Stopped by user request before $Model / char$size"; break }
    $version = "char$size-overlap64-article-v1"
    $directory = $(if ($size -eq 500) { $DataRoot } else { Join-Path $DataRoot "variants/$version" })
    if (!(Test-Path -LiteralPath (Join-Path $directory 'questions.json'))) { throw "Dataset missing: $version" }
    Write-Host "Starting $Model / $version"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'evaluate.ps1') -Model $Model -DatasetDirectory $directory -Port $Port
    if ($LASTEXITCODE -ne 0) { throw "Evaluation failed: $Model / $version" }
    $pointer = Get-Content -Raw -Encoding UTF8 (Join-Path $ResultsRoot "latest-$Model-$version.json") | ConvertFrom-Json
    $report = Get-Content -Raw -Encoding UTF8 (Join-Path $pointer.directory 'report.json') | ConvertFrom-Json
    if ($report.datasetSha256 -ne (Get-FileHash (Join-Path $directory 'questions.json')).Hash) { throw 'Published report input hash mismatch.' }
    $queries = @(Import-Csv (Join-Path $pointer.directory 'queries.csv'))
    $top = @(Import-Csv (Join-Path $pointer.directory 'top10.csv'))
    if ($queries.Count -ne 110 -or $top.Count -ne 1100 -or @($queries.queryId | Select-Object -Unique).Count -ne 110) { throw 'Incomplete model results.' }
    $target = Join-Path $ReportsRoot "$Model/$version"
    $null = New-Item -ItemType Directory -Force $target
    Copy-Item -LiteralPath (Join-Path $pointer.directory 'report.json') -Destination (Join-Path $target 'summary.json')
    foreach ($file in @('summary.csv','queries.csv','top10.csv')) { Copy-Item -LiteralPath (Join-Path $pointer.directory $file) -Destination (Join-Path $target $file) }
    Write-Host "Verified and saved: reports/$Model/$version"
}
