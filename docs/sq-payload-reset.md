# Store-queue payload reset removal

Remove reset from `paddrs`, `datas`, `paddrBs`, `vaddrAs`, `vaddrBs`, `maskAs`
and `maskBs` only. At depth eight these are 1536 payload reset bits. Preserve
reset on every control/classification field, including validity, data-ready,
committed/orphan flags, ROB IDs, sizes, cache modes, split-valid bits, pointers,
accepted-half count and split phases. This targets reset routing, not an
assumed FF-count reduction or RAM inference. Ports and cycle timing do not change.

Allocation writes all seven payloads before making an entry valid. A late-data
reservation cannot forward or drain until publication; publication writes data
and asserts data-ready on the same edge, with the existing qualified same-cycle
publication bypass unchanged. Flush either retains the complete occupied entry
or invalidates it. A split fault reports the faulting half's allocated virtual
address. Reset clears ownership and outstanding-transaction state as before;
this change does not alter the surrounding SoC's reset/response pairing contract.

Raw payload outputs while their qualifier is false are unspecified. In particular,
forward data is qualified by hit, drain descriptors by valid, and fault addresses
by fault-completion valid. Do not add zeroing muxes to preserve invalid values.

Before editing production RTL, poison actual unowned storage and verify all
occupied payload against independently recorded allocation/publication inputs.
Exercise reserved and ordinary modes, split/non-split accesses, subword and
same-cycle publication forwarding, accepted-but-unacked drains, backpressure,
flush-retained committed/orphaned entries, faults in either split half, and warm
reset. Compare qualified cycle traces before/after; a suppressed real payload
write must make the test fail. Run existing SQ and SQ/D-cache pipeline tests,
required test-fast, matched IPC and production generation/reset audits. Physical
acceptance requires the unchanged full-SoC 200 MHz timing gates.
