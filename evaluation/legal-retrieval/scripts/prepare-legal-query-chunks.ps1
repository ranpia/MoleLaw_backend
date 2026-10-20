[CmdletBinding()]
param([switch]$ValidateOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
if(!('LegalDenseIndex' -as [type])){Add-Type -Path (Join-Path $PSScriptRoot 'lib/DenseIndex.cs')}
$outputDir=Join-Path $DataRoot 'legal-query-chunks-v1'
$manifestPath=Join-Path $outputDir 'manifest.json'
if(!$ValidateOnly -and (Test-Path -LiteralPath $manifestPath)){throw 'Frozen manifest already exists; use -ValidateOnly. No input was overwritten.'}
$sourcePath=Join-Path $DataRoot 'final-validation-v1/corpus.jsonl'
$sourceManifestPath=Join-Path $DataRoot 'final-validation-v1/manifest.json'
$transformManifestPath=Join-Path $DataRoot 'legal-query-transform-v1/manifest.json'
$questionPath=Join-Path $DataRoot 'independent-validation-v1/questions.json'
$transformPath=Join-Path $ReviewRoot 'legal-query-transform-v1/transforms.json'
$baselineDir=Join-Path $ReportsRoot 'legal-query-transform-v1/kure-v1'
$sourceManifest=Get-Content -LiteralPath $sourceManifestPath -Raw -Encoding UTF8|ConvertFrom-Json
$transformManifest=Get-Content -LiteralPath $transformManifestPath -Raw -Encoding UTF8|ConvertFrom-Json
$frozenPaths=[ordered]@{
    sourceCorpusSha256=$sourcePath;transformsSha256=$transformPath;transformManifestSha256=$transformManifestPath
    questionsSha256=$questionPath;baseline300ReportSha256=(Join-Path $baselineDir 'report.json')
    baseline300QueriesSha256=(Join-Path $baselineDir 'queries.csv');baseline300Top10Sha256=(Join-Path $baselineDir 'top10.csv')
}
$hashes=@{};foreach($name in $frozenPaths.Keys){$hashes[$name]=(Get-FileHash -LiteralPath $frozenPaths[$name]).Hash}
if($hashes.sourceCorpusSha256 -ne $sourceManifest.corpusSha256 -or $hashes.sourceCorpusSha256 -ne $transformManifest.corpusSha256 -or $hashes.transformsSha256 -ne $transformManifest.transformsSha256 -or $hashes.questionsSha256 -ne $transformManifest.originalQuestionsSha256){throw 'Source/transform provenance mismatch'}
$revision='8b418a58414668e75532ed045c22d9ca018ae2b2'
if($sourceManifest.model.revision -ne $revision -or $transformManifest.modelRevision -ne $revision -or $sourceManifest.model.dimensions -ne 1024){throw 'Fixed KURE model contract mismatch'}
$questions=Get-Content -LiteralPath $questionPath -Raw -Encoding UTF8|ConvertFrom-Json
$transforms=Get-Content -LiteralPath $transformPath -Raw -Encoding UTF8|ConvertFrom-Json
if(@($questions.questions).Count -ne 100 -or @($questions.questions.id|Select-Object -Unique).Count -ne 100 -or @($transforms).Count -ne 100 -or @($transforms.id|Select-Object -Unique).Count -ne 100){throw 'Expected frozen 100 questions/transforms'}
$questionMap=@{};foreach($q in $questions.questions){$questionMap[$q.id]=$q}
foreach($t in $transforms){if(!$questionMap.ContainsKey($t.id) -or $questionMap[$t.id].text -cne $t.originalText -or [string]::IsNullOrWhiteSpace($t.searchText)){throw 'Transform identities changed'}}
$baselineReport=Get-Content -LiteralPath $frozenPaths.baseline300ReportSha256 -Raw -Encoding UTF8|ConvertFrom-Json
if($baselineReport.freezeSha256 -ne $hashes.transformManifestSha256 -or $baselineReport.manifest.transformsSha256 -ne $hashes.transformsSha256 -or $baselineReport.documentCollection -ne $transformManifest.collection){throw '300-character baseline provenance mismatch'}
$baselineQueries=@(Import-Csv -LiteralPath $frozenPaths.baseline300QueriesSha256)
$baselineTop=@(Import-Csv -LiteralPath $frozenPaths.baseline300Top10Sha256)
if($baselineQueries.Count -ne 300 -or $baselineTop.Count -ne 3000){throw 'Incomplete 300-character baseline'}
foreach($mode in @('original','rewritten','legal_transformed')){
    $rows=@($baselineQueries|Where-Object queryMode -eq $mode)
    if($rows.Count -ne 100 -or @($rows.queryId|Select-Object -Unique).Count -ne 100){throw 'Baseline mode coverage mismatch'}
    foreach($r in $rows){if(!$questionMap.ContainsKey($r.queryId) -or $r.originalText -cne $questionMap[$r.queryId].text -or $r.cohort -ne $questionMap[$r.queryId].cohort){throw 'Baseline question mismatch'}}
}
function Read-ReconstructedCorpus([string]$Path,[int]$MaxChars){
    $groups=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $chunkIds=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $count=0
    foreach($line in [IO.File]::ReadLines($Path)){
        if([string]::IsNullOrWhiteSpace($line)){throw 'Blank corpus line'}
        $chunk=$line|ConvertFrom-Json
        if([string]::IsNullOrWhiteSpace($chunk.id) -or !$chunkIds.Add($chunk.id) -or [string]::IsNullOrWhiteSpace($chunk.unitId) -or [string]::IsNullOrEmpty($chunk.text) -or $chunk.text.Length -gt $MaxChars){throw 'Invalid or duplicate chunk'}
        if([int]$chunk.startChar -lt 0 -or [int]$chunk.endChar-[int]$chunk.startChar -ne $chunk.text.Length -or [LegalDenseIndex]::InputKey($chunk.text) -ne $chunk.textSha256){throw 'Chunk offset/hash mismatch'}
        if(!$groups.ContainsKey($chunk.unitId)){$groups.Add($chunk.unitId,[Collections.Generic.List[object]]::new())}
        $groups[$chunk.unitId].Add($chunk);$count++
    }
    $ids=[string[]]@($groups.Keys);[Array]::Sort($ids,[StringComparer]::Ordinal)
    $units=[Collections.Generic.List[object]]::new()
    foreach($id in $ids){
        $parts=@($groups[$id]|Sort-Object {[int]$_.startChar})
        $first=$parts[0];$builder=[Text.StringBuilder]::new();$previousStart=-1
        foreach($part in $parts){
            foreach($key in @('unitId','lawId','title','sourceUrl','sourceSha256')){if($part.$key -cne $first.$key){throw "Unit metadata mismatch: $key"}}
            $start=[int]$part.startChar
            if($start -le $previousStart){throw 'Duplicate or unordered chunk start'}
            $overlap=$builder.Length-$start
            if(($previousStart -eq -1 -and $start -ne 0) -or ($previousStart -ne -1 -and $overlap -ne 64) -or $overlap -lt 0 -or $overlap -ge $part.text.Length){throw 'Source chunk gap/overlap mismatch'}
            if($overlap -gt 0 -and $builder.ToString($start,$overlap) -cne $part.text.Substring(0,$overlap)){throw 'Source overlap text mismatch'}
            $null=$builder.Append($part.text.Substring($overlap));$previousStart=$start
        }
        $units.Add([pscustomobject][ordered]@{id=$id;lawId=$first.lawId;title=$first.title;text=$builder.ToString();sourceUrl=$first.sourceUrl;sourceSha256=$first.sourceSha256})
    }
    [pscustomobject]@{units=$units.ToArray();chunkCount=$count}
}
function Get-UnitsHash($Units){
    $builder=[Text.StringBuilder]::new()
    foreach($unit in $Units){$null=$builder.Append(($unit|ConvertTo-Json -Depth 5 -Compress));$null=$builder.Append("`n")}
    [LegalDenseIndex]::InputKey($builder.ToString())
}
function Get-UnitChunks($Unit,[int]$Size){
    $version="char$Size-overlap64-article-v1";$start=0;$number=0
    while($start -lt $Unit.text.Length){
        $length=[math]::Min($Size,$Unit.text.Length-$start)
        # Preserve the two existing source policies; do not normalize or parse text again.
        if(!$Unit.id.StartsWith('final-v1:',[StringComparison]::Ordinal) -and $start+$length -lt $Unit.text.Length){
            $space=$Unit.text.LastIndexOf(' ',$start+$length-1,$length)
            if($space -gt $start+[math]::Floor($Size*0.6)){$length=$space-$start}
        }
        $text=$Unit.text.Substring($start,$length);$hash=[LegalDenseIndex]::InputKey($text)
        [pscustomobject][ordered]@{id="$($Unit.id):${version}:${number}:$($hash.Substring(0,12))";unitId=$Unit.id;lawId=$Unit.lawId;title=$Unit.title;text=$text;startChar=$start;endChar=$start+$length;textSha256=$hash;sourceUrl=$Unit.sourceUrl;sourceSha256=$Unit.sourceSha256}
        $number++
        if($start+$length -eq $Unit.text.Length){break}
        $start+=$length-64
    }
}
$source=Read-ReconstructedCorpus $sourcePath 300
if(@($source.units).Count -ne 6527 -or $source.chunkCount -ne 10999){throw 'Expected frozen 6527 units / 10999 source chunks'}
$sourceUnitsHash=Get-UnitsHash $source.units
function Assert-Variant([int]$Size,[string]$CorpusPath,[string]$ExpectedHash,[int]$ExpectedCount){
    if((Get-FileHash -LiteralPath $CorpusPath).Hash -ne $ExpectedHash){throw 'Variant corpus SHA mismatch'}
    $reconstructed=Read-ReconstructedCorpus $CorpusPath $Size
    if($reconstructed.chunkCount -ne $ExpectedCount -or @($reconstructed.units).Count -ne 6527 -or (Get-UnitsHash $reconstructed.units) -ne $sourceUnitsHash){throw 'Variant lost source unit text or metadata'}
    $reader=[IO.StreamReader]::new($CorpusPath,[Text.Encoding]::UTF8)
    try{
        foreach($unit in $source.units){foreach($expected in @(Get-UnitChunks $unit $Size)){
            $actual=$reader.ReadLine()
            if($null -eq $actual -or $actual -cne ($expected|ConvertTo-Json -Depth 5 -Compress)){throw 'Variant deterministic chunk ID/content/policy mismatch'}
        }}
        if($null -ne $reader.ReadLine()){throw 'Unexpected trailing variant chunks'}
    }finally{$reader.Dispose()}
}
if($ValidateOnly){
    $manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8|ConvertFrom-Json
    if($manifest.id -ne 'legal-query-chunks-v1' -or $manifest.questionCount -ne 100 -or $manifest.modelRevision -ne $revision -or $manifest.dimensions -ne 1024 -or $manifest.overlapChars -ne 64 -or $manifest.sourceUnitsSha256 -ne $sourceUnitsHash){throw 'Frozen chunk manifest contract mismatch'}
    foreach($name in $hashes.Keys){if($manifest.$name -ne $hashes[$name]){throw "Frozen manifest input SHA mismatch: $name"}}
    if(@($manifest.sizes).Count -ne 2 -or @($manifest.sizes.maxChars|Sort-Object -Unique).Count -ne 2){throw 'Expected two frozen sizes'}
    foreach($size in @(500,700)){
        $entry=@($manifest.sizes|Where-Object maxChars -eq $size)
        if($entry.Count -ne 1){throw 'Frozen size missing'}
        $name='molelaw_legal_chunks_kure_v1_c'+$size+'_'+$entry[0].corpusSha256.Substring(0,8).ToLowerInvariant()+'_'+$revision.Substring(0,8)
        if($entry[0].collection -ne $name){throw 'Variant collection name mismatch'}
        Assert-Variant $size (Join-Path $outputDir "char$size/corpus.jsonl") $entry[0].corpusSha256 $entry[0].chunkCount
    }
    foreach($name in $frozenPaths.Keys){if((Get-FileHash -LiteralPath $frozenPaths[$name]).Hash -ne $hashes[$name]){throw 'Input changed during validation'}}
    Write-Host 'PASS: frozen 500/700 corpora reproduce all 6527 original unit texts and metadata, IDs, hashes and policies.'
    return
}
$entries=@()
foreach($size in @(500,700)){
    $variantDir=Join-Path $outputDir "char$size";$null=New-Item -ItemType Directory -Path $variantDir -Force
    $corpusPath=Join-Path $variantDir 'corpus.jsonl'
    $writer=[IO.StreamWriter]::new($corpusPath,$false,[Text.UTF8Encoding]::new($false));$writer.NewLine="`n";$count=0
    try{foreach($unit in $source.units){foreach($chunk in @(Get-UnitChunks $unit $size)){$writer.WriteLine(($chunk|ConvertTo-Json -Depth 5 -Compress));$count++}}}finally{$writer.Dispose()}
    $hash=(Get-FileHash -LiteralPath $corpusPath).Hash
    Assert-Variant $size $corpusPath $hash $count
    $collection='molelaw_legal_chunks_kure_v1_c'+$size+'_'+$hash.Substring(0,8).ToLowerInvariant()+'_'+$revision.Substring(0,8)
    $entries+=[ordered]@{maxChars=$size;corpusSha256=$hash;chunkCount=$count;collection=$collection}
    Write-Host "Prepared char$size : $count chunks, all 6527 unit texts and metadata unchanged."
}
foreach($name in $frozenPaths.Keys){if((Get-FileHash -LiteralPath $frozenPaths[$name]).Hash -ne $hashes[$name]){throw 'Source input changed during preparation'}}
$manifest=[ordered]@{id='legal-query-chunks-v1';sourceCorpusSha256=$hashes.sourceCorpusSha256;sourceUnitsSha256=$sourceUnitsHash;questionCount=100;modelRevision=$revision;dimensions=1024;overlapChars=64;sizes=$entries;transformsSha256=$hashes.transformsSha256;transformManifestSha256=$hashes.transformManifestSha256;questionsSha256=$hashes.questionsSha256;baseline300ReportSha256=$hashes.baseline300ReportSha256;baseline300QueriesSha256=$hashes.baseline300QueriesSha256;baseline300Top10Sha256=$hashes.baseline300Top10Sha256}
[IO.File]::WriteAllText($manifestPath,($manifest|ConvertTo-Json -Depth 12)+"`n",[Text.UTF8Encoding]::new($false))
Write-Host 'Frozen legal-query-chunks-v1 manifest. No model or database actions performed.'
