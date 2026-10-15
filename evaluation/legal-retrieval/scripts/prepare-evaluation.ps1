[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
function Read-Json([string]$Name) { Get-Content -Raw -Encoding UTF8 (Resolve-EvaluationPath $Name) | ConvertFrom-Json }
function Set-Field($Object, [string]$Name, $Value) { $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force }
function Write-Json([string]$Name, $Value) {
    [IO.File]::WriteAllText((Resolve-EvaluationPath $Name), ($Value | ConvertTo-Json -Depth 22) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}
function Get-TextHash([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
$data = Read-Json 'questions.json'
$original = @($data.questions | Where-Object answerability -ne 'out_of_corpus')
if ($original.Count -ne 100) { throw 'Expected 100 original questions.' }
$base = Read-Json 'snapshots/manifest.json'
$supplemental = Read-Json 'snapshots/supplemental/manifest.json'
$sources = @($base) + @($supplemental)
$special = (Read-Json 'special-evidence.json').evidence
$supplementalMap = (Read-Json 'supplemental-review.json').mappings
$evidenceFile = Read-Json 'verified-evidence.json'
$evidence = @{}; $units = @{}; $sourceRecords = @()
foreach ($item in $evidenceFile.evidence) { $evidence[$item.id] = $item }
foreach ($source in $sources) {
    $raw = $(if ($source.id -in $base.id) { 'snapshots/' } else { 'snapshots/supplemental/' }) + $source.id + '.html'
    if ((Get-FileHash (Join-Path $ArtifactsRoot $raw)).Hash -ne $source.sha256) { throw "Source hash mismatch: $($source.id)" }
    if ([datetime]$source.effectiveDate -gt [datetime]$data.referenceDate) { throw "Future version: $($source.id)" }
    $headerHtml = Get-Content -Raw -Encoding UTF8 (Join-Path $ArtifactsRoot $raw)
    $dateMatch = [regex]::Match($headerHtml, '\[\uC2DC\uD589\s+(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.')
    if (!$dateMatch.Success) { throw "No effective-date header: $($source.id)" }
    $headerDate = '{0}-{1:D2}-{2:D2}' -f $dateMatch.Groups[1].Value,[int]$dateMatch.Groups[2].Value,[int]$dateMatch.Groups[3].Value
    if ($headerDate -ne $source.effectiveDate) { throw "Effective-date mismatch: $($source.id)" }
    $sourceRecords += [ordered]@{ id = $source.id; name = $source.name; lawId = $source.lawId
        mst = $source.mst; effectiveDate = $source.effectiveDate; sourceUrl = $source.sourceUrl
        sha256 = $source.sha256; collectedAt = $source.collectedAt }
    foreach ($article in $source.articles) {
        if ($article.text -match '^\uC81C\d+\uC870(?:\uC758\d+)?\s*\uC0AD\uC81C') { continue }
        $id = "$($source.id):$($source.mst):$($article.articleCode)"
        $units[$id] = [ordered]@{ id = $id; lawId = $source.id; title = "$($source.name) $($article.heading)"
            text = $article.text; sourceUrl = $source.sourceUrl; sourceSha256 = $source.sha256 }
    }
}
foreach ($item in $evidenceFile.evidence | Where-Object provisionType -eq 'addendum') {
    $units[$item.id] = [ordered]@{ id = $item.id; lawId = $item.lawId; title = "$($item.lawId) addendum 15666"
        text = $item.text; sourceUrl = $item.sourceUrl; sourceSha256 = $item.sourceSha256 }
}
foreach ($item in $special) {
    $units[$item.id] = [ordered]@{ id = $item.id; lawId = $item.lawId; title = $item.heading
        text = $item.text; sourceUrl = $item.sourceUrl; sourceSha256 = $item.sourceSha256 }
    $evidence[$item.id] = $item
}
foreach ($mapping in $supplementalMap) {
    $question = @($original | Where-Object id -eq $mapping.questionId)[0]
    $source = @($supplemental | Where-Object id -eq $mapping.lawId)[0]
    foreach ($number in $mapping.articleNumbers) {
        $parts = $number -split '-'; $sub = 0
        if ($parts.Count -gt 1) { $sub = [int]$parts[1] }
        $code = '{0:D4}{1:D2}' -f [int]$parts[0], $sub
        $id = "$($source.id):$($source.mst):$code"
        if (!$units.ContainsKey($id)) { throw "Missing supplemental article: $id" }
        if ($id -notin $question.relevantArticles.evidenceId) {
            $article = @($source.articles | Where-Object articleCode -eq $code)[0]
            $question.relevantArticles += [pscustomobject]@{ lawId = $source.id; officialLawId = $source.lawId
                lawName = $source.name; articleNumber = $number; articleCode = $code; heading = $article.heading
                mst = $source.mst; effectiveDate = $source.effectiveDate; sourceUrl = $source.sourceUrl; evidenceId = $id }
            $evidence[$id] = [ordered]@{ id = $id; lawId = $source.id; mst = $source.mst; articleCode = $code
                heading = $article.heading; text = $article.text; sourceUrl = $source.sourceUrl; sourceSha256 = $source.sha256 }
        }
    }
}
$chunkVersion = 'char500-overlap64-article-v1'
$chunks = [Collections.Generic.List[object]]::new()
$chunkMap = @{}
foreach ($unit in ($units.Values | Sort-Object { $_.id })) {
    $text = [regex]::Replace($unit.text, '\s+', ' ').Trim()
    if (!$text) { throw "Empty unit: $($unit.id)" }
    $start = 0; $index = 0; $chunkIds = @()
    while ($start -lt $text.Length) {
        $length = [math]::Min(500, $text.Length - $start)
        if ($start + $length -lt $text.Length) {
            $space = $text.LastIndexOf(' ', $start + $length - 1, $length)
            if ($space -gt $start + 300) { $length = $space - $start }
        }
        $body = $text.Substring($start, $length)
        $hash = Get-TextHash $body
        $id = "$($unit.id):$chunkVersion`:$index`:$($hash.Substring(0,12))"
        $chunks.Add([ordered]@{ id = $id; unitId = $unit.id; lawId = $unit.lawId; title = $unit.title
            text = $body; startChar = $start; endChar = $start + $length; textSha256 = $hash
            sourceUrl = $unit.sourceUrl; sourceSha256 = $unit.sourceSha256 })
        $chunkIds += $id; $index++
        if ($start + $length -eq $text.Length) { break }
        $start += $length - 64
    }
    $chunkMap[$unit.id] = $chunkIds
}
foreach ($question in $original) {
    $goldUnits = @($question.relevantArticles.evidenceId)
    $goldUnits += @($question.supportingEvidenceIds | Where-Object { $_ })
    $goldUnits += @($special | Where-Object { $question.id -in $_.questionIds } | ForEach-Object id)
    $goldUnits = @($goldUnits | Select-Object -Unique)
    $groups = @(); $ids = @()
    foreach ($unitId in $goldUnits) {
        if (!$chunkMap.ContainsKey($unitId)) { throw "Missing gold unit: $($question.id) $unitId" }
        $groups += [ordered]@{ unitId = $unitId; chunkIds = @($chunkMap[$unitId]) }
        $ids += @($chunkMap[$unitId])
    }
    Set-Field $question 'relevantUnitIds' $goldUnits
    Set-Field $question 'relevantChunkGroups' $groups
    Set-Field $question 'relevantChunkIds' @($ids | Select-Object -Unique)
    Set-Field $question 'relevanceGranularity' 'article_or_annex_any_child_chunk_matches_unit'
    $verifiedSources = @($supplementalMap | Where-Object questionId -eq $question.id | ForEach-Object {
        $sourceId = $_.lawId
        $source = @($supplemental | Where-Object id -eq $sourceId)[0]
        [ordered]@{ id = $source.id; name = $source.name; mst = $source.mst; sourceUrl = $source.sourceUrl }
    })
    $verifiedSources += @($special | Where-Object { $question.id -in $_.questionIds } | ForEach-Object {
        [ordered]@{ id = $_.id; name = $_.heading; mst = $_.mst; sourceUrl = $_.sourceUrl }
    })
    Set-Field $question 'supplementalSourcesVerified' $verifiedSources
    if (!$question.PSObject.Properties['initialReviewNotes']) { Set-Field $question 'initialReviewNotes' $question.reviewNotes }
    Set-Field $question 'reviewNotes' $(if ($verifiedSources.Count -gt 0) {
        'Official main and supplemental provisions checked; actual relevant chunk groups linked. Initial review notes are retained separately.'
    } else { $question.initialReviewNotes })
    Set-Field $question 'supplementalSourcesNeeded' @()
    Set-Field $question 'reviewStatus' 'ready_for_frozen_corpus_retrieval'
    Set-Field $question 'answerability' 'in_corpus'
    Set-Field $question 'completeAnswerGrounded' $null
    Set-Field $question 'labelScope' 'frozen_corpus_retrieval_not_individual_case_legal_adjudication'
}
# Keep shared answer units together; deterministic subset sum preserves 30/70 positive split.
$components = [Collections.Generic.List[object]]::new()
foreach ($q in $original) {
    $group = [Collections.Generic.List[object]]::new(); $group.Add($q)
    for ($i = $components.Count - 1; $i -ge 0; $i--) {
        $otherUnits = @($components[$i] | ForEach-Object relevantUnitIds)
        if (@($q.relevantUnitIds | Where-Object { $_ -in $otherUnits }).Count -gt 0) {
            foreach ($item in $components[$i]) { $group.Add($item) }
            $components.RemoveAt($i)
        }
    }
    $components.Add($group)
}
$choices = @{ 0 = @() }
for ($i = 0; $i -lt $components.Count; $i++) {
    foreach ($sum in @($choices.Keys | Sort-Object -Descending)) {
        $next = [int]$sum + $components[$i].Count
        if ($next -le 30 -and !$choices.ContainsKey($next)) { $choices[$next] = @($choices[$sum]) + @($i) }
    }
}
if (!$choices.ContainsKey(30)) { throw 'Cannot create grouped 30/70 split.' }
for ($i = 0; $i -lt $components.Count; $i++) {
    foreach ($q in $components[$i]) {
        if (!$q.PSObject.Properties['originalSplit']) { Set-Field $q 'originalSplit' $q.split }
        Set-Field $q 'split' $(if ($i -in $choices[30]) { 'development' } else { 'held_out' })
    }
}
$negatives = (Read-Json 'no-answer-questions.json').questions
foreach ($q in $negatives) { Set-Field $q 'relevantUnitIds' @(); Set-Field $q 'relevantChunkGroups' @() }
Set-Field $data 'questions' (@($original) + @($negatives))
Set-Field $data 'schemaVersion' 3
Set-Field $data 'id' 'legal-retrieval-ready-v1'
Set-Field $data 'status' 'ready_for_frozen_corpus_retrieval'
Set-Field $data 'evaluationReady' $true
Set-Field $data 'evaluationScope' 'dense_retrieval_of_statute_articles_and_selected_required_annexes_not_answer_generation'
Set-Field $data 'readinessRequirements' @()
Set-Field $data 'corpus' ([ordered]@{ path = 'corpus.jsonl'; manifestPath = 'corpus-manifest.json'
    chunkingVersion = $chunkVersion; sourceCount = $sources.Count; noticeCount = 1; extraAnnexCount = 2
    scope = '33 statute/decree/rule main bodies plus reviewed notice and required addendum/annexes'
    excluded = @('deleted main articles','other historical addenda','unneeded annexes/forms','case law')
    relevanceGranularity = 'article_or_annex'; metric = 'Recall@K over distinct source units, MRR over unit ranking'
    maxChars = 500; overlapChars = 64; unitCount = $units.Count; chunkCount = $chunks.Count })
Set-Field $data 'splitReview' ([ordered]@{ method = 'shared_gold_unit_connected_components_then_deterministic_subset_sum'
    positiveDevelopment = 30; positiveHeldOut = 70; negativeDevelopment = 5; negativeHeldOut = 5
    reason = 'Prevent shared gold article/annex across development and held-out.' })
Set-Field $data.verification 'needsSupplementalSources' 0
Set-Field $data.verification 'supplementalStatutesVerified' $supplemental.Count
Set-Field $data.verification 'specialEvidenceVerified' 3
Set-Field $data.verification 'limitations' @('Retrieval readiness is not an answer-generation or legal-advice quality certificate.',
    'Labels identify reviewed relevant units within this frozen corpus, not every legally relevant source.',
    'No-answer labels are relative to the explicitly frozen corpus scope.')
Set-Field $data.verification 'duplicateScopeCandidates' @()
$corpusPath = Join-Path $DataRoot 'corpus.jsonl'
$writer = [IO.StreamWriter]::new($corpusPath, $false, [Text.UTF8Encoding]::new($false))
try { foreach ($chunk in $chunks) { $writer.WriteLine(($chunk | ConvertTo-Json -Depth 5 -Compress)) } } finally { $writer.Dispose() }
$corpusHash = (Get-FileHash $corpusPath).Hash
$data.corpus['sha256'] = $corpusHash
Write-Json 'corpus-manifest.json' ([ordered]@{ version = 1; corpusSha256 = $corpusHash
    chunking = $data.corpus; sources = $sourceRecords; specialSources = $special
    unitCount = $units.Count; chunkCount = $chunks.Count })
Write-Json 'verified-evidence.json' ([ordered]@{ schemaVersion = 2; source = 'National Law Information Center'
    hashTarget = 'saved_utf8_html_or_original_pdf'; evidence = @($evidence.Values | Sort-Object { $_.id }) })
Write-Json 'questions.json' $data
Write-Host "Prepared $($chunks.Count) chunks / $($units.Count) units / $($data.questions.Count) questions."
