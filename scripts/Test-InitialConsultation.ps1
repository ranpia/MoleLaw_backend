[CmdletBinding()]
param(
    [ValidatePattern('^http://(localhost|127\.0\.0\.1)(:[0-9]+)?$')]
    [string]$BaseUrl = 'http://localhost:8080',
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Question,
    [ValidateRange(1, 86400)]
    [int]$TimeoutSec = 1200
)

# Explicit local smoke test: uses real configured providers and can incur LLM charges.
# Only the synthetic account created by this invocation is deleted afterwards.
$ErrorActionPreference = 'Stop'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$created = $false

function Invoke-JsonRequest {
    param([string]$Method, [string]$Path, $Body, [string]$CsrfToken)
    $arguments = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        WebSession = $session
        UseBasicParsing = $true
        TimeoutSec = $TimeoutSec
    }
    if ($CsrfToken) { $arguments.Headers = @{ 'X-XSRF-TOKEN' = $CsrfToken } }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
    }
    $response = Invoke-WebRequest @arguments
    if ($response.RawContentStream.Length -gt 0) {
        return [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()) | ConvertFrom-Json
    }
}

try {
    Write-Host '1. CSRF and temporary local account'
    $csrf = Invoke-JsonRequest 'GET' '/api/auth/csrf'
    $null = Invoke-JsonRequest 'POST' '/api/auth/signup' @{
        email = 'initial-flow-' + [guid]::NewGuid().ToString('N') + '@example.com'
        password = 'Check!' + [guid]::NewGuid().ToString('N')
        nickname = 'InitialFlowCheck'
    } $csrf.token
    $created = $true
    $csrf = Invoke-JsonRequest 'GET' '/api/auth/csrf'
    Write-Host '2. Question -> retrieval -> collection/ETL when needed -> decision -> first answer -> persistence'
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $room = Invoke-JsonRequest 'POST' '/api/chat-rooms' @{ content = $Question } $csrf.token
    if (-not $room.id -or ($room.messages.sender -join ',') -ne 'USER,BOT,INFO') {
        throw 'Unexpected first-turn response'
    }
    Write-Host '3. Verify the persisted conversation'
    $history = Invoke-JsonRequest 'GET' ("/api/chat-rooms/" + $room.id + '/messages')
    if (($history.sender -join ',') -ne 'USER,BOT,INFO' -or $history[1].content -ne $room.messages[1].content) {
        throw 'Persisted first answer does not match the response'
    }
    [pscustomobject]@{
        temporaryRoomId = $room.id
        answer = $room.messages[1].content
        evidence = $room.messages[2].content
        elapsedSeconds = [math]::Round($timer.Elapsed.TotalSeconds, 1)
        historyVerified = $true
    }
}
finally {
    if ($created) {
        try {
            # A real ETL run can outlive the five-minute Access token.
            $csrf = Invoke-JsonRequest 'GET' '/api/auth/csrf'
            $null = Invoke-JsonRequest 'POST' '/api/auth/refresh' $null $csrf.token
            $csrf = Invoke-JsonRequest 'GET' '/api/auth/csrf'
            $null = Invoke-JsonRequest 'DELETE' '/api/users/me' $null $csrf.token
            Write-Host '4. Temporary account and its conversation removed'
        } catch {
            Write-Warning 'Temporary account cleanup failed; the original test result is preserved. Remove only this test account separately.'
        }
    }
}
