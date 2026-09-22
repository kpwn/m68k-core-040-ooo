# Stationary issue-queue producer positions

This is a cycle-preserving representation change, not a new issue policy.
The 16-slot, two-way compacting IQ still shifts hot records and triggers on
each accepted push. Age selection, registered readiness, dynamic wakeups,
same-push dependencies and the mandatory delayed-clear bypass stay unchanged.

Today each of 118 physical-register scoreboard entries (54 integer plus four
16-entry classes) stores a four-bit current slot, decremented by two on every
push. Instead store a stationary
four-bit position at producer insertion, with one shared three-bit line epoch.
The epoch advances modulo eight on every accepted push. If E is the current
epoch, physical slot S is represented by P = S + 2E modulo 16. On insertion
the new epoch is E+1 and the landing slot is 14+way, so P = 2E+way modulo 16.
An older producer's post-compaction trigger slot is P - 2(E+1) modulo 16.
The way bit never participates in subtraction; only three line bits do.

Only producer insertion writes a position entry. Remove all 118 per-entry
decrement/update paths. This does not promise fewer payload FFs: the expected
benefit is less update logic and broadcast-enable load. Sixteen dependency
lookups gain three-bit variable subtraction, an explicit timing tradeoff.
No latency, queue capacity, resource lifecycle or Global/service API changes.
The cold payload remains indexed by ROB id, unrelated to this representation.

The raw busy bits can briefly retain an already-selected producer until its
registered clear arrives. The position invariant still matches the original
slot bookkeeping then; it does not assert that the producer remains in the IQ.
The existing effective-busy clear bypass prevents creating a dependency on
that stale position. No bypass or write/clear priority is relaxed.

Reset initializes epoch and busy state. Flush clears busy state as before;
the epoch may continue because no surviving dependency refers to old entries.
Uninitialized positions are never used unless their busy bit was set by a
producer insertion. The one-live-producer-per-physical-register invariant and
the no-discard-of-occupied-line-zero rule remain prerequisites.

Validation: simulation-only shadow of the original per-entry decrement scheme
compares every busy entry every cycle, including wraparound and delayed clear.
Seeded standalone dependency/backpressure/flush traffic must produce identical
cycle digests before/after. Run all focused IQ tests, required test-fast,
matched BoardStringCopy IPC rows, and production generation. A wrong epoch
offset mutant must fail. Final acceptance requires measured routed full-SoC
200 MHz benefit; the old routed control is retained.

Initial validation (2026-09-22): all 25 focused IQ tests pass before/after.
The seeded trace is identical across 2492 cycles, 1603 accepted pushes,
2294 issued uops and 23 flushes, exercising all five static scoreboard classes.
Its SHA-256 is
`1c9147a8c9bf54574e5d947c17d86c1bf572026c06c2bf39ad6a420dcccddffd`.
Changing the actual RTL epoch step from +1 to +2 triggers the legacy-position
assertion; restoring +1 passes the entire focused suite again. The required
fast gate passes 396 tests (two ignored); IPC and routed results are tracked
in the congestion ledger, not inferred from these tests.
