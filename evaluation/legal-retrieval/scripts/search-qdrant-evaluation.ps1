[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Model,[Parameter(Mandatory=$true)][string]$QueryId,
    [ValidateSet('original','rewritten')][string]$QueryMode='rewritten',[ValidateSet(300,500,700)][int]$Size=500,[int]$QdrantPort=6333,[string]$QueryCollection)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
Initialize-QdrantLab $QdrantPort
$prefix='molelaw_eval_queries_'+$Model.Replace('-','_').Replace('.','_')+'_'
$collections=@((Invoke-QdrantLab GET 'collections').result.collections | Where-Object {$_.name.StartsWith($prefix)})
if($QueryCollection){$collections=@($collections | Where-Object name -eq $QueryCollection)}
if($collections.Count -ne 1){throw 'Expected one query collection; select the checkpoint with -QueryCollection.'}
$name=$collections[0].name
$info=(Invoke-QdrantLab GET "collections/$name").result
if($info.config.metadata.owner -ne $script:LabOwner -or $info.config.metadata.model -ne $Model -or $info.config.metadata.kind -ne 'evaluation-queries-v1'){throw 'Query collection identity mismatch'}
$id=Get-QdrantLabPointId "$name/$QueryId/$QueryMode"
$point=@((Invoke-QdrantLab POST "collections/$name/points" @{ids=@($id);with_payload=$true;with_vector=$true}).result)
if($point.Count -ne 1 -or $point[0].payload.query_id -ne $QueryId -or $point[0].payload.query_mode -ne $QueryMode){throw 'Query not found'}
$stored=$point[0]
$collection=$stored.payload.results."char$Size".collection
if(!$collection){throw "No completed char$Size evaluation for this checkpoint."}
$documentInfo=(Invoke-QdrantLab GET "collections/$collection").result
if($documentInfo.config.metadata.owner -ne $script:LabOwner -or $documentInfo.config.metadata.model -ne $Model -or $documentInfo.config.metadata.model_digest -ne $info.config.metadata.model_digest -or $documentInfo.config.params.vectors.size -ne $stored.vector.Count){throw 'Document/query model identity mismatch'}
$response=(Invoke-QdrantLab POST "collections/$collection/points/query/groups" @{query=@($stored.vector);group_by='unit_id';group_size=1;limit=10;with_payload=$true;params=@{exact=$true}}).result
$rank=0
foreach($group in $response.groups){
    $rank++
    [pscustomobject]@{queryId=$QueryId;queryMode=$QueryMode;retrievalText=$stored.payload.retrieval_text;rank=$rank;unitId=$group.id;similarity=$group.hits[0].score;relevant=($group.id -in @($stored.payload.relevant_unit_ids));title=$group.hits[0].payload.title;text=$group.hits[0].payload.text;sourceUrl=$group.hits[0].payload.source_url}
}
