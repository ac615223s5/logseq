<#
.SYNOPSIS
  Install or update Logseq (this fork's account-less DB build) from its GitHub release.

.DESCRIPTION
  - Not installed yet  -> downloads and runs the Windows NSIS installer (per-user, no admin).
  - Already installed  -> updates IN PLACE from the ZIP (overwrites files without deleting
    the exe), so the Start-menu shortcut and taskbar pin survive.
  Skips work if the release hasn't changed since the last run (unless -Force).

.EXAMPLE
  # One-liner (install or update):
  powershell -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/ac615223s5/logseq/master/scripts/update-logseq.ps1 | iex"

  # From a saved copy:
  powershell -ExecutionPolicy Bypass -File "$HOME\update-logseq.ps1" -Force
#>
[CmdletBinding()]
param(
  [string]$Repo    = 'ac615223s5/logseq',
  [string]$Tag     = '2.0.1',                 # release tag; 'latest' for the latest release
  [ValidateSet('', 'x64', 'arm64')]
  [string]$Arch    = '',                      # target arch; empty = detect this machine's
  [string]$Token   = $env:GITHUB_TOKEN,       # optional; only for a private repo
  [switch]$Force,                             # (re)install even if unchanged
  [switch]$NoRelaunch                         # don't launch Logseq afterward
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\Logseq'
$Exe        = Join-Path $InstallDir 'Logseq.exe'
$Marker     = Join-Path $env:LOCALAPPDATA 'logseq-update.marker'

function Info($m){ Write-Host "[logseq] $m" }
function Fail($m){ Write-Host "[logseq] ERROR: $m" -ForegroundColor Red; exit 1 }

$headers = @{ 'User-Agent' = 'logseq-installer'; 'Accept' = 'application/vnd.github+json' }
if ($Token) { $headers['Authorization'] = "Bearer $Token" }

# --- resolve release ---
Info "Checking release '$Tag' in $Repo ..."
try {
  if ($Tag -eq 'latest') {
    # /releases/latest skips prereleases, and this fork's builds are all marked
    # prerelease, so take the newest published release from the full list instead
    $all = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases?per_page=30" -Headers $headers
    $rel = $all | Where-Object { -not $_.draft } | Select-Object -First 1
    if (-not $rel) { Fail "no published release in $Repo" }
    Info "Latest published release: $($rel.tag_name)"
  } else {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$Tag" -Headers $headers
  }
} catch { Fail "cannot reach GitHub API: $($_.Exception.Message)" }

$installed = Test-Path $Exe
$mode      = if ($installed) { 'update' } else { 'install' }
Info "Mode: $mode"

# --- target architecture ---
if (-not $Arch) {
  # PROCESSOR_ARCHITECTURE describes the *process*, so a 32-bit PowerShell on an
  # x64 machine reports x86. PROCESSOR_ARCHITEW6432 is set only in that case and
  # carries the real machine arch.
  $machineArch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
  $Arch = if ($machineArch -eq 'ARM64') { 'arm64' } else { 'x64' }
}
Info "Arch: $Arch"

# Releases carry every architecture, so the pattern must pin one: matching on
# 'win' alone picks whichever GitHub lists first, which is arm64.
function Select-Asset([string]$suffixPattern) {
  $patterns = @("-win-$Arch-.*$suffixPattern")
  # an arm64 machine runs the x64 build under emulation, so it beats failing
  if ($Arch -eq 'arm64') { $patterns += "-win-x64-.*$suffixPattern" }
  foreach ($p in $patterns) {
    $a = $rel.assets | Where-Object { $_.name -match $p } | Select-Object -First 1
    if ($a) {
      if ($p -match 'x64' -and $Arch -eq 'arm64') { Info "no arm64 asset; using the x64 build (emulated)" }
      return $a
    }
  }
  return $null
}

# pick asset: ZIP for in-place update, NSIS installer for first install
if ($installed) {
  $asset = Select-Asset '\.zip$'
  # pre-2.0.1 releases used a single-arch name (Logseq-<ver>-win.zip), always x64
  if (-not $asset -and $Arch -eq 'x64') {
    $asset = $rel.assets | Where-Object { $_.name -match '-win\.zip$' } | Select-Object -First 1
  }
  if (-not $asset) { Fail "no Windows $Arch .zip asset on release '$($rel.tag_name)'" }
} else {
  $asset = Select-Asset 'nsis\.exe$'
  if (-not $asset) { $asset = Select-Asset '\.exe$' }
  if (-not $asset) { Fail "no Windows $Arch installer .exe on release '$($rel.tag_name)'" }
}
Info "Asset: $($asset.name)  ($([math]::Round($asset.size/1MB,1)) MB, updated $($asset.updated_at))"

# --- up-to-date check ---
$assetId = "$mode|$($rel.tag_name)|$($asset.id)|$($asset.updated_at)|$($asset.size)"
if (-not $Force -and (Test-Path $Marker) -and ((Get-Content $Marker -Raw).Trim() -eq $assetId)) {
  Info "Already up to date. Use -Force to reinstall."; exit 0
}

# --- download ---
$tmp = Join-Path $env:TEMP $asset.name
Info "Downloading ..."
$oldPref = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
try { Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tmp -Headers @{ 'User-Agent' = 'logseq-installer' } }
catch { Fail "download failed: $($_.Exception.Message)" }
finally { $ProgressPreference = $oldPref }
if ((Get-Item $tmp).Length -ne $asset.size) { Fail "downloaded size mismatch; aborting." }
Info "Downloaded OK."

# --- close any running instance ---
$running = Get-Process Logseq -ErrorAction SilentlyContinue
if ($running) { Info "Closing running Logseq ..."; $running | Stop-Process -Force; Start-Sleep -Seconds 3 }

if (-not $installed) {
  # --- first install via NSIS (silent, per-user) ---
  Info "Installing ..."
  Start-Process -FilePath $tmp -ArgumentList '/S' -Wait
  for ($i=0; $i -lt 30 -and -not (Test-Path $Exe); $i++) { Start-Sleep -Seconds 1 }
  if (-not (Test-Path $Exe)) { Fail "install finished but $Exe not found." }
} else {
  # --- in-place update from ZIP (keeps taskbar pin / shortcut) ---
  $tmpDir = Join-Path $env:TEMP ("logseq-update-" + [guid]::NewGuid().ToString('N'))
  Info "Extracting ..."
  Expand-Archive -Path $tmp -DestinationPath $tmpDir -Force
  $top = Get-ChildItem $tmpDir
  $srcRoot = if ($top.Count -eq 1 -and $top[0].PSIsContainer) { $top[0].FullName } else { $tmpDir }
  if (-not (Test-Path (Join-Path $srcRoot 'Logseq.exe'))) { Fail "archive has no Logseq.exe at root." }
  Info "Updating files in place ..."
  try { Copy-Item -Path (Join-Path $srcRoot '*') -Destination $InstallDir -Recurse -Force }
  catch { Fail "copy failed (a file locked?): $($_.Exception.Message)" }
  Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}

# --- record + report ---
Set-Content -Path $Marker -Value $assetId -NoNewline
$v = (Get-Item $Exe).VersionInfo
Info "Done: ProductName=$($v.ProductName)  FileVersion=$($v.FileVersion)  at $InstallDir"
Remove-Item $tmp -Force -ErrorAction SilentlyContinue
if ($mode -eq 'install') { Info "Tip: right-click Logseq in the Start menu (or its taskbar button) -> Pin to taskbar." }

if (-not $NoRelaunch) { Info "Launching Logseq ..."; Start-Process -FilePath $Exe | Out-Null }
