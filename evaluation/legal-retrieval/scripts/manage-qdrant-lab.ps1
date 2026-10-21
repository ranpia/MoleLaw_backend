[CmdletBinding()]
param([ValidateSet('List','ApplyAliases','Verify')][string]$Action='List',[int]$QdrantPort=6333)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
Initialize-QdrantLab $QdrantPort
$catalogPath=Join-Path $EvaluationRoot 'experiments/infrastructure/qdrant/collections.json'
function Get-Classification([string]$Name) {
    if($Name -match '^molelaw_candidates_(bge_m3|qwen3_0_6b)_c(300|500|700)_[a-f0-9]{8}(_queries)?$'){
        $model=$matches[1];$size=$matches[2];$role=$(if($matches[3]){'queries'}else{'docs'})
        return @('query-strategies',("lab_candidates_${model}_c${size}_$role"),$role,'experiments/query-strategies/candidate-coverage-v1/reports')
    }
    if($Name -eq 'molelaw_eval_catalog_v1'){return @('model-comparison','lab_models_catalog','catalog','experiments/model-comparison/reports')}
    if($Name -match '^molelaw_exp_(.+)_c(300|500|700)_[a-f0-9]{8}_[a-f0-9]{8}$'){return @('model-comparison',('lab_models_'+$matches[1].Replace('.','_')+'_c'+$matches[2]+'_docs'),'documents','experiments/model-comparison/reports')}
    if($Name -match '^molelaw_eval_queries_(.+)_[a-f0-9]{8}_[a-f0-9]{8}$'){return @('model-comparison',('lab_models_'+$matches[1].Replace('.','_')+'_queries'),'queries','experiments/model-comparison/reports')}
    if($Name -match '^molelaw_validation_final_v1_manifest_[a-f0-9]{8}$'){return @('retrieval-validation','lab_validation_expanded_manifest','manifest','experiments/retrieval-validation/expanded-v1/reports')}
    if($Name -match '^molelaw_validation_kure_v1_c300_[a-f0-9]{8}_[a-f0-9]{8}(_queries)?$'){
        $role=$(if($matches[1]){'queries'}else{'docs'});return @('retrieval-validation',('lab_validation_expanded_'+$role),$role,'experiments/retrieval-validation/expanded-v1/reports')
    }
    if($Name -match '^molelaw_independent_kure_v1_[a-f0-9]{8}$'){return @('retrieval-validation','lab_validation_independent_queries','queries','experiments/retrieval-validation/independent-v1/reports')}
    if($Name -match '^molelaw_legal_chunks_kure_v1_c(500|700)_[a-f0-9]{8}_[a-f0-9]{8}(_queries)?$'){
        $size=$matches[1];$role=$(if($matches[2]){'queries'}else{'docs'});return @('query-strategies',("lab_queries_c${size}_$role"),$role,'experiments/query-strategies/chunk-sizes-v1/reports')
    }
    if($Name -match '^molelaw_legal_query_kure_v1_[a-f0-9]{8}$'){return @('query-strategies','lab_queries_transformed_queries','queries','experiments/query-strategies/sentence-transform-v1/reports')}
    if($Name -match '^molelaw_recall50_kure_v1_c(300|500|700)_[a-f0-9]{8}$'){return @('query-strategies',('lab_queries_c'+$matches[1]+'_candidates50'),'candidates','experiments/query-strategies/candidate-coverage-v1/reports')}
    return @('unmanaged',$null,'unknown',$null)
}
function Get-Inventory {
    foreach($collection in @((Invoke-QdrantLab GET 'collections').result.collections|Sort-Object name)){
        $info=(Invoke-QdrantLab GET "collections/$($collection.name)").result
        $classification=Get-Classification $collection.name
        if($info.config.metadata.owner -ne $script:LabOwner){$classification=@('unmanaged',$null,'unknown',$null)}
        $count=(Invoke-QdrantLab POST "collections/$($collection.name)/points/count" @{exact=$true}).result.count
        [pscustomobject][ordered]@{collection=$collection.name;group=$classification[0];alias=$classification[1];role=$classification[2];reports=$classification[3];points=$count;metadata=$info.config.metadata;vectors=$info.config.params.vectors}
    }
}
$before=@(Get-Inventory)
$aliases=@((Invoke-QdrantLab GET 'aliases').result.aliases)
if($Action -eq 'List'){
    $before|Sort-Object group,alias|Select-Object group,alias,role,points,collection
    return
}
if($Action -eq 'ApplyAliases'){
    $actions=@(foreach($entry in $before|Where-Object {$_.group -ne 'unmanaged'}){
        $existing=@($aliases|Where-Object alias_name -eq $entry.alias)
        if($existing.Count -gt 1 -or ($existing.Count -eq 1 -and $existing[0].collection_name -ne $entry.collection)){throw "Alias conflict: $($entry.alias)"}
        if(@($before|Where-Object collection -eq $entry.alias).Count){throw "Alias collides with physical collection: $($entry.alias)"}
        if(!$existing.Count){@{create_alias=@{collection_name=$entry.collection;alias_name=$entry.alias}}}
    })
    if(@($before.alias|Where-Object {$_}|Group-Object|Where-Object Count -gt 1).Count){throw 'Duplicate planned alias'}
    $audit=Join-Path $ArtifactsRoot 'organization-v1';$null=New-Item -ItemType Directory -Force -Path $audit
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
    [IO.File]::WriteAllText((Join-Path $audit "qdrant-before-$stamp.json"),(@{collections=$before;aliases=$aliases}|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
    if($actions.Count){$null=Invoke-QdrantLab POST 'collections/aliases' @{actions=$actions}}
    $after=@(Get-Inventory)
    if(($before|ConvertTo-Json -Depth 30 -Compress) -cne ($after|ConvertTo-Json -Depth 30 -Compress)){throw 'Collection inventory changed; inspect saved before inventory'}
    [IO.File]::WriteAllText($catalogPath,(@{schemaVersion=1;collections=$before}|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
    Write-Host "Applied $($actions.Count) aliases; physical collections, exact counts and schemas unchanged."
}
if(!(Test-Path -LiteralPath $catalogPath)){throw 'Catalog missing; run ApplyAliases first'}
$catalog=Get-Content -Raw -Encoding UTF8 $catalogPath|ConvertFrom-Json
$aliases=@((Invoke-QdrantLab GET 'aliases').result.aliases)
foreach($entry in $catalog.collections|Where-Object {$_.group -ne 'unmanaged'}){
    $actual=@($before|Where-Object collection -eq $entry.collection)
    if($actual.Count -ne 1 -or $actual[0].points -ne $entry.points){throw "Catalog count mismatch: $($entry.collection)"}
    if(($actual[0].metadata|ConvertTo-Json -Depth 20 -Compress) -cne ($entry.metadata|ConvertTo-Json -Depth 20 -Compress)){throw "Catalog metadata mismatch: $($entry.collection)"}
    $alias=@($aliases|Where-Object alias_name -eq $entry.alias)
    if($alias.Count -ne 1 -or $alias[0].collection_name -ne $entry.collection){throw "Alias mapping mismatch: $($entry.alias)"}
    $info=(Invoke-QdrantLab GET "collections/$($entry.alias)").result
    if(($info.config.params.vectors|ConvertTo-Json -Depth 20 -Compress) -cne ($entry.vectors|ConvertTo-Json -Depth 20 -Compress)){throw 'Alias vector schema mismatch'}
    if((Invoke-QdrantLab POST "collections/$($entry.alias)/points/count" @{exact=$true}).result.count -ne $entry.points){throw 'Alias count mismatch'}
    $physical=(Invoke-QdrantLab POST "collections/$($entry.collection)/points/scroll" @{limit=1;with_payload=$true;with_vector=$true}).result.points|ConvertTo-Json -Depth 30 -Compress
    $viaAlias=(Invoke-QdrantLab POST "collections/$($entry.alias)/points/scroll" @{limit=1;with_payload=$true;with_vector=$true}).result.points|ConvertTo-Json -Depth 30 -Compress
    if($physical -cne $viaAlias){throw 'Alias sample point mismatch'}
}
Write-Host "PASS: $(@($catalog.collections|Where-Object {$_.group -ne 'unmanaged'}).Count) managed aliases, exact counts, vector schemas and sample points."
