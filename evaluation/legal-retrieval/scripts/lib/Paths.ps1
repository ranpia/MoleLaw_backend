$EvaluationRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$EvaluationLayout = Get-Content -Raw -Encoding UTF8 (Join-Path $EvaluationRoot 'layout.json') | ConvertFrom-Json
$DefaultDataRoot = Join-Path $EvaluationRoot $EvaluationLayout.defaults.data
$DefaultReviewRoot = Join-Path $EvaluationRoot $EvaluationLayout.defaults.review
$DefaultReportsRoot = Join-Path $EvaluationRoot $EvaluationLayout.defaults.reports
$DataRoot = $DefaultDataRoot
$ReviewRoot = $DefaultReviewRoot
$ReportsRoot = $DefaultReportsRoot
$ArtifactsRoot = Join-Path $EvaluationRoot 'artifacts'
$ResultsRoot = Join-Path $ArtifactsRoot 'results'

function Get-ExperimentPath([string]$Kind,[string]$Name,[string]$Root,[string]$DefaultRoot) {
    if (!$Name) { return $Root }
    if ([IO.Path]::IsPathRooted($Name)) { return $Name }
    if ($Root -ne $DefaultRoot) { return Join-Path $Root $Name }
    $parts=$Name.Replace('\','/').Split('/',2)
    if ($Kind -eq 'reports' -and $Name -eq 'final-summary.md') { return Join-Path $EvaluationRoot 'experiments/summary.md' }
    $route=$EvaluationLayout.experiments.PSObject.Properties[$parts[0]]
    if ($route) {
        $target=Join-Path $EvaluationRoot ('experiments/'+$route.Value+'/'+$Kind)
        if ($parts.Count -gt 1) { $target=Join-Path $target $parts[1] }
        return $target
    }
    return Join-Path $Root $Name
}
function Get-DataPath([string]$Name) { Get-ExperimentPath 'data' $Name $DataRoot $DefaultDataRoot }
function Get-ReviewPath([string]$Name) { Get-ExperimentPath 'review' $Name $ReviewRoot $DefaultReviewRoot }
function Get-ReportPath([string]$Name) { Get-ExperimentPath 'reports' $Name $ReportsRoot $DefaultReportsRoot }

function Resolve-EvaluationPath([string]$Name) {
    if ($Name -match '^snapshots[/\\]') { return Join-Path $ArtifactsRoot $Name }
    if ($Name -in @('questions.json','corpus.jsonl','corpus-manifest.json')) { return Get-DataPath $Name }
    return Get-ReviewPath $Name
}
