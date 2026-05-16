#!/bin/sh

JAVA_BIN="$1"
CLASSPATH="$2"
MAIN_CLASS="$3"
shift 3

child_pid=""

terminate_child() {
  reason="$1"
  echo "JCEF spike wrapper received ${reason}; killing forked JVM without native CEF shutdown." >&2
  if [ -n "$child_pid" ]; then
    kill -KILL "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
  exit 0
}

trap 'terminate_child SIGINT' INT
trap 'terminate_child SIGTERM' TERM
trap 'terminate_child SIGHUP' HUP

(
  # Inherit ignored terminal signals in the Java child. The wrapper owns Ctrl+C
  # handling and uses SIGKILL to avoid macOS/JCEF native shutdown traps in this
  # dev-only spike.
  trap '' INT TERM HUP
  exec "$JAVA_BIN" \
    --enable-preview \
    --add-exports java.base/java.lang=ALL-UNNAMED \
    --add-exports java.desktop/sun.awt=ALL-UNNAMED \
    --add-opens java.desktop/sun.awt=ALL-UNNAMED \
    --add-exports java.desktop/sun.lwawt=ALL-UNNAMED \
    --add-opens java.desktop/sun.lwawt=ALL-UNNAMED \
    --add-exports java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
    --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
    --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
    -classpath "$CLASSPATH" \
    "$MAIN_CLASS" "$@"
) &
child_pid="$!"

wait "$child_pid"
status="$?"
child_pid=""
exit "$status"
