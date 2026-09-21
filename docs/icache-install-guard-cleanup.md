# I-cache install-start guard simplification

This preserves the current SG-1 acceptance policy and the one-fill-owner-per-set
rule. It does not add a pipeline stage or relax same-set exclusion.

The old IDLE install condition was:

```
pfInstallAny && !demandFillStart &&
!(cmdPort.fire && mshrSet(pfInstallMshr) == lookupSet)
```

When `pfInstallAny` is true, `pfInstallMshr` selects a live speculative entry:
each candidate is `valid && complete && !error && !poison`, and the selector
chooses its first set bit. If that entry's set equals `lookupSet`, its lane in
`pfLookupSetBusy` is true. IDLE calls `lookupTick`, whose ready expression
includes `!pfLookupSetBusy`; consequently `cmdPort.fire` must be false.
The final conflict term is therefore always false whenever the install
condition could fire. Removing it preserves the install cycle and priority.

Previously the RTL deliberately kept this redundant guard as protection
against a future SG-1 relaxation. The replacement retains that obligation
as simulation assertions: selected entry live, matching set reported busy,
and matching-set command not accepted. Any future acceptance-policy change
must preserve these properties or redesign the install/held-verdict protocol.
These assertions are excluded from production generation.

Motivation: the strict 200 MHz SoC placement report at source `0e9cd58`
shows a 22-level path from FetchAlign through ITLB lookup and command
acceptance into I-cache install state. Removing the dead condition disconnects
that acceptance cone from installer control rather than delaying the request.
Placement is only a diagnostic; no routed saving is claimed yet.

This candidate is based on CPU `6e36e527`, independently of the separate
one-bit install-counter cleanup. Required evidence before acceptance:

- I-cache demand, prefetch, verdict-shadow and recycled-install correctness.
- Matched full-core IPC and retirement results.
- Required `test-fast` gate and production generation/lint.
- Serialized SoC implementation and routed timing/resource comparison.

Validation (2026-09-21): 58 focused tests passed, including IcacheOrderOracleSpec;
all 16 fresh matched full-core byte-copy IPC rows are identical to CPU 6e36e527
(L2 hit 5 cycles, DDR 70, both early-store options enabled); required fast gate
passed 391 tests with 2 ignored and no failures/aborts. Source hashes were
checked after the gates. Logs are `/tmp/ipc-cleanup-icache-install-guard-`
`{tests,ipc-before,ipc-after,fast,validation}.log`.

Production generation, SoC lint and routed timing/resource validation are pending.
