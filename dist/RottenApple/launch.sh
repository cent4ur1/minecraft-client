#!/bin/bash
# RottenApple launcher for Lunar Client 1.8.9 (macOS / Linux).
# Run with bash: ./launch.sh   (first time: chmod +x launch.sh)
#
# Zero dependencies beyond a JDK and Lunar Client itself. Everything is
# resolved relative to this script, so the whole folder (launch.sh +
# RottenApple-Agent.jar + rottenapple-launcher.jar) works from anywhere:
# copy it anywhere and run ./launch.sh
#
# What it does:
#   1. Detects a running Lunar Client 1.8.9 JVM (jps preferred, ps fallback).
#   2. If not running, asks you to launch it (any stage is fine: menu,
#      singleplayer, or server), then waits for it.
#   3. Injects RottenApple-Agent.jar via the JDK Attach API, retrying while
#      the game JVM starts up. The agent itself waits for game classes, so
#      injection works at any stage, anywhere.
#   4. On failure, offers to collect a debug log bundle, and prints a
#      -javaagent fallback that always works.
#
# Usage:
#   ./launch.sh          detect + inject
#   ./launch.sh --logs   just collect a debug log bundle
#
# Build: gradle build   (output lands in dist/RottenApple/)

set -u

APP="RottenApple"
WANT_VERSION="1.8.9"
TIMEOUT_SECS=180
ATTACH_TIMEOUT_SECS=90

# ---------- portable paths: always relative to this script ----------
DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- run log (everything below goes to console AND this file) ----------
LOG_FILE="$DIR/RottenApple-launcher.log"
echo "===== RottenApple run: $(date '+%Y-%m-%d %H:%M:%S') =====" >>"$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

find_jar() {
    # $1 = exact filename, $@ rest = glob fallbacks (searched newest-first)
    local exact="$1"
    shift
    if [ -f "$DIR/$exact" ]; then
        echo "$DIR/$exact"
        return 0
    fi
    local pat hit
    for pat in "$@"; do
        # shellcheck disable=SC2086
        hit=$(ls -t $DIR/$pat 2>/dev/null | head -n 1)
        if [ -n "${hit:-}" ] && [ -f "$hit" ]; then
            echo "$hit"
            return 0
        fi
    done
    return 1
}

to_clipboard() {
    # stdin -> system clipboard (pbcopy / xclip / xsel). 0 on success.
    if [ "$(uname)" = "Darwin" ]; then
        pbcopy 2>/dev/null
    elif command -v xclip >/dev/null 2>&1; then
        xclip -selection clipboard 2>/dev/null
    elif command -v xsel >/dev/null 2>&1; then
        xsel --clipboard 2>/dev/null
    else
        return 1
    fi
}

collect_logs() {
    # Bundle launcher log + system snapshot into the portable folder.
    local ts tmpdir snap bundle
    ts="$(date '+%Y%m%d-%H%M%S')"
    tmpdir="$(mktemp -d -t rottenapple-logs.XXXXXX)"
    snap="$tmpdir/snapshot.txt"
    {
        echo "=== RottenApple debug snapshot $(date) ==="
        echo "--- uname ---"
        uname -a 2>&1
        echo "--- whoami ---"
        id 2>&1
        echo "--- launcher java ---"
        if [ -n "${JAVA_BIN:-}" ]; then "$JAVA_BIN" -version 2>&1; else echo "(java not resolved)"; fi
        echo "--- jps ---"
        if command -v jps >/dev/null 2>&1; then jps -lvm 2>&1 | cut -c1-400; else echo "(no jps)"; fi
        echo "--- ps (lunar) ---"
        ps aux 2>/dev/null | grep -i "[l]unar" | cut -c1-400 || true
        echo "--- hsperfdata ---"
        ls -la /tmp/hsperfdata_* "${TMPDIR:-/tmp}"/hsperfdata_* 2>&1 || true
        echo "--- agent jar ---"
        ls -la "${AGENT_JAR:-<not resolved>}" 2>&1 || true
    } >"$snap" 2>&1
    cp "$LOG_FILE" "$tmpdir/RottenApple-launcher.log" 2>/dev/null \
        || echo "(log copy failed)" >"$tmpdir/RottenApple-launcher.log"
    # FIRST option: straight to clipboard so the user can paste immediately.
    # If they take it, ask nothing else about logs.
    printf "Copy logs to clipboard for pasting? [Y/n]: "
    read -r toclip
    case "${toclip:-}" in
        [Nn]*) ;;
        *)
            clip_text="$tmpdir/clipboard.txt"
            {
                cat "$snap"
                echo ""
                echo "--- launcher log (last 200 lines) ---"
                tail -n 200 "$tmpdir/RottenApple-launcher.log" 2>/dev/null
            } >"$clip_text" 2>&1
            if to_clipboard <"$clip_text"; then
                echo "[$APP] logs copied — paste them anywhere."
                rm -rf "$tmpdir"
                return 0
            fi
            echo "[$APP] clipboard unavailable, falling back to a file bundle."
            ;;
    esac

    bundle="$DIR/RottenApple-logs-$ts.tar.gz"
    COPYFILE_DISABLE=1 tar -czf "$bundle" -C "$tmpdir" snapshot.txt RottenApple-launcher.log 2>/dev/null
    rm -rf "$tmpdir"
    echo "[$APP] logs saved: $bundle"
    if [ "$(uname)" = "Darwin" ]; then
        printf "Reveal in Finder? [y/N]: "
        read -r rev
        case "${rev:-}" in
            [Yy]*) open -R "$bundle" 2>/dev/null || true ;;
        esac
        printf "Copy path to clipboard? [y/N]: "
        read -r cpq
        case "${cpq:-}" in
            [Yy]*) printf "%s" "$bundle" | to_clipboard && echo "[$APP] path copied." || echo "[$APP] clipboard unavailable." ;;
        esac
    else
        echo "[$APP] attach $bundle to your message."
    fi
}

AGENT_JAR="$(find_jar "RottenApple-Agent.jar" \
    "dist/RottenApple/RottenApple-Agent*.jar" \
    "build/libs/RottenApple-Agent*.jar")" || {
    echo "[$APP] RottenApple-Agent.jar not found. Run 'gradle build' first."
    exit 1
}
INJECTOR_JAR="$(find_jar "rottenapple-launcher.jar" \
    "dist/RottenApple/rottenapple-launcher*.jar" \
    "build/libs/rottenapple-launcher*.jar")" || {
    echo "[$APP] rottenapple-launcher.jar not found. Run 'gradle build' first."
    exit 1
}

# ---------- java ----------
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN="$(command -v java || true)"
fi
if [ -z "${JAVA_BIN:-}" ]; then
    echo "[$APP] No 'java' on PATH. Install a JDK 11+ (e.g. Temurin) and retry."
    exit 1
fi

if [ "${1:-}" = "--logs" ]; then
    collect_logs
    exit 0
fi

# ---------- detection ----------
jvm_lines() {
    # Prints one line per Lunar JVM, pid first.
    if command -v jps >/dev/null 2>&1; then
        jps -lvm 2>/dev/null | grep -i "lunar" || true
    else
        # No JDK tools on PATH: fall back to ps, normalized to pid-first.
        ps aux 2>/dev/null | grep -i "[l]unar" | grep -i "java" \
            | awk '{ pid=$2; $1=$2=""; sub(/^ +/, ""); print pid " " $0 }' || true
    fi
}

pid_of() {
    # $1 = line -> prints pid. jps lines start with the pid; `ps aux`
    # lines start with the username (pid is the 2nd field).
    echo "$1" | awk '{ if ($1 ~ /^[0-9]+$/) print $1; else print $2; }'
}

best_pid() {
    # Highest score wins: 1.8.9 (+2), minecraft-ish (+2), genesis (+1).
    # Used to (re)pick the game JVM as new processes appear.
    jvm_lines | awk '
        { line=$0; score=0;
          if (line ~ /1\.8\.9/) score+=2;
          low=tolower(line);
          if (low ~ /minecraft/) score+=2;
          if (low ~ /genesis/) score+=1;
          if (score > best) { best=score; pid=$1 } }
        END { if (best > 0) print pid }'
}

echo "[$APP] Looking for Lunar Client ($WANT_VERSION)..."

LINES="$(jvm_lines)"
if [ -z "$LINES" ]; then
    echo "[$APP] Lunar Client doesn't appear to be running."
    echo "      1) Open Lunar Client and start Minecraft $WANT_VERSION"
    echo "         (any stage is fine: menu, singleplayer, or server)."
    echo "      2) Then come back here."
    printf "Type 'open' to try auto-opening Lunar (macOS), Enter to watch, or 'q' to quit: "
    read -r ans
    case "${ans:-}" in
        [Oo]*)
            if [ "$(uname)" = "Darwin" ]; then
                open -a "Lunar Client" 2>/dev/null \
                    || echo "[$APP] auto-open failed, please open it manually."
            else
                echo "[$APP] auto-open is macOS-only, please open it manually."
            fi
            ;;
        [Qq]*)
            exit 0
            ;;
    esac
    echo "[$APP] Watching for Lunar (up to ${TIMEOUT_SECS}s, Ctrl+C aborts)..."
    waited=0
    while [ "$waited" -lt "$TIMEOUT_SECS" ]; do
        sleep 5
        waited=$((waited + 5))
        LINES="$(jvm_lines)"
        if [ -n "$LINES" ]; then
            break
        fi
        echo "  ... ${waited}s"
    done
    if [ -z "$LINES" ]; then
        echo "[$APP] Timed out. Start Lunar $WANT_VERSION, then run ./launch.sh again."
        exit 1
    fi
fi

# ---------- pick pid (prefer the 1.8.9 *game* JVM, not the launcher) ----------
PREFERRED="$(echo "$LINES" | grep "$WANT_VERSION" || true)"
POOL="$LINES"
if [ -n "$PREFERRED" ]; then
    GAME="$(echo "$PREFERRED" | grep -i "minecraft\|net.minecraft.client" || true)"
    if [ -n "$GAME" ]; then
        POOL="$GAME"
    else
        POOL="$PREFERRED"
    fi
else
    echo "[$APP] warning: found Lunar JVM(s) but none clearly say $WANT_VERSION:"
    echo "$LINES"
    printf "Continue anyway? [y/N]: "
    read -r go
    case "${go:-}" in
        [Yy]*) ;;
        *) echo "[$APP] aborted."; exit 1 ;;
    esac
fi

echo "[$APP] candidate JVM(s):"
echo "$POOL" | awk '{ printf "  [%d] %s\n", NR, substr($0, 1, 220) }'

count="$(echo "$POOL" | grep -c . || true)"
PID=""
if [ "$count" = "1" ]; then
    PID="$(pid_of "$POOL")"
    echo "[$APP] using pid $PID"
else
    printf "Pick one [1-%s]: " "$count"
    read -r choice
    case "${choice:-}" in
        ''|*[!0-9]*) echo "[$APP] invalid choice."; exit 1 ;;
    esac
    if [ "$choice" -lt 1 ] || [ "$choice" -gt "$count" ]; then
        echo "[$APP] invalid choice."
        exit 1
    fi
    line="$(echo "$POOL" | sed -n "${choice}p")"
    PID="$(pid_of "$line")"
fi

case "${PID:-}" in
    ''|*[!0-9]*) echo "[$APP] could not determine pid. Is Lunar fully started?"; exit 1 ;;
esac
if ! kill -0 "$PID" 2>/dev/null; then
    echo "[$APP] pid $PID is gone. Re-run ./launch.sh once Lunar is up."
    exit 1
fi

# ---------- validate: is it really an attachable game JVM? ----------
CMD="$(ps -p "$PID" -o command= 2>/dev/null || true)"
echo "[$APP] target: $(echo "$CMD" | cut -c1-220)"
if echo "$CMD" | grep -q -- "-javaagent:.*[Rr]otten"; then
    # Position matters: the JVM ignores -javaagent placed AFTER the main class.
    AGENT_POS="$(echo "$CMD" | tr ' ' '\n' | grep -n -- "-javaagent:.*[Rr]otten" | head -n 1 | cut -d: -f1)"
    MAIN_POS="$(echo "$CMD" | tr ' ' '\n' | grep -n -E "genesis.Genesis|minecraft.client.main.Main" | head -n 1 | cut -d: -f1)"
    if [ -n "${AGENT_POS:-}" ] && [ -n "${MAIN_POS:-}" ] && [ "$AGENT_POS" -lt "$MAIN_POS" ]; then
        echo "[$APP] RottenApple agent is in this JVM's startup flags (arg $AGENT_POS, main class $MAIN_POS) — nothing to inject."
        echo "[$APP] Launch/stay in Lunar $WANT_VERSION and press P or INSERT (Fn+Return on MacBooks)."
        exit 0
    else
        echo "[$APP] WARNING: RottenApple -javaagent found at token ${AGENT_POS:-?} but main class at token ${MAIN_POS:-?}."
        echo "      The JVM ignores -javaagent placed AFTER the main class, so the agent never loads."
        echo "      Lunar must put JVM arguments BEFORE the main class."
    fi
fi
case "$CMD" in
    *[Jj][Aa][Vv][Aa]*|*jvm*|*JVM*) ;;
    *)
        echo "[$APP] pid $PID does not look like a Java process."
        echo "[$APP] Attach needs the *game* JVM, not the Lunar launcher app."
        echo "[$APP] Make sure Lunar $WANT_VERSION is started, then retry."
        exit 1
        ;;
esac

# Definitive check: did Lunar disable the attach mechanism?
case "$CMD" in
    *DisableAttachMechanism*)
        echo "[$APP] target runs with -XX:+DisableAttachMechanism: dynamic attach is"
        echo "      impossible by design. Skipping retries, jumping to the fallback."
        ATTACH_TIMEOUT_SECS=0
        ;;
esac

# The attach handshake uses files in the target's TMPDIR. If ours differs,
# run the injector with the target's TMPDIR (read from its environment).
TARGET_TMPDIR="$(ps eww -p "$PID" 2>/dev/null | tr ' ' '\n' | grep '^TMPDIR=' | tail -n 1 | cut -d= -f2-)"
INJECTOR_TMPDIR=""
if [ -n "${TARGET_TMPDIR:-}" ] && [ "$TARGET_TMPDIR" != "${TMPDIR:-/tmp}" ]; then
    echo "[$APP] target TMPDIR differs (target: $TARGET_TMPDIR, ours: ${TMPDIR:-/tmp})."
    echo "      Running the injector with the target's TMPDIR."
    INJECTOR_TMPDIR="$TARGET_TMPDIR"
fi

# Architecture check: cross-arch attach is unreliable on macOS.
TARGET_JAVA="$(echo "$CMD" | awk '{print $1}')"
MY_ARCH="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | grep 'os.arch' | awk '{print $3}')"
TARGET_ARCH=""
if [ -n "${TARGET_JAVA:-}" ] && [ -x "$TARGET_JAVA" ]; then
    TARGET_ARCH="$("$TARGET_JAVA" -XshowSettings:properties -version 2>&1 | grep 'os.arch' | awk '{print $3}')"
fi
echo "[$APP] arch: machine=$(uname -m) launcher-jvm=${MY_ARCH:-unknown} target-jvm=${TARGET_ARCH:-unknown}"
if [ -n "${TARGET_ARCH:-}" ] && [ -n "${MY_ARCH:-}" ] && [ "$TARGET_ARCH" != "$MY_ARCH" ]; then
    echo "[$APP] warning: architecture mismatch (launcher $MY_ARCH vs target $TARGET_ARCH)."
    echo "      This alone can break attach. Install a $TARGET_ARCH JDK and re-run"
    echo "      with JAVA_HOME pointing at it."
fi

ME="$(id -un 2>/dev/null || whoami)"
PERF_OK=""
for d in "${TMPDIR:-/tmp}" /tmp; do
    if [ -f "$d/hsperfdata_${ME}/$PID" ]; then
        PERF_OK="$d"
        break
    fi
done
if [ -z "$PERF_OK" ]; then
    echo "[$APP] warning: no HotSpot perf data for pid $PID"
    echo "      (looked in ${TMPDIR:-/tmp}/hsperfdata_${ME}/$PID and /tmp/hsperfdata_${ME}/$PID)."
    echo "      Attach will probably fail: wrong process, another user, or attach disabled."
    printf "Try anyway? [y/N]: "
    read -r force
    case "${force:-}" in
        [Yy]*) ;;
        *) echo "[$APP] aborted."; exit 1 ;;
    esac
fi

# ---------- inject (retries while the game JVM starts; any stage works) ----------
try_attach() {
    # $1 = pid, $2 = quiet (1 hides repeat refusals). Echoes injector output.
    # Returns 0 = ok, 3 = target refused (retryable), else fatal.
    local pid="$1" quiet="${2:-0}" out rc
    out="$(mktemp -t rottenapple-inject.XXXXXX)"
    if [ -n "${INJECTOR_TMPDIR:-}" ]; then
        env TMPDIR="$INJECTOR_TMPDIR" "$JAVA_BIN" -cp "$INJECTOR_JAR" rottenapple.launcher.Injector "$pid" "$AGENT_JAR" >"$out" 2>&1
    else
        "$JAVA_BIN" -cp "$INJECTOR_JAR" rottenapple.launcher.Injector "$pid" "$AGENT_JAR" >"$out" 2>&1
    fi
    rc=$?
    if [ "$quiet" = "0" ] || [ "$rc" != "3" ] || ! grep -q "refused dynamic attach" "$out" 2>/dev/null; then
        cat "$out"
    else
        echo "  ... still refused (retrying, Ctrl+C to stop)"
    fi
    rm -f "$out"
    return "$rc"
}

echo "[$APP] Injecting (retries up to ${ATTACH_TIMEOUT_SECS}s; works at any stage)..."
ATTACHED=""
LAST_RC=3
ATTEMPT=0
STOP=$((SECONDS + ATTACH_TIMEOUT_SECS))
while [ "$SECONDS" -lt "$STOP" ]; do
    if [ -z "${PID:-}" ] || ! kill -0 "$PID" 2>/dev/null; then
        PID="$(best_pid || true)"
    else
        BETTER="$(best_pid || true)"
        case "$BETTER" in
            ""|"$PID") ;;
            *) echo "[$APP] switching to better candidate pid $BETTER"; PID="$BETTER" ;;
        esac
    fi
    if [ -z "${PID:-}" ]; then
        echo "  ... waiting for Lunar JVM"
        sleep 5
        continue
    fi
    ATTEMPT=$((ATTEMPT + 1))
    if [ "$ATTEMPT" -gt 1 ]; then QUIET=1; else QUIET=0; fi
    try_attach "$PID" "$QUIET"
    LAST_RC=$?
    if [ "$LAST_RC" = "0" ]; then
        ATTACHED="$PID"
        break
    fi
    if [ "$LAST_RC" != "3" ]; then
        break
    fi
    sleep 5
done

if [ -n "$ATTACHED" ]; then
    echo "[$APP] Done (pid $ATTACHED). In game, press P or INSERT (Fn+Return on MacBooks) for the menu."
    exit 0
fi

echo "[$APP] Could not attach (last exit $LAST_RC)."
printf "Collect debug logs to share? [Y/n]: "
read -r wantlogs
case "${wantlogs:-}" in
    [Nn]*) ;;
    *) collect_logs ;;
esac

echo ""
echo "[$APP] WARNING: Lunar blocks dynamic attach (-XX:+DisableAttachMechanism),"
echo "      so injecting into a running game is impossible. Lunar supports JVM"
echo "      arguments (like other macOS clients use) — inject that way instead:"
echo ""
echo "  1) In the Lunar launcher, open Settings and find the JVM / Java arguments field."
echo "  2) Add exactly this as one argument:"
echo ""
echo "       -javaagent:$AGENT_JAR"
echo ""
printf "Copy this argument to clipboard? [Y/n]: "
read -r copyarg
case "${copyarg:-}" in
    [Nn]*) ;;
    *)
        if printf "%s" "-javaagent:$AGENT_JAR" | to_clipboard; then
            echo "[$APP] argument copied — paste it into Lunar's JVM arguments field."
        else
            echo "[$APP] clipboard unavailable — select and copy the line above manually."
        fi
        ;;
esac
echo "  3) Launch Lunar $WANT_VERSION normally (menu, singleplayer, or server)."
echo "     The RottenApple menu appears automatically; no need to run ./launch.sh afterwards."
exit "$LAST_RC"
