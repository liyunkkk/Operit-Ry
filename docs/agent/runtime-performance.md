# Runtime performance and persistence

## Collaboration storage

`CollaborationStore` stores immutable per-root task trees under
`files/subagent-v2-store/`. Only changed trees are serialized. An `AtomicFile`
manifest publishes all changed trees together, including startup reconciliation
and multi-tree deletion. A completed task and its parent notification remain in
the same transaction.

The legacy `subagent-v2.json` is read only when no manifest exists. The first
successful save publishes the new format before removing the legacy file.
A corrupt manifest or missing referenced tree is an error, not a reason to
reload stale legacy data. Orphan generations are cleaned after a successful save.
Older application versions do not read this new format.

Raw export copies the manifest and its referenced generations under the storage
lock before ZIP compression. Raw restore treats the two collaboration formats as
alternatives: an incoming collaboration dataset replaces both previous formats
and their atomic-file sidecars. Backups with no collaboration data retain the
existing non-destructive merge behavior.

## File logging

The file logger queues at most 128 bounded records and writes batches of up to
32 with shared buffered writers. Records contain the original event timestamp
and bounded throwable text, not references to exception object graphs.

Background producers wait when the queue is full, preserving request-body log
chunks. UI producers never wait for disk capacity: overload is reported to
Android Log and counted in a later file-log warning. A requested clear discards
previously queued records and runs after any active batch, before new records.

Export waits up to five seconds for logging. It still exports available logs if
that wait or a write fails, with an explicit incomplete-export message.

## Streaming Markdown

Streaming batches convert dirty top-level nodes and newly appended nodes only.
Structural updates invalidate cached nodes even when text length is unchanged.
End-of-stream flushes synchronously, and a new stream cancels the previous
timer and clears conversion state. Stable node identities and process expansion
keys are retained. Grouping and layout are still separate work; this does not
make every rendering operation constant time.

Focused regression suites: `CollaborationStoreTest`, `BatchLogQueueTest`,
`RenderBatchCoordinatorTest`, `ThinkToolsGroupingTest`, `AgentMailboxTest`,
and `RequestBodyLogTest`.
