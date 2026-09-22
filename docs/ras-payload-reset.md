# RAS payload reset removal

Remove reset only from the live and checkpoint return-PC arrays. Keep reset
on both stack pointers, both occupancy counts and the deferred-save arm.
Default depth 16 means 1024 payload reset bits and 19 retained control bits,
matching the routed RAS reset-load census. This targets reset distribution,
not an assumed FF-count reduction or BRAM inference: checkpoint save/restore
still copies entire arrays in parallel.

Ownership invariant: every entry covered by live occupancy has been written
by a push or restored from a checkpoint with the same invariant. Push writes
before increasing occupancy; pop cannot increase it; invalidate clears it.
A checkpoint captures the live data, pointer and count from the same cycle;
restore copies that same tuple. Reset clears both counts and the pending save,
so neither a warm reset nor a restore before the first save exposes old data.
The documented push-XOR-pop input contract remains unchanged.

No push/pop/overflow behavior, checkpoint delay/priority, port, Global key,
prediction latency or fetch policy changes. `predTarget` while `predValid` is
false is unspecified; FetchAlign qualifies RAS target selection with validity.
Do not add a zero-target mux to compensate for unowned data.

Before changing RTL, test the actual arrays with deliberately poisoned
unowned entries. Compare live/checkpoint ownership, all occupied payloads,
valid prediction targets and cycle traces against a software reference across
overflow, empty pops, deferred saves, overlapping invalidation/restore and
warm resets. Cover depths 2, 4 and production depth 16. Deliberately suppressing the
push data write must fail, proving the test detects a valid poisoned entry.
Then run existing RAS tests, test-fast, matched IPC and production generation.
Final acceptance still requires full-SoC 200 MHz routing with intact resets on
all control state. The small exhaustive ownership model is supporting evidence,
not a replacement for these RTL and physical gates.
