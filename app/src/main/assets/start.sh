#!/system/bin/sh
# ---------------------------------------------------------------------------
# GameMode Pro helper launcher (v2)
#
# Run from adb:   adb shell sh /sdcard/start.sh [command] [options]
#
# Commands:
#   start     (default) start the helper and keep it alive
#   stop      stop the helper and undo the system tweaks this script made
#   restart   stop, then start
#   status    show whether the helper is running
#   log       show the last 40 log lines   (log -f  to follow)
#   help      show this text
#
# Options for start / restart:
#   --no-lock   don't freeze device_config (leave Android free to re-sync it)
#   --keep      with stop: leave the system tweaks in place
# ---------------------------------------------------------------------------

VERSION="2.0"
PKG="com.diffmemorymas.gamemode.pro"
SERVER_CLASS="com.diffmemorymas.gamemode.pro.server.Server"
BASE="${BASE:-/data/local/tmp/adbwifi}"
LAUNCHER="$BASE/launcher.sh"
PIDFILE="$BASE/supervisor.pid"
LOG="$BASE/server.log"
STATE="$BASE/tweaks.state"
MAX_LOG_BYTES=524288

CHILD=""

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG" 2>/dev/null
}

say() {
  echo "$*"
}

get_apk() {
  pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -n1
}

supervisor_pid() {
  [ -f "$PIDFILE" ] || return 1
  PID=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$PID" ] || return 1
  kill -0 "$PID" 2>/dev/null || return 1
  echo "$PID"
}

server_running() {
  pgrep -f "$SERVER_CLASS" >/dev/null 2>&1
}

kill_server() {
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "$SERVER_CLASS" 2>/dev/null
  else
    for P in $(pgrep -f "$SERVER_CLASS" 2>/dev/null); do
      [ "$P" != "$$" ] && kill "$P" 2>/dev/null
    done
  fi
}

rotate_log() {
  [ -f "$LOG" ] || return 0
  SIZE=$(wc -c < "$LOG" 2>/dev/null)
  if [ "${SIZE:-0}" -gt "$MAX_LOG_BYTES" ]; then
    mv -f "$LOG" "$LOG.1" 2>/dev/null
    log "log rotated"
  fi
}

# ---------------------------------------------------------------------------
# System tweaks (Android 12+): save the old values first so stop can undo them
# ---------------------------------------------------------------------------
apply_tweaks() {
  LOCK="$1"
  SDK=$(getprop ro.build.version.sdk 2>/dev/null)
  say "Android SDK: ${SDK:-unknown}"
  if ! [ "${SDK:-0}" -ge 31 ] 2>/dev/null; then
    say "Android 11 or older: no phantom process tweaks needed"
    return 0
  fi

  if [ ! -f "$STATE" ]; then
    OLD_MON=$(settings get global settings_enable_monitor_phantom_procs 2>/dev/null)
    OLD_MAX=$(device_config get activity_manager max_phantom_processes 2>/dev/null)
    {
      echo "mon=${OLD_MON:-null}"
      echo "max=${OLD_MAX:-null}"
      echo "lock=0"
    } > "$STATE"
  fi

  say "Disabling the phantom process killer"
  if settings put global settings_enable_monitor_phantom_procs false 2>/dev/null; then
    say "  settings_enable_monitor_phantom_procs = false"
  else
    say "  could not change settings (continuing)"
  fi

  if device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null; then
    say "  max_phantom_processes = unlimited"
  else
    say "  could not change device_config (continuing)"
  fi

  if [ "$LOCK" = "1" ]; then
    if device_config set_sync_disabled_for_tests persistent 2>/dev/null; then
      sed -i 's/^lock=.*/lock=1/' "$STATE" 2>/dev/null
      say "  device_config locked (stop will unlock it)"
    fi
  fi
}

revert_tweaks() {
  [ -f "$STATE" ] || return 0
  MON=$(sed -n 's/^mon=//p' "$STATE")
  MAX=$(sed -n 's/^max=//p' "$STATE")
  LOCKED=$(sed -n 's/^lock=//p' "$STATE")

  if [ "$LOCKED" = "1" ]; then
    device_config set_sync_disabled_for_tests none 2>/dev/null && say "  device_config unlocked"
  fi
  if [ -n "$MON" ] && [ "$MON" != "null" ]; then
    settings put global settings_enable_monitor_phantom_procs "$MON" 2>/dev/null
  else
    settings delete global settings_enable_monitor_phantom_procs 2>/dev/null
  fi
  if [ -n "$MAX" ] && [ "$MAX" != "null" ]; then
    device_config put activity_manager max_phantom_processes "$MAX" 2>/dev/null
  else
    device_config delete activity_manager max_phantom_processes 2>/dev/null
  fi
  rm -f "$STATE"
  say "  system tweaks restored"
}

# ---------------------------------------------------------------------------
# Supervisor (internal): keeps the helper alive with backoff on crash loops
# ---------------------------------------------------------------------------
stop_child() {
  if [ -n "$CHILD" ]; then
    kill "$CHILD" 2>/dev/null
    CHILD=""
  fi
  kill_server
}

cmd_supervise() {
  echo $$ > "$PIDFILE"
  trap 'log "supervisor stopping"; stop_child; rm -f "$PIDFILE"; exit 0' TERM INT HUP
  echo -1000 > /proc/self/oom_score_adj 2>/dev/null

  FAILS=0
  log "supervisor started (pid $$, v$VERSION)"

  while :; do
    APK=$(get_apk)
    if [ -z "$APK" ]; then
      log "APK for $PKG not found, retrying in 10s"
      sleep 10 &
      wait $!
      continue
    fi

    rotate_log
    T0=$(date +%s)
    log "starting helper from $APK"

    ANDROID_DATA="$BASE" CLASSPATH="$APK" \
      app_process -Djava.class.path="$APK" /system/bin \
      --nice-name=adbwifi_server "$SERVER_CLASS" "$PKG" >> "$LOG" 2>&1 &
    CHILD=$!
    wait "$CHILD"
    CODE=$?
    CHILD=""

    RAN=$(( $(date +%s) - T0 ))
    if [ "$RAN" -ge 60 ]; then FAILS=0; else FAILS=$((FAILS + 1)); fi
    DELAY=$((FAILS * 2))
    [ "$DELAY" -lt 2 ] && DELAY=2
    [ "$DELAY" -gt 60 ] && DELAY=60

    log "helper exited (code $CODE) after ${RAN}s, restarting in ${DELAY}s"
    sleep "$DELAY" &
    wait $!
  done
}

# ---------------------------------------------------------------------------
# User commands
# ---------------------------------------------------------------------------
cmd_start() {
  LOCK=1
  for A in "$@"; do
    [ "$A" = "--no-lock" ] && LOCK=0
  done

  UID_NOW=$(id -u 2>/dev/null)
  say "GameMode Pro helper v$VERSION"
  say "Running as uid ${UID_NOW:-?} ($(id -un 2>/dev/null))"
  if [ "$UID_NOW" != "2000" ] && [ "$UID_NOW" != "0" ]; then
    say "Warning: this should run through 'adb shell' (uid 2000) or root"
  fi

  APK=$(get_apk)
  if [ -z "$APK" ]; then
    say "Error: $PKG is not installed"
    return 1
  fi
  say "APK: $APK"

  if ! command -v app_process >/dev/null 2>&1; then
    say "Error: app_process not found on this device"
    return 1
  fi

  mkdir -p "$BASE" 2>/dev/null
  chmod 755 "$BASE" 2>/dev/null

  if PID=$(supervisor_pid); then
    say "Already running (supervisor pid $PID). Use 'restart' to restart it."
    return 0
  fi

  apply_tweaks "$LOCK"

  kill_server
  sleep 1

  SELF="$0"
  if [ -r "$SELF" ]; then
    cp -f "$SELF" "$LAUNCHER" 2>/dev/null
  fi
  if [ ! -r "$LAUNCHER" ]; then
    say "Error: could not install launcher copy at $LAUNCHER"
    return 1
  fi
  chmod 755 "$LAUNCHER" 2>/dev/null

  if command -v setsid >/dev/null 2>&1; then
    setsid nohup sh "$LAUNCHER" _supervise >/dev/null 2>&1 &
    say "Supervisor launched (setsid)"
  else
    nohup sh "$LAUNCHER" _supervise >/dev/null 2>&1 &
    say "Supervisor launched (nohup)"
  fi

  I=0
  while [ "$I" -lt 10 ]; do
    sleep 1
    if server_running; then
      say "Helper is running"
      say "  status : sh $LAUNCHER status"
      say "  log    : sh $LAUNCHER log -f"
      say "  stop   : sh $LAUNCHER stop"
      return 0
    fi
    I=$((I + 1))
  done

  say "Helper did not start within 10s. Last log lines:"
  tail -n 30 "$LOG" 2>/dev/null
  return 1
}

cmd_stop() {
  KEEP=0
  for A in "$@"; do
    [ "$A" = "--keep" ] && KEEP=1
  done

  if PID=$(supervisor_pid); then
    kill "$PID" 2>/dev/null
    I=0
    while [ "$I" -lt 10 ] && kill -0 "$PID" 2>/dev/null; do
      sleep 1
      I=$((I + 1))
    done
    kill -9 "$PID" 2>/dev/null
  fi
  kill_server
  rm -f "$PIDFILE"
  say "Helper stopped"

  if [ "$KEEP" = "0" ]; then
    revert_tweaks
  else
    say "System tweaks left in place (--keep)"
  fi
}

cmd_status() {
  if PID=$(supervisor_pid); then
    say "Supervisor: running (pid $PID)"
  else
    say "Supervisor: not running"
  fi
  if server_running; then
    say "Helper    : running"
  else
    say "Helper    : not running"
  fi
  if [ -f "$LOG" ]; then
    say "--- last log lines ---"
    tail -n 5 "$LOG"
  fi
}

cmd_log() {
  if [ "$1" = "-f" ]; then
    tail -f "$LOG"
  else
    tail -n 40 "$LOG" 2>/dev/null || say "No log yet"
  fi
}

cmd_help() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

CMD="${1:-start}"
[ $# -gt 0 ] && shift

case "$CMD" in
  start|--no-lock)
    [ "$CMD" = "--no-lock" ] && set -- --no-lock "$@"
    cmd_start "$@"
    ;;
  stop)       cmd_stop "$@" ;;
  restart)    cmd_stop "$@" ; sleep 1 ; cmd_start "$@" ;;
  status)     cmd_status ;;
  log)        cmd_log "$@" ;;
  _supervise) cmd_supervise ;;
  help|-h|--help) cmd_help ;;
  *)          say "Unknown command: $CMD"; cmd_help; exit 2 ;;
esac
