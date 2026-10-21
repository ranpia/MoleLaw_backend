[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('molelaw-eval-contract-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory $temporary
try {
    foreach ($name in @('questions.json','corpus.jsonl','corpus-manifest.json')) { Copy-Item -LiteralPath (Get-DataPath $name) -Destination $temporary }
    $identity = Get-ComparisonIdentity $DataRoot
    foreach ($size in @(300,700)) {
        $variant = Get-DataPath "variants/char$size-overlap64-article-v1"
        & (Join-Path $PSScriptRoot 'validate-evaluation.ps1') -DatasetDirectory $variant
        if ((Get-ComparisonIdentity $variant) -ne $identity) { throw 'Variant canonical identity differs.' }
    }
    $mutations = [ordered]@{
        missing_unit = { param($d) $d.questions[0].relevantUnitIds[0] = 'missing-unit' }
        changed_unit = { param($d) $d.questions[0].relevantUnitIds[0] = $d.questions[1].relevantUnitIds[0] }
        missing_group = { param($d) $d.questions[0].relevantChunkGroups = @() }
        duplicate_unit = { param($d) $d.questions[0].relevantUnitIds += $d.questions[0].relevantUnitIds[0] }
        split_leakage = { param($d) $d.questions[1].relevantUnitIds = $d.questions[0].relevantUnitIds; $d.questions[1].relevantChunkGroups = $d.questions[0].relevantChunkGroups; $d.questions[1].relevantChunkIds = $d.questions[0].relevantChunkIds; $d.questions[1].split = $(if ($d.questions[0].split -eq 'development') { 'held_out' } else { 'development' }) }
    }
    foreach ($name in $mutations.Keys) {
        $data = Get-Content -Raw -Encoding UTF8 (Get-DataPath 'questions.json') | ConvertFrom-Json
        & $mutations[$name] $data
        [IO.File]::WriteAllText((Join-Path $temporary 'questions.json'),($data | ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
        $rejected = $false
        try { & (Join-Path $PSScriptRoot 'validate-evaluation.ps1') -DatasetDirectory $temporary } catch { $rejected = $true }
        if (!$rejected) { throw "Mutation accepted: $name" }
        Write-Host "PASS: rejected $name"
    }
    $data = Get-Content -Raw -Encoding UTF8 (Get-DataPath 'questions.json') | ConvertFrom-Json
    $data.questions[0].text += ' changed'
    [IO.File]::WriteAllText((Join-Path $temporary 'questions.json'),($data | ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
    if ((Get-ComparisonIdentity $temporary) -eq $identity) { throw 'Changed question did not change identity.' }
    Write-Host 'PASS: changed question changes canonical identity.'
    $reportSource = Get-ReportPath 'bge-m3/char500-overlap64-article-v1'
    $reportTarget = Join-Path $temporary 'reports/bge-m3/char500-overlap64-article-v1'
    $null = New-Item -ItemType Directory -Force $reportTarget
    Copy-Item -Path (Join-Path $reportSource '*') -Destination $reportTarget
    foreach ($field in @('datasetSha256','comparisonIdentitySha256')) {
        $report = Get-Content -Raw -Encoding UTF8 (Join-Path $reportSource 'summary.json') | ConvertFrom-Json
        $report | Add-Member -NotePropertyName $field -NotePropertyValue 'invalid' -Force
        $report | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 (Join-Path $reportTarget 'summary.json')
        $rejected = $false
        try { & (Join-Path $PSScriptRoot 'compare-results.ps1') -ReportDirectory (Join-Path $temporary 'reports') } catch { $rejected = $true }
        if (!$rejected) { throw "Report mutation accepted: $field" }
        Write-Host "PASS: rejected report $field"
    }
    Copy-Item -LiteralPath (Join-Path $reportSource 'summary.json') -Destination (Join-Path $reportTarget 'summary.json')
    $rows = @(Import-Csv (Join-Path $reportTarget 'queries.csv'))
    $rows[0].split = 'invalid'
    $rows | Export-Csv (Join-Path $reportTarget 'queries.csv') -NoTypeInformation -Encoding UTF8
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'compare-results.ps1') -ReportDirectory (Join-Path $temporary 'reports') } catch { $rejected = $true }
    if (!$rejected) { throw 'Changed query result split accepted.' }
    Write-Host 'PASS: rejected changed query result split.'
    Write-Host 'PASS: all three variants share canonical source/question/gold/split identity.'
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (!$resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'molelaw-eval-contract-*') { throw 'Unsafe temporary cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
