[CmdletBinding()]
param([string]$ServiceRepository,
    [ValidateRange(1,36)][int]$QuestionLimit=36,[string]$SnapshotDirectory,[string]$RunId)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RerankerComparison.ps1')
if(!$ServiceRepository){$ServiceRepository=Join-Path $PSScriptRoot '../../../../MoleLaw_backend'}
$ServiceRepository=[IO.Path]::GetFullPath($ServiceRepository)
if(!$RunId){$RunId=[datetime]::UtcNow.ToString('yyyyMMddTHHmmssZ')}
if($RunId -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid run ID.'}
$backend=@(docker inspect backend-service-backend-1 | ConvertFrom-Json)[0]
if(!$backend){throw 'Running service configuration unavailable.'}
$public=@{}; $private=@{}
foreach($entry in $backend.Config.Env){
    $parts=$entry.Split('=',2)
    if($parts[0] -in @('OPENAI_API_KEY','QDRANT_API_KEY')){$private[$parts[0]]=$parts[1]}
    elseif($parts[0] -match '^(APP_AI_(QUESTION|EVIDENCE|READ)_|APP_LAW_RETRIEVAL_|APP_LAW_RERANKER_)'){$public[$parts[0]]=$parts[1]}
}
foreach($name in @('OPENAI_API_KEY','QDRANT_API_KEY')){
    if(!$private[$name]){throw "Service credential unavailable: $name"}
    [Environment]::SetEnvironmentVariable($name,$private[$name],'Process')
}
$private.Clear()
Initialize-QdrantLab 6333
$collection='molelaw_legal_chunks_kure_v1_c500_431ce0e1_8b418a58'
$info=(Invoke-QdrantLab GET "collections/$collection").result
$count=(Invoke-QdrantLab POST "collections/$collection/points/count" @{exact=$true}).result.count
if($info.config.metadata.owner -ne $script:LabOwner -or $count -ne 8286 -or
    $info.config.metadata.corpus_sha256 -ne '431CE0E1D41EE6894F21E8966E49396387147E788CC8DBF0DB8255990EA5353E' -or
    $info.config.params.vectors.size -ne 1024 -or $info.config.params.vectors.distance -ne 'Cosine') {throw 'Frozen collection identity mismatch.'}
$inputPath=Get-DataPath 'reranker-v1/c500-legal_transformed/candidates.jsonl'
$corpusPath=Get-DataPath 'legal-query-chunks-v1/char500/corpus.jsonl'
if((Get-FileHash $inputPath).Hash -ne 'EF03C5E0D59C9FC45F789E6EE420370951DB3E496BD3DF6C5F3E60F2E1C37DE4' -or
    (Get-FileHash $corpusPath).Hash -ne $info.config.metadata.corpus_sha256){throw 'Frozen input hash mismatch.'}
$expected=@{APP_AI_QUESTION_MODEL='gpt-5.4-mini';APP_AI_EVIDENCE_MODEL='gpt-5.4';
    APP_AI_QUESTION_REASONING_EFFORT='low';APP_AI_EVIDENCE_REASONING_EFFORT='medium';
    APP_LAW_RETRIEVAL_PROCESSING_TIMEOUT='15m';APP_LAW_RERANKER_READ_TIMEOUT_MILLIS='120000'}
foreach($key in $expected.Keys){if($public[$key] -cne $expected[$key]){throw "Running service option changed: $key"}}
if(!$SnapshotDirectory){
    $SnapshotDirectory=Join-Path $ArtifactsRoot "adaptive-comparison-v1/snapshots/$RunId"
    if(Test-Path -LiteralPath $SnapshotDirectory){throw 'Snapshot already exists.'}
    $null=New-Item -ItemType Directory -Path $SnapshotDirectory
    $null=New-Item -ItemType Directory -Path (Join-Path $SnapshotDirectory 'src')
    Copy-Item -LiteralPath (Join-Path $ServiceRepository 'src/main') -Destination (Join-Path $SnapshotDirectory 'src/main') -Recurse
    Copy-Item -LiteralPath (Join-Path $ServiceRepository 'gradle') -Destination (Join-Path $SnapshotDirectory 'gradle') -Recurse
    Copy-Item -LiteralPath (Join-Path $ServiceRepository 'gradlew.bat') -Destination $SnapshotDirectory
    $build=[IO.File]::ReadAllText((Join-Path $ServiceRepository 'build.gradle'))
    $null=New-Item -ItemType Directory -Path (Join-Path $SnapshotDirectory 'harness')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'java/AdaptiveRetrievalComparison.java') -Destination (Join-Path $SnapshotDirectory 'harness')
    $harness='harness'
    $build+="`nsourceSets.test.java.setSrcDirs(['$harness'])`nsourceSets.test.resources.setSrcDirs([])`n"
    $build+=@'
tasks.register('adaptiveComparison', JavaExec) {
    dependsOn testClasses
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'com.MoleLaw_backend.law.service.tool.AdaptiveRetrievalComparison'
    maxHeapSize = '2g'
    args providers.gradleProperty('evaluationRoot').get(), providers.gradleProperty('comparisonOutput').get(), providers.gradleProperty('questionLimit').get()
}
'@
    [IO.File]::WriteAllText((Join-Path $SnapshotDirectory 'build.gradle'),$build,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $SnapshotDirectory 'settings.gradle'),"rootProject.name='adaptive-retrieval-frozen-runtime'`n")
    $files=@(Get-ChildItem -LiteralPath (Join-Path $SnapshotDirectory 'src/main') -Recurse -File | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{path=$_.FullName.Substring($SnapshotDirectory.Length+1).Replace('\','/');sha256=(Get-FileHash $_.FullName).Hash}
    })
    Write-RerankerJson (Join-Path $SnapshotDirectory 'snapshot.json') @{sourceRepository=$ServiceRepository;head=(& git -C $ServiceRepository rev-parse HEAD);
        capturedAtUtc=[datetime]::UtcNow.ToString('o');files=$files;runtime=$public;harnessSha256=(Get-FileHash (Join-Path $PSScriptRoot 'java/AdaptiveRetrievalComparison.java')).Hash}
}
$SnapshotDirectory=[IO.Path]::GetFullPath($SnapshotDirectory)
$snapshot=Read-RerankerJson (Join-Path $SnapshotDirectory 'snapshot.json')
foreach($file in $snapshot.files){if((Get-FileHash (Join-Path $SnapshotDirectory $file.path)).Hash -ne $file.sha256){throw 'Runtime snapshot changed.'}}
if((Get-FileHash (Join-Path $PSScriptRoot 'java/AdaptiveRetrievalComparison.java')).Hash -ne $snapshot.harnessSha256){throw 'Harness changed; create a fresh snapshot.'}
$output=Get-ReportPath "adaptive-comparison-v1/$RunId"
if(Test-Path -LiteralPath $output){throw 'Report already exists; use a new run ID.'}
$null=New-Item -ItemType Directory -Path $output
Write-RerankerJson (Join-Path $output 'run.json') @{completed=$false;questionCount=$QuestionLimit;snapshotDirectory=$SnapshotDirectory;
    snapshotSha256=(Get-FileHash (Join-Path $SnapshotDirectory 'snapshot.json')).Hash;runtime=$public;
    corpusSha256=(Get-FileHash $corpusPath).Hash;questionsSha256=(Get-FileHash $inputPath).Hash;collection=$collection;
    intervention='Shared prefix: initial post-fusion/balancing pool vs terminal pool. Same reranker; no external collection; no answer generation.'}
Write-RerankerJson (Join-Path $output 'source-snapshot.json') $snapshot
Write-RerankerJson (Join-Path $output 'qdrant-before.json') @{count=$count;config=$info.config}
$env:GRADLE_USER_HOME=$(if($env:GRADLE_USER_HOME){$env:GRADLE_USER_HOME}else{Join-Path $env:USERPROFILE '.gradle'})
Push-Location $SnapshotDirectory
try {
    & .\gradlew.bat adaptiveComparison "-PevaluationRoot=$EvaluationRoot" "-PcomparisonOutput=$output" "-PquestionLimit=$QuestionLimit" --console=plain
    if($LASTEXITCODE -ne 0){throw 'Adaptive comparison process failed; partial report preserved.'}
} finally {Pop-Location}
$after=(Invoke-QdrantLab GET "collections/$collection").result
$afterCount=(Invoke-QdrantLab POST "collections/$collection/points/count" @{exact=$true}).result.count
Write-RerankerJson (Join-Path $output 'qdrant-after.json') @{count=$afterCount;config=$after.config}
if($count -ne $afterCount -or ($info.config | ConvertTo-Json -Depth 30 -Compress) -cne ($after.config | ConvertTo-Json -Depth 30 -Compress)){throw 'Qdrant collection changed during experiment.'}
$run=Read-RerankerJson (Join-Path $output 'run.json');$run.completed=$true
Write-RerankerJson (Join-Path $output 'run.json') $run
Write-Host "COMPLETED: $output"
