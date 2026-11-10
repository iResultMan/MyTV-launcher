# MyTV installer for Windows: puts the launcher on an Android TV over the network and sets up everything the family
# features need (the key helper, its permission, the TCL auto-start allowance), so nothing is left to do by hand.
#   Double-click install-mytv.cmd, or in PowerShell:  .\install-mytv.ps1 192.168.1.50
# $env:MYTV_APK = 'C:\path\MyTV.apk' installs a file you already have instead of downloading the release.
param([string]$Tv = $env:TV_HOST)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo = 'iResultMan/MyTV-launcher'
$Base = "https://github.com/$Repo/releases/latest/download"
$Pkg = 'com.gathin.tvhome'
$Work = if ($env:MYTV_HOME) { $env:MYTV_HOME } else { Join-Path $env:USERPROFILE '.mytv' }
$MinSdk = 26
New-Item -ItemType Directory -Force -Path $Work | Out-Null

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# --- the TV's address
if (-not $Tv) { $Tv = Read-Host "The TV's IP address (Settings > Network on the TV), for example 192.168.1.50" }
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }

# --- adb: the one on PATH, or Google's platform-tools downloaded once into .mytv
$cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
if ($cmd) { $Adb = $cmd.Source } else {
  $Adb = Join-Path $Work 'platform-tools\adb.exe'
  if (-not (Test-Path $Adb)) {
    Write-Host "Downloading Google's platform-tools (adb) ..."
    $zip = Join-Path $Work 'platform-tools.zip'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $Work -Force
    Remove-Item $zip
  }
}

# --- the APK: a local file, or the latest release checked against its published hash
$Apk = $env:MYTV_APK
if (-not $Apk -and (Test-Path '.\MyTV.apk')) { $Apk = (Resolve-Path '.\MyTV.apk').Path }
if (-not $Apk) {
  $Apk = Join-Path $Work 'MyTV.apk'
  Write-Host 'Downloading MyTV ...'
  Invoke-WebRequest -UseBasicParsing -Uri "$Base/MyTV.apk" -OutFile $Apk
  $sumFile = Join-Path $Work 'MyTV.apk.sha256'
  Invoke-WebRequest -UseBasicParsing -Uri "$Base/MyTV.apk.sha256" -OutFile $sumFile
  $want = ((Get-Content $sumFile -Raw).Trim() -split '\s+')[0].ToLower()
  if ((Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower() -ne $want) {
    Remove-Item $Apk
    Fail 'the downloaded MyTV.apk does not match its published checksum; nothing was installed'
  }
}
if (-not (Test-Path $Apk)) { Fail "no such file: $Apk" }

# adb writes progress to stderr, which PowerShell 5 turns into errors: run it with errors tolerated, read the text.
function Tv {
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { (& $Adb -s $Tv @args 2>&1 | ForEach-Object { "$_" }) -join "`n" } finally { $ErrorActionPreference = $old }
}

# --- connect
Write-Host "Connecting to $Tv ..."
$old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& $Adb connect $Tv 2>&1 | Out-Null
$ErrorActionPreference = $old
$state = ''
for ($i = 0; $i -lt 15; $i++) {
  $state = (Tv get-state).Trim()
  if ($state -eq 'device') { break }
  Start-Sleep -Seconds 2
}
if ($state -ne 'device') {
  Fail "the TV did not answer at $Tv. Turn on Developer options > USB debugging (or Network debugging) on the TV, check the address, and accept the 'Allow debugging?' question on the TV screen (tick 'Always allow'), then run this again."
}

$sdk = ((Tv shell getprop ro.build.version.sdk) -replace '[^0-9]', '')
if ($sdk -and [int]$sdk -lt $MinSdk) { Fail "this TV runs Android SDK $sdk and MyTV needs $MinSdk or newer; nothing was changed." }

# --- install
Write-Host 'Installing MyTV ...'
$out = Tv install -r $Apk
Write-Host $out
if ($out -notmatch 'Success') {
  if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE') {
    Fail 'another build of MyTV with a different signature (or a newer version) is on the TV. Remove it on the TV (Settings > Apps > MyTV > Uninstall; its settings go with it), then run this again.'
  }
  Fail 'the TV refused the install (see above); nothing else was changed.'
}

# --- everything the helper needs, in one script run on the TV
$setup = @'
#--- TV script begin
PKG=com.gathin.tvhome
am start -W -n $PKG/.MainActivity
sleep 3
pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
pm grant $PKG android.permission.WRITE_SECURE_SETTINGS && echo mytv-restore-granted
cmd appops set $PKG AUTO_START allow 2>/dev/null
cmd appops set $PKG APP_AUTO_START allow 2>/dev/null
appops write-settings 2>/dev/null
svc=$PKG/.HomeKeyService
s=$(settings get secure enabled_accessibility_services)
case "$s" in
  *HomeKeyService*) settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 2; settings put secure enabled_accessibility_services "$s" ;;
  null|'') settings put secure enabled_accessibility_services $svc ;;
  *) settings put secure enabled_accessibility_services "$s:$svc" ;;
esac
settings put secure accessibility_enabled 1
sleep 2
dumpsys accessibility | grep -A6 'Bound services' | grep -q HomeKeyService && echo mytv-service-bound
cmd package set-home-activity --user 0 $PKG/.MainActivity
cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | grep -q "^$PKG/" && echo mytv-home-ours
#--- TV script end
'@
$setupFile = Join-Path $Work 'mytv-setup.sh'
# LF line endings and no byte-order mark: the TV's shell reads the file as it is.
[IO.File]::WriteAllText($setupFile, $setup.Replace("`r`n", "`n") + "`n", (New-Object Text.UTF8Encoding $false))
Tv push $setupFile /data/local/tmp/mytv-setup.sh | Out-Null
$result = (Tv shell sh /data/local/tmp/mytv-setup.sh) -replace "`r", ''
Tv shell rm -f /data/local/tmp/mytv-setup.sh | Out-Null
$lines = $result -split "`n"
if ($lines -notcontains 'mytv-restore-granted') { Write-Host 'WARNING: the permission that lets MyTV repair its helper was not granted.' -ForegroundColor Yellow }
if ($lines -contains 'mytv-service-bound') { Write-Host 'Key helper: running.' }
else { Write-Host 'WARNING: the key helper is not running yet. It starts by itself after the TV restarts; if the gear double tap and the children''s lock still do nothing, run this installer again.' -ForegroundColor Yellow }
if ($lines -contains 'mytv-home-ours') { Write-Host 'Home button: opens MyTV.' }
else { Write-Host "NOTE: press Home on the remote and, if the TV asks which home screen to use, choose MyTV and 'Always'." }
Write-Host 'Done. MyTV is on the TV; the old launcher is untouched and can be chosen again at any time.'
