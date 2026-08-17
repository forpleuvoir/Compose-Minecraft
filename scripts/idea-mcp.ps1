# idea-mcp.ps1 - lightweight SSE client for the IntelliJ IDEA MCP Server
#
# Purpose: AGENTS.md requires build/run/search via IDEA MCP (mcp__idea__*), but an
# agent session may not have those tools injected. This script reads the SSE endpoint
# from opencode.json (mcp.jetbrains.url), opens a one-shot session and issues JSON-RPC,
# equivalent to mcp__idea__build_project / read_file / search_symbol /
# execute_run_configuration etc.
#
# Usage (run from anywhere; repo root recommended):
#   pwsh scripts/idea-mcp.ps1 tools                                      # list tools
#   pwsh scripts/idea-mcp.ps1 call build_project '{"rebuild":true}'      # full build
#   pwsh scripts/idea-mcp.ps1 call read_file '{"filePath":"common/src/..."}'
#   pwsh scripts/idea-mcp.ps1 call get_run_configurations '{}'
#   pwsh scripts/idea-mcp.ps1 call execute_run_configuration '{"name":"..."}'
#
# Notes:
# - Each invocation opens its own SSE session (message endpoint is session-bound);
# - Long tasks (build_project / execute_run_configuration) pass -TimeoutSec;
# - Prerequisite: IntelliJ is running with the MCP Server plugin enabled (port 64342).

param(
    [Parameter(Position = 0)][string]$Action = "tools",
    [Parameter(Position = 1)][string]$Tool = "",
    [Parameter(Position = 2)][string]$ArgsJson = "{}",
    [int]$TimeoutSec = 3600,
    [string]$Endpoint = ""
)

$ErrorActionPreference = "Stop"

if (-not $Endpoint) {
    $cfg = "$env:USERPROFILE\.config\opencode\opencode.json"
    if (Test-Path $cfg) {
        $j = Get-Content $cfg -Raw | ConvertFrom-Json
        $Endpoint = $j.mcp.jetbrains.url
    }
}
if (-not $Endpoint) {
    $Endpoint = "http://127.0.0.1:64342/sse"
}
if (-not $Endpoint.EndsWith("/sse")) {
    $Endpoint = $Endpoint.TrimEnd("/") + "/sse"
}

Write-Host "[idea-mcp] SSE: $Endpoint" -ForegroundColor DarkGray

$tmp = Join-Path $env:TEMP ("dsh_idea_mcp_sse_" + [guid]::NewGuid().ToString("N") + ".txt")
$job = Start-Job -ScriptBlock {
    param($u, $f)
    & curl.exe -s -N --no-buffer $u -o $f 2>$null
} -ArgumentList $Endpoint, $tmp

try {
    $deadline = (Get-Date).AddSeconds(10)
    $messageUrl = $null
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $tmp) {
            $content = Get-Content $tmp -Raw -ErrorAction SilentlyContinue
            if ($content -match "(?m)^data: (\S+)") {
                $messageUrl = $Matches[1]
                break
            }
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $messageUrl) {
        $tail = (Get-Content $tmp -Raw -ErrorAction SilentlyContinue)
        throw "No message endpoint from SSE handshake (server output: $tail). Is IntelliJ running with the MCP Server plugin enabled?"
    }
    # The JetBrains plugin sends a relative path (/message?sessionId=...); resolve it
    if ($messageUrl -notmatch "^https?://") {
        $base = $Endpoint.Substring(0, $Endpoint.LastIndexOf("/"))
        $messageUrl = $base + $messageUrl
    }
    Write-Host "[idea-mcp] session: $messageUrl" -ForegroundColor DarkGray

    function Post-Rpc([int]$id, [string]$method, $params) {
        $body = @{ jsonrpc = "2.0"; id = $id; method = $method; params = $params } |
            ConvertTo-Json -Depth 12 -Compress
        $posBefore = 0
        if (Test-Path $tmp) { $posBefore = (Get-Item $tmp).Length }
        # HTTP POST is accepted immediately; the JSON-RPC response is pushed
        # over the SSE stream (event: message / data: {...})
        $null = Invoke-RestMethod -Uri $messageUrl -Method Post -ContentType "application/json" -Body $body -TimeoutSec $TimeoutSec
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 150
            if (-not (Test-Path $tmp)) { continue }
            $len = (Get-Item $tmp).Length
            if ($len -le $posBefore) { continue }
            $new = $null
            try {
                $fs = [System.IO.File]::Open($tmp, 'Open', 'Read', 'ReadWrite')
                try {
                    $fs.Seek($posBefore, 'Begin') | Out-Null
                    $sr = New-Object System.IO.StreamReader($fs)
                    $new = $sr.ReadToEnd()
                } finally { $fs.Dispose() }
            } catch { continue }
            $ms = [regex]::Matches($new, 'data: (\{.*\})', [System.Text.RegularExpressions.RegexOptions]::Singleline)
            foreach ($m in $ms) {
                try {
                    $obj = $m.Groups[1].Value | ConvertFrom-Json
                    if ($obj.id -eq $id) { return $obj }
                } catch { }
            }
            $posBefore = $len
        }
        throw "Timed out waiting for JSON-RPC response id=$id"
    }

    $null = Post-Rpc 1 "initialize" @{
        protocolVersion = "2024-11-05"
        capabilities    = @{}
        clientInfo      = @{ name = "dsh-pwsh"; version = "0.1" }
    }

    try {
        $nb = @{ jsonrpc = "2.0"; method = "notifications/initialized"; params = @{} } |
            ConvertTo-Json -Depth 6 -Compress
        Invoke-WebRequest -Uri $messageUrl -Method Post -ContentType "application/json" -Body $nb -TimeoutSec 15 | Out-Null
    } catch {
        # notification has no response body; ignore
    }

    switch ($Action) {
        "tools" {
            $r = Post-Rpc 3 "tools/list" @{}
            if ($r.result.tools) {
                foreach ($t in $r.result.tools) {
                    Write-Output ("- {0}: {1}" -f $t.name, ($t.description -replace "\s+", " "))
                }
            } elseif ($r.error) {
                Write-Output ("ERROR: " + ($r.error | ConvertTo-Json -Depth 5 -Compress))
            }
        }
        "call" {
            if (-not $Tool) { throw "call requires a tool name" }
            $argsObj = if ($ArgsJson) { $ArgsJson | ConvertFrom-Json } else { @{} }
            $r = Post-Rpc 4 "tools/call" @{ name = $Tool; arguments = $argsObj }
            if ($r.error) {
                Write-Output ("TOOL-ERROR: " + ($r.error | ConvertTo-Json -Depth 5 -Compress))
                exit 2
            }
            if ($r.result.isError) {
                Write-Host "[tool reported error]" -ForegroundColor Yellow
            }
            foreach ($c in $r.result.content) {
                if ($c.type -eq "text") { Write-Output $c.text }
                else { Write-Output ($c | ConvertTo-Json -Depth 6 -Compress) }
            }
        }
        "call-json" {
            if (-not $Tool) { throw "call-json requires a tool name" }
            $argsObj = if ($ArgsJson) { $ArgsJson | ConvertFrom-Json } else { @{} }
            $r = Post-Rpc 5 "tools/call" @{ name = $Tool; arguments = $argsObj }
            $r | ConvertTo-Json -Depth 12 -Compress
        }
        default { throw "Unknown action: $Action (choose tools / call / call-json)" }
    }
} finally {
    Stop-Job $job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $job -Force -ErrorAction SilentlyContinue | Out-Null
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue | Out-Null
}
