$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
$context=Get-QdrantLabContext 'embeddinggemma'
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
