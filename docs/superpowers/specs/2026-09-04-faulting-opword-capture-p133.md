# Faulting-opword capture on p133 — the blocker is a corrupt return address, not a decode gap

**Date:** 2026-09-04
**Bitstream:** p133, `build_id 0x14F3C599` (verified against `buildinfo` at session start *and* at session end)
**Board/ROM:** unchanged; ROM verified calibration-fix-only in the prior session. **No ROM patch was made or proposed.**
**Vivado builds run:** none. Mutex checked free with `flock -n /var/tmp/m68k-ooo-vivado.lock -c true` and never taken.
**SD card:** not touched. `hw_server`: left alone.
**JTAG lease:** acquired as `opword-capture`, released at end.

---

## 1. Headline

The faulting instruction was captured, twice, byte-identically.

| item | value |
|---|---|
| exception vector | **3 — address error** |
| faulting PC (stacked) | **`0x408999E2`** |
| stacked format word | **`0x200C`** → format `$2`, vector offset `0x00C`, vector 3 |
| **raw opword at that PC** | **`0x4E75`** |
| decode | **`RTS`** |
| fault address (from `exc-ring` `fa=`) | **`0x00A64089`** — **odd** |

**A real 68040 executes `RTS` without complaint.** `0x4E75` is not an unimplemented,
reserved, or optional encoding. The core did not reject an instruction — it executed
`RTS`, and the return address it popped was garbage and odd, so it correctly raised
an address error (vector 3 is exactly the 68040's response to an odd instruction
address).

**Conclusion: this is a control-flow divergence, not a decode gap.** cpu040's behaviour
at the faulting instruction is *correct*. The bug is upstream — something corrupted the
stack before the `RTS`.

**None of the three candidate spurious-illegal bugs match.** `CMP2`/`CHK2` full-format
EA (#257), `move_idx_idx` (#223) and non-FPU line-F format-0 (#222) all require the
faulting opword to be a CMP2/CHK2, an indexed MOVE, or a `0xF...` line-F word. It is
`0x4E75`. They are excluded by direct measurement, not by argument.

---

## 2. What the corruption actually is

The stack pointer is **exactly 2 bytes too low** at the `RTS`.

Reading the stack shifted by +2 bytes yields a completely coherent frame, and every
field cross-checks against live registers:

```
0x0040076C: 00 A6            <- 2 stray bytes; SP points HERE
0x0040076E: 40 89 92 0E      <- the REAL return address 0x4089920E
0x00400772: 00 00 02 00      D2
0x00400776: 00 00 00 01      D3
0x0040077A: 00 00 00 20      D4
0x0040077E: 00 00 00 00      D5
0x00400782: 00 40 07 E4      D6
0x00400786: 00 40 07 E4      A1
0x0040078A: 00 40 08 00      A2
0x0040078E: 50 F0 F0 00      A3  == live A3 ✓
0x00400792: 00 00 88 B0      A4  == live A4 ✓
0x00400796: 00 40 0B FC      A5  == live A5 ✓
0x0040079A..0x0040079F        6 bytes of locals  (ROM uses %fp@(-6))
0x004007A0: 00 40 07 D6      saved A6  == live A6 (0x004007A0) ✓
0x004007A4: 40 89 8C 26      caller return address
```

`0x4089920E` is the return address of `jsr %a0@` at `0x4089920C`, and `0x40899214` is
`movem.l %sp@+,%d2-%d6/%a1-%a5` — a 10-register, 40-byte restore that lands exactly on
the block above. The 6-byte gap before A6 is the `link %fp,#-6` local area the handler
itself uses via `%fp@(-6)`. The layout is confirmed three independent ways.

The `RTS` read `0x0040076C` and got `0x00A64089` — the last 2 stray bytes (`00 A6`)
concatenated with the first half of the real return address (`40 89`). Odd → address
error. Everything is consistent.

---

## 3. The ROM path, fully reconstructed

`pc-trace` captured the entire recovery, and it disassembles cleanly against
`files/420dbff3.rom` (offset = PC − `0x40800000`).

The ROM probes a SCSI alias address, *expects* a bus error, and unwinds:

```
40899300..4089931c   8x  move.w %a1@(256),%a2@+     ; unrolled 53C96 FIFO PIO drain
                                                    ; A1=0x50F4F000 -> A1+0x100 = 0x50F4F100
   -> BUS ERROR (vector 2) at 0x4089931c, fa=0x50F4F100        [ correct and expected ]

4089993a  move.l %d0,-(%sp)                          ; bus-error handler
4089993c  move.l 0xc00,%d0
40899940  addi.l #256,%d0
40899946  cmp.l  %sp@(24),%d0                        ; %sp@(24) = frame+$14 = FAULT ADDRESS
4089994a  beq.s  40899988                            ; not taken
4089994c  addi.l #262144,%d0
40899952  cmp.l  %sp@(24),%d0
40899956  beq.s  40899988                            ; TAKEN: 0x50F0F000+0x40100 == 0x50F4F100
40899988  subq.w #1,%fp@(-6)                         ; retry counter
4089998c  beq.s  4089999c                            ; TAKEN: counter exhausted -> final unwind
4089999c  addq.l #4,%sp                              ; drop saved D0
4089999e  move.w %sp@,%d0                            ; D0 = stacked SR
408999a0  bfextu %sp@(6){0:4},%d1                    ; D1 = STACKED FORMAT NIBBLE
408999a6  cmpi.b #7,%d1
408999aa  beq.s  408999b8                            ; TAKEN -> format was $7
408999b8  lea    %sp@(60),%sp                        ; discard the 60-byte format-$7 frame
408999c2  move.w %d7,-(%sp)                          ; synthesise an RTE frame: format word (D7=0)
408999c4  pea    %pc@(0x408999cc)                    ;                          PC
408999c8  move.w %d0,-(%sp)                          ;                          SR
408999ca  rte                                        ; -> 0x408999cc
408999cc  moveq  #9,%d0
408999ce  btst   #7,%a3@(64)
408999d4  beq.s  408999e2
408999d6  move.b %a3@(80),%d5
408999da  btst   #4,%d5
408999de  beq.s  408999e2
408999e0  moveq  #5,%d0                               ; D0=5 == live D0 ✓
408999e2  rts                                         ; <<< FAULTS: pops 0x00A64089, odd
```

The design is a classic probe unwind: the handler discards the exception frame and
`RTE`s to a stub that sets a result code and `RTS`es **straight back to the `jsr`'s
caller**, skipping the probe routine entirely. It therefore depends absolutely on SP
being exact.

Note the ROM **switches on the stacked format nibble** and pops 32 / 60 / 92 bytes for
format `$A` / `$7` / `$B`. The `pc-trace` proves the `$7` arm was taken, so cpu040 did
stack format `$7` and the ROM's 60-byte arithmetic is the 68040-correct one.

---

## 4. What was measured — and what was DISPROVEN

Three hypotheses were tested on the board and **two were killed by measurement**.

### 4.1 DISPROVEN — "the format-$7 frame cpu040 pushes is the wrong size"

Halted at the bus-error handler entry (`pc_live=0x4089993a`), so **A7 == frame base**:

```
A7 = 0x00400852
+$00 SR            = 0x2000
+$02 PC            = 0x40899664
+$06 format word   = 0x7008   -> format $7, vector offset 8 = vector 2 ✓
+$08 Effective Addr= 0x50F4F100
+$0C SSW           = 0x0145
+$0E/$10/$12 WB3S/WB2S/WB1S = 0
+$14 Fault Address = 0x50F4F100  ✓ (exactly what the ROM's %sp@(24) reads)
+$18..+$3B writeback/push fields = all zero
frame ends at +$3C = 60 bytes
```

and the pre-exception return address `0x4089920E` sits at **`0x0040088E` = A7 + 60**.
**The frame is exactly 60 bytes with all fields at their spec offsets.** Correct.

### 4.2 DISPROVEN — "cpu040's RTE pops the wrong number of bytes for format $7"

Broke at the retry target `0x40899664` immediately after the handler's retry `RTE`:

```
A7 after RTE = 0x0040088E  ==  frame_base(0x00400852) + 60
```

**The format-$7 `RTE` pops exactly 60. Correct.** This was a clean prediction that
failed, and it is reported as such.

### 4.3 SUPPORTED — SP is *already* 2 low when the bus error is taken

Measuring frame-base → return-address distance across many bus errors:

| bus error site | preceding exception | frame_base → ret addr | verdict |
|---|---|---|---|
| `0x40899664` (×13 observed) | IRQ at `0x40899628`–`0x40899664` (**not** in the drain loop) | **60** | clean |
| `0x4089931c` (drain loop) | **IRQ at a drain-loop PC** (`0x40899304`, `0x4089930c`, `0x4089931c`) | **62** (derived) | corrupt |

The 62 for the failing case is arithmetic from the measured final SP:
final SP at the `RTS` = `0x0040076C`; subtract the synthesised RTE frame (net 0) and the
`lea %sp@(60),%sp` ⇒ frame_base = `0x00400730`; the real return address is at
`0x0040076E` = frame_base + **62**.

So the bus-error frame is a correct 60 bytes, but it is pushed from an SP that is
**already 2 bytes low**. The drift is introduced *before* the bus error.

**The correlation is exact in every sample taken:** the drift appears only when an
autovector IRQ was taken **at a `move.w %a1@(256),%a2@+` inside the unrolled SCSI FIFO
drain loop**. Thirteen bus errors preceded by an IRQ *outside* that loop all measured a
clean 60.

`move.w <mem>,(An)+` is a **memory-to-memory move with postincrement** — precisely the
shape of the long-standing open `exc_partial_macro_move_mem_mem` / A3 multi-access
restartability class. This is the first time that class has been tied to a live
hardware boot blocker.

**Not proven:** I did not directly catch an IRQ inside the drain block and watch A7
change across it. The correlation is strong and consistent but is correlation, and it is
labelled as such.

---

## 5. The pseudo-DMA / stale-I-cache hypothesis — TESTED, NOT CONFIRMED

Tested exactly as specified: plain JTAG read → `coherent-dump` (halted D-push) →
plain re-read, at `0x00300000` (boot B's PC region), across **6 boots**
(2 of which had fired the vector-3 fault, 4 of which were parked).

**Result: all three reads byte-identical in all 6 boots. No change. Negative.**

Two further findings make the negative stronger:

1. **Low RAM contains no code at all.** The contents are
   `B6DB6DB6 / DB6DB6DB / 6DB6DB6D` repeating — a uniform `110110110…` bit pattern,
   i.e. untouched/uninitialised DRAM. The ROM had not pseudo-DMA'd anything to
   `0x00300000` in any boot. The failure happens **earlier**, during SCSI hardware
   probing, before any code load.
2. **`CACR = 0x80008000` in every sample** — `DE=1` *and* `IE=1`. The ROM is not asking
   for the I-cache to be disabled, so the missing `CACR.IE` consumer cannot be the
   active cause here.

I did confirm the underlying code claim: **`CACR.IE` (bit 15) genuinely has no consumer**
in `src/main`. `IcachePlugin.scala` computes `lookupCacheable = lookupCmode =/=
CacheMode.INHIBITED` from the page attribute alone; only `ss.cacr(31)` (DE) is consumed.
That is a real fidelity gap worth fixing — it is just not what is breaking this boot.
(Note: `docs/BUG_cacr_ie_has_no_consumer.md` **does not exist** in this repo.)

### 5.1 Bonus — this also explains boot B

`0x00300018` holds `B6DB 6DB6`, which disassembles as:

```
300018:  b6db     cmpa.w (a3)+,a3
30001a:  6db6     blt.s  0x2fffd2
```

A valid, self-sustaining loop that walks A3 upward through memory and bus-errors
repeatedly — matching boot B's reported "pinned at `0x00300018`, ring 17× `vec=0x02`"
exactly. **Boot B is a wild jump into uninitialised DRAM**, i.e. the *same*
control-flow-divergence root cause, not stranded cache code. The three different Sad Mac
vectors are three different garbage landing sites, exactly as a corrupt return address
would produce.

---

## 6. Boot-outcome distribution

**15 armed cold boots** (`halt-exc-mask raw 0 0x00000818` = vectors 3/4/11, then `reset`),
plus 1 unarmed observation.

| outcome | count |
|---|---|
| **vector 3 address error at `0x408999E2`** (halt fired) | **4** |
| parked at `0x40899706` (53C96 poll, boot-D class) | 9 |
| parked at `0x4084Axxx` / `0x4084Afxx` (ROM-monitor loop, boot-A/C class) | 2 |
| **vector 4 (illegal instruction)** | **0** |
| **vector 11 (line-F)** | **0** |

Every one of the 4 fires was **identical**: vec 3, `exc_pc=0x408999E2`,
`fa=0x00A64089`, `A7=0x00400760`, same stack bytes, same `pc-trace` path.
**When it fires, it is perfectly deterministic.** The non-determinism is entirely in
*whether* the drain-loop IRQ/bus-error coincidence happens on a given boot.

Separately, a bus-error-armed campaign observed **13 bus errors at `0x40899664`** and
**2 at `0x4089931c`**, all with `fa=0x50F4F100` and all correctly recognised by the ROM
as expected probe faults.

---

## 7. Instrumentation corrections (measured this session)

**`reset` — a bare cold-reset pulse — DOES restart the CPU on p133, reliably, ~15 s to
first instruction, 20 times in a row with no REPL hang.** The prior session's claim that
"only `load-bit` boots it" is **wrong**, or at least not true of the `reset` path; it was
derived from `vio-hard-reset` failing. This is a large practical win: an armed boot
attempt costs ~45 s instead of a full reprogram, which is what made a 15-boot
distribution affordable.

Also confirmed:
* The halt-on-exception mask **survives a `reset` pulse** (debug reset domain), so it can
  be armed *before* the boot rather than raced in afterwards. This is strictly better
  than arming after `load-bit`.
* `exc-ring` records `fa=` for address errors — it gave `0x00A64089` directly,
  independently confirming the stack decode.
* `reset-and-break-pc` was **not** used (known REPL-hanging hazard). Plain `break-pc` on
  an already-running/halted CPU was used instead and behaved perfectly.
* `break-pc` is documented pre-effect at the commit head, so it cannot catch a PC that
  faults; the REPL says so explicitly on a miss. Use vector halts for faulting PCs.

---

## 8. What was NOT run

* **No Vivado build of any kind.** No synthesis, no implementation, no bitstream.
* **No MAME/Musashi cross-check.** Judged low value here and not run: the divergence is a
  cycle-level interrupt/exception coincidence, which Musashi's instruction-at-a-time model
  cannot reproduce, and cpu040 itself already demonstrates the correct behaviour on 11 of
  15 boots. This is a real gap in the evidence and is not being papered over.
* **No direct proof that the drain-loop IRQ causes the 2-byte drift.** I did not catch an
  IRQ inside `0x408992FE`–`0x40899320` and observe A7 change across it. §4.3 is a strong,
  repeated correlation only.
* **Boot B (`0x00300018`) was not reproduced** in 15 boots, so the cache test in §5 was run
  against `0x00300000` on other boots rather than on a live boot-B. The DRAM-pattern
  finding in §5.1 is offline decode plus board reads, not a boot-B capture.
* **Vectors 4 and 11 were armed the whole time and never fired.** The brief's boot-A/C Sad
  Mac codes `0F 03` / `0F 0A` were therefore **not** reproduced as live CPU exceptions.
  Caveat: waits were 30–45 s per boot, so a later fault could have been missed.
* No ROM was patched, read back, or re-verified this session (the prior session's
  calibration-fix-only verification was taken as given).
* The 53C96 `c96_phase_bits()` RTL gap was not investigated (explicitly out of scope).

---

## 9. Recommended next steps

1. **Do not chase a decode gap.** The opword is `RTS`. Close #257/#223/#222 as
   *not implicated in this blocker* (they may still be real bugs on their own merits).
2. **Target the exception/interrupt interaction on `MOVE.W <mem>,(An)+`** — the
   `exc_partial_macro_move_mem_mem` class. The precise question: when an autovector IRQ is
   recognised on a memory-to-memory postincrement MOVE, does A7 end up 2 bytes low after
   the IRQ's `RTE`? This is reproducible in simulation and does not need the board.
3. **Fix `CACR.IE`** (no consumer) as a separate, genuine fidelity gap — but do not expect
   it to change this boot.
4. Adopt `reset` + pre-armed `halt-exc-mask` as the standard cheap boot-attempt loop.
