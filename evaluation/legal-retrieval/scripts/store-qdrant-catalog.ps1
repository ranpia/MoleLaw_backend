[CmdletBinding()]
param([int]$QdrantPort=6333)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/ComparisonIdentity.ps1')
Initialize-QdrantLab $QdrantPort
$name='molelaw_eval_catalog_v1'
$owner=@{owner=$script:LabOwner;kind='evaluation-catalog-v1'}
$entries=@()
foreach($size in @(300,500,700)){
    $dir=$(if($size -eq 500){$DataRoot}else{Get-DataPath "variants/char$size-overlap64-article-v1"})
    $path=Join-Path $dir 'questions.json'
    $data=Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
    $identity=Get-ComparisonIdentity $dir
    if($data.corpus.sha256 -ne (Get-FileHash (Join-Path $dir $data.corpus.path)).Hash){throw 'Catalog corpus hash mismatch'}
    $hash=(Get-FileHash $path).Hash
    $entries+=@{id=(Get-QdrantLabPointId "catalog/dataset/$hash");vector=@{};payload=@{kind='dataset';max_chars=$size;sha256=$hash;comparison_identity_sha256=$identity;source_bytes_base64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($path));data=$data}}
}
$path=Get-ReviewPath 'query-rewrites-v1.json'
$hash=(Get-FileHash $path).Hash
$entries+=@{id=(Get-QdrantLabPointId "catalog/rewrites/$hash");vector=@{};payload=@{kind='query_rewrites';sha256=$hash;source_bytes_base64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($path));data=(Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json)}}
$models=Get-Content -Raw -Encoding UTF8 (Get-ReviewPath 'models.json') | ConvertFrom-Json
foreach($property in $models.PSObject.Properties){
    $entries+=@{id=(Get-QdrantLabPointId "catalog/model/$($property.Name)/$($property.Value.inputMode)");vector=@{};payload=@{kind='model_profile';model=$property.Name;data=$property.Value}}
}
if(@((Invoke-QdrantLab GET 'collections').result.collections | Where-Object name -eq $name).Count -eq 0){
    $null=Invoke-QdrantLab PUT "collections/$name" @{vectors=@{};metadata=$owner}
}
$info=(Invoke-QdrantLab GET "collections/$name").result
foreach($field in $owner.Keys){if($info.config.metadata.$field -ne $owner[$field]){throw 'Catalog ownership mismatch'}}
if(@($info.config.params.vectors.PSObject.Properties).Count -ne 0){throw 'Catalog must be payload-only'}
$null=Invoke-QdrantLab PUT "collections/$name/index?wait=true" @{field_name='kind';field_schema='keyword'}
$null=Invoke-QdrantLab PUT "collections/$name/points?wait=true" @{points=$entries}
$readback=@((Invoke-QdrantLab POST "collections/$name/points" @{ids=@($entries.id);with_payload=$true}).result)
if($readback.Count -ne $entries.Count){throw 'Catalog roundtrip count mismatch'}
foreach($entry in $entries){
    $stored=@($readback | Where-Object id -eq $entry.id)[0]
    if($stored.payload.kind -ne $entry.payload.kind -or $stored.payload.sha256 -ne $entry.payload.sha256){throw 'Catalog roundtrip identity mismatch'}
    if($entry.payload.kind -eq 'dataset' -and @($stored.payload.data.questions).Count -ne 110){throw 'Catalog questions lost'}
    if($entry.payload.source_bytes_base64){
        $sha=[Security.Cryptography.SHA256]::Create()
        try{$actual=[BitConverter]::ToString($sha.ComputeHash([Convert]::FromBase64String($stored.payload.source_bytes_base64))).Replace('-','')}finally{$sha.Dispose()}
        if($actual -ne $entry.payload.sha256){throw 'Catalog original byte roundtrip failed'}
    }
}
Write-Host "PASS: $name, $($entries.Count) catalog entries (datasets, frozen rewrites, model profiles). No query/model vectors mixed into catalog."
