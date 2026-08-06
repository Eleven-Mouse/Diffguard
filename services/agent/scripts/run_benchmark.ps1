param(
  [ValidateSet("security", "logic", "quality", "clean", "all")]
  [string]$Scenario = "all",
  [int]$Runs = 3,
  [string]$BaseUrl = "http://127.0.0.1:8000",
  [string]$Provider = "claude",
  [string]$Model = "glm-5.2",
  [string]$ApiBaseUrl = "https://open.bigmodel.cn/api/anthropic",
  [string]$OutputDir = (Join-Path $env:TEMP "diffguard-benchmark"),
  [switch]$InvokeAgent
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$cases = @{
  security = @{ expected = @("SQL 注入", "硬编码 Token", "Token 泄露", "命令注入"); diff = @(
    "diff --git a/app.py b/app.py", "@@ -0,0 +1,7 @@",
    "+ADMIN_TOKEN = 'demo-token'", "+def list_orders(customer_id):", "+    return db.execute(`"SELECT * FROM orders WHERE customer_id = '`" + customer_id + `"'`")",
    "+def export_orders():", "+    return {'token': ADMIN_TOKEN, 'orders': list_orders('')}", "+def preview_report(name):", "+    return subprocess.check_output('cat reports/' + name, shell=True)"
  ) -join "`n" }
  logic = @{ expected = @("空值解引用", "金额边界未校验", "转账未回滚"); diff = @(
    "diff --git a/account.py b/account.py", "@@ -1,2 +1,9 @@", "+def get_balance(accounts, account_id):", "+    return accounts.get(account_id).balance",
    "+def transfer(source, target, amount):", "+    source.balance -= amount", "+    if target is None:", "+        return False", "+    target.balance += amount"
  ) -join "`n" }
  quality = @{ expected = @("资源未关闭", "异常被吞掉", "过深嵌套"); diff = @(
    "diff --git a/settings.py b/settings.py", "@@ -1,2 +1,13 @@", "+def load_settings(path):", "+    file = open(path)", "+    try:", "+        return json.load(file)", "+    except Exception:", "+        return {}", "+def classify(value):", "+    if value > 0:", "+        if value < 10:", "+            if value % 2 == 0:", "+                if value > 4: return 'small-even'"
  ) -join "`n" }
  clean = @{ expected = @(); diff = @("diff --git a/math.py b/math.py", "@@ -1,2 +1,3 @@", "-def calculate_total(prices):", "+def compute_total(prices):", "+    '''计算订单总价。'''", "     return sum(prices)") -join "`n" }
}

if (Test-Path $OutputDir) { Remove-Item $OutputDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$selected = if ($Scenario -eq "all") { @("security", "logic", "quality", "clean") } else { @($Scenario) }
$report = @("# DiffGuard 基准评测报告", "", "| 场景 | 轮次 | 预期问题数 | 返回问题数 | 状态 | 耗时(ms) |", "|---|---:|---:|---:|---|---:|")

foreach ($name in $selected) {
  $case = $cases[$name]
  for ($run = 1; $run -le $Runs; $run++) {
    $dir = Join-Path $OutputDir "$name-run-$run"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $request = @{ request_id = "benchmark-$name-$run"; mode = "PIPELINE"; project_dir = $dir; tool_server_url = ""; allowed_files = @(); llm_config = @{ provider = $Provider; model = $Model; api_key_env = "DIFFGUARD_API_KEY"; base_url = $ApiBaseUrl; max_tokens = 2000; temperature = 0.2; timeout_seconds = 120 }; review_config = @{ language = "zh"; rules_enabled = @("security", "bug-risk", "code-style", "performance") }; diff_entries = @(@{ file_path = "$name.py"; content = $case.diff; token_count = [Math]::Ceiling($case.diff.Length / 4) }) }
    $requestJson = $request | ConvertTo-Json -Depth 8
    Set-Content -LiteralPath (Join-Path $dir "review-request.json") -Value $requestJson -Encoding utf8
    Set-Content -LiteralPath (Join-Path $dir "evaluation-checklist.md") -Value "# $name`n`n预期问题：$($case.expected -join '、')`n`n干净 Diff 的预期问题数为 0。" -Encoding utf8
    if (-not $InvokeAgent) { $report += "| $name | $run | $($case.expected.Count) | - | dry-run | - |"; continue }
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/review" -ContentType "application/json" -Body $requestJson
    $response | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $dir "review-response.json") -Encoding utf8
    $report += "| $name | $run | $($case.expected.Count) | $(@($response.issues).Count) | $($response.status) | $($response.review_duration_ms) |"
    $report += "`n## $name / 第 $run 轮`n预期：$($case.expected -join '、')`n摘要：$($response.summary)"
    foreach ($issue in @($response.issues)) { $report += "- [$($issue.severity)] $($issue.type) — $($issue.file):$($issue.line)：$($issue.message)" }
  }
}
$report += "`n## 人工判分`n- TP：命中预期问题；FN：预期未报；FP：无关或重复问题。`n- Precision = TP / (TP + FP)，Recall = TP / (TP + FN)。`n- 行号、严重性和建议质量需逐条人工复核。"
$reportPath = Join-Path $OutputDir "benchmark-evaluation.md"
Set-Content -LiteralPath $reportPath -Value $report -Encoding utf8
Write-Host "报告：$reportPath"
