# Instruction-buffer payload reset removal

Remove reset from the 20 stored words and their simple/length/ambiguity/size
metadata: 24 bits per slot, 480 reset bits total. The stored ctrlXfer bit already
has no reset. Keep both five-bit occupancy/head-pointer resets, every payload
write, and all output/default/ready/shift/flush logic unchanged.

Every newly occupied slot is written by the same push that increases occupancy.
Shifts remove only an occupied prefix; wrap is modulo 20. Flush and reset empty
the buffer. A push concurrent with flush may write storage, but the cleared
occupancy prevents exposing it. The existing output muxes produce word zero,
BYTE size and false/zero metadata outside occupancy; these defaults are part
of the interface and must remain exact. No extra masking or pipeline stage is
needed. This targets reset distribution, not an assumed BRAM/area reduction.

Before changing RTL, test actual poisoned unowned slots against an independent
FIFO model, checking all ten output words and every metadata field even when
invalid. Cover all physical head positions and occupancies, pushes of zero to
four words, shifts of zero to ten, simultaneous push/shift, full backpressure,
push/flush overlap, wrap and warm reset. Hold pending Stream payload stable
while blocked. A deliberately omitted real word write must expose poison and
fail. Compare exact cycle traces before/after, run existing instruction-buffer
and frontend redirect/cadence tests, required test-fast, matched IPC, production
generation and reset-assignment audits. Acceptance still needs full-SoC 200 MHz
physical results with unchanged timing constraints and reset controls.
