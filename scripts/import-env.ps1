$envPath = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
foreach ($line in Get-Content -LiteralPath $envPath) {
 if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
