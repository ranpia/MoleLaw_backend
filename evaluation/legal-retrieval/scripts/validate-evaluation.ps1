[CmdletBinding()]
param([string]$DatasetDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
if ($DatasetDirectory) { $DataRoot = [IO.Path]::GetFullPath($DatasetDirectory) }
$data = Get-Content -Raw -Encoding UTF8 (Get-DataPath 'questions.json') | ConvertFrom-Json
$corpusPath = Get-DataPath $data.corpus.path
if ((Get-FileHash $corpusPath).Hash -ne $data.corpus.sha256) { throw 'Corpus SHA256 mismatch.' }
$manifest = Get-Content -Raw -Encoding UTF8 (Get-DataPath 'corpus-manifest.json') | ConvertFrom-Json
if ($manifest.corpusSha256 -ne $data.corpus.sha256) { throw 'Manifest mismatch.' }
$chunks = @{}
foreach ($line in [IO.File]::ReadLines($corpusPath)) {
    $chunk = $line | ConvertFrom-Json
    if ($chunks.ContainsKey($chunk.id)) { throw "Duplicate chunk: $($chunk.id)" }
    if (!$chunk.text -or $chunk.text.Length -gt $data.corpus.maxChars) { throw "Invalid chunk text: $($chunk.id)" }
    if ($chunk.endChar - $chunk.startChar -ne $chunk.text.Length) { throw 'Offset mismatch.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($chunk.text)))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
    if ($hash -ne $chunk.textSha256) { throw 'Chunk hash mismatch.' }
    $chunks[$chunk.id] = $chunk
}
if ($chunks.Count -ne $manifest.chunkCount -or $chunks.Count -ne $data.corpus.chunkCount) { throw 'Chunk count mismatch.' }
$units = @{}; foreach ($chunk in $chunks.Values) { $units[$chunk.unitId] = $true }
if ($units.Count -ne $data.corpus.unitCount -or $units.Count -ne $manifest.unitCount) { throw 'Unit count mismatch.' }
function Assert-SameSet($Left, $Right, [string]$Label) {
    $a = @($Left); $b = @($Right)
    if (@($a | Select-Object -Unique).Count -ne $a.Count -or @($b | Select-Object -Unique).Count -ne $b.Count) { throw "Duplicate gold: $Label" }
    if ($a.Count -ne $b.Count -or @($a | Where-Object { $_ -notin $b }).Count -gt 0) { throw "Gold set mismatch: $Label" }
}
$seen = @{}; $splitsByUnit = @{}
foreach ($q in $data.questions) {
    if ($seen.ContainsKey($q.id)) { throw 'Duplicate question ID.' }
    $seen[$q.id] = $true
    if ($q.split -notin @('development','held_out')) { throw 'Unknown split.' }
    if ($q.answerability -eq 'in_corpus') {
        if (@($q.relevantUnitIds).Count -lt 1 -or @($q.relevantChunkIds).Count -lt 1) { throw "Missing gold: $($q.id)" }
        if (@($q.supplementalSourcesNeeded).Count -ne 0) { throw 'Unresolved supplemental source.' }
        Assert-SameSet $q.relevantUnitIds @($q.relevantChunkGroups | ForEach-Object { $_.unitId }) $q.id
        Assert-SameSet $q.relevantChunkIds @($q.relevantChunkGroups | ForEach-Object { $_.chunkIds }) $q.id
        foreach ($unitId in $q.relevantUnitIds) {
            if (!$units.ContainsKey($unitId)) { throw "Missing gold unit: $unitId" }
            if ($splitsByUnit.ContainsKey($unitId) -and $splitsByUnit[$unitId] -ne $q.split) { throw "Split leakage: $unitId" }
            $splitsByUnit[$unitId] = $q.split
        }
        foreach ($group in $q.relevantChunkGroups) {
            if (@($group.chunkIds).Count -eq 0) { throw 'Empty gold group.' }
            if ($splitsByUnit.ContainsKey($group.unitId) -and $splitsByUnit[$group.unitId] -ne $q.split) { throw "Split leakage: $($group.unitId)" }
            $splitsByUnit[$group.unitId] = $q.split
            foreach ($id in $group.chunkIds) {
                if (!$chunks.ContainsKey($id) -or $chunks[$id].unitId -ne $group.unitId) { throw 'Invalid gold chunk group.' }
            }
        }
        foreach ($id in $q.relevantChunkIds) { if (!$chunks.ContainsKey($id)) { throw 'Missing gold chunk.' } }
    } elseif ($q.answerability -eq 'out_of_corpus') {
        if (@($q.relevantChunkIds).Count -ne 0 -or @($q.relevantUnitIds).Count -ne 0 -or @($q.relevantChunkGroups).Count -ne 0) { throw 'Negative question has gold.' }
    } else { throw "Unknown answerability: $($q.id)" }
}
foreach ($expected in @(@('in_corpus','development',30),@('in_corpus','held_out',70),@('out_of_corpus','development',5),@('out_of_corpus','held_out',5))) {
    if (@($data.questions | Where-Object { $_.answerability -eq $expected[0] -and $_.split -eq $expected[1] }).Count -ne $expected[2]) { throw 'Unexpected split counts.' }
}
if (!$data.evaluationReady) { throw 'Dataset not marked ready.' }
Write-Host "PASS: $($data.questions.Count) questions, $($chunks.Count) chunks, valid hashes/gold references, zero shared gold units across splits."
