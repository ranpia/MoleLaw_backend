[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$questionsPath = Get-DataPath 'questions.json'
$dataset = Get-Content -Raw -Encoding UTF8 $questionsPath | ConvertFrom-Json
if ($dataset.evaluationReady -and $dataset.schemaVersion -ge 3) {
    throw 'Refusing to downgrade a ready dataset. Create a new dataset version for statute re-review.'
}
$mapPath = Get-ReviewPath 'article-review-map.json'
$map = Get-Content -Raw -Encoding UTF8 $mapPath | ConvertFrom-Json
$decisions = Get-Content -Raw -Encoding UTF8 (Get-ReviewPath 'review-decisions.json') | ConvertFrom-Json
$snapshots = Get-Content -Raw -Encoding UTF8 (Join-Path $ArtifactsRoot 'snapshots/manifest.json') | ConvertFrom-Json
function Set-Field($Object, [string]$Name, $Value) {
    $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
}
function Write-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 20) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}
$evidence = @{}
foreach ($law in $dataset.laws) {
    $snapshot = @($snapshots | Where-Object id -eq $law.id)
    if ($snapshot.Count -ne 1) { throw "Missing snapshot: $($law.id)" }
    $source = $snapshot[0]
    if ([datetime]$source.effectiveDate -gt [datetime]$dataset.referenceDate) { throw "Future version: $($law.id)" }
    $rawPath = Join-Path $ArtifactsRoot ("snapshots/$($law.id).html")
    if ((Get-FileHash $rawPath -Algorithm SHA256).Hash -ne $source.sha256) { throw "Source hash mismatch: $($law.id)" }
    Set-Field $law 'name' $source.name
    Set-Field $law 'snapshot' ([ordered]@{ lawId = $source.lawId; mst = $source.mst
        effectiveDate = $source.effectiveDate; collectedAt = $source.collectedAt; sha256 = $source.sha256
        hashTarget = 'saved_utf8_html'; sourceUrl = $source.sourceUrl; articleCount = $source.articleCount
        extractionVersion = $source.extractionVersion })
}
foreach ($question in $dataset.questions) {
    $mapping = @($map.mappings | Where-Object questionId -eq $question.id)
    if ($mapping.Count -ne 1) { throw "Missing or duplicate mapping: $($question.id)" }
    $mapping = $mapping[0]
    $source = @($snapshots | Where-Object id -eq $mapping.lawId)[0]
    $revision = $decisions.revisions.PSObject.Properties[$question.id]
    if ($revision) {
        if (!$question.PSObject.Properties['originalText']) { Set-Field $question 'originalText' $question.text }
        Set-Field $question 'text' $revision.Value.text
        Set-Field $question 'revisionReason' $revision.Value.reason
        $mapping.articleNumbers = @($revision.Value.articles)
    }
    $labels = @()
    foreach ($number in $mapping.articleNumbers) {
        $parts = $number -split '-'; $sub = 0
        if ($parts.Count -gt 1) { $sub = [int]$parts[1] }
        $code = '{0:D4}{1:D2}' -f [int]$parts[0], $sub
        $articles = @($source.articles | Where-Object articleCode -eq $code)
        if ($articles.Count -ne 1) { throw "Missing or duplicate article: $($question.id) $number" }
        $article = $articles[0]
        $expectedHeading = '\uC81C' + $parts[0] + '\uC870'
        if ($sub -gt 0) { $expectedHeading += '\uC758' + $sub }
        if ($article.heading -notmatch ('^' + $expectedHeading + '(?![0-9])')) { throw "Heading mismatch: $($question.id) $number" }
        if ($article.text -match ('^' + $expectedHeading + '\s*\uC0AD\uC81C')) { throw "Deleted article: $($question.id) $number" }
        $key = "$($source.id):$($source.mst):$code"
        $label = [ordered]@{ lawId = $source.id; officialLawId = $source.lawId; lawName = $source.name
            articleNumber = $number; articleCode = $code; heading = $article.heading
            mst = $source.mst; effectiveDate = $source.effectiveDate
            sourceUrl = $source.sourceUrl; evidenceId = $key }
        $labels += $label
        $evidence[$key] = [ordered]@{ id = $key; lawId = $source.id; mst = $source.mst
            articleCode = $code; heading = $article.heading; text = $article.text
            sourceUrl = $source.sourceUrl; sourceSha256 = $source.sha256 }
    }
    Set-Field $question 'relevantArticles' @($labels)
    Set-Field $question 'relevantChunkIds' $null
    Set-Field $question 'answerability' 'statute_level_grounded'
    Set-Field $question 'reviewStatus' 'statute_grounded'
    Set-Field $question 'reviewedAt' $decisions.reviewDate
    Set-Field $question 'labelScope' 'primary_statute_articles_not_exhaustive_cross_law_gold'
    $note = $decisions.notes.PSObject.Properties[$question.id]
    Set-Field $question 'reviewNotes' $(if ($note) { $note.Value } else { $decisions.defaultNote })
    $extra = $decisions.supplemental.PSObject.Properties[$question.id]
    Set-Field $question 'supplementalSourcesNeeded' $(if ($extra) { @($extra.Value) } else { @() })
    Set-Field $question 'completeAnswerGrounded' $(if ($extra) { $false } else { $null })
    if ($extra) { Set-Field $question 'reviewStatus' 'statute_grounded_needs_supplemental' }
}
# Preserve the applicable minimum-wage transitional provision from the same official snapshot.
$minimum = @($snapshots | Where-Object id -eq 'minimum')[0]
$minimumHtml = Get-Content -Raw -Encoding UTF8 (Join-Path $ArtifactsRoot 'snapshots/minimum.html')
$annex = [regex]::Match($minimumHtml, '(?is)<a name="J6445175".*?(?=<a name="J6445177")')
if (!$annex.Success -or $annex.Value -notmatch '2024') { throw 'Missing minimum-wage transitional provision.' }
$annexText = [Net.WebUtility]::HtmlDecode([regex]::Replace($annex.Value, '<[^>]+>', ' '))
$annexText = [regex]::Replace($annexText, '\s+', ' ').Trim()
$annexKey = "minimum:$($minimum.mst):addendum-15666"
$evidence[$annexKey] = [ordered]@{ id = $annexKey; lawId = 'minimum'; mst = $minimum.mst
    provisionType = 'addendum'; amendingLawNumber = '15666'; promulgationDate = '2018-06-12'
    text = $annexText; sourceUrl = $minimum.sourceUrl; sourceSha256 = $minimum.sha256 }
$minimumQuestion = @($dataset.questions | Where-Object id -eq 'minimum-2')[0]
Set-Field $minimumQuestion 'supportingEvidenceIds' @($annexKey)
Set-Field $dataset 'schemaVersion' 2
Set-Field $dataset 'id' 'legal-retrieval-statute-reviewed-v1'
Set-Field $dataset 'status' 'statutes_reviewed_pending_chunks_and_supplemental_sources'
Set-Field $dataset 'evaluationReady' $false
Set-Field $dataset 'referenceDateMeaning' 'Official versions resolved during collection; each effective date was checked not to exceed the target date.'
Set-Field $dataset 'verification' ([ordered]@{ reviewer = $decisions.reviewer; method = $decisions.method
    reviewDate = $decisions.reviewDate; officialStatutes = $dataset.laws.Count
    statuteGroundedQuestions = $dataset.questions.Count
    revisedQuestions = @($dataset.questions | Where-Object { $_.PSObject.Properties['originalText'] }).Count
    needsSupplementalSources = @($dataset.questions | Where-Object reviewStatus -eq 'statute_grounded_needs_supplemental').Count
    duplicateScopeCandidates = $decisions.leakageCandidates
    limitations = @('Primary statute retrieval labels; not exhaustive cross-law answer adjudication.',
        'Raw HTML retained; normalized snapshots currently extract main articles only, not all addenda/annex tables.',
        'Supplemental laws/decrees/rules listed as needed have not been collected or verified.',
        'Chunk IDs and final split leakage audit remain pending.') })
Set-Field $dataset 'readinessRequirements' @('Freeze chunking and map article labels to actual chunk IDs.',
    'Collect or exclude supplemental-source questions for full-answer evaluation.',
    'Preserve applicable addenda and annex tables in the indexing pipeline.',
    'Audit cross-law relevance and development/held-out overlap before final scoring.',
    'Add reviewed no-answer questions after the corpus is frozen.')
$map.status = 'official_articles_checked'
Write-Json $mapPath $map
Write-Json (Get-ReviewPath 'verified-evidence.json') ([ordered]@{ schemaVersion = 1
    source = 'National Law Information Center'; hashTarget = 'saved_utf8_html'
    evidence = @($evidence.Values | Sort-Object { $_.id }) })
Write-Json $questionsPath $dataset
$dataset.verification | Format-List
