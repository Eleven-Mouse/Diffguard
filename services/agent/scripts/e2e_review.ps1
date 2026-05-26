param(
    [string]$BaseUrl = "http://127.0.0.1:8000",
    [int]$FileCount = 12,
    [int]$TokenPerFile = 900,
    [string]$Provider = "openai",
    [string]$Model = "gpt-4o",
    [string]$ApiBaseUrl = "",
    [string]$ProjectDir = "",
    [switch]$SaveRequest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectDir)) {
    $ProjectDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$requestId = "e2e-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$tmpDir = Join-Path $env:TEMP "diffguard-e2e"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$requestPath = Join-Path $tmpDir "review_request_$requestId.json"
$responsePath = Join-Path $tmpDir "review_response_$requestId.json"

$entries = @()
for ($i = 1; $i -le $FileCount; $i++) {
    $file = "e2e/file_$i.py"
    $diff = @"
diff --git a/$file b/$file
--- a/$file
+++ b/$file
@@ -1,1 +1,2 @@
+print('file_$i')
"@
    $entries += @{
        file_path = $file
        content = $diff
        token_count = $TokenPerFile
    }
}

$body = @{
    request_id = $requestId
    mode = "PIPELINE"
    project_dir = $ProjectDir
    tool_server_url = ""
    allowed_files = @()
    llm_config = @{
        provider = $Provider
        model = $Model
        api_key_env = "DIFFGUARD_API_KEY"
        base_url = $(if ([string]::IsNullOrWhiteSpace($ApiBaseUrl)) { $null } else { $ApiBaseUrl })
        max_tokens = 2000
        temperature = 0.2
        timeout_seconds = 120
    }
    review_config = @{
        language = "zh"
        rules_enabled = @("security", "bug-risk", "code-style", "performance")
    }
    diff_entries = $entries
}

$json = $body | ConvertTo-Json -Depth 8
Set-Content -Path $requestPath -Value $json -Encoding UTF8

if ($SaveRequest) {
    Write-Host "Request saved: $requestPath"
}

$uri = "$BaseUrl/api/v1/review"
try {
    $resp = Invoke-RestMethod -Method Post -Uri $uri -ContentType "application/json" -Body $json
} catch {
    Write-Error "Request failed: $($_.Exception.Message)"
    Write-Host "Request file: $requestPath"
    exit 1
}

$respJson = $resp | ConvertTo-Json -Depth 12
Set-Content -Path $responsePath -Value $respJson -Encoding UTF8

$summary = ""
if ($null -ne $resp.summary) {
    $summary = [string]$resp.summary
}

$hasPartial = $summary.Contains("[partial review]")
$hasFallback = $summary.Contains("[fallback-applied]")
$issueCount = 0
if ($null -ne $resp.issues) {
    $issueCount = @($resp.issues).Count
}

Write-Host ""
Write-Host "=== DiffGuard E2E Result ==="
Write-Host "RequestId        : $requestId"
Write-Host "Status           : $($resp.status)"
Write-Host "HasCriticalFlag  : $($resp.has_critical_flag)"
Write-Host "IssueCount       : $issueCount"
Write-Host "TotalTokensUsed  : $($resp.total_tokens_used)"
Write-Host "DurationMs       : $($resp.review_duration_ms)"
Write-Host "PartialReview    : $hasPartial"
Write-Host "FallbackApplied  : $hasFallback"
Write-Host "Summary          : $summary"
Write-Host "Response file    : $responsePath"
Write-Host ""
