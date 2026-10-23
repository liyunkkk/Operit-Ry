# Subagent v2 implementation and validation

Reference: local `codex`, OpenAI `main` commit
`c55db1b8d9b876230f9fb22be78d77fc0b526d0c` (2026-09-09).

## Required behavior

- Retain the current synchronous `task` tool as v1, including reading companion's
  internal callers. The settings selector defaults to v2.
- Implement Codex's separate v2 collaboration surface: `spawn_agent`,
  `send_message`, `followup_task`, `interrupt_agent`, `list_agents`, `wait_agent`.
- Canonical paths start at `/root`; relative names resolve beneath the caller.
  Cross-branch addressing uses canonical paths, scoped to one root conversation.
- Spawn is asynchronous. Agents retain identity and conversation across turns.
  Messages steer active turns at safe boundaries; queue-only messages do not wake
  idle agents. Follow-up tasks wake idle agents and cannot target root.
- Interrupt ends the active turn without deleting the agent. Wait wakes on inbox
  activity or user steering and respects a bounded timeout; when no other agent can
  reach the caller it returns an explicit idle result instead of idling to the
  timeout. Completion messages are delivered to the parent, with sender and
  recipient identity.
- Context forking supports all, none and a positive turn count. Preserve parent
  model settings when inherited; expose explicit model selection consistently
  with Operit's configured models.
- Persist agent identity and mailbox state and isolate separate root chats.
  Enforce concurrency and nesting limits without blocking parent tool calls.
- UI must distinguish v1 task runs from v2 agents and retain usable conversation
  navigation, progress and cancellation.
- Steering and mailbox inputs split the displayed reply without starting a new
  turn: retain the first assistant header, suppress repeated continuation headers,
  and show aggregate usage/timing only on the final segment. Intermediate
  segments in existing history also hide copied statistics.
- With completed-process folding enabled, one toggle collapses the intermediate
  process while the first AI identity header stays above the toggle in both states.
  The fold contains the intermediate
  AI messages and agent event cards of a completed turn. Human inputs remain in
  place; the final answer stays visible and shares the toggle for its own process
  prefix. Message navigation expands hidden target segments before scrolling.
- The message locator excludes collaboration events and tasks from normal,
  search, and favorites results; they belong to the AI process, not human input.
- Both v1 and v2 agents can use `todowrite` for their own chat. Storage and the
  Todo dock are keyed by the executing chat ID, so parent and sibling lists stay
  independent. Opening a child chat shows that child's Todo progress.

## Execution plan

1. Update and inspect the reference; map Codex handlers, agent control,
   resolver, mailbox, lifecycle and context-fork semantics to Operit's runtime.
2. Implement v2 state/control and tools, then integrate prompt exposure,
   settings and management UI. Preserve v1 runtime and reading callers.
3. Compile and run focused lifecycle, mailbox, isolation, fork and exposure tests.
4. Independent read-only audit of final changes; fix findings and re-audit.
5. Build a debug APK and install with data preserved. An independent agent runs
   real-device validation using isolated test conversations and reports evidence
   for v2 collaboration, settings switching and v1 compatibility.

## Prior validation (before parity follow-up)

- Reference update: complete.
- Initial six-tool implementation: complete; this was not proof of exhaustive Codex parity.
- Compilation and focused tests: final debug APK build passed; 100 focused JVM
  tests passed with zero failures or skips.
- Independent audit: CLEAN for runtime changes and opt-in live validation test.
- Independent real-device verification: PASS on the final debug candidate,
  SHA-256 `82086ACD7C5FB4637FF4EF51A640BA0B6C855B3E285522D97279C3DC65B490ED`.
  Verified fork 1/all without recursive delegation, asynchronous lifecycle,
  queue/followup/wait, interruption, process recovery with retained identity,
  result cards, real v1 task execution and settings restoration to v2.
- Live compaction instrumentation: PASS (`OK (1 test)`, 45.113 seconds).
  The checkpoint excluded the model-generated token; a later turn recalled it
  from the post-checkpoint answer with the same chat and run IDs. The disposable
  model configuration was removed and app data was preserved.
- Full Lint was not run for this validation.
- Event-row UI follow-up: compilation, debug APK build and independent static UI
  audit passed. The device displayed the initially collapsed, bubble-free event
  row; details remained expanded after visiting and returning from management,
  and long-press did not expose user edit/resend actions.

Device tests must not uninstall or clear the user's app. Do not use Gradle
connected-test tasks that can uninstall it. Test APKs, if needed, are installed
by replacement and invoked with `am instrument`.

## Using the two versions

User conversations always use v2; settings no longer offer a version selector.
Old global and per-chat version preferences are ignored, including saved v1 choices.
Existing task records are retained. Reading Companion, permission review and other
internal tasks keep the v1 coordinator contract and call it directly; they do not
receive collaboration tools or recursively delegate.

The legacy `task` tool is not exposed to chat models. v2 exposes six separate
collaboration operations and the on-demand `list_agent_models` discovery tool.
`send_message` queues input without waking an idle agent;
`followup_task` starts an idle agent and reuses its identity and transcript.
`interrupt_agent` stops the current turn without deleting that identity.
`wait_agent` reports mailbox activity, user steering, an idle wait with nothing to
observe, or timeout; messages are delivered through the model's actual turn inbox.
Mailbox success requires a message already accepted by that execution inbox,
not merely a durable mailbox entry. Wait reconciles and offers only its caller's
pending messages before reporting readiness; the normal model boundary still
owns draining, transcript persistence and acknowledgement. This prevents a
successful wait result from reaching a model request without the message body.
Wait subscribes to inbox and agent-state changes. Once-per-second reconciliation
remains a recovery fallback, and only restored or previously handed-off messages
need a stored-history read. Senders deliver to their recipient directly; separate
recipients no longer wait for each other's history. A pending input returns
immediately without consuming or acknowledging it.
Tool dispatch runs non-`task` calls serially in the model's call order, so a
`spawn_agent` earlier in the same batch is registered before a following
`wait_agent` starts. When the order is reversed the wait can report idle before
that child exists; the tool description tells the model to call `wait_agent` again
once the child is running instead of failing the turn.

### Input during a model request

User steering cancels only the current sampling request, including connection,
streaming and retry/rate-limit waits. The turn, prompt snapshot, reasoning
configuration and running tools stay alive. Cancellation uses the request-local
HTTP call, not a provider-wide cancellation that could affect another chat.

Mailbox input yields at a provider's complete reasoning boundary, after any
Responses replay metadata. Gemini retains completed native parts/signatures
before the first unexecuted function call. Providers without such a boundary
continue to use the existing end-of-response/tool boundary.

Interrupted reasoning/text is kept, with the app-owned reasoning tag closed.
Unexecuted tool arguments are discarded rather than repaired or run; network
retry rollbacks still remove failed drafts. Input is then appended through the
existing transcript/acknowledgement handoff. A real user Stop still cancels the
whole turn.

Terminal start/wait observations yield a current `runId` snapshot when input
arrives; the underlying command continues. Ordinary tools finish and record their
results before the next model request. No automatic rerun or tool cancellation is
introduced. Reference: local Codex `8c0f7011`, user-input watcher and complete-item
mailbox boundaries in `session/input_queue.rs` and `session/turn.rs`.

Agent-to-agent replies must use `send_message` addressed to the incoming sender;
ordinary assistant prose or a handwritten envelope is not a delivery. A child's
automatic final response goes only to its parent, so it cannot serve as a reply
to a peer. Prompt guidance makes this distinction explicit and requires accepted
tool delivery before claiming a message was sent. Idle non-root recipients that
need to work must be continued with `followup_task`. These are model instructions,
not an automatic rewrite of assistant prose into outgoing messages.

`fork_turns` accepts `all` (default), `none`, or a positive turn count. A full fork
inherits the running parent's prepared context and model selection unless an
explicit profile/model overrides it. To override `model`, first call
`list_agent_models`, then copy its readable `model` selector into `spawn_agent`.
Each model within a configuration is listed separately and resolves to its
actual index. Duplicate names are qualified by provider and, only when still
necessary, configuration identity/index. Missing or ambiguous choices fail
without falling back to a different model. The catalog returns only selectors
and provider names; it is not included in system prompts or `list_agents`.
`reasoning_effort` overrides that agent's request parameter without editing the
user's model configuration. Model-provider support for the selected effort
remains required.

The v2 settings expose active child count (default three per root), depth
(default one: direct children only) and minimum/default/maximum wait durations
(10,000/30,000/3,600,000 ms). The active count excludes root, corresponding to
Codex's default four-thread allowance including root. Model-specific concurrency
limits also apply. v2 rejects saturated model
admission instead of waiting while a parent may hold the needed model slot.

Agent identity, pending input, model selection and context checkpoints persist
in private application storage. Process restart marks formerly active agents
interrupted; follow-up can continue them. Successful mailbox handoff is
recoverable by its persisted message ID. Context compaction stays in the same
agent turn, preserving the inbox and cancellation ownership.

Management rows carry v1/v2 labels. v2 messages use compact, initially collapsed
event rows, with details available on tap. They have no user-input bubble or
user edit/resend menu.
Parent-facing final results omit child thinking and internal tool markup;
the child conversation retains its complete execution transcript.

## Parity follow-up

The follow-up corrects portable behavior found by a fresh source-level audit:

| Area | Operit behavior |
| --- | --- |
| Fork filtering | Keep system context, user inputs, final assistant text and checkpoints; remove agent communication, intermediate assistants, tools and reasoning. Count real user/NEW_TASK boundaries before filtering agent messages. |
| Trusted message identity | Persist distinct collaboration task/event and intermediate assistant display modes; prompt filtering does not infer identity from user-controlled text. |
| Full default fork | Retain parent prepared instructions, model and role-card tool access; do not append the general profile instructions unless a profile is explicitly selected. |
| Wait | Distinguish mailbox activity, user steering, an idle tree with an empty caller mailbox, and timeout; report minimum clamping; reject values above configured maximum. Terminal notifications are enqueued before jobs are removed. One locked snapshot with no job, reservation or streaming peer proves the idle result. Inbox/state changes wake the wait; once-per-second reconciliation is a recovery fallback. |
| List | Include `agent_name` and `agent_status`. Per the user's context-budget preference, completed status is `{"completed": null}`: final text is delivered through the mailbox, not repeated on each list call. Durable final results remain stored. Existing Operit IDs/status fields remain available. |
| Limits | Configure whole-tree active children, depth and wait durations. The v1 per-parent ten-task quota does not cap v2. Provider/model concurrency limits remain shared. |
| Arguments | Reject unknown and duplicate parameters, including legacy `fork_context`, before any agent can be created. |
| Compaction | Send quoted history to the summarizer. Preserve the current execution's task and accepted corrections verbatim, with their source metadata, alongside the checkpoint. Keep the live continuation notice out of durable history; later follow-up uses its own new input. Check capacity before persisting the replacement. |
| UI | Collaboration task and message rows remain initially collapsed events without user edit/resend actions. |

Platform adaptations are explicit:

- Codex retains resident threads and evicts idle ones by LRU. Operit already
  releases each child's EnhancedAIService instance when its turn ends, including
  cancellation/error paths. Follow-up reconstructs execution from durable
  identity, profile, history and mailbox. A separate resident quota would count
  UI containers rather than Codex-equivalent running threads and is not exposed.
- Operit selects from configured provider/model choices, not the Codex model catalog.
  Effort names are validated, but provider-specific effort support is validated
  by that provider; Operit has no authoritative catalog to preflight it.
- Workspace/environment metadata follows Operit's workspace model. Codex server
  environments, guardian configuration, encrypted rollout types, service tiers
  and private telemetry are not Android runtime features.
- The persisted transcript retains execution details for the user; forked model
  context filters them. Durable agent listing includes cold agents so they can
  still be addressed after process recovery.

Follow-up build and static validation:

- Independent final source audit: CLEAN; reported P1/P2 findings were fixed and re-reviewed.
- 116 focused JVM tests: zero failures, errors or skips.
- Debug app and instrumentation APK builds: passed.
- Initial parity candidate SHA-256: `E32B9E2D6468FA67BE1B893774A6010906CC1D30389E5168D202953CAACFE7D7`.
  Native replay/rejection tests, live fork/follow-up/list/wait, v1 and event UI
  passed, but live compaction failed because the summary displaced the task.
  That failure led to the compaction correction above and a fresh CLEAN audit.
- Corrected app SHA-256: `0E2D2A80499A0BB2D14FA123BDCDC766EED94CE4257F26E82D33425DC0249DCB`.
  Test APK SHA-256: `AB7E681EF2B46D4BF414546E4899DDE0BC1743324BF3E8D90BB03CF7F5270E4B`.
- On the corrected APK, the independent native replay/rejection tests passed
  again (`OK (2 tests)`). The live compaction test passed on its first run
  (`OK (1 test)`, 23.383 seconds): the checkpoint did not contain the generated
  nonce; the post-cutoff answer did; the next turn recalled it without receiving
  it in the request and kept the same chat/run IDs. Temporary model config was
  removed. Independent final device verdict: PASS for the documented parity
  matrix. Installed APK hash matched; settings returned to v2 and default
  limits; CE/DE data directories and the test package were preserved, with no
  running validation agents or temporary model configurations left.
- Real-user steering during `wait_agent` could not be injected reliably through
  the device UI and is not counted as a live pass. Inbox/outcome unit tests and
  the source audit cover that path. Provider capability and native Codex protocol
  adaptations above are not claimed as equivalent device-tested features.
- Cosmetic observation: pre-response compaction can leave an empty old assistant
  placeholder before the actual post-cutoff answer. It does not change task
  identity, content or collaboration-event rendering.

## Opt-in live compaction validation

User Stop on a root conversation cancels and awaits its v2 task tree. A stop
generation rejects delayed spawn/follow-up work, and prevents mailbox delivery
or queued task restarts while cancellation is in progress. The interrupt tool
continues to target only its explicitly named agent.

Context-window estimates use the latest v2 checkpoint and inherited context,
not the retained display transcript. Cumulative input/output usage remains the
sum of provider requests. Stream snapshot projection serializes boundary updates
and replay snapshots; revision counts keep delayed rollbacks from erasing a
newer canonical tool boundary.

Sealed response segments are persisted once before their boundary is registered.
Later snapshots update only the current segment. Visible-window absence is not
used as evidence that a row is new: message persistence uses database upsert.
Opening history and loading runtime context repair exact duplicate intermediate
AI rows, comparing every stored field except row ID and display order, retaining
one identical copy. Other content, favorites and variants remain distinct.
Idle v2 conversations re-estimate their window when loaded; cumulative usage
and the token ledger are preserved.

The spawn description states the configured concurrency and depth limits:
concurrency is shared by all descendants of one root, includes pending creation,
and excludes the root. `list_agents` also reports the current configured limits,
without repeating final-answer bodies.

`CollaborationCompactionLiveAndroidTest` is skipped unless `v2LiveValidation=true`.
It requires `v2ValidationChatId` to identify a newly created empty test conversation
and `v2ValidationModelId` to identify an existing usable model. It clones that
model into a disposable configuration to force a checkpoint, then removes only
that temporary configuration in `finally`. Existing model settings are unchanged.

The first response generates a token absent from the initial task and checkpoint.
The next turn must recall that token from the post-checkpoint transcript while
retaining the same chat and run identity. This verifies the cutoff boundary in
the real request path rather than only checking stored timestamps.

Build `:app:assembleDebugAndroidTest`, install the main and test APKs using
`adb install -r`, and invoke the single class with `am instrument`. Leave the
test package installed. Do not use `connectedAndroidTest` or clear app data.
