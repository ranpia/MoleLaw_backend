$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
$target=Join-Path $EvaluationRoot 'data/final-validation-v1'
if(Test-Path -LiteralPath (Join-Path $target 'manifest.json')){throw 'Final validation already frozen. Use a new version.'}
$null=New-Item -ItemType Directory -Force $target
$review=Join-Path $ReviewRoot 'final-validation-v1'
$snapshots=Join-Path $ArtifactsRoot 'snapshots/final-validation-v1'
$base=Get-Content -Raw -Encoding UTF8 (Join-Path $DataRoot 'variants/char300-overlap64-article-v1/questions.json') | ConvertFrom-Json
$sources=Get-Content -Raw -Encoding UTF8 (Join-Path $snapshots 'manifest.json') | ConvertFrom-Json
$draft=@(Import-Csv (Join-Path $review 'questions.psv') -Delimiter '|')
if($sources.Count -ne 10 -or $draft.Count -ne 120){throw 'Expected 10 new statutes and 120 new positive questions'}
$chunks=[Collections.Generic.List[object]]::new()
foreach($line in [IO.File]::ReadLines((Join-Path $DataRoot 'variants/char300-overlap64-article-v1/corpus.jsonl'))){$chunks.Add(($line|ConvertFrom-Json))}
$oldUnits=@{};foreach($c in $chunks){$oldUnits[$c.unitId]=$true}
$sourceMap=@{};$unitMap=@{}
foreach($s in $sources){
    if([datetime]$s.effectiveDate -gt [datetime]'2026-10-19'){throw 'Future statute version'}
    if($s.sha256 -ne (Get-FileHash (Join-Path $snapshots ($s.id+'.html'))).Hash){throw 'Source hash mismatch'}
    if($s.lawId -in @($base.laws.snapshot.lawId)){throw 'New statute duplicates existing main law'}
    $sourceMap[$s.id]=$s
    foreach($a in $s.articles){
        $text=[regex]::Replace($a.text,'\s+',' ').Trim()
        if(!$text -or $text -match '^제\d+조(?:의\d+)?\s+삭제'){continue}
        $unit='final-v1:'+ $s.id+':'+$s.mst+':'+$a.articleCode
        if($oldUnits.ContainsKey($unit) -or $unitMap.ContainsKey($unit)){throw 'Duplicate source unit'}
        $unitMap[$unit]=@{source=$s;article=$a;text=$text}
        $index=0
        for($start=0;$start -lt $text.Length;$start+=236){
            $length=[math]::Min(300,$text.Length-$start);$body=$text.Substring($start,$length);$hash=[LegalDenseIndex]::InputKey($body)
            $chunks.Add([ordered]@{id="$unit`:char300-overlap64-article-v1:$index`:$($hash.Substring(0,12))";unitId=$unit;lawId=$s.id;title=($s.name+' '+$a.heading);text=$body;startChar=$start;endChar=$start+$length;textSha256=$hash;sourceUrl=$s.sourceUrl;sourceSha256=$s.sha256})
            $index++;if($start+$length -eq $text.Length){break}
        }
    }
}
$chunkMap=@{};foreach($c in $chunks){$chunkMap[$c.unitId]=@($chunkMap[$c.unitId]|Where-Object{$null -ne $_})+@($c.id)}
$questions=[Collections.Generic.List[object]]::new();$rewrites=[Collections.Generic.List[object]]::new()
$oldRewrites=Get-Content -Raw -Encoding UTF8 (Join-Path $ReviewRoot 'query-rewrites-v1.json')|ConvertFrom-Json
foreach($q in $base.questions){
    $q|Add-Member -NotePropertyName cohort -NotePropertyValue ('legacy_'+$q.split)
    $questions.Add($q)
    $r=@($oldRewrites.questions|Where-Object id -eq $q.id)[0]
    $rewrites.Add(@{id=$q.id;originalText=$q.text;rewrittenText=$r.rewrittenText;origin='frozen-original-rewrites'})
}
$counts=@{};$evidence=@()
foreach($row in $draft){
    $s=$sourceMap[$row.law];if(!$s){throw 'Unknown question law'}
    $counts[$row.law]=1+[int]$counts[$row.law];$id='final-'+$row.law+'-'+('{0:D2}' -f $counts[$row.law])
    $units=@(foreach($code in ($row.articles -split ',')){'final-v1:'+$s.id+':'+$s.mst+':'+$code})
    $groups=@();$flat=@()
    foreach($u in $units){if(!$unitMap.ContainsKey($u)){throw "Missing gold article: $u"};$groups+=@{unitId=$u;chunkIds=@($chunkMap[$u])};$flat+=@($chunkMap[$u]);$evidence+=@{questionId=$id;unitId=$u;title=($s.name+' '+$unitMap[$u].article.heading);sourceUrl=$s.sourceUrl;sourceSha256=$s.sha256;textSha256=[LegalDenseIndex]::InputKey($unitMap[$u].text)}}
    if(!$row.original -or !$row.rewritten -or $row.original -match '第\d+|제\d+조' -or $row.rewritten -match '제\d+조'){throw 'Empty text or article-number shortcut'}
    $questions.Add(@{id=$id;text=$row.original;sourceLawId=$row.law;cohort='expanded_held_out';split='held_out';answerability='in_corpus';style=$(if($units.Count -gt 1){'multi_evidence'}else{'scenario'});relevantUnitIds=$units;relevantChunkGroups=$groups;relevantChunkIds=$flat;supplementalSourcesNeeded=@()})
    $rewrites.Add(@{id=$id;originalText=$row.original;rewrittenText=$row.rewritten;origin='assistant-authored-once-before-evaluation'})
}
if(@($counts.Values|Where-Object{$_ -ne 12}).Count){throw 'Expected 12 positives per new law'}
$foreign=@(
'미국 캘리포니아주에서 주거 임대차 보증금 반환 기한은 며칠인가요?',
'미국 뉴욕주의 주거 임대료 인상 제한을 알려주세요.',
'일본 노동기준법상 연차휴가 발생 요건은 무엇인가요?',
'일본 민법상 유류분 청구 기한을 알려주세요.',
'독일에서 임차인을 퇴거시키는 민법상 요건은 무엇인가요?',
'독일 노동법상 해고 예고 기간을 알고 싶어요.',
'프랑스 노동법상 법정 유급휴가 일수는 얼마인가요?',
'프랑스에서 주택 임대차 계약을 해지하는 기간은 어떻게 정하나요?',
'영국 잉글랜드에서 임차 보증금 보호 예치 제도는 어떻게 되나요?',
'영국 노동법상 부당해고 신청 기간을 알려주세요.',
'캐나다 온타리오주의 주거 임대차 보증금 상한은 얼마인가요?',
'캐나다 브리티시컬럼비아주의 임대료 인상 상한을 알려주세요.',
'호주 뉴사우스웨일스주에서 임대차 보증금은 어디에 맡기나요?',
'호주 노동법상 부당해고 구제를 신청하는 기한은 얼마인가요?',
'싱가포르 고용법상 초과근무 수당 요건을 알려주세요.',
'싱가포르에서 이혼을 신청할 때 거주 요건이 있나요?',
'대만 노동법에서 연차휴가 발생 요건은 무엇인가요?',
'스페인에서 주거 임대차 갱신 기간은 어떻게 되나요?',
'이탈리아 민법상 상속 포기의 절차를 알려주세요.',
'미국 연방 파산법상 챕터 7의 신청 요건을 알려주세요.')
$private=@(
'제가 어제 낸 정보공개 청구서가 지금 어느 담당자 책상에 있는지 확인해 주세요.',
'오늘 제 행정심판 사건 담당 위원의 실제 이름을 조회해 주세요.',
'제가 낸 건축허가 신청이 방금 승인됐는지 현재 처리 상태만 확인해 주세요.',
'제 공무원 인사기록에 지금 입력된 징계 점수를 알려주세요.',
'저희 공장에 오늘 위험성평가 결과가 실제로 게시됐는지 확인해 주세요.',
'제 지방세 조사 담당자가 오늘 실제로 조사를 시작했는지 알려주세요.',
'저희 주택조합 계좌의 현재 잔액이 얼마인지 조회해 주세요.',
'제가 산 아파트의 실제 사용검사 접수번호를 알려주세요.',
'지금 제 민원 신청을 담당하는 공무원의 개인 휴대전화 번호를 알려주세요.',
'제가 어제 관청에서 받은 등기우편의 실제 배달 완료 시각을 알려주세요.',
'제 행정소송 재판부가 오늘 내린 실제 결론을 사건자료 없이 확인해 주세요.',
'제 납부 계좌에서 지방세가 오늘 실제 출금됐는지 확인해 주세요.',
'우리 회사의 이번 달 직원별 실제 건강검진 결과를 조회해 주세요.',
'제가 가입한 주택조합이 어제 돌려준 실제 환급액은 얼마인가요?',
'우리 건물 피난 계단의 오늘 실제 폭을 원격으로 측정해 주세요.',
'제가 제출한 환경계획 의견서의 기관 내부 접수번호를 확인해 주세요.',
'제 허가신청 첨부서류에 오늘 실제로 누락된 파일명을 조회해 주세요.',
'우리 공사장 감독관이 오늘 쓴 내부 점검일지를 보여주세요.',
'제가 이번에 받은 지방세 부과 통지서에 실제 적힌 금액을 서류 없이 알려주세요.',
'우리 부서에서 어제 비밀문서를 누가 열람했는지 실제 접속기록을 확인해 주세요.')
$i=0
foreach($text in @($foreign)+@($private)){
    $i++;$id='final-negative-'+('{0:D2}' -f $i)
    $questions.Add(@{id=$id;text=$text;cohort='expanded_held_out';split='held_out';answerability='out_of_corpus';style=$(if($i -le 20){'foreign_jurisdiction'}else{'unavailable_case_facts'});relevantUnitIds=@();relevantChunkGroups=@();relevantChunkIds=@();supplementalSourcesNeeded=@()})
    $rewrites.Add(@{id=$id;originalText=$text;rewrittenText=$text;origin='identity-preserved-negative-control'})
}
if($questions.Count -ne 270 -or $rewrites.Count -ne 270 -or @($questions|ForEach-Object{$_.id}|Select-Object -Unique).Count -ne 270){throw 'Unexpected question counts'}
$oldGold=@($base.questions|ForEach-Object{$_.relevantUnitIds}|Select-Object -Unique)
if(@($questions|Where-Object cohort -eq 'expanded_held_out'|ForEach-Object{$_.relevantUnitIds}|Where-Object{$_ -in $oldGold}).Count){throw 'Expanded gold overlaps original gold'}
$enc=[Text.UTF8Encoding]::new($false)
$writer=[IO.StreamWriter]::new((Join-Path $target 'corpus.jsonl'),$false,$enc)
try{foreach($c in $chunks){$writer.WriteLine(($c|ConvertTo-Json -Depth 5 -Compress))}}finally{$writer.Dispose()}
$corpusHash=(Get-FileHash (Join-Path $target 'corpus.jsonl')).Hash
$doc=[ordered]@{id='final-validation-v1';referenceDate='2026-10-19';evaluationReady=$true;questionAuthor='conversation-assistant';blindEvaluation=$false;newPositiveCount=120;newNegativeCount=40;legacyQuestionCount=110;laws=@($base.laws)+@($sources|Select-Object id,name,lawId,mst,effectiveDate,sourceUrl,sha256);corpus=@{path='corpus.jsonl';sha256=$corpusHash;maxChars=300;overlapChars=64;chunkingVersion='char300-overlap64-article-v1';unitCount=$oldUnits.Count+$unitMap.Count;chunkCount=$chunks.Count};questions=@($questions)}
[IO.File]::WriteAllText((Join-Path $target 'questions.json'),($doc|ConvertTo-Json -Depth 24)+"`n",$enc)
$rawThreshold=@((Get-Content -Raw -Encoding UTF8 (Join-Path $ReportsRoot 'kure-v1/char300-overlap64-article-v1/summary.json')|ConvertFrom-Json).summary|Where-Object split -eq 'development')[0].threshold
$rewriteThreshold=@((Get-Content -Raw -Encoding UTF8 (Join-Path $ReportsRoot 'query-rewrite-v1/char300/kure-v1/report.json')|ConvertFrom-Json).summary|Where-Object split -eq 'development')[0].threshold
$manifest=[ordered]@{version=1;frozenAtUtc=[datetime]::UtcNow.ToString('o');datasetSha256=(Get-FileHash (Join-Path $target 'questions.json')).Hash;corpusSha256=$corpusHash;draftSha256=(Get-FileHash (Join-Path $review 'questions.psv')).Hash;model=(Get-Content -Raw -Encoding UTF8 (Join-Path $ReviewRoot 'models.json')|ConvertFrom-Json).'kure-v1';newSourceCount=10;sourceUnitCount=$doc.corpus.unitCount;chunkCount=$chunks.Count;questionCount=270;newPositiveCount=120;newNegativeCount=40;thresholds=@{original=$rawThreshold;rewritten=$rewriteThreshold};thresholdPolicy='Frozen original development thresholds; no recalibration on expanded corpus';criteria=@{newRewrittenRecallAt5Minimum=0.75;newRewrittenCompleteAt5Minimum=0.60;legacyHeldOutRecallAt5Minimum=(0.805238095238095-0.05);noRewriteRegressionOnNewRecall=$true};limitations=@('Assistant saw official source/gold when authoring; exploratory, not independent blind human review','100 single-article plus 20 multi-article positives; overlapping gold within a law','Foreign-jurisdiction and unavailable-case-fact negatives; not an exhaustive abstention benchmark','Existing 110 questions are regression controls, not independent new evidence','New laws intentionally selected, not a random sample of all law')}
[IO.File]::WriteAllText((Join-Path $target 'rewrites.json'),(@{version=1;complete=$true;questions=@($rewrites)}|ConvertTo-Json -Depth 8)+"`n",$enc)
$manifest.rewritesSha256=(Get-FileHash (Join-Path $target 'rewrites.json')).Hash
[IO.File]::WriteAllText((Join-Path $target 'manifest.json'),($manifest|ConvertTo-Json -Depth 12)+"`n",$enc)
[IO.File]::WriteAllText((Join-Path $review 'gold-evidence.json'),($evidence|ConvertTo-Json -Depth 7)+"`n",$enc)
$sourceInventory=@($sources|Select-Object id,name,lawId,mst,effectiveDate,sourceUrl,sha256,articleCount,extractionVersion)
[IO.File]::WriteAllText((Join-Path $review 'source-inventory.json'),($sourceInventory|ConvertTo-Json -Depth 7)+"`n",$enc)
Write-Host "FROZEN: 30 main laws; 270 questions (120 new positive, 40 new negative, 110 regression); $($chunks.Count) chunks."
