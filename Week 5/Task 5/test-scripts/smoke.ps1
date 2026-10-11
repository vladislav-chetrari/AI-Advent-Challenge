#Requires -Version 5.1
# Smoke-тест Task 5: health -> models -> docs -> chat x3 -> embeddings -> лимиты.
# Запуск: .\smoke.ps1 [-Base http://localhost:8080] [-Key $env:TASK5_API_KEY]
param([string]$Base = "http://localhost:8080", [string]$Key = $env:TASK5_API_KEY)
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

function Call([string]$Method, [string]$Path, $Body = $null) {
    $h = @{ "Content-Type" = "application/json" }
    if ($Key -ne "") { $h["Authorization"] = "Bearer $Key" }
    $p = @{ Uri = "$Base$Path"; Method = $Method; Headers = $h }
    if ($Body -ne $null) { $p["Body"] = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    try {
        return Invoke-RestMethod @p
    } catch {
        $resp = $_.Exception.Response
        if ($resp -ne $null) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            Write-Output "HTTP $(([int]$resp.StatusCode)): $($sr.ReadToEnd())"
        } else { Write-Output "NETWORK ERROR: $($_.Exception.Message)" }
        return $null
    }
}

Write-Output "=== 1. /health ==="
Call GET "/health" | ConvertTo-Json -Depth 6
Write-Output "`n=== 2. /v1/models ==="
Call GET "/v1/models" | ConvertTo-Json -Depth 6
Write-Output "`n=== 3. POST /v1/docs (text) ==="
Call POST "/v1/docs" @{ type = "text"; title = "Вороны"; input = "Вороны — род птиц семейства врановых. Обыкновенный ворон — один из самых умных видов птиц: умеет использовать орудия, решать задачи в несколько шагов и узнавать себя в зеркале. Серые вороны живут стаями и активно общаются карканьем. Гнёзда вороны строят на деревьях и скалах, в кладке обычно 4-6 яиц." } | ConvertTo-Json
Write-Output "`n=== 4. GET /v1/docs ==="
Call GET "/v1/docs" | ConvertTo-Json -Depth 6
Write-Output "`n=== 5. chat простой (small-talk, без источников) ==="
$r = Call POST "/v1/chat/completions" @{ model = "qwen2.5:7b"; messages = @(@{ role = "user"; content = "Привет!" }); max_tokens = 256 }
if ($r -ne $null) { $r.choices[0].message.content; $r.usage | ConvertTo-Json -Compress }
Write-Output "`n=== 6. chat средний (по базе, ждём Источники) ==="
$r = Call POST "/v1/chat/completions" @{ model = "qwen2.5:7b"; messages = @(@{ role = "user"; content = "Чем питаются вороны и где строят гнёзда?" }); max_tokens = 512 }
if ($r -ne $null) { $r.choices[0].message.content; $r.usage | ConvertTo-Json -Compress }
Write-Output "`n=== 7. chat out-of-domain (про дроздов при базе про ворон — честный фолбэк) ==="
$r = Call POST "/v1/chat/completions" @{ model = "qwen2.5:7b"; messages = @(@{ role = "user"; content = "Расскажи про дроздов" }); max_tokens = 512 }
if ($r -ne $null) { $r.choices[0].message.content }
Write-Output "`n=== 8. /v1/embeddings (ждём dim 768) ==="
$r = Call POST "/v1/embeddings" @{ model = "embeddinggemma:300m"; input = "Вороны строят гнёзда" }
if ($r -ne $null) { Write-Output "dim = $($r.data[0].embedding.Count)"; $r.usage | ConvertTo-Json -Compress }
Write-Output "`n=== 9. лимит контекста (гигантский запрос -> 400) ==="
$big = ("слово " * 12000) -join ""
Call POST "/v1/chat/completions" @{ model = "qwen2.5:7b"; messages = @(@{ role = "user"; content = $big }); max_tokens = 512 }
Write-Output "`n=== smoke done ==="
