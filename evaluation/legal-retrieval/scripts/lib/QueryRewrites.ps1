function Read-QueryRewrites([string]$Path, $Data, [string]$DatasetSha256, [string]$ComparisonIdentity) {
    $rewrites = Get-Content -Raw -Encoding UTF8 $Path | ConvertFrom-Json
    if (!$rewrites.complete -or $rewrites.sourceDatasetSha256 -ne $DatasetSha256 -or $rewrites.sourceComparisonIdentitySha256 -ne $ComparisonIdentity) { throw 'Incomplete or incompatible query rewrites.' }
    if (@($rewrites.questions).Count -ne @($Data.questions).Count) { throw 'Rewrite count mismatch.' }
    $byId = @{}
    foreach ($row in $rewrites.questions) {
        if ($byId.ContainsKey($row.id) -or [string]::IsNullOrWhiteSpace($row.rewrittenText) -or $row.rewrittenText.Length -gt 600) { throw 'Invalid or duplicate rewrite.' }
        $byId[$row.id] = $row
    }
    foreach ($q in $Data.questions) {
        if (!$byId.ContainsKey($q.id) -or $byId[$q.id].originalText -cne $q.text) { throw 'Rewrite original question mismatch.' }
    }
    return $byId
}
