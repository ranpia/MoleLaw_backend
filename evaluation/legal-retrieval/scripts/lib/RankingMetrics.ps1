function Get-RankingMetrics($Hits, $GoldUnitIds) {
    $gold = @($GoldUnitIds | Select-Object -Unique)
    if ($gold.Count -eq 0) { throw 'Ranking metrics require positive gold units.' }
    $ordered = @($Hits | Sort-Object { [int]$_.rank })
    if (@($ordered.unitId | Select-Object -Unique).Count -ne $ordered.Count) { throw 'Duplicate ranked source units.' }
    $result = [ordered]@{}
    foreach ($k in @(5,10)) {
        $found = 0; $dcg = 0.0; $ideal = 0.0
        foreach ($hit in $ordered) {
            if ([int]$hit.rank -le $k -and $hit.unitId -in $gold) {
                $found++; $dcg += 1.0 / [math]::Log(([int]$hit.rank + 1),2)
            }
        }
        for ($rank = 1; $rank -le [math]::Min($k,$gold.Count); $rank++) { $ideal += 1.0 / [math]::Log(($rank + 1),2) }
        $result["hitAt$k"] = [double]($found -gt 0)
        $result["completeAt$k"] = [double]($found -eq $gold.Count)
        $result["precisionAt$k"] = $found / [double]$k
        $result["recallAt$k"] = $found / [double]$gold.Count
        $result["ndcgAt$k"] = $dcg / $ideal
    }
    [pscustomobject]$result
}
