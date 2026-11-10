#!/usr/bin/env bash
# MyTV installer for macOS and Linux: puts the launcher on an Android TV over the network and sets up everything the
# family features need (the key helper, its permission, the TCL auto-start allowance), so nothing is left to do by hand.
#   curl -fsSL https://github.com/iResultMan/MyTV-launcher/releases/latest/download/install-mytv.sh | bash
#   bash install-mytv.sh 192.168.1.50          (the TV's address; asked when missing)
# MYTV_APK=/path/MyTV.apk installs a file you already have instead of downloading the release.
set -euo pipefail

REPO=iResultMan/MyTV-launcher
BASE=https://github.com/$REPO/releases/latest/download
PKG=com.gathin.tvhome
WORK=${MYTV_HOME:-$HOME/.mytv}
MIN_SDK=26
mkdir -p "$WORK"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

fetch() {   # fetch URL FILE
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else die "need curl or wget to download $1"; fi
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# --- the TV's address
TV=${1:-${TV_HOST:-}}
if [ -z "$TV" ]; then
  [ -r /dev/tty ] || die "give the TV's IP address: bash install-mytv.sh 192.168.1.50"
  printf "The TV's IP address (Settings > Network on the TV), for example 192.168.1.50: " > /dev/tty
  read -r TV < /dev/tty
fi
case "$TV" in *:*) ;; *) TV=$TV:5555 ;; esac

# --- adb: the one on PATH, or Google's platform-tools downloaded once into ~/.mytv
ADB=$(command -v adb || true)
if [ -z "$ADB" ]; then
  ADB=$WORK/platform-tools/adb
  if [ ! -x "$ADB" ]; then
    case "$(uname -s)" in Darwin) OS=darwin ;; Linux) OS=linux ;; *) die "unsupported system; on Windows use install-mytv.ps1" ;; esac
    command -v unzip >/dev/null 2>&1 || die "need unzip to unpack Google's platform-tools"
    say "Downloading Google's platform-tools (adb) ..."
    fetch "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip" "$WORK/platform-tools.zip"
    unzip -q -o "$WORK/platform-tools.zip" -d "$WORK"
    rm -f "$WORK/platform-tools.zip"
  fi
fi

# --- the APK: a local file, or the latest release checked against its published hash
APK=${MYTV_APK:-}
[ -z "$APK" ] && [ -f ./MyTV.apk ] && APK=./MyTV.apk
if [ -z "$APK" ]; then
  APK=$WORK/MyTV.apk
  say "Downloading MyTV ..."
  fetch "$BASE/MyTV.apk" "$APK"
  fetch "$BASE/MyTV.apk.sha256" "$WORK/MyTV.apk.sha256"
  WANT=$(cut -d' ' -f1 "$WORK/MyTV.apk.sha256" | tr -d '\r')
  [ "$(sha256 "$APK")" = "$WANT" ] || { rm -f "$APK"; die "the downloaded MyTV.apk does not match its published checksum; nothing was installed"; }
fi
[ -f "$APK" ] || die "no such file: $APK"

# --- connect
say "Connecting to $TV ..."
"$ADB" connect "$TV" >/dev/null 2>&1 || true
STATE=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  STATE=$("$ADB" -s "$TV" get-state 2>/dev/null || true)
  [ "$STATE" = device ] && break
  sleep 2
done
if [ "$STATE" != device ]; then
  die "the TV did not answer at $TV. Turn on Developer options > USB debugging (or Network debugging) on the TV, \
check the address, and accept the 'Allow debugging?' question on the TV screen (tick 'Always allow'), then run this again."
fi
tv() { "$ADB" -s "$TV" "$@" </dev/null; }

SDK=$(tv shell getprop ro.build.version.sdk | tr -dc '0-9')
if [ -n "$SDK" ] && [ "$SDK" -lt "$MIN_SDK" ]; then
  die "this TV runs Android SDK $SDK and MyTV needs $MIN_SDK or newer; nothing was changed."
fi

# --- install
say "Installing MyTV ..."
OUT=$(tv install -r "$APK" 2>&1 || true)
printf '%s\n' "$OUT"
case "$OUT" in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_FAILED_VERSION_DOWNGRADE*)
    die "another build of MyTV with a different signature (or a newer version) is on the TV. Remove it on the TV (Settings > Apps > MyTV > Uninstall; its settings go with it), then run this again." ;;
  *) die "the TV refused the install (see above); nothing else was changed." ;;
esac

# --- everything the helper needs, in one script run on the TV
SETUP=$WORK/mytv-setup.sh
cat > "$SETUP" <<'TVSCRIPT'
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
TVSCRIPT
tv push "$SETUP" /data/local/tmp/mytv-setup.sh >/dev/null
RESULT=$(tv shell sh /data/local/tmp/mytv-setup.sh 2>&1 | tr -d '\r' || true)
tv shell rm -f /data/local/tmp/mytv-setup.sh >/dev/null 2>&1 || true
grep -q '^mytv-restore-granted$' <<<"$RESULT" || say "WARNING: the permission that lets MyTV repair its helper was not granted."
if grep -q '^mytv-service-bound$' <<<"$RESULT"; then say "Key helper: running."
else say "WARNING: the key helper is not running yet. It starts by itself after the TV restarts; if the gear double tap and the children's lock still do nothing, run this installer again."; fi
if grep -q '^mytv-home-ours$' <<<"$RESULT"; then say "Home button: opens MyTV."
else say "NOTE: press Home on the remote and, if the TV asks which home screen to use, choose MyTV and 'Always'."; fi
say "Done. MyTV is on the TV; the old launcher is untouched and can be chosen again at any time."
