$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$target = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $target) { throw '.env already exists; refusing to overwrite secrets' }
$content = Get-Content -LiteralPath (Join-Path $repoRoot '.env.example') -Raw
function New-RandomSecret { param([int]$Length = 32) $bytes = New-Object byte[] $Length; [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes); [Convert]::ToBase64String($bytes) }
$placeholders = [regex]::Matches($content, 'CHANGE_ME_[A-Z0-9_]+') | ForEach-Object { $_.Value } | Select-Object -Unique
foreach ($placeholder in $placeholders) { $length = if ($placeholder -eq 'CHANGE_ME_CANAL_PASSWORD') { 24 } else { 32 }; $content = $content.Replace($placeholder, (New-RandomSecret -Length $length)) }
[System.IO.File]::WriteAllText($target, $content)
Write-Host 'Created .env with random local credentials.'
