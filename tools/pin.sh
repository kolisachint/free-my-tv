#!/bin/sh
# The home PIN from a computer (the parent's way back). Needs adb and the TV's network debugging on.
#
#   tools/pin.sh 192.168.1.7 status      # PIN on/off, key service on/off
#   tools/pin.sh 192.168.1.7 remove      # forgot the PIN: remove it (settings kept), then press Home on the TV
#   tools/pin.sh 192.168.1.7 guard       # turn the lock's key service back on (Android turns it off at every app update)
#   tools/pin.sh 192.168.1.7 unguard     # turn the key service off (the PIN screen stays, apps are no longer blocked)
#   tools/pin.sh 192.168.1.7 original    # last resort: give the TV its Google TV home back (Free My TV stays installed)
set -e
TV="${1:?TV address, like 192.168.1.7}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
"$ADB" connect "$TV:5555" >/dev/null 2>&1 || true
A="$ADB -s $TV:5555"
SERVICE=io.freemytv/io.freemytv.RemoteKeyService

case "${2:-status}" in
  status)
    $A shell content call --uri content://io.freemytv.recovery --method status
    case "$($A shell settings get secure enabled_accessibility_services)" in
      *"$SERVICE"*) echo "Key service: on" ;;
      *) echo "Key service: OFF (run: tools/pin.sh $TV guard)" ;;
    esac ;;
  remove)
    $A shell content call --uri content://io.freemytv.recovery --method remove_pin ;;
  guard)
    cur="$($A shell settings get secure enabled_accessibility_services | tr -d '\r')"
    case "$cur" in *"$SERVICE"*) ;; null|"") cur="$SERVICE" ;; *) cur="$cur:$SERVICE" ;; esac
    $A shell settings put secure enabled_accessibility_services "$cur"
    $A shell settings put secure accessibility_enabled 1
    echo "Key service: on" ;;
  unguard)
    cur="$($A shell settings get secure enabled_accessibility_services | tr -d '\r' | tr ':' '\n' | grep -v "^$SERVICE\$" | paste -sd: -)"
    if [ -z "$cur" ]; then $A shell settings delete secure enabled_accessibility_services >/dev/null
    else $A shell settings put secure enabled_accessibility_services "$cur"; fi
    echo "Key service: off" ;;
  original)
    $A shell content call --uri content://io.freemytv.recovery --method remove_pin
    $A shell pm enable com.google.android.apps.tv.launcherx >/dev/null
    home="$($A shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
      | tr -d '\r' | grep -E '^ *com\.google\.android\.apps\.tv\.launcherx/' | head -1 | tr -d ' ')"
    [ -n "$home" ] || { echo "Google TV's home not found"; exit 1; }
    $A shell cmd package set-home-activity "$home"
    $A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
    echo "Google TV home is back. Free My TV > Home Screen > Free My TV switches again." ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
