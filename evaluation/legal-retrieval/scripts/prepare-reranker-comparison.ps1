[CmdletBinding()]
param([ValidateSet(300,500,700)][int]$Size=500,
    [ValidateSet('original','rewritten','legal_transformed')][string]$QueryMode='legal_transformed',
    [int]$QdrantPort=6333,[string]$OutputDirectory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RerankerComparison.ps1')
Initialize-QdrantLab $QdrantPort
if(!$OutputDirectory){$OutputDirectory=Get-DataPath "reranker-v1/c$Size-$QueryMode"}
$OutputDirectory=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $OutputDirectory){throw 'Use a fresh directory; frozen experiment inputs are never overwritten.'}
$manifestPath=Get-ReportPath 'recall50-v1/kure-v1/manifest.json'
$reportPath=Get-ReportPath 'recall50-v1/kure-v1/report.json'
$topPath=Get-ReportPath 'recall50-v1/kure-v1/top50.csv'
$queriesPath=Get-ReportPath 'legal-query-chunks-v1/kure-v1/queries.csv'
$questionsPath=Get-DataPath 'independent-validation-v1/questions.json'
$manifest=Read-RerankerJson $manifestPath; $report=Read-RerankerJson $reportPath
if((Get-FileHash $questionsPath).Hash -ne $manifest.goldQuestionsSha256 -or
    (Get-FileHash $queriesPath).Hash -ne $manifest.baselineQueriesSha256){throw 'Frozen gold/query hashes changed.'}
$source=@($manifest.sources | Where-Object {$_.size -eq $Size -and $QueryMode -in $_.modes})
if($source.Count -ne 1){throw 'Ambiguous baseline source.'}; $source=$source[0]
$archive="molelaw_recall50_kure_v1_c${Size}_$($report.freezeSha256.Substring(0,8))"
$info=(Invoke-QdrantLab GET "collections/$archive").result
$doc=(Invoke-QdrantLab GET "collections/$($source.document)").result
foreach($c in @($info,$doc)) {
    if($c.config.metadata.owner -ne $script:LabOwner -or $c.config.metadata.model_digest -ne $manifest.modelRevision -or
        $c.config.metadata.corpus_sha256 -ne $source.corpus -or $c.config.params.vectors.size -ne 1024 -or
        $c.config.params.vectors.distance -ne 'Cosine'){throw 'Experiment collection ownership/model/corpus/schema mismatch.'}
}
if($info.config.metadata.freeze_sha256 -ne $report.freezeSha256 -or $doc.config.metadata.freeze_sha256 -ne $source.freeze){throw 'Collection freeze mismatch.'}
$gold=@{}; foreach($q in (Read-RerankerJson $questionsPath).questions){if($q.cohort -eq 'in_corpus'){$gold[$q.id]=$q}}
$queries=@{}; foreach($q in Import-Csv $queriesPath){if($q.maxChars -eq "$Size" -and $q.queryMode -eq $QueryMode){$queries[$q.queryId]=$q}}
$frozen=@{}; foreach($hit in Import-Csv $topPath){if($hit.maxChars -eq "$Size" -and $hit.queryMode -eq $QueryMode){$frozen["$($hit.queryId)/$($hit.rank)"]=$hit}}
$records=@(); $offset=$null; $seen=@{}
do {
    $body=@{limit=100;with_payload=$true;with_vector=$true}; if($null -ne $offset){$body.offset=$offset}
    $page=(Invoke-QdrantLab POST "collections/$archive/points/scroll" $body).result
    foreach($point in $page.points) {
        $p=$point.payload
        if($p.query_mode -ne $QueryMode){continue}
        $id=$p.query_id
        if(!$gold.ContainsKey($id) -or !$queries.ContainsKey($id) -or $seen.ContainsKey($id)){throw 'Unexpected or duplicate query.'}
        $seen[$id]=$true; $q=$gold[$id]; $query=$queries[$id]
        if($p.freeze_sha256 -ne $report.freezeSha256 -or $p.document_collection -ne $source.document -or
            $p.retrieval_text -cne $query.retrievalText -or
            (@($p.gold_unit_ids | Sort-Object) -join '|') -cne (@($q.goldUnitIds | Sort-Object) -join '|')){throw 'Query/gold identity mismatch.'}
        $groups=@(Invoke-QdrantLab POST "collections/$($source.document)/points/query/groups" @{
            query=@($point.vector);group_by='unit_id';group_size=1;limit=50;with_payload=$true;with_vector=$false;params=@{exact=$true}})
        $groups=@($groups[0].result.groups)
        if($groups.Count -ne 50 -or @($groups.id | Select-Object -Unique).Count -ne 50){throw 'Expected exactly 50 unique candidate units.'}
        $candidates=@(for($i=0;$i -lt 50;$i++){
            $group=$groups[$i]; $hit=$group.hits[0]; $old=$frozen["$id/$($i+1)"]
            if(!$old -or $old.unitId -cne $group.id -or [math]::Abs([double]$old.similarity-[double]$hit.score) -gt 0.00001){throw "Frozen dense ranking changed: $id/$($i+1)"}
            if([string]::IsNullOrWhiteSpace($hit.payload.title) -or [string]::IsNullOrWhiteSpace($hit.payload.text)){throw 'Missing representative chunk text.'}
            [pscustomobject]@{rank=$i+1;unitId=$group.id;similarity=[double]$hit.score;pointId=$hit.id;
                title=$hit.payload.title;text=$hit.payload.text;sourceUrl=$hit.payload.source_url}
        })
        $records+=[pscustomobject]@{queryId=$id;queryMode=$QueryMode;originalText=$query.originalText;retrievalText=$p.retrieval_text;
            goldUnitIds=@($q.goldUnitIds);candidates=$candidates}
        Write-Host "Frozen $($records.Count)/36 queries ($id)"
    }
    $offset=$page.next_page_offset
} while($null -ne $offset)
if($records.Count -ne 36){throw 'Expected all 36 in-corpus questions.'}
$null=New-Item -ItemType Directory -Path $OutputDirectory
$candidatePath=Join-Path $OutputDirectory 'candidates.jsonl'
$lines=@($records | Sort-Object queryId | ForEach-Object {$_ | ConvertTo-Json -Depth 20 -Compress})
[IO.File]::WriteAllText($candidatePath,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
Write-RerankerJson (Join-Path $OutputDirectory 'manifest.json') ([ordered]@{
    id='reranker-dense50-v1';createdAtUtc=[datetime]::UtcNow.ToString('o');maxChars=$Size;queryMode=$QueryMode;
    candidateCount=50;questionCount=36;model='kure-v1';modelRevision=$manifest.modelRevision;
    documentCollection=$source.document;queryArchive=$archive;corpusSha256=$source.corpus;
    goldQuestionsSha256=$manifest.goldQuestionsSha256;baselineQuerySha256=$manifest.baselineQueriesSha256;
    baselineTop50Sha256=(Get-FileHash $topPath).Hash;baselineManifestSha256=(Get-FileHash $manifestPath).Hash;
    candidatesSha256=(Get-FileHash $candidatePath).Hash;documentCount=$doc.points_count;
    documentFormat='title + newline + representative dense-search chunk';thresholdApplied=$false;
    policy='Exploratory paired reranking on a previously explored 36-question set. Read-only Qdrant; no embeddings, corpus or gold changes.'})
Write-Host "FROZEN: $OutputDirectory (36 x 50 candidates; Qdrant unchanged)"
