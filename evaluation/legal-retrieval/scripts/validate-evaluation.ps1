[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$data = Get-Content -Raw -Encoding UTF8 (Join-Path $DataRoot 'questions.json') | ConvertFrom-Json
$corpusPath = Join-Path $DataRoot $data.corpus.path
if ((Get-FileHash $corpusPath).Hash -ne $data.corpus.sha256) { throw 'Corpus SHA256 mismatch.' }
$manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $DataRoot 'corpus-manifest.json') | ConvertFrom-Json
if ($manifest.corpusSha256 -ne $data.corpus.sha256) { throw 'Manifest mismatch.' }
$chunks = @{}
foreach ($line in [IO.File]::ReadLines($corpusPath)) {
    $chunk = $line | ConvertFrom-Json
    if ($chunks.ContainsKey($chunk.id)) { throw "Duplicate chunk: $($chunk.id)" }
    if (!$chunk.text -or $chunk.text.Length -gt 500) { throw "Invalid chunk text: $($chunk.id)" }
    if ($chunk.endChar - $chunk.startChar -ne $chunk.text.Length) { throw 'Offset mismatch.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($chunk.text)))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
    if ($hash -ne $chunk.textSha256) { throw 'Chunk hash mismatch.' }
    $chunks[$chunk.id] = $chunk
}
if ($chunks.Count -ne $manifest.chunkCount) { throw 'Chunk count mismatch.' }
$seen = @{}; $splitsByUnit = @{}
foreach ($q in $data.questions) {
    if ($seen.ContainsKey($q.id)) { throw 'Duplicate question ID.' }
    $seen[$q.id] = $true
    if ($q.answerability -eq 'in_corpus') {
        if (@($q.relevantUnitIds).Count -lt 1 -or @($q.relevantChunkIds).Count -lt 1) { throw "Missing gold: $($q.id)" }
        if (@($q.supplementalSourcesNeeded).Count -ne 0) { throw 'Unresolved supplemental source.' }
        foreach ($group in $q.relevantChunkGroups) {
            if ($splitsByUnit.ContainsKey($group.unitId) -and $splitsByUnit[$group.unitId] -ne $q.split) { throw "Split leakage: $($group.unitId)" }
            $splitsByUnit[$group.unitId] = $q.split
            foreach ($id in $group.chunkIds) {
                if (!$chunks.ContainsKey($id) -or $chunks[$id].unitId -ne $group.unitId) { throw 'Invalid gold chunk group.' }
            }
        }
        foreach ($id in $q.relevantChunkIds) { if (!$chunks.ContainsKey($id)) { throw 'Missing gold chunk.' } }
    } elseif ($q.answerability -eq 'out_of_corpus') {
        if (@($q.relevantChunkIds).Count -ne 0 -or @($q.relevantUnitIds).Count -ne 0) { throw 'Negative question has gold.' }
    } else { throw "Unknown answerability: $($q.id)" }
}
foreach ($expected in @(@('in_corpus','development',30),@('in_corpus','held_out',70),@('out_of_corpus','development',5),@('out_of_corpus','held_out',5))) {
    if (@($data.questions | Where-Object { $_.answerability -eq $expected[0] -and $_.split -eq $expected[1] }).Count -ne $expected[2]) { throw 'Unexpected split counts.' }
}
if (!$data.evaluationReady) { throw 'Dataset not marked ready.' }
Write-Host "PASS: $($data.questions.Count) questions, $($chunks.Count) chunks, valid hashes/gold references, zero shared gold units across splits."
