# Drive an Android TV over ADB while testing: source it, then send keys and take screenshots.
#
#   TV=192.168.1.7 . tools/tv.sh       # TV defaults to 192.168.1.7 (port 5555)
#   tv_up                              # connect (wakes a TV that dropped off in standby)
#   k DPAD_DOWN DPAD_CENTER            # key events, 0.7 s apart
#   s home 3                           # wait 3 s, screenshot to /tmp/t_home.png and a small /tmp/t_homes.png
#   tv_search 'Reacher on TV'          # what Google Assistant sends: opens our search with that text
#   tv_playing com.netflix.ninja       # media session state + title (DRM apps screenshot black: use this)
#   tv_top                             # the activity on screen
#   tv_perf                            # frame times and memory of the home screen (reset first: tv_perf reset)
TV="${TV:-192.168.1.7}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb -s $TV:5555"
tv_up(){ nc -z -G 3 "$TV" 5555 && ${ADB%% -s*} connect "$TV:5555" >/dev/null; $ADB get-state; }
k(){ for x in "$@"; do $ADB shell input keyevent "$x"; sleep 0.7; done; }
s(){ sleep "${2:-2}"; $ADB exec-out screencap -p > "/tmp/t_$1.png"; sips -Z 1100 "/tmp/t_$1.png" --out "/tmp/t_$1s.png" >/dev/null 2>&1 || cp "/tmp/t_$1.png" "/tmp/t_$1s.png"; }
tv_search(){ $ADB shell "am start -a android.intent.action.SEARCH --es query '$1' -n io.freemytv/.HomeActivity" >/dev/null; }
tv_playing(){ $ADB shell dumpsys media_session | grep -A16 "package=$1" | grep -E "state=PlaybackState|description" | head -2; }
tv_top(){ $ADB shell dumpsys activity activities | grep -m1 mResumedActivity | grep -oE "u0 [^ ]+"; }
tv_perf(){
  if [ "$1" = reset ]; then $ADB shell dumpsys gfxinfo io.freemytv:home reset >/dev/null; return; fi
  $ADB shell dumpsys gfxinfo io.freemytv:home | grep -E "Total frames|Janky frames:|50th perc|90th perc|99th perc" | head -5
  $ADB shell dumpsys meminfo io.freemytv:home | grep -E "TOTAL PSS|Native Heap:|Java Heap:"
}
