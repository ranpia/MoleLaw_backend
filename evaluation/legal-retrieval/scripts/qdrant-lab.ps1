[CmdletBinding()]
param([ValidateSet('List','Index','Search','Verify','Delete')][string]$Action='List',
    [ValidateSet('embeddinggemma','bge-m3','kure-v1')][string]$Model='embeddinggemma', [string]$Query,
    [ValidateRange(1,65535)][int]$QdrantPort=6333, [ValidateRange(0,65535)][int]$OllamaPort=0,
    [ValidateRange(1,50)][int]$Limit=10, [switch]$Exact, [string]$LawId)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
Initialize-QdrantLab $QdrantPort
if ($Action -eq 'List') {
    $collections=(Invoke-QdrantLab GET 'collections').result.collections
    foreach ($entry in @($collections | Where-Object { $_.name -like 'molelaw_exp_*' })) {
        $info=(Invoke-QdrantLab GET "collections/$($entry.name)").result
        [pscustomobject]@{collection=$entry.name;owner=$info.config.metadata.owner;points=$info.points_count;indexedVectors=$info.indexed_vectors_count;dimensions=$info.config.params.vectors.size;status=$info.status}
    }
    return
}
$context=Get-QdrantLabContext $Model
if (!$OllamaPort) { $OllamaPort=$(if ($Model -eq 'kure-v1') {11436} else {11435}) }
$exists=@((Invoke-QdrantLab GET 'collections').result.collections | Where-Object name -eq $context.collection).Count -eq 1
if (!$exists -and $Action -ne 'Index') { throw 'Experiment collection not indexed. Run -Action Index first.' }
if ($exists) {
    $info=(Invoke-QdrantLab GET "collections/$($context.collection)").result
    Assert-QdrantLabCollection $context $info
}
if ($Action -eq 'Delete') {
    $null=Invoke-QdrantLab DELETE "collections/$($context.collection)"
    Write-Host "Deleted owned experiment collection: $($context.collection)"
    return
}
if ($Action -eq 'Index') {
    & (Join-Path $PSScriptRoot 'validate-evaluation.ps1') -DatasetDirectory $context.directory
    $cache=Read-QdrantLabCache $context
    $chunks=Get-QdrantLabChunks $context
    foreach ($chunk in $chunks) { if (!$cache.Contains((Get-QdrantLabDocumentKey $context $chunk))) { throw 'Missing frozen document vector; indexing refused.' } }
    if (!$exists) {
        $null=Invoke-QdrantLab PUT "collections/$($context.collection)" @{vectors=@{size=[int]$context.profile.dimensions;distance='Cosine'};metadata=$context.metadata
            hnsw_config=@{m=16;ef_construct=100;full_scan_threshold=1000};optimizers_config=@{indexing_threshold=1000;default_segment_number=2}}
    }
    $info=(Invoke-QdrantLab GET "collections/$($context.collection)").result
    Assert-QdrantLabCollection $context $info
    foreach ($field in @('unit_id','law_id')) {
        $null=Invoke-QdrantLab PUT "collections/$($context.collection)/index?wait=true" @{field_name=$field;field_schema='keyword'}
    }
    for ($offset=0; $offset -lt $chunks.Count; $offset+=64) {
        $last=[math]::Min($offset+64,$chunks.Count)
        $points=@(foreach ($chunk in $chunks[$offset..($last-1)]) {
            @{id=(Get-QdrantLabPointId $chunk.id);vector=@($cache.GetVector((Get-QdrantLabDocumentKey $context $chunk)))
                payload=@{lab_owner=$script:LabOwner;chunk_id=$chunk.id;unit_id=$chunk.unitId;law_id=$chunk.lawId;title=$chunk.title;text=$chunk.text
                    source_url=$chunk.sourceUrl;source_sha256=$chunk.sourceSha256;text_sha256=$chunk.textSha256;start_char=$chunk.startChar;end_char=$chunk.endChar
                    corpus_sha256=$context.baseline.corpusSha256;chunking_version=$context.baseline.chunkingVersion;model=$Model;model_digest=$context.baseline.model.modelDigest}}
        })
        $null=Invoke-QdrantLab PUT "collections/$($context.collection)/points?wait=true" @{points=$points}
        if ($offset % 640 -eq 0 -or $last -eq $chunks.Count) { Write-Host "Indexed $last/$($chunks.Count): $($context.collection)" }
    }
    $count=(Invoke-QdrantLab POST "collections/$($context.collection)/points/count" @{exact=$true}).result.count
    if ($count -ne $chunks.Count) { throw 'Indexed point count mismatch.' }
    Write-Host "Indexed $count frozen vectors; repeat Index upserts the same IDs without duplicates."
    return
}
$count=(Invoke-QdrantLab POST "collections/$($context.collection)/points/count" @{exact=$true}).result.count
if ($count -ne $context.data.corpus.chunkCount) { throw 'Partial collection; rerun Index.' }
if ($Action -eq 'Search') {
    if ([string]::IsNullOrWhiteSpace($Query)) { throw 'Search requires -Query.' }
    $vector=Get-QdrantLabQueryVector $context $Query $OllamaPort
    $groups=@(Invoke-QdrantLabGroups $context $vector $Limit ([bool]$Exact) $LawId)
    $rank=0
    foreach ($group in $groups) {
        $rank++; $hit=$group.hits[0]
        [pscustomobject]@{rank=$rank;similarity=$hit.score;unitId=$group.id;title=$hit.payload.title;text=$hit.payload.text;sourceUrl=$hit.payload.source_url;chunkId=$hit.payload.chunk_id;exact=[bool]$Exact}
    }
    return
}
if ($Action -eq 'Verify') {
    $cache=Read-QdrantLabCache $context
    $chunks=Get-QdrantLabChunks $context
    $local=[LegalDenseIndex]::new([int]$context.profile.dimensions)
    foreach ($chunk in $chunks) { $local.Add($chunk.id,$cache.GetVector((Get-QdrantLabDocumentKey $context $chunk))); $local.SetUnit($chunk.id,$chunk.unitId) }
    # Document-vector probes need no live embedding model and test cache import, payload, grouping, filter, and ranking.
    $probes=@($chunks[0],$chunks[[int]($chunks.Count/2)],$chunks[-1])
    $checks=@()
    foreach ($probe in $probes) {
        $vector=$cache.GetVector((Get-QdrantLabDocumentKey $context $probe))
        $retrieved=(Invoke-QdrantLab POST "collections/$($context.collection)/points" @{ids=@((Get-QdrantLabPointId $probe.id));with_payload=$true;with_vector=$true}).result
        if (@($retrieved).Count -ne 1 -or $retrieved[0].payload.text -cne $probe.text -or $retrieved[0].vector.Count -ne $vector.Length) { throw 'Stored point roundtrip failed.' }
        for ($i=0; $i -lt $vector.Length; $i++) { if ([math]::Abs([double]$retrieved[0].vector[$i]-$vector[$i]) -gt 1e-5) { throw 'Stored vector differs from frozen cache.' } }
        $expected=@($local.Rank($vector,10))
        $exactGroups=@(Invoke-QdrantLabGroups $context $vector 10 $true)
        $approxGroups=@(Invoke-QdrantLabGroups $context $vector 10 $false)
        if ($exactGroups.Count -ne 10 -or @($exactGroups.id | Select-Object -Unique).Count -ne 10) { throw 'Grouping count mismatch.' }
        for ($i=0; $i -lt 10; $i++) {
            if ([math]::Abs($exactGroups[$i].hits[0].score-$expected[$i].Score) -gt 1e-5) { throw 'Exact search score differs from local full scan.' }
        }
        $filtered=@(Invoke-QdrantLabGroups $context $vector 5 $true $probe.lawId)
        if ($filtered.Count -eq 0 -or @($filtered | Where-Object { $_.hits[0].payload.law_id -ne $probe.lawId }).Count -gt 0) { throw 'Law filter failed.' }
        $checks+=[pscustomobject]@{chunkId=$probe.id;exactTop10ScoreMatch=$true;annTop10UnitOverlap=@($approxGroups | Where-Object { $_.id -in @($exactGroups.id) }).Count/10.0;filterVerified=$true;vectorRoundtripVerified=$true}
    }
    $report=@{verifiedAtUtc=[datetime]::UtcNow.ToString('o');collection=$context.collection;metadata=$context.metadata;pointCount=$count
        qdrantVersion=(Invoke-RestMethod $script:LabUri -TimeoutSec 10).version;checks=$checks;scope='Three stored document-vector probes, not a full question benchmark or latency comparison.'}
    if (![string]::IsNullOrWhiteSpace($Query)) {
        $queryVector=Get-QdrantLabQueryVector $context $Query $OllamaPort
        $queryGroups=@(Invoke-QdrantLabGroups $context $queryVector 3 $true)
        if ($queryGroups.Count -ne 3) { throw 'Live query smoke test did not return three article groups.' }
        $report.querySmoke=@{query=$Query;exact=$true;results=@(foreach ($group in $queryGroups) {
            @{unitId=$group.id;title=$group.hits[0].payload.title;similarity=$group.hits[0].score}
        })}
    }
    $directory=Join-Path $ReportsRoot 'qdrant-lab'
    $null=New-Item -ItemType Directory -Force $directory
    [IO.File]::WriteAllText((Join-Path $directory "$Model-verification.json"),($report | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
    $checks | Format-Table -AutoSize
    Write-Host "PASS: $count points, cache roundtrip, exact cosine scores, article grouping and law filter."
}
