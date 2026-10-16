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
    Write-Host 'PASS: valid rewrite, changed original, duplicate, empty, incomplete, incompatible identity.'
} finally { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path } }
