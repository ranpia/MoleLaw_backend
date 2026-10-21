$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
Set-Location (Split-Path (Split-Path $EvaluationRoot -Parent) -Parent)
$logs=Join-Path $ArtifactsRoot 'logs';$null=New-Item -ItemType Directory -Force $logs
Start-Transcript -Path (Join-Path $logs ('recall50-bge-qwen-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log'))
try{
 Write-Host 'Sequential queue: BGE-M3 then Qwen3-Embedding-0.6B. 300/500/700 chars; original/rewritten/legal_transformed; no threshold gating. CPU 6 / RAM 8GB.' -ForegroundColor Cyan
 foreach($model in @('bge-m3','qwen3-0.6b')){
  & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-model-recall50.ps1') -Model $model
  if($LASTEXITCODE -ne 0){throw "$model failed. Queue stopped; completed vector batches retained."}
 }
 Write-Host 'BOTH MODELS COMPLETE. See experiments/query-strategies/candidate-coverage-v1/reports/{bge-m3,qwen3-0.6b}.' -ForegroundColor Green
}finally{Stop-Transcript}
