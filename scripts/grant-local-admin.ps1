param([Parameter(Mandatory=$true)][string]$Email)
$ErrorActionPreference = 'Stop'
if ($Email -notmatch '^[A-Za-z0-9.!#$%&''*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+$' -or $Email.Length -gt 254) { throw 'Invalid email' }
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $repoRoot
$emailHex = [Convert]::ToHexString([Text.Encoding]::ASCII.GetBytes($Email.ToLowerInvariant()))
$sql = "INSERT INTO user_roles(user_id,role) SELECT user_id,'ADMIN' FROM users WHERE email=CONVERT(0x$emailHex USING ascii) AND status='ACTIVE' ON DUPLICATE KEY UPDATE role=role; SELECT u.email,r.role FROM users u JOIN user_roles r ON r.user_id=u.user_id WHERE u.email=CONVERT(0x$emailHex USING ascii) AND r.role='ADMIN';"
# Explicit local-only administrative action. Password stays inside the MySQL container.
$sql | docker compose -f compose.yml -f compose.local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root framehub'
if ($LASTEXITCODE -ne 0) { throw 'Local administrator provisioning failed' }
