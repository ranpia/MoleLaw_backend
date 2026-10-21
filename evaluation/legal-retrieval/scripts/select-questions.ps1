[CmdletBinding()]
param([uint32]$Seed = 20261015)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$existingPath = Get-DataPath 'questions.json'
if (Test-Path -LiteralPath $existingPath) {
    $existing = Get-Content -Raw -Encoding UTF8 -LiteralPath $existingPath | ConvertFrom-Json
    if ($existing.status -ne 'pending_corpus_and_label_review') {
        throw 'Refusing to overwrite reviewed questions. Create a new dataset version instead.'
    }
}
$bankPath = Get-ReviewPath 'question-bank.json'
$bank = Get-Content -LiteralPath $bankPath -Raw -Encoding UTF8 | ConvertFrom-Json
$script:randomState = [uint64]$Seed
function Get-Shuffled($Items) {
    $copy = @($Items)
    for ($i = $copy.Count - 1; $i -gt 0; $i--) {
        $script:randomState = ([uint64]1664525 * $script:randomState + [uint64]1013904223) % [uint64]4294967296
        $j = [int]($script:randomState % [uint64]($i + 1))
        $temporary = $copy[$i]; $copy[$i] = $copy[$j]; $copy[$j] = $temporary
    }
    $copy
}
$laws = @(); $questions = @(); $lawIndex = 0
foreach ($law in $bank.laws) {
    if (@($law.candidates).Count -lt 5) { throw "Not enough candidates: $($law.id)" }
    $laws += [ordered]@{ id = $law.id; name = $law.name; domain = $law.domain
        sourceUrl = $law.sourceUrl; snapshot = $law.snapshot }
    $selected = @(Get-Shuffled $law.candidates | Select-Object -First 5)
    # Alternate 1/2 development questions per law: 30 development, 70 held-out.
    $devCount = 1 + ($lawIndex % 2)
    for ($i = 0; $i -lt $selected.Count; $i++) {
        $candidate = $selected[$i]
        $questions += [ordered]@{ id = $candidate.id; text = $candidate.text
            style = $(if ($candidate.id -match '-4$') { 'contrast' } else { 'scenario' })
            sourceLawId = $law.id; expectedLawIdsDraft = @($law.id)
            split = $(if ($i -lt $devCount) { 'development' } else { 'held_out' })
            answerability = 'unverified'; relevantChunkIds = $null; relevantArticles = $null
            reviewStatus = 'pending'; reviewNotes = $null }
    }
    $lawIndex++
}
if ($laws.Count -ne 20 -or $questions.Count -ne 100) { throw 'Expected 20 laws / 100 questions.' }
if (@($questions.id | Select-Object -Unique).Count -ne 100) { throw 'Duplicate question IDs.' }
$dataset = [ordered]@{
    schemaVersion = 1; id = 'legal-retrieval-draft-v1'; status = 'pending_corpus_and_label_review'
    referenceDate = '2026-10-15'; referenceDateMeaning = 'Target corpus date, not a verified effective date.'
    sampling = [ordered]@{ seed = $Seed; algorithm = 'LCG32(1664525,1013904223,2^32)+Fisher-Yates-descending'
        lawSelection = 'curated_20_laws_not_random'; questionSelection = '5_of_6_per_law_without_replacement'
        bankSha256 = (Get-FileHash -LiteralPath $bankPath -Algorithm SHA256).Hash
        developmentCount = 30; heldOutCount = 70 }
    evaluationReady = $false
    readinessRequirements = @('Freeze official law snapshots and chunking version.',
        'Review question scope and all relevant article/chunk IDs, including cross-law matches.',
        'Check for duplicated or overly similar development/held-out questions.',
        'Add reviewed no-answer questions after the corpus is frozen.')
    laws = $laws; questions = $questions
}
$outputPath = Get-DataPath 'questions.json'
[IO.File]::WriteAllText($outputPath, ($dataset | ConvertTo-Json -Depth 12) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Host "Saved $($questions.Count) questions / $($laws.Count) laws: $outputPath"
