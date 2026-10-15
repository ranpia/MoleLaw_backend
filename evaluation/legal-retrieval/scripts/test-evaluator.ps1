[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
Add-Type -Path (Join-Path $PSScriptRoot 'lib/DenseIndex.cs')
$directory = Join-Path $ArtifactsRoot ('results/math-check-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Force $directory
$a = New-Object single[] 768; $b = New-Object single[] 768; $c = New-Object single[] 768
$a[0] = 2; $b[1] = 1; $c[0] = -3
$index = [LegalDenseIndex]::new()
$path = Join-Path $directory 'known.bin'
$index.WriteBatch($path, [string[]]@('a','b','c'), [single[][]]@($a,$b,$c))
$index.SetUnit('a','a'); $index.SetUnit('b','b'); $index.SetUnit('c','c')
$hits = $index.Rank($a,3)
if ([math]::Abs($hits[0].Score - 1) -gt 0.000001 -or [math]::Abs($hits[1].Score) -gt 0.000001 -or [math]::Abs($hits[2].Score + 1) -gt 0.000001) {
    throw 'Independent cosine reference values 1/0/-1 do not match.'
}
$copy = [LegalDenseIndex]::new(); $copy.ReadBatch($path)
$copy.SetUnit('a','a'); $copy.SetUnit('b','a'); $copy.SetUnit('c','c')
$hits = $copy.Rank($a,3)
if ($hits.Count -ne 2 -or $hits[0].ChunkId -ne 'a') { throw 'Article ranking did not use the maximum child score.' }
foreach ($invalid in @((New-Object single[] 768),(New-Object single[] 767))) {
    $rejected = $false
    try { $null = $copy.Rank($invalid,1) } catch { $rejected = $true }
    if (!$rejected) { throw 'Invalid query vector was accepted.' }
}
Write-Host 'PASS: cosine 1/0/-1, normalization, cache roundtrip, article collapse, invalid vector rejection.'
$large = [LegalDenseIndex]::new(1024)
$vector1024 = New-Object single[] 1024; $vector1024[0] = 1
$large.Add('bge',[single[]]$vector1024); $large.SetUnit('bge','bge-unit')
if ([math]::Abs($large.Rank($vector1024,1)[0].Score - 1) -gt 0.000001) { throw '1024-dimensional cosine failed.' }
$rejected = $false
try { $large.ReadBatch($path) } catch { $rejected = $true }
if (!$rejected) { throw 'Mixed model dimensions accepted.' }
Write-Host 'PASS: 1024-dimensional scoring and rejection of 768-dimensional cache in 1024-dimensional index.'
