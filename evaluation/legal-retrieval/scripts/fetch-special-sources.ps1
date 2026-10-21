[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$spec = Get-Content -Raw -Encoding UTF8 (Get-ReviewPath 'special-sources.json') | ConvertFrom-Json
$directory = Join-Path $ArtifactsRoot 'snapshots/supplemental'
$null = New-Item -ItemType Directory -Force $directory
$entries = @()
$notice = $spec.notice
$path = Join-Path $directory ($notice.id + '-body.html')
if (!(Test-Path -LiteralPath $path)) {
    $response = Invoke-WebRequest -UseBasicParsing $notice.sourceUrl -TimeoutSec 60
    [IO.File]::WriteAllText($path, $response.Content, [Text.UTF8Encoding]::new($false))
}
$html = Get-Content -Raw -Encoding UTF8 $path
$match = [regex]::Match($html, '(?is)<p class="gtit"[^>]*>(.*?)</p>')
if (!$match.Success) { throw 'Missing official notice text.' }
$text = [Net.WebUtility]::HtmlDecode([regex]::Replace($match.Groups[1].Value, '<[^>]+>', ' '))
$text = [regex]::Replace($text, '\s+', ' ').Trim()
$entries += [ordered]@{ id = $notice.id; lawId = $notice.id; heading = $notice.name
    mst = $notice.version; effectiveDate = $notice.effectiveDate; provisionType = 'notice'
    text = $text; sourceUrl = $notice.sourceUrl; sourceSha256 = (Get-FileHash $path -Algorithm SHA256).Hash
    extractionVersion = 'official-notice-html-v1'; questionIds = $notice.questionIds }
$jar = Join-Path $ArtifactsRoot 'snapshots/pdfbox-app-3.0.5.jar'
if (!(Test-Path -LiteralPath $jar)) {
    Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.5/pdfbox-app-3.0.5.jar' -OutFile $jar -TimeoutSec 60
}
foreach ($annex in $spec.annexes) {
    $pdf = Join-Path $directory ($annex.id + '.pdf')
    $txt = Join-Path $directory ($annex.id + '.txt')
    if (!(Test-Path -LiteralPath $pdf)) { Invoke-WebRequest -UseBasicParsing $annex.sourceUrl -OutFile $pdf -TimeoutSec 60 }
    & java -jar $jar export:text -i $pdf -o $txt -encoding UTF-8
    if ($LASTEXITCODE -ne 0) { throw "PDF extraction failed: $($annex.id)" }
    $parent = Get-Content -Raw -Encoding UTF8 (Join-Path $directory ($annex.parentLawId + '.json')) | ConvertFrom-Json
    $text = Get-Content -Raw -Encoding UTF8 $txt
    if ($text.Length -lt 100) { throw "Empty annex: $($annex.id)" }
    $entries += [ordered]@{ id = $annex.id; lawId = $annex.parentLawId; heading = $annex.heading
        mst = $parent.mst; effectiveDate = $parent.effectiveDate; provisionType = 'annex'
        text = $text.Trim(); sourceUrl = $annex.sourceUrl
        sourceSha256 = (Get-FileHash $pdf -Algorithm SHA256).Hash; fileId = $annex.fileId
        extractionVersion = 'pdfbox-3.0.5-plain-text-v1'; questionIds = $annex.questionIds }
}
[IO.File]::WriteAllText((Get-ReviewPath 'special-evidence.json'), (@{ evidence = $entries } | ConvertTo-Json -Depth 10) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Host "Saved $($entries.Count) official notice/annex evidence entries."
