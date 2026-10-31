[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/RerankerComparison.ps1')
$directory=Join-Path $ArtifactsRoot ('adaptive-comparison-v1/contract-tests/'+[guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $directory
$input=([IO.File]::ReadAllLines((Get-DataPath 'reranker-v1/c500-legal_transformed/candidates.jsonl'))[0] | ConvertFrom-Json)
$items=@(for($i=0;$i -lt $input.goldUnitIds.Count;$i++){[pscustomobject]@{id="fixture-$i";sourceId=$input.goldUnitIds[$i];title='Synthetic metric fixture';content='Synthetic metric fixture'}})
if($items.Count -lt 2){throw 'Test fixture needs multiple gold units.'}
$trace=[ordered]@{queryId=$input.queryId;question=$input.originalText;goldUnitIds=$input.goldUnitIds;
    initialQueries=@('fixture query');initialPool=@($items[0]);lastPool=$items;selectedEvidence=$items;
    status='SUCCESS';initialElapsedMs=10;elapsedMs=30;searches=@();
    rounds=@(@{remainingActions=4;pool=@($items[0]);decision=@{action='SEARCH_AGAIN';evidenceIds=@($items[0].id)}},
             @{remainingActions=3;pool=$items;decision=@{action='ANSWER_READY';evidenceIds=@($items.id)}})}
Write-RerankerJson (Join-Path $directory 'run.json') @{completed=$true;questionCount=1}
$path=Join-Path $directory ($input.queryId+'.json')
Write-RerankerJson $path $trace
& (Join-Path $PSScriptRoot 'summarize-adaptive-comparison.ps1') -ReportDirectory $directory | Out-Null
$row=@(Import-Csv (Join-Path $directory 'queries.csv'))[0]
if([double]$row.initial_completeAt15 -ne 0 -or [double]$row.terminal_completeAt15 -ne 1 -or
    [math]::Abs([double]$row.initial_recallAt15-1.0/$items.Count) -gt 0.000001 -or [double]$row.delta_completeAt15 -ne 1){throw 'Paired gold coverage calculation failed.'}
$trace.status='FAILED'; $trace.Remove('selectedEvidence'); Write-RerankerJson $path $trace
& (Join-Path $PSScriptRoot 'summarize-adaptive-comparison.ps1') -ReportDirectory $directory | Out-Null
$row=@(Import-Csv (Join-Path $directory 'queries.csv'))[0]
if([double]$row.selected_recallAt15 -ne 0 -or [double]$row.terminal_recallAt15 -ne 1){throw 'Failed terminal candidates must not count as published evidence.'}
$trace.status='SUCCESS';$trace.selectedEvidence=@(@{id='unknown';sourceId='unknown'})
Write-RerankerJson $path $trace
$rejected=$false
try{& (Join-Path $PSScriptRoot 'summarize-adaptive-comparison.ps1') -ReportDirectory $directory | Out-Null}catch{$rejected=$true}
if(!$rejected){throw 'Unknown selected evidence was accepted.'}
$trace.selectedEvidence=$items; $trace.lastPool=@($items[0]);Write-RerankerJson $path $trace
$rejected=$false
try{& (Join-Path $PSScriptRoot 'summarize-adaptive-comparison.ps1') -ReportDirectory $directory | Out-Null}catch{$rejected=$true}
if(!$rejected){throw 'Mismatched terminal pool was accepted.'}
Write-Host 'PASS: paired coverage, failure zero scoring, unknown selection rejection, shared-prefix/terminal identity.'
