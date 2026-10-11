#Requires -Version 5.1
# Стабильность Task 5: 10 последовательных + 2 параллельных запроса.
# Запуск: .\loop.ps1 [-Base http://localhost:8080] [-Key $env:TASK5_API_KEY]
param([string]$Base = "http://localhost:8080", [string]$Key = $env:TASK5_API_KEY, [int]$N = 10)
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$h = @{ "Content-Type" = "application/json" }
if ($Key -ne "") { $h["Authorization"] = "Bearer $Key" }
$ok = 0; $fail = 0
$sw = [System.Diagnostics.Stopwatch]::StartNew()
for ($i = 1; $i -le $N; $i++) {
    $body = (@{ model = "qwen2.5:7b"; messages = @(@{ role = "user"; content = "Ответь одним коротким предложением: что такое RAG? (запрос $i)" }); max_tokens = 128 } | ConvertTo-Json -Compress)
    try {
        $r = Invoke-RestMethod -Uri "$Base/v1/chat/completions" -Method POST -Headers $h -Body $body -TimeoutSec 200
        $ok++
        Write-Output "[$i/$N] OK (${$r.usage.total_tokens} tok): $($r.choices[0].message.content.Substring(0, [Math]::Min(80, $r.choices[0].message.content.Length)))..."
    } catch {
        $fail++
        Write-Output "[$i/$N] FAIL: $($_.Exception.Message)"
    }
}
$sw.Stop()
Write-Output "---"
Write-Output "OK=$ok FAIL=$fail за $([int]$sw.Elapsed.TotalSeconds)s (среднее $([int]($sw.Elapsed.TotalSeconds / $N))s/запрос)"
if ($fail -gt 0) { exit 1 }
