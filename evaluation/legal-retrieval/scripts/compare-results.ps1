[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$records = @()
foreach ($file in Get-ChildItem -LiteralPath $ReportsRoot -Recurse -Filter summary.json) {
    $r = Get-Content -Raw -Encoding UTF8 $file.FullName | ConvertFrom-Json
    if (!$r.chunkingVersion) { continue }
    $dev = @($r.summary | Where-Object split -eq 'development')[0]
    $held = @($r.summary | Where-Object split -eq 'held_out')[0]
    $size = [int]([regex]::Match($r.chunkingVersion,'char(\d+)').Groups[1].Value)
    $records += [pscustomobject]@{ model = $r.model.model; dimensions = $r.model.dimensions; maxChars = $size
        overlapChars = 64; devRecallAt5 = $dev.recallAt5; devMrr = $dev.mrr
        heldOutTop1HitRate = $held.top1HitRate; heldOutRecallAt5 = $held.recallAt5
        heldOutRecallAt10 = $held.recallAt10; heldOutMrr = $held.mrr
        heldOutNoAnswerFalseAcceptanceRate = $held.noAnswerFalseAcceptanceRate
        heldOutMeanQueryEmbeddingMs = $held.meanQueryEmbeddingMs; corpusSha256 = $r.corpusSha256 }
}
$records = @($records | Sort-Object model,maxChars)
$records | Export-Csv (Join-Path $ReportsRoot 'comparison.csv') -NoTypeInformation -Encoding UTF8
$lines = @('# Model and chunk comparison', '', 'Character-based chunks with 64-character overlap; identical source units, questions and splits.', '',
    '| Model | Dimensions | Characters | Dev Recall@5 | Dev MRR | Held-out top-1 | Held-out Recall@5 | Held-out MRR |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($r in $records) {
    $lines += '| '+$r.model+' | '+$r.dimensions+' | '+$r.maxChars+' | '+([math]::Round(100*$r.devRecallAt5,2))+'% | '+([math]::Round($r.devMrr,4))+' | '+([math]::Round(100*$r.heldOutTop1HitRate,2))+'% | '+([math]::Round(100*$r.heldOutRecallAt5,2))+'% | '+([math]::Round($r.heldOutMrr,4))+' |'
}
$lines += @('', 'Use development results to choose chunk/model settings. Held-out rows are descriptive final checks, not a repeated tuning objective.', '',
    'Recall ranks distinct source articles/annexes by maximum child-chunk score. No-answer rates use only five held-out negative questions and score-based thresholds, not generated answers.', '',
    'Raw similarity thresholds are fitted independently per configuration; do not compare absolute cosine scores across models.', '',
    'Query latency is one warm request per question, not a repeated load test. Character limits are not tokenizer-exact token limits.')
[IO.File]::WriteAllText((Join-Path $ReportsRoot 'comparison.md'),($lines -join [Environment]::NewLine)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$records | Format-Table model,maxChars,devRecallAt5,heldOutRecallAt5,heldOutMrr -AutoSize
