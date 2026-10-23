# Super Admin Shell tasks

`super_admin:shell` runs a fresh, non-interactive Android shell under the user's
selected STANDARD, Shizuku or ROOT permission. It is separate from Ubuntu Terminal.

- `shell(command, timeoutMs?, yieldMs?)`: total execution limit defaults to
  120,000 ms (range 1,000–1,800,000); request wait defaults to 10,000 ms
  (range 0–30,000). Use `yieldMs=0` to return immediately.
- `shell_wait(runId, yieldMs?)`: inspect or wait for the same task. New chat input
  may end this wait without stopping execution. Never rerun a command merely
  because a wait ended.
- `shell_cancel(runId)`: request cancellation and wait briefly for cleanup.
  Poll again if the result is still `cancelling`.

Results include `runId`, `command`, `status`, `exitCode`, `terminationReason`,
`timedOut`, `output`, `outputTruncated`, and `outputMode="tail_snapshot"`.
Output combines stdout and stderr and retains the last 12,000 characters.
Replace earlier snapshots; do not append them. Only `completed` with
`exitCode=0` means success. Other terminal states are `failed`, `timed_out`,
and `cancelled`. `running` and `cancelling` are not terminal.

Each task uses a dedicated `setsid` process group. A supervisor handshake prevents
user commands from starting before the app knows their group ID. The group leader
stays alive until cleanup, including after a command exits, so ordinary background
children can be terminated without relying on a recycled PID. Timeout, cancellation
and normal completion clean up that group. Deliberately detached processes (for
example another `setsid` or a system service started with `am`) are outside its
lifetime; this is a task lifecycle mechanism, not a sandbox or rollback.

There is no interactive stdin, persistent cwd or shared environment. Use foreground
commands and `yieldMs=0` rather than trailing `&`. Devices without the Android
`setsid` command fail explicitly. ADMIN and ACCESSIBILITY cannot start these tasks.
Permission or cleanup failures are reported rather than silently switching identity
or claiming successful cancellation.

The existing `Tools.System.shell(command)` and synchronous internal Android shell
callers keep their previous contract. Super Admin uses `Tools.System.shellTask`.
Completed task history is in memory, bounded to 16 entries together with active
tasks, and scoped by caller chat. Expired IDs must not trigger automatic reruns.
