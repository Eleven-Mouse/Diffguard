param(
    [string]$OutputDir = (Join-Path $env:TEMP "diffguard-manual-pr"),
    [string]$BaseUrl = "http://127.0.0.1:8000",
    [string]$Provider = "openai",
    [string]$Model = "gpt-4o",
    [string]$ApiBaseUrl = "",
    [switch]$InvokeAgent
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

function Repair-Mojibake([string]$Text) {
    if ([string]::IsNullOrEmpty($Text) -or $Text -notmatch '[\u0080-\u00FF]') {
        return $Text
    }

    try {
        $bytes = [System.Text.Encoding]::GetEncoding(28591).GetBytes($Text)
        $repaired = [System.Text.Encoding]::UTF8.GetString($bytes)
        if ($repaired -match '[\u4E00-\u9FFF]') {
            return $repaired
        }
    } catch {
        # 无法安全还原时保留原文本。
    }

    return $Text
}

function Write-Utf8File([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Get-TokenEstimate([string]$Text) {
    return [Math]::Max(1, [Math]::Ceiling($Text.Length / 4.0))
}

if (Test-Path -LiteralPath $OutputDir) {
    Remove-Item -LiteralPath $OutputDir -Recurse -Force
}

$projectDir = Join-Path $OutputDir "sample-project"
New-Item -ItemType Directory -Force -Path $projectDir | Out-Null

# 基线版本：安全且可工作的简单订单接口。
$baselineApp = @'
import os
import sqlite3
from flask import Flask, request

app = Flask(__name__)


@app.get("/orders")
def list_orders():
    customer_id = request.args.get("customer_id", "")
    with sqlite3.connect(os.environ["DATABASE_URL"]) as conn:
        return list(conn.execute("SELECT id, status FROM orders WHERE customer_id = ?", (customer_id,)))
'@
Write-Utf8File (Join-Path $projectDir "app.py") $baselineApp
Write-Utf8File (Join-Path $projectDir "README.md") "# Sample Order API`n"

Push-Location $projectDir
try {
    git init -q
    git add .
    git -c user.name=DiffGuard -c user.email=diffguard@example.invalid commit -qm "baseline order API"
    git checkout -qb "feature/manual-evaluation"

    # 模拟 PR 改动：每类问题都应产生可人工判定的审查结论。
    $changedApp = @'
import os
import sqlite3
from flask import Flask, request

app = Flask(__name__)
ADMIN_TOKEN = "dg-demo-admin-token-please-report-this"


@app.get("/orders")
def list_orders():
    customer_id = request.args.get("customer_id", "")
    query = f"SELECT id, status FROM orders WHERE customer_id = '{customer_id}'"
    with sqlite3.connect(os.environ["DATABASE_URL"]) as conn:
        return list(conn.execute(query))


@app.get("/admin/export")
def export_orders():
    return {"token": ADMIN_TOKEN, "orders": list_orders()}
'@
    Write-Utf8File (Join-Path $projectDir "app.py") $changedApp
    $worker = @'
import subprocess


def preview_report(report_name: str) -> str:
    return subprocess.check_output("cat reports/" + report_name, shell=True, text=True)
'@
    Write-Utf8File (Join-Path $projectDir "worker.py") $worker

    git add app.py worker.py
    $diff = (git diff --cached --no-ext-diff --unified=3) -join "`n"
    if ([string]::IsNullOrWhiteSpace($diff)) {
        throw "未生成模拟 PR Diff。"
    }
}
finally {
    Pop-Location
}

$entries = @()
$parts = [regex]::Split($diff, '(?m)(?=^diff --git )') | Where-Object { $_.Trim() }
foreach ($part in $parts) {
    $match = [regex]::Match($part, '(?m)^\+\+\+ b/(.+)$')
    if (-not $match.Success) { continue }
    $entries += @{
        file_path = $match.Groups[1].Value.Trim()
        content = $part.TrimEnd()
        token_count = Get-TokenEstimate $part
    }
}

$request = @{
    request_id = "manual-pr-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    mode = "PIPELINE"
    project_dir = $projectDir
    tool_server_url = ""
    allowed_files = @()
    llm_config = @{
        provider = $Provider
        model = $Model
        api_key_env = "DIFFGUARD_API_KEY"
        base_url = $(if ($ApiBaseUrl) { $ApiBaseUrl } else { $null })
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

$requestPath = Join-Path $OutputDir "review-request.json"
$checklistPath = Join-Path $OutputDir "evaluation-checklist.md"
$requestJson = $request | ConvertTo-Json -Depth 8
Write-Utf8File $requestPath $requestJson
$checklist = @'
# DiffGuard 人工 PR 评测清单

## 这次模拟 PR 的已知问题

| 文件 | 期望发现 | 严重性建议 | 关键位置 |
|---|---|---|---|
| `app.py` | 硬编码管理员 Token | High/Critical | `ADMIN_TOKEN` |
| `app.py` | SQL 注入：f-string 拼接 `customer_id` | High/Critical | `list_orders` |
| `app.py` | 管理导出接口无认证且返回 Token | High/Critical | `export_orders` |
| `worker.py` | 命令注入：`shell=True` + 用户输入拼接 | High/Critical | `preview_report` |

## 人工记录

- [ ] 每项已知问题是否被报告？
- [ ] 文件路径和行号是否指向新增代码？
- [ ] 严重性是否合理？
- [ ] 建议是否可执行，且没有泄露或复述 Token？
- [ ] 是否产生无关误报？记录误报数量：____
- [ ] 记录总 Token、耗时和模型：____

> 这是功能评测样本，不要把示例中的假 Token 当成真实凭据。
'@
Write-Utf8File $checklistPath $checklist

Write-Host "模拟 PR 已创建：$projectDir"
Write-Host "Diff 请求：$requestPath"
Write-Host "人工清单：$checklistPath"
Write-Host "期望：4 类安全问题；请同时记录误报、行号、严重性、Token 与耗时。"

if (-not $InvokeAgent) {
    Write-Host "未调用 Agent（默认）。确认服务、DIFFGUARD_API_KEY 后加 -InvokeAgent 执行真实评测。"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($env:DIFFGUARD_API_KEY)) {
    throw "-InvokeAgent 需要通过环境变量设置 DIFFGUARD_API_KEY。"
}

$response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/review" -ContentType "application/json" -Body $requestJson
$response.summary = Repair-Mojibake $response.summary
foreach ($issue in @($response.issues)) {
    $issue.type = Repair-Mojibake $issue.type
    $issue.message = Repair-Mojibake $issue.message
    $issue.suggestion = Repair-Mojibake $issue.suggestion
}

$responsePath = Join-Path $OutputDir "review-response.json"
Write-Utf8File $responsePath ($response | ConvertTo-Json -Depth 12)
Write-Host "Agent 响应：$responsePath"
Write-Host "状态：$($response.status)；问题数：$(@($response.issues).Count)；Token：$($response.total_tokens_used)；耗时：$($response.review_duration_ms)ms"

if ($response.error) {
    Write-Host "错误：$($response.error)"
    exit 1
}

Write-Host "`n=== 审查结果 ==="
Write-Host "摘要：$($response.summary)"
foreach ($issue in @($response.issues)) {
    $location = "{0}:{1}" -f $issue.file, $issue.line
    Write-Host "`n[$($issue.severity)] $($issue.type) — $location"
    Write-Host "问题：$($issue.message)"
    Write-Host "建议：$($issue.suggestion)"
}
