$EvaluationRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$DataRoot = Join-Path $EvaluationRoot 'data'
$ReviewRoot = Join-Path $EvaluationRoot 'review'
$ReportsRoot = Join-Path $EvaluationRoot 'reports'
$ArtifactsRoot = Join-Path $EvaluationRoot 'artifacts'
$ResultsRoot = Join-Path $ArtifactsRoot 'results'

function Resolve-EvaluationPath([string]$Name) {
    if ($Name -match '^snapshots[/\\]') { return Join-Path $ArtifactsRoot $Name }
    if ($Name -in @('questions.json','corpus.jsonl','corpus-manifest.json')) { return Join-Path $DataRoot $Name }
    return Join-Path $ReviewRoot $Name
}
