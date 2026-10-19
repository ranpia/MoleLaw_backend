. (Join-Path $PSScriptRoot 'Paths.ps1')
if (!('LegalDenseIndex' -as [type])) { Add-Type -Path (Join-Path $PSScriptRoot 'DenseIndex.cs') }
$script:LabOwner = 'molelaw-legal-retrieval-lab-v1'

function Initialize-QdrantLab([int]$Port) {
    $values = @{}
    $repoRoot = Split-Path (Split-Path $EvaluationRoot -Parent) -Parent
    foreach ($file in @((Join-Path $repoRoot 'docker/.env.compose'),(Join-Path $repoRoot '.env'))) {
        if (Test-Path -LiteralPath $file) {
            foreach ($line in [IO.File]::ReadAllLines($file)) {
                if ($line -match '^\s*QDRANT_API_KEY\s*=\s*(.*)$' -and !$values.ContainsKey('key')) { $values.key=$matches[1].Trim().Trim('"').Trim("'") }
            }
        }
    }
    $key = $(if ($env:QDRANT_API_KEY) { $env:QDRANT_API_KEY } else { $values.key })
    $script:LabHeaders = @{}
    if ($key) { $script:LabHeaders['api-key'] = $key }
    $script:LabUri = "http://127.0.0.1:$Port"
}

function Invoke-QdrantLab([string]$Method,[string]$Path,$Body=$null) {
    $arguments=@{Method=$Method;Uri="$script:LabUri/$Path";Headers=$script:LabHeaders;TimeoutSec=120;UseBasicParsing=$true}
    if ($null -ne $Body) {
        $arguments.ContentType='application/json; charset=utf-8'
        $arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 30 -Compress))
    }
    # Explicit UTF-8 decoding also works when a JSON response omits charset on PowerShell 5.1.
    $response=Invoke-WebRequest @arguments
    $response.RawContentStream.Position=0
    $reader=[IO.StreamReader]::new($response.RawContentStream,[Text.UTF8Encoding]::new($false,$true))
    try { $result=$reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($result.status -ne 'ok') { throw 'Qdrant operation did not succeed.' }
    return $result
}

function Get-QdrantLabContext([string]$Model) {
    $version='char700-overlap64-article-v1'
    $directory=Join-Path $DataRoot "variants/$version"
    $data=Get-Content -Raw -Encoding UTF8 (Join-Path $directory 'questions.json') | ConvertFrom-Json
    $baseline=Get-Content -Raw -Encoding UTF8 (Join-Path $ReportsRoot "$Model/$version/summary.json") | ConvertFrom-Json
    $profile=(Get-Content -Raw -Encoding UTF8 (Join-Path $ReviewRoot 'models.json') | ConvertFrom-Json).$Model
    if ($baseline.datasetSha256 -ne (Get-FileHash (Join-Path $directory 'questions.json')).Hash -or $baseline.corpusSha256 -ne (Get-FileHash (Join-Path $directory $data.corpus.path)).Hash) { throw 'Frozen dataset mismatch.' }
    foreach ($field in @('dimensions','inputMode','documentFormat','queryFormat')) { if ($profile.$field -ne $baseline.model.$field) { throw "Model profile mismatch: $field" } }
    $collection='molelaw_exp_'+$Model.Replace('-','_')+'_c700_'+$baseline.corpusSha256.Substring(0,8).ToLowerInvariant()+'_'+$baseline.model.modelDigest.Substring(0,8)
    $metadata=@{owner=$script:LabOwner;model=$Model;model_digest=$baseline.model.modelDigest;corpus_sha256=$baseline.corpusSha256
        chunking_version=$version;input_mode=$profile.inputMode;dimensions=[int]$profile.dimensions;chunk_count=[int]$data.corpus.chunkCount}
    return [pscustomobject]@{model=$Model;collection=$collection;directory=$directory;data=$data;baseline=$baseline;profile=$profile;metadata=$metadata}
}

function Assert-QdrantLabCollection($Context,$Info) {
    if ($Context.collection -notmatch '^molelaw_exp_(embeddinggemma|bge_m3|kure_v1)_c700_[a-f0-9]{8}_[a-f0-9]{8}$') { throw 'Not a managed experiment collection.' }
    foreach ($field in @('owner','model','model_digest','corpus_sha256','chunking_version','input_mode','dimensions','chunk_count')) {
        if ($null -eq $Context.metadata.$field -or $Info.config.metadata.$field -ne $Context.metadata.$field) { throw "Collection ownership/identity mismatch: $field" }
    }
    if ($Info.config.params.vectors.size -ne $Context.profile.dimensions -or $Info.config.params.vectors.distance -ne 'Cosine') { throw 'Collection vector schema mismatch.' }
}

function Get-QdrantLabPointId([string]$ChunkId) {
    $hash=[LegalDenseIndex]::InputKey('molelaw-lab-v1:'+ $ChunkId)
    return $hash.Substring(0,8)+'-'+$hash.Substring(8,4)+'-'+$hash.Substring(12,4)+'-'+$hash.Substring(16,4)+'-'+$hash.Substring(20,12)
}

function Read-QdrantLabCache($Context) {
    $key=$Context.model+'-'+$Context.baseline.model.modelDigest.Substring(0,16)+'-'+$Context.profile.inputMode
    $directory=Join-Path $ResultsRoot "shared-cache/$key"
    $metadata=Get-Content -Raw -Encoding UTF8 (Join-Path $directory 'metadata.json') | ConvertFrom-Json
    foreach ($field in @('modelDigest','dimensions','inputMode','documentFormat','queryFormat')) { if ($metadata.$field -ne $Context.baseline.model.$field) { throw "Cache mismatch: $field" } }
    if ($Context.profile.runtime -eq 'sentence-transformers') {
        foreach ($field in @('inferenceRuntimeVersion','dtype','device')) { if ($metadata.$field -ne $Context.baseline.model.$field) { throw "Cache runtime mismatch: $field" } }
    }
    $cache=[LegalDenseIndex]::new([int]$Context.profile.dimensions)
    foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.bin' | Sort-Object Name) { $cache.ReadBatch($file.FullName) }
    return $cache
}

function Get-QdrantLabChunks($Context) {
    return @(foreach ($line in [IO.File]::ReadLines((Join-Path $Context.directory $Context.data.corpus.path))) { $line | ConvertFrom-Json })
}

function Get-QdrantLabDocumentKey($Context,$Chunk) {
    return [LegalDenseIndex]::InputKey($Context.profile.documentFormat.Replace('{title}',$Chunk.title).Replace('{text}',$Chunk.text))
}

function Invoke-QdrantLabGroups($Context,[single[]]$Vector,[int]$Limit=10,[bool]$Exact=$false,[string]$LawId='',[bool]$IncludeTiming=$false) {
    if ($Vector.Length -ne $Context.profile.dimensions) { throw 'Query vector dimension mismatch.' }
    $body=@{query=@($Vector);group_by='unit_id';group_size=1;limit=$Limit;with_payload=$true;with_vector=$false;params=@{exact=$Exact;hnsw_ef=128}}
    if ($LawId) { $body.filter=@{must=@(@{key='law_id';match=@{value=$LawId}})} }
    $response=Invoke-QdrantLab POST "collections/$($Context.collection)/points/query/groups" $body
    if ($IncludeTiming) { return [pscustomobject]@{groups=@($response.result.groups);serverMs=1000.0*$response.time} }
    return $response.result.groups
}

function Assert-QdrantLabRuntime($Context,$Version) {
    if ($Context.profile.runtime -eq 'sentence-transformers') {
        if ($Version.runtime -ne 'sentence-transformers' -or $Version.version -ne $Context.baseline.model.inferenceRuntimeVersion -or $Version.revision -ne $Context.baseline.model.modelDigest -or $Version.dtype -ne $Context.baseline.model.dtype -or $Version.device -ne $Context.baseline.model.device) { throw 'Pinned Sentence Transformers runtime mismatch.' }
    } elseif ($Version.version -ne $Context.baseline.model.ollamaVersion) { throw 'Ollama runtime changed since baseline.' }
}

function Get-QdrantLabQueryVector($Context,[string]$Query,[int]$OllamaPort,[string]$KeepAlive='0') {
    $uri="http://127.0.0.1:$OllamaPort"
    Assert-QdrantLabRuntime $Context (Invoke-RestMethod "$uri/api/version" -TimeoutSec 10)
    $tags=Invoke-RestMethod "$uri/api/tags" -TimeoutSec 10
    $tag=@($tags.models | Where-Object name -eq $Context.profile.tag)
    if ($tag.Count -ne 1 -or $tag[0].digest -ne $Context.baseline.model.modelDigest) { throw 'Ollama model digest mismatch.' }
    $running=Invoke-RestMethod "$uri/api/ps" -TimeoutSec 10
    if (@($running.models | Where-Object name -ne $Context.profile.tag).Count -gt 0) { throw 'Another Ollama model is loaded.' }
    $body=@{model=$Context.profile.tag;input=@($Context.profile.queryFormat.Replace('{text}',$Query));truncate=$false;keep_alive=$KeepAlive;options=@{num_thread=6;num_gpu=0}}
    $result=Invoke-RestMethod -Method Post -Uri "$uri/api/embed" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6 -Compress))) -TimeoutSec 300
    if ($result.embeddings.Count -ne 1 -or $result.embeddings[0].Count -ne $Context.profile.dimensions) { throw 'Invalid query embedding.' }
    if ($Context.profile.runtime -eq 'sentence-transformers' -and $KeepAlive -eq '0') {
        $null=Invoke-RestMethod -Method Post -Uri "$uri/api/embed" -ContentType 'application/json' -Body (@{model=$Context.profile.tag;input=@();keep_alive=0} | ConvertTo-Json -Compress) -TimeoutSec 30
    }
    return ,([single[]]$result.embeddings[0])
}
