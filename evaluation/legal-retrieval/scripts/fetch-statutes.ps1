[CmdletBinding()]
param([switch]$Refresh, [string]$DatasetPath, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
if (!$DatasetPath) { $DatasetPath = Get-DataPath 'questions.json' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $ArtifactsRoot 'snapshots' }
$dataset = Get-Content -Raw -Encoding UTF8 $DatasetPath | ConvertFrom-Json
$directory = $OutputDirectory
if ((Test-Path -LiteralPath $directory) -and !$Refresh -and @(Get-ChildItem -LiteralPath $directory -Filter '*.html').Count -gt 0) {
    throw 'Snapshots already exist. Keep frozen versions or explicitly use -Refresh for a new collection.'
}
$null = New-Item -ItemType Directory -Force $directory
$manifest = @()
function Get-PlainText([string]$Html) {
    $text = [regex]::Replace($Html, '(?is)<script\b.*?</script>|<style\b.*?</style>', '')
    $text = [regex]::Replace($text, '(?i)<br\s*/?>|</p>', "`n")
    $text = [Net.WebUtility]::HtmlDecode([regex]::Replace($text, '<[^>]+>', ' '))
    $text = [regex]::Replace($text, '[\t\r ]+', ' ')
    $text.Trim()
}
foreach ($law in $dataset.laws) {
    Write-Host "Fetching $($law.id)..."
    $landing = Invoke-WebRequest -UseBasicParsing -Uri $law.sourceUrl -TimeoutSec 60
    $frame = [regex]::Match($landing.Content, '(?is)<iframe[^>]+src="([^"]+)"')
    if (!$frame.Success) { throw "Missing official law iframe: $($law.id)" }
    $frameUrl = [Net.WebUtility]::HtmlDecode($frame.Groups[1].Value)
    $mst = [regex]::Match($frameUrl, 'lsiSeq=(\d+)').Groups[1].Value
    $efYd = [regex]::Match($frameUrl, 'efYd=(\d+)').Groups[1].Value
    if (!$mst -or !$efYd) { throw "Missing version: $($law.id)" }
    $url = "https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=$mst&chrClsCd=010202&efYd=$efYd"
    $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 60
    $html = $response.Content
    $lawId = [regex]::Match($html, 'id="lsId"[^>]*value="([^"]+)"').Groups[1].Value
    $officialName = [Net.WebUtility]::HtmlDecode([regex]::Match($html, 'id="lsNm"[^>]*value="([^"]+)"').Groups[1].Value)
    if (!$lawId -or !$officialName -or $html -notmatch 'joNoList') { throw "Missing statute body: $($law.id)" }
    $path = Join-Path $directory ($law.id + '.html')
    [IO.File]::WriteAllText($path, $html, [Text.UTF8Encoding]::new($false))
    $articles = @()
    foreach ($block in [regex]::Matches($html, '(?is)<div class="lawcon">(.*?)</div>')) {
        $articleId = [regex]::Match($block.Value, 'name="joNoList"[^>]*id="Y(\d+)"').Groups[1].Value
        if (!$articleId) { continue }
        $text = Get-PlainText $block.Groups[1].Value
        $heading = Get-PlainText ([regex]::Match($block.Value, '(?is)<label[^>]*>(.*?)</label>').Groups[1].Value)
        if (!$heading -and $text -notmatch '\uC0AD\uC81C') { throw "Missing article heading: $($law.id) $articleId" }
        $articles += [ordered]@{ articleCode = $articleId; heading = $heading; text = $text }
    }
    if ($articles.Count -eq 0) { throw "No extracted articles: $($law.id)" }
    $entry = [ordered]@{ id = $law.id; name = $officialName; lawId = $lawId; mst = $mst
        effectiveDate = [datetime]::ParseExact($efYd, 'yyyyMMdd', $null).ToString('yyyy-MM-dd')
        collectedAt = [datetime]::UtcNow.ToString('o'); sourceUrl = $url
        sha256 = (Get-FileHash $path -Algorithm SHA256).Hash; articleCount = $articles.Count
        extractionVersion = 'lawcon-html-v1'; articles = $articles }
    [IO.File]::WriteAllText((Join-Path $directory ($law.id + '.json')), ($entry | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    $manifest += $entry
}
[IO.File]::WriteAllText((Join-Path $directory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
Write-Host "Saved $($manifest.Count) official statute snapshots."
