$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
$context=Get-QdrantLabContext 'embeddinggemma'
$variantContexts=@(foreach ($size in @(300,500,700)) {
    $variant=Get-QdrantLabContext 'embeddinggemma' $size
    $version="char$size-overlap64-article-v1"
    $expectedDirectory=$(if ($size -eq 500) { $DataRoot } else { Get-DataPath "variants/$version" })
    $expectedCount=@{300=8497;500=6634;700=6031}[$size]
    if ($variant.directory -ne $expectedDirectory -or $variant.data.corpus.chunkCount -ne $expectedCount -or $variant.metadata.chunk_count -ne $expectedCount -or $variant.metadata.chunking_version -ne $version -or $variant.collection -notmatch "_c$($size)_") { throw "Invalid size context: $size" }
    $variantInfo=[pscustomobject]@{config=[pscustomobject]@{metadata=$variant.metadata;params=[pscustomobject]@{vectors=[pscustomobject]@{size=768;distance='Cosine'}}}}
    Assert-QdrantLabCollection $variant $variantInfo
    foreach ($field in @('chunking_version','chunk_count')) {
        $badInfo=$variantInfo | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $badInfo.config.metadata.$field=$(if ($field -eq 'chunk_count') { 1 } else { 'char999-overlap64-article-v1' })
        $rejected=$false
        try { Assert-QdrantLabCollection $variant $badInfo } catch { $rejected=$true }
        if (!$rejected) { throw "Wrong size metadata accepted: $size/$field" }
    }
    $variant
})
if (@($variantContexts.collection | Select-Object -Unique).Count -ne 3 -or @($variantContexts.directory | Select-Object -Unique).Count -ne 3) { throw 'Chunk variants share a collection or dataset path.' }
Write-Host 'PASS: 300/500/700 paths, corpus counts, collection separation and size metadata guards.'
$info=[pscustomobject]@{config=[pscustomobject]@{metadata=$context.metadata;params=[pscustomobject]@{vectors=[pscustomobject]@{size=768;distance='Cosine'}}}}
Assert-QdrantLabCollection $context $info
$id=Get-QdrantLabPointId 'chunk-one'
if ($id -ne (Get-QdrantLabPointId 'chunk-one') -or $id -eq (Get-QdrantLabPointId 'chunk-two') -or $id -notmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') { throw 'Invalid deterministic point ID.' }
foreach ($case in @('owner','dimension','name','corpus')) {
    $badContext=$context | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $badInfo=$info | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    switch ($case) {
        'owner' { $badInfo.config.metadata.owner='another-application' }
        'dimension' { $badInfo.config.params.vectors.size=1024 }
        'name' { $badContext.collection='law_documents' }
        'corpus' { $badInfo.config.metadata.corpus_sha256='different-data' }
    }
    $rejected=$false
    try { Assert-QdrantLabCollection $badContext $badInfo } catch { $rejected=$true }
    if (!$rejected) { throw "Unsafe collection accepted: $case" }
}
Write-Host 'PASS: deterministic IDs; collection owner, dimension, name and corpus guards. No live deletion performed.'
$kure=Get-QdrantLabContext 'kure-v1'
$runtime=[pscustomobject]@{runtime='sentence-transformers';version=$kure.baseline.model.inferenceRuntimeVersion;revision=$kure.baseline.model.modelDigest;dtype='float32';device='cpu'}
Assert-QdrantLabRuntime $kure $runtime
foreach ($field in @('runtime','version','revision','dtype','device')) {
    $bad=$runtime | ConvertTo-Json | ConvertFrom-Json
    $bad.$field='unexpected'
    $rejected=$false
    try { Assert-QdrantLabRuntime $kure $bad } catch { $rejected=$true }
    if (!$rejected) { throw "Incompatible KURE runtime accepted: $field" }
}
$kureInfo=[pscustomobject]@{config=[pscustomobject]@{metadata=$kure.metadata;params=[pscustomobject]@{vectors=[pscustomobject]@{size=1024;distance='Cosine'}}}}
Assert-QdrantLabCollection $kure $kureInfo
Write-Host 'PASS: KURE collection identity and pinned runtime/revision/dtype/device guards.'
