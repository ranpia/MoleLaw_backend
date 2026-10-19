$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/QueryRewrites.ps1')
$path = Join-Path ([IO.Path]::GetTempPath()) ('molelaw-rewrite-test-'+[guid]::NewGuid().ToString('N')+'.json')
$data = [pscustomobject]@{questions=@([pscustomobject]@{id='q1';text='original'})}
function Save-Fixture($Fixture) { [IO.File]::WriteAllText($path,($Fixture | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false)) }
try {
    $valid = @{complete=$true;sourceDatasetSha256='source';sourceComparisonIdentitySha256='identity';questions=@(@{id='q1';originalText='original';rewrittenText='rewrite'})}
    Save-Fixture $valid
    $loaded = Read-QueryRewrites $path $data 'source' 'identity'
    if ($loaded.q1.rewrittenText -ne 'rewrite') { throw 'Valid rewrite not loaded.' }
    $variant = Read-QueryRewrites $path $data 'different-target-dataset' 'identity' 'source'
    if ($variant.q1.rewrittenText -ne 'rewrite') { throw 'Cross-variant rewrite not loaded.' }
    foreach ($case in @('implicit-target','wrong-source','changed-identity','changed-text')) {
        $targetData = $data | ConvertTo-Json -Depth 5 | ConvertFrom-Json
        $identity = 'identity'; $source = 'source'
        switch ($case) {
            'implicit-target' { $source = '' }
            'wrong-source' { $source = 'incorrect-source' }
            'changed-identity' { $identity = 'different-canonical-identity' }
            'changed-text' { $targetData.questions[0].text = 'different original' }
        }
        $rejected = $false
        try { $null = Read-QueryRewrites $path $targetData 'different-target-dataset' $identity $source } catch { $rejected = $true }
        if (!$rejected) { throw "Unsafe cross-variant rewrite accepted: $case" }
    }
    foreach ($mutation in @('original','duplicate','empty','incomplete','identity')) {
        $fixture = $valid | ConvertTo-Json -Depth 5 | ConvertFrom-Json
        switch ($mutation) {
            'original' { $fixture.questions[0].originalText = 'changed' }
            'duplicate' { $fixture.questions += $fixture.questions[0] }
            'empty' { $fixture.questions[0].rewrittenText = ' ' }
            'incomplete' { $fixture.complete = $false }
            'identity' { $fixture.sourceComparisonIdentitySha256 = 'changed' }
        }
        Save-Fixture $fixture
        $rejected = $false
        try { $null = Read-QueryRewrites $path $data 'source' 'identity' } catch { $rejected = $true }
        if (!$rejected) { throw "Invalid rewrite accepted: $mutation" }
    }
    Write-Host 'PASS: valid rewrite, explicit cross-variant source, source hash/identity/text guards, duplicate, empty and incomplete rewrites.'
} finally { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path } }
