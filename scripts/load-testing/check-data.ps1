# Sequential correctness checks only; credentials/cookies/tokens are never printed.
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoPath 'docker/loadtest/.env'))) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim() }
}
$fixture = Get-Content -Raw -Encoding UTF8 (Join-Path $repoPath 'build/loadtest-data/manifest.json') | ConvertFrom-Json
if ($fixture.dataset -ne 'lt-read-v1' -or $fixture.users.Count -ne 20 -or -not $settings.LT_DATA_PASSWORD) {
    throw 'Prepare the reviewed lt-read-v1 fixture and its local password first.'
}
$baseUrl = 'http://localhost:' + $settings.LT_BACKEND_PORT

function Request-Json($Path, $Session, $Method = 'GET', $Payload = $null, $Token = $null) {
    $requestArgs = @{ Uri = $baseUrl + $Path; WebSession = $Session; Method = $Method; TimeoutSec = 15; UseBasicParsing = $true }
    if ($null -ne $Payload) {
        $requestArgs.ContentType = 'application/json'
        $requestArgs.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Compress))
    }
    if ($Token) { $requestArgs.Headers = @{ 'X-XSRF-TOKEN' = $Token } }
    try { $response = Invoke-WebRequest @requestArgs }
    catch { throw "Fixture HTTP check failed: $Method $Path (status $([int]$_.Exception.Response.StatusCode))" }
    # JSON is UTF-8 even if the server omits an explicit charset header.
    $json = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if ($json) { return ($json | ConvertFrom-Json) }
}

$checkedRooms = 0
for ($userIndex = 0; $userIndex -lt $fixture.users.Count; $userIndex++) {
    $account = $fixture.users[$userIndex]
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Request-Json '/api/auth/csrf' $session
    $null = Request-Json '/api/auth/login' $session 'POST' @{ email = $account.email; password = $settings.LT_DATA_PASSWORD } $csrf.token
    try {
        $profile = Request-Json '/api/users/me' $session
        if ($profile.data.email -ne $account.email) { throw 'Fixture login did not resolve to its owner.' }
        $rooms = @(Request-Json '/api/chat-rooms?page=0&size=100' $session)
        if ($rooms.Count -ne $fixture.roomsPerUser) { throw 'Unexpected room count.' }
        $actualIds = @($rooms | ForEach-Object { [long]$_.id } | Sort-Object)
        $expectedIds = @($account.roomIds | ForEach-Object { [long]$_ } | Sort-Object)
        if (@(Compare-Object $expectedIds $actualIds).Count) { throw 'Room ownership differs from manifest.' }
        foreach ($roomIndex in @(0, ($fixture.roomsPerUser - 1))) {
            $roomId = $account.roomIds[$roomIndex]
            $messages = @(Request-Json "/api/chat-rooms/$roomId/messages" $session)
            $storedMessages = @($messages | Where-Object { $_.sender -ne 'INFO' })
            if ($storedMessages.Count -ne $fixture.messagesPerRoom -or @($messages | Where-Object { $_.sender -eq 'INFO' }).Count -ne 1) {
                throw 'Unexpected stored messages or rendered evidence count.'
            }
            $marker = $account.roomMarkers[$roomIndex]
            for ($messageIndex = 0; $messageIndex -lt $storedMessages.Count; $messageIndex++) {
                $expectedSender = if ($messageIndex % 2 -eq 0) { 'USER' } else { 'BOT' }
                $message = $storedMessages[$messageIndex]
                if ($message.sender -ne $expectedSender -or -not $message.content.Contains($marker) -or $message.content.StartsWith('gcm1.')) {
                    throw 'API message decryption, ordering or sender differs from the synthetic fixture.'
                }
            }
            $checkedRooms++
        }
        if ($userIndex -eq 0) {
            $otherRoom = $fixture.users[1].roomIds[0]
            try {
                $null = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/api/chat-rooms/$otherRoom/messages" -WebSession $session -TimeoutSec 10
                throw 'Unexpected access to another fixture user room.'
            } catch {
                if ([int]$_.Exception.Response.StatusCode -ne 403) { throw 'Cross-owner access was not rejected with 403.' }
            }
        }
    } finally {
        $csrf = Request-Json '/api/auth/csrf' $session
        $null = Request-Json '/api/auth/logout' $session 'POST' $null $csrf.token
    }
}
"OK: $($fixture.users.Count) user logins, room ownership, $checkedRooms sampled room histories, decrypted USER/BOT order, rendered INFO and cross-owner 403."
'No AI requests or concurrent load were generated.'
