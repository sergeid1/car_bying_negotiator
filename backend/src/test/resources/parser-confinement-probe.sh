#!/bin/sh
# Used only by the test-probe image, invoked as a native HEIC decoder by the real worker.
set -eu
if test "$(od -An -tu1 -j23 -N1 "$1" | tr -d ' ')" = 1; then
  while :; do :; done
fi
test "$(id -u)" = 10001
test "$(cat /sys/fs/cgroup/memory.max)" = 1610612736
test "$(cat /sys/fs/cgroup/memory.swap.max)" = 0
test "$(cat /sys/fs/cgroup/pids.max)" = 64
test "$(cat /sys/fs/cgroup/cpu.max)" = '100000 100000'
test "$(awk '/^CapEff:/ {print $2}' /proc/self/status)" = 0000000000000000
test "$(awk '/^NoNewPrivs:/ {print $2}' /proc/self/status)" = 1
test "$(wc -l < /proc/net/route)" -eq 1
test ! -e /var/run/docker.sock
test ! -e /app/var/document-objects
if touch /app/forbidden 2>/dev/null; then exit 1; fi
# Native output cannot grow beyond the tmpfs quota; no application filesystem is mounted.
if dd if=/dev/zero of=/tmp/quota-probe bs=1M count=129 2>/dev/null; then exit 1; fi
rm /tmp/quota-probe
# All checks must pass before the parser sees a valid result.
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAC0lEQVR4nGNgQAcAABIAAXfx+gAAAAAASUVORK5CYII=' | base64 -d > "$2"
