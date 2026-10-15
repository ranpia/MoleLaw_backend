[CmdletBinding()]
param([int[]]$Sizes = @(300,700), [ValidateRange(0,128)][int]$Overlap = 64)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
Add-Type -Path (Join-Path $PSScriptRoot 'lib/DenseIndex.cs')
$baselineDirectory = $DataRoot
$baseline = Get-Content -Raw -Encoding UTF8 (Join-Path $baselineDirectory 'questions.json') | ConvertFrom-Json
$baselineHash = (Get-FileHash (Join-Path $baselineDirectory $baseline.corpus.path)).Hash
if ($baselineHash -ne $baseline.corpus.sha256) { throw 'Baseline corpus hash mismatch.' }
$sourceChunks = @(foreach ($line in [IO.File]::ReadLines((Join-Path $baselineDirectory $baseline.corpus.path))) { $line | ConvertFrom-Json })
$units = @()
foreach ($group in ($sourceChunks | Group-Object unitId)) {
    $parts = @($group.Group | Sort-Object startChar)
    $builder = [Text.StringBuilder]::new()
    foreach ($part in $parts) {
        $overlapLength = $builder.Length - $part.startChar
        if ($overlapLength -lt 0 -or $overlapLength -gt $part.text.Length) { throw 'Source chunk gap/overlap mismatch.' }
        if ($overlapLength -gt 0 -and $builder.ToString($part.startChar,$overlapLength) -cne $part.text.Substring(0,$overlapLength)) { throw 'Source chunk overlap text mismatch.' }
        $null = $builder.Append($part.text.Substring($overlapLength))
    }
    $units += [pscustomobject]@{ id = $group.Name; title = $parts[0].title; text = $builder.ToString()
        lawId = $parts[0].lawId; sourceUrl = $parts[0].sourceUrl; sourceSha256 = $parts[0].sourceSha256 }
}
foreach ($size in $Sizes) {
    if ($size -lt 200 -or $size -gt 1000 -or $Overlap -ge $size) { throw 'Invalid variant chunk size/overlap.' }
    $version = "char$size-overlap$Overlap-article-v1"
    $directory = Join-Path $baselineDirectory "variants/$version"
    $null = New-Item -ItemType Directory -Force $directory
    $corpusPath = Join-Path $directory 'corpus.jsonl'
    $writer = [IO.StreamWriter]::new($corpusPath,$false,[Text.UTF8Encoding]::new($false))
    $chunkMap = @{}; $count = 0
    try {
        foreach ($unit in ($units | Sort-Object id)) {
            $start = 0; $number = 0; $ids = @()
            while ($start -lt $unit.text.Length) {
                $length = [math]::Min($size,$unit.text.Length-$start)
                if ($start+$length -lt $unit.text.Length) {
                    $space = $unit.text.LastIndexOf(' ',$start+$length-1,$length)
                    if ($space -gt $start+[math]::Floor($size*0.6)) { $length = $space-$start }
                }
                $text = $unit.text.Substring($start,$length)
                $hash = [LegalDenseIndex]::InputKey($text)
                $id = "$($unit.id):${version}:${number}:$($hash.Substring(0,12))"
                $chunk = [ordered]@{ id = $id; unitId = $unit.id; lawId = $unit.lawId; title = $unit.title; text = $text
                    startChar = $start; endChar = $start+$length; textSha256 = $hash
                    sourceUrl = $unit.sourceUrl; sourceSha256 = $unit.sourceSha256 }
                $writer.WriteLine(($chunk | ConvertTo-Json -Depth 5 -Compress)); $ids += $id; $number++; $count++
                if ($start+$length -eq $unit.text.Length) { break }
                $start += $length-$Overlap
            }
            $chunkMap[$unit.id] = $ids
        }
    } finally { $writer.Dispose() }
    $data = Get-Content -Raw -Encoding UTF8 (Join-Path $baselineDirectory 'questions.json') | ConvertFrom-Json
    foreach ($q in $data.questions) {
        $groups = @(); $ids = @()
        foreach ($unitId in $q.relevantUnitIds) { $groups += [ordered]@{ unitId = $unitId; chunkIds = @($chunkMap[$unitId]) }; $ids += @($chunkMap[$unitId]) }
        $q.relevantChunkGroups = $groups; $q.relevantChunkIds = $ids
    }
    $data.id = "legal-retrieval-$version"
    $data.corpus.chunkingVersion = $version; $data.corpus.maxChars = $size; $data.corpus.overlapChars = $Overlap
    $data.corpus.chunkCount = $count; $data.corpus.sha256 = (Get-FileHash $corpusPath).Hash
    $data | Add-Member -NotePropertyName baselineCorpusSha256 -NotePropertyValue $baselineHash -Force
    $manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $baselineDirectory 'corpus-manifest.json') | ConvertFrom-Json
    $manifest.corpusSha256 = $data.corpus.sha256; $manifest.chunking = $data.corpus; $manifest.chunkCount = $count
    foreach ($entry in @(@('questions.json',$data),@('corpus-manifest.json',$manifest))) {
        [IO.File]::WriteAllText((Join-Path $directory $entry[0]),($entry[1] | ConvertTo-Json -Depth 22)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
    }
    Write-Host "Prepared $version : $count chunks, identical unit texts/questions/gold units/splits."
    & (Join-Path $PSScriptRoot 'validate-evaluation.ps1') -DatasetDirectory $directory
}
