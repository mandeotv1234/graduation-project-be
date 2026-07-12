---
name: vm-health-check
description: Check the project VM's RAM, disk, inode usage, listening ports, PM2 status, and top memory processes over SSH. Use when the user asks to inspect VM resources, diagnose whether the VM is low on memory or disk, check service health on ports such as 4000/4001, or collect a quick operational snapshot before restarting or changing VM services.
---

# VM Health Check

## Workflow

Use this skill for read-only VM health inspection. Do not restart services, delete files, prune Docker, or change firewall/process state unless the user explicitly asks after seeing the health output.

1. Run the bundled script from the backend repo:

```bash
.agents/skills/vm-health-check/scripts/check-vm-health.sh
```

2. If the VM host or SSH key differs, override them without editing the skill:

```bash
VM_HOST=root@168.144.41.177 VM_SSH_KEY="$HOME/.ssh/do_github_cicd" \
  .agents/skills/vm-health-check/scripts/check-vm-health.sh
```

3. Summarize the result in Vietnamese unless the user asks otherwise:
- RAM: total, used, available, swap, and top memory-heavy processes.
- Disk: `/`, `/home`, `/tmp`, inode usage, and any filesystem over 80%.
- Services: PM2 status and listeners on ports `4000`, `4001`, `8080`, and `3000`.
- Health endpoints: `HTTP 200` means healthy, `HTTP 401` means service is reachable but requires auth, `HTTP 000` or connection errors mean the port is not reachable locally.

## Safety

- Treat the script output as diagnostic context only.
- Do not expose API keys or tokens in the final answer.
- If SSH fails, report the exact failure mode and the host/key used, but do not invent VM state.
- If disk is critically full, propose the least destructive next checks first, such as `du -h -d 1 /home` or PM2 log size inspection.
