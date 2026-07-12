#!/usr/bin/env bash
set -euo pipefail

VM_HOST="${VM_HOST:-root@168.144.41.177}"
VM_SSH_KEY="${VM_SSH_KEY:-$HOME/.ssh/do_github_cicd}"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'USAGE'
Usage:
  check-vm-health.sh
  VM_HOST=root@host VM_SSH_KEY=/path/to/key check-vm-health.sh

Read-only checks:
  - uptime and load
  - RAM and swap
  - disk and inode usage for /, /home, /tmp
  - top memory processes
  - listeners on ports 4000, 4001, 8080, 3000
  - PM2 process table when pm2 is installed
  - local /health HTTP status for ports 4001 and 4000
USAGE
  exit 0
fi

if [[ ! -r "$VM_SSH_KEY" ]]; then
  echo "error: SSH key not readable: $VM_SSH_KEY" >&2
  exit 2
fi

echo "Target: $VM_HOST"
echo

ssh \
  -i "$VM_SSH_KEY" \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=accept-new \
  -o ConnectTimeout=10 \
  "$VM_HOST" \
  'sh -s' <<'REMOTE'
set -u

section() {
  printf '\n== %s ==\n' "$1"
}

section "Host"
hostname 2>/dev/null || true
date -u '+UTC %Y-%m-%d %H:%M:%S' 2>/dev/null || true
uptime 2>/dev/null || true

section "Memory"
if command -v free >/dev/null 2>&1; then
  free -h
else
  vm_stat 2>/dev/null || true
fi

section "Disk"
df -h / /home /tmp 2>/dev/null || df -h

section "Inodes"
df -ih / /home /tmp 2>/dev/null || true

section "Top memory processes"
if ps -eo pid,user,pmem,rss,comm,args --sort=-rss >/dev/null 2>&1; then
  ps -eo pid,user,pmem,rss,comm,args --sort=-rss | sed -n '1,12p'
else
  ps aux | sort -nrk 4 | sed -n '1,12p'
fi

section "Listening ports"
if command -v ss >/dev/null 2>&1; then
  ss -ltnp | grep -E ':(4000|4001|8080|3000)\b' || true
elif command -v netstat >/dev/null 2>&1; then
  netstat -ltnp 2>/dev/null | grep -E ':(4000|4001|8080|3000)\b' || true
else
  echo "ss/netstat not available"
fi

section "PM2"
if command -v pm2 >/dev/null 2>&1; then
  pm2 list
else
  echo "pm2 not installed or not on PATH"
fi

section "Local health endpoints"
if command -v curl >/dev/null 2>&1; then
  for port in 4001 4000; do
    out="/tmp/vm-health-${port}.out"
    status="$(curl -sS -m 5 -o "$out" -w '%{http_code}' "http://127.0.0.1:${port}/health" 2>/dev/null || printf '000')"
    printf 'port %s /health HTTP %s\n' "$port" "$status"
    if [ -s "$out" ]; then
      head -c 300 "$out"
      printf '\n'
    fi
    rm -f "$out"
  done
else
  echo "curl not available"
fi
REMOTE
