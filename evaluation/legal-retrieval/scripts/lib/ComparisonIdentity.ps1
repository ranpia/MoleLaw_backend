function Get-ComparisonIdentity([string]$Directory) {
    $data = Get-Content -Raw -Encoding UTF8 (Join-Path $Directory 'questions.json') | ConvertFrom-Json
    $corpusPath = Join-Path $Directory $data.corpus.path
    if ((Get-FileHash $corpusPath).Hash -ne $data.corpus.sha256) { throw 'Identity corpus hash mismatch.' }
    $chunks = @(foreach ($line in [IO.File]::ReadLines($corpusPath)) { $line | ConvertFrom-Json })
    $units = @(foreach ($group in ($chunks | Group-Object unitId | Sort-Object Name)) {
        $parts = @($group.Group | Sort-Object startChar)
        $builder = [Text.StringBuilder]::new()
        foreach ($part in $parts) {
            $overlap = $builder.Length - $part.startChar
            if ($overlap -lt 0 -or $overlap -gt $part.text.Length) { throw 'Identity source coverage gap.' }
            if ($overlap -gt 0 -and $builder.ToString($part.startChar,$overlap) -cne $part.text.Substring(0,$overlap)) { throw 'Identity source overlap mismatch.' }
            $null = $builder.Append($part.text.Substring($overlap))
        }
        [ordered]@{ id = $group.Name; title = $parts[0].title; text = $builder.ToString()
            lawId = $parts[0].lawId; sourceUrl = $parts[0].sourceUrl; sourceSha256 = $parts[0].sourceSha256 }
    })
    $questions = @(foreach ($q in ($data.questions | Sort-Object id)) {
        [ordered]@{ id = $q.id; text = $q.text; split = $q.split; answerability = $q.answerability
            relevantUnitIds = @($q.relevantUnitIds | Sort-Object) }
    })
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $json = [ordered]@{ version = 1; units = $units; questions = $questions } | ConvertTo-Json -Depth 12 -Compress
        ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)))).Replace('-','').ToLowerInvariant()
    } finally { $sha.Dispose() }
}
