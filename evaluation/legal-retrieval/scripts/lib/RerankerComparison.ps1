function Read-RerankerJson([string]$Path) {
    Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}
function Write-RerankerJson([string]$Path,$Value) {
    [IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 35)+"`n",[Text.UTF8Encoding]::new($false))
}
function Invoke-RerankerJson([string]$Uri,$Body=$null) {
    $args=@{Uri=$Uri;UseBasicParsing=$true;TimeoutSec=300;ErrorAction='Stop'}
    if ($null -ne $Body) {
        $args.Method='Post'; $args.ContentType='application/json; charset=utf-8'
        $args.Body=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    $response=Invoke-WebRequest @args
    $response.RawContentStream.Position=0
    $reader=[IO.StreamReader]::new($response.RawContentStream,[Text.UTF8Encoding]::new($false,$true))
    try { $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
}
function Get-RerankerOrder($Candidates,$Response,[string]$Model,[string]$Revision) {
    if ($Response.model -cne $Model -or $Response.revision -cne $Revision) { throw 'Reranker model/revision mismatch.' }
    $ids=@($Candidates | ForEach-Object {$_.unitId})
    if (!$ids.Count -or @($ids | Select-Object -Unique).Count -ne $ids.Count) { throw 'Duplicate or empty candidates.' }
    if (@($Response.results).Count -ne $ids.Count) { throw 'Reranker result count mismatch.' }
    $scores=@{}
    foreach($item in $Response.results) {
        if ($item.id -cnotin $ids -or $scores.ContainsKey($item.id) -or $null -eq $item.score -or $item.score -is [string] -or $item.score -is [bool]) { throw 'Unknown, duplicate, or invalid reranker result.' }
        $score=[double]$item.score
        if ([double]::IsNaN($score) -or [double]::IsInfinity($score)) { throw 'Non-finite reranker score.' }
        $scores[$item.id]=$score
    }
    # Stable ties preserve the original dense rank. Never combine cosine and cross-encoder scores.
    $ordered=@($Candidates | Sort-Object @{Expression={$scores[$_.unitId]};Descending=$true},@{Expression={[int]$_.rank}})
    for($i=0;$i -lt $ordered.Count;$i++) {
        [pscustomobject]@{rank=$i+1;unitId=$ordered[$i].unitId;baselineRank=[int]$ordered[$i].rank;
            similarity=[double]$ordered[$i].similarity;rerankScore=$scores[$ordered[$i].unitId]}
    }
}
function Get-RerankerMetrics($Hits,$Gold) {
    $m=Get-RankingMetrics $Hits $Gold
    $first=@($Hits | Where-Object {$_.unitId -in $Gold} | Sort-Object {[int]$_.rank} | Select-Object -First 1)
    $rr=0.0
    if($first.Count -and [int]$first[0].rank -le 10){$rr=1.0/[int]$first[0].rank}
    $found=@($Hits | Where-Object {$_.unitId -in $Gold}).Count
    $goldCount=@($Gold | Select-Object -Unique).Count
    [pscustomobject]@{hitAt5=$m.hitAt5;completeAt5=$m.completeAt5;recallAt5=$m.recallAt5;ndcgAt5=$m.ndcgAt5;
        hitAt10=$m.hitAt10;completeAt10=$m.completeAt10;recallAt10=$m.recallAt10;ndcgAt10=$m.ndcgAt10;
        mrrAt10=$rr;recallAt50=$found/[double]$goldCount;hitAt50=[double]($found -gt 0);
        completeAt50=[double]($found -eq $goldCount)}
}
