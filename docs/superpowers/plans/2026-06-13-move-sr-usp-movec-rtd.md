# MOVE-to-SR / MOVE-USP / MOVEC / RTD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or
> superpowers:subagent-driven-development) to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax. COMMIT per task (the controller resumes from the last commit).

**Goal:** Implement the privileged line-4 system-state ops MOVE-to-SR (0x46C0|ea), MOVE-USP
(0x4E60/0x4E68), MOVEC (0x4E7A/0x4E7B), and RTD (0x4E74) on the m68k 68040 OoO core,
lock-stepped vs the Musashi oracle. ASSESS + likely DEFER STOP/RESET/MOVES honestly.

**Tech Stack:** SpinalHDL (Scala), Verilator lock-step vs the Musashi C oracle (`m68k-linux-gnu-as
-m68040` assembles the test programs — full AT&T m68k syntax), sbt (`~/sbt/bin/sbt`, NOT on PATH),
Vivado OOC synth gate (>=200 MHz).

---

## Architecture (verified against the merged code, 2026-06-13)

### Two delivery mechanisms, picked by whether the op touches COMMITTED SYSTEM STATE

**RTD** touches NO committed system state — it is a pure control-flow op (pop PC, A7 += 4+disp,
jump). It is implemented as a **µcode-engine straight-line crack** (RTS pop-load + an A7 add +
an ibranch), entirely in the existing fast/µcode datapath. NOT a commit-time op. NOT privileged
on the 68040.

**MOVE-to-SR / MOVE-USP / MOVEC** ALL write or read **committed architectural system state**
(`srSys`/`usp`/`ssp`/`vbr`/`cacr`) owned EXCLUSIVELY by the commit-side machinery
(`exception/SystemState.scala`, mutated only by `exception/ExceptionUnit.scala` — the SAME FSM
RTE uses). They are therefore **serializing commit-time "system ops"**, delivered through the
ExceptionUnit's commit obs channel (channel-2 / `ExcRec`), exactly like RTE.

### Why a NEW value-capture path is required (the crux)

RTE gets ALL its data from MEMORY (loads at commit, inside the FSM). There is currently NO path
for "a RENAMED REGISTER value reaches the commit-time SystemState write." MOVE-to-SR (src EA →
SR) and MOVE An→USP / MOVEC Rn→Rc need exactly that: the SOURCE register's VALUE, read in the
OoO datapath, must arrive at the serializing commit FSM.

**Mechanism (mirrors the existing `ccrCompletion`/`nzvcValStore` value-capture):** the system op
is a normal datapath µop that READS its source register (`srcA`) and produces a writeback VALUE
through an EU (the ALU EU, op = MOVE: result = srcA). That writeback value is captured per-ROB-
entry via a NEW completion port (`sysValStore`, mirroring `nzvcValStore` which already captures
the EU's NZVC writeback). At retire, the serializing system-op head feeds `sysValStore(h0)` into
the SystemState write (the FSM applies it + re-banks + redirects). The READ direction (USP/VBR/
CACR → Rn) is the inverse: the committed system value is read at retire and written to the int
PRF via the existing `a7Write`-style port (reuse the ExceptionUnit's arch-reg write port,
generalized to an arbitrary arch reg + value).

### Privilege model (S-bit is committed → check at COMMIT)

S=0 (user) executing MOVE-to-SR / MOVE-USP / MOVEC ⇒ privilege-violation trap, **vector 8**.
The S-bit is committed state (unknown at decode), so the check is at the SERIALIZING retire: a
`sysOp` head whose committed `ss.s == 0` converts into a vector-8 fault (reusing the existing
`exceptionPending`/format-$0 entry path) INSTEAD of applying its effect. RTD is NOT privileged
(user-mode return on the 68040) ⇒ no priv check.

### MOVE-to-SR serialization + A7 banking (the hard one)

`move <ea>,%sr` writes the FULL 16-bit SR: the system byte (`srSys` = SR[15:8], incl S/T/I)
AND the CCR (SR[4:0]). Effects, applied at the serializing retire by the ExceptionUnit:
1. `ss.setSrSys := newSrSys` (the new system byte) — may flip S → supervisor/user switch.
2. The CCR low 5 bits → the committed CCR (`committedCcr` register) so the whitebox's running
   CCR resyncs (MOVE-to-SR is the only non-exception op that writes the FULL SR incl CCR).
3. **A7 banking:** A7 (arch reg 15) must now reflect the bank selected by the NEW S. The current
   in-flight A7 value lives in BOTH `ss.a7` (banked mux of usp/ssp) AND the int PRF arch-15. On
   an S flip the active bank CHANGES, so the FSM writes the int PRF arch-15 with the value of the
   NOW-selected bank (`ss.writeA7`-style + the a7Write PRF port), AND the whitebox resyncs A7 to
   `ss.a7` via the channel-2 `ExcRec` (it already resyncs A7 on any ss.a7 change — see
   `WhiteboxCapture.scala` `a7Static` resync). SERIALIZING (redirect after) so younger instrs
   re-fetch with the new S/A7 — exactly RTE's contract.

   GOTCHA: `ss.a7 = Mux(s, ssp, usp)` is COMBINATIONAL on the committed `s`. When `setSrSys`
   flips S in cycle N, `ss.a7` reflects the new bank in cycle N+1. The obs (`obsA7`) must sample
   the POST-write a7 (the FSM's redirect cycle is already 1+ cycles after the SR write in RTE;
   for the system op we sequence SR-write → obs in separate FSM states so obsA7 sees the
   re-banked value). VERIFY against Musashi a7 step-for-step.

### MOVEC control-register coverage

`movec` ext word: bit15 = A/D (1=An), bits14:12 = reg#, bits11:0 = Rc (control reg id). Direction
is the opword bit0 (0x4E7A = Rc→Rn, 0x4E7B = Rn→Rc). Implement these Rc:
- **VBR (0x801):** committed `ss.vbr`. WRITE = `ss.setVbr`; then an exception vectors through it
  (lock-step: write VBR, take a trap, observe handler PC = VBR + vec*4).
- **USP (0x800):** committed `ss.usp` (also reachable via MOVE-USP). Read/write the USP bank.
- **CACR (0x002):** RAZ-WI (no cache-enable in this core). WRITE = discard; READ = 0. Documented
  as architecturally-inert (the 040 CACR's only effects are cache enables, which this core lacks).
- **SFC (0x000) / DFC (0x001):** the alt-function-code regs, used by MOVES. We DEFER MOVES, so
  SFC/DFC have no functional consumer. Make them RAZ-WI (read 0 / write discarded) so a `movec`
  to/from them does not trap — documented as inert pending MOVES. (Lock-step note: Musashi tracks
  SFC/DFC; since the trace does NOT emit them and no op reads them, RAZ-WI is indistinguishable
  in the trace. A WRITE-then-READ-back of SFC/DFC would diverge (DUT reads 0, Musashi reads the
  written value) — so DO NOT write a read-back-SFC test; document the limitation.)
- **MSP/ISP (0x803/0x804):** 68040 master/interrupt stack pointers. This core has a SINGLE
  supervisor SP (ssp), no M-bit master/interrupt split. ASSESS: trap as illegal-Rc, OR alias to
  ssp. DECISION: trap-as-unimplemented is wrong (the 040 does NOT trap a valid Rc); but aliasing
  MSP/ISP to ssp would DIVERGE vs Musashi (which keeps them distinct). RESOLUTION: do NOT test
  MSP/ISP (out of scope — needs M-bit/dual-SP banking, a separate slice); document that a `movec`
  to MSP/ISP is currently mishandled and untested. Cover ONLY VBR/USP/CACR + inert SFC/DFC.

  Other Rc ids (TC, DTT0/1, ITT0/1, URP/SRP — 0x003/0x004..0x007): MMU control regs. DEFER (no
  MMU-enable in scope). A `movec` to them currently aliases the RAZ-WI default — document as
  untested/inert. (Musashi DOES model TC/TTRs; a write-then-MMU-enable would diverge, but we
  don't enable the MMU in these programs, so the RAZ-WI inert path is trace-indistinguishable for
  the tested programs.)

### Decode-lane ownership (reconcile with the PARALLEL Track C)

- OWN **0x46C0|ea** = the **opmode-6** case of the 0x4xC0 family (`opword(11 downto 6) === 0x1B`,
  i.e. bits 8:6 = 110 + bits 11:9 = 011 → wait: 0x46C0 = `0100 0110 11 mmmrrr`, so bits 11:9 =
  011, bits 7:6 = 11). The 0x4xC0 family is `0100 ooo0 11 mmmrrr` where ooo selects:
  0=MOVE-from-SR (0x40C0, Track C), 2=MOVE-from-CCR (0x42C0... actually 0x44C0, Track C),
  4=MOVE-to-CCR (0x44C0, Track C), 6=MOVE-to-SR (0x46C0, OURS). Keep our edit to the **opmode-6**
  case ONLY (a distinct `when`), so the controller reconciles with Track C (precedent: the
  ADDX/EXG carve-outs share a line but distinct cases). Do NOT touch LEA(0x41C0)/PEA(0x4840).
- OWN the **0x4E6x** band (MOVE-USP 0x4E60/0x4E68) + **0x4E7x** band (MOVEC 0x4E7A/0x4E7B,
  RTD 0x4E74). NOTE 0x4E73=RTE (existing, assembler-owned), 0x4E75=RTS, 0x4E76=TRAPV,
  0x4E77=RTR (existing) — carve our cases to the EXACT opwords/masks, do not disturb those.
- OWN line-0 **0x0E00** (MOVES) only if implemented — currently DEFERRED (see below).

### STOP / RESET / MOVES assessment

- **STOP (0x4E72) + #data:** loads SR from the imm word THEN halts until an interrupt. Privileged.
  The "load SR" half is a MOVE-to-SR-immediate (reuses our SR-write path); the "halt" half needs a
  pipeline-quiesce + wait-for-interrupt, which the lock-step harness drives by injecting an IRQ.
  ASSESS: the SR-write is straightforward, but the STOP-halt-then-IRQ-wake interacts with the
  interrupt-recognition FSM (`interruptPending`) in a way that is NOT cleanly lock-steppable
  without an IRQ-injection program + a "halted" core state. DECISION: **DEFER** — STOP needs a
  halt/wake state machine + IRQ-coupled lock-step that is its own slice. (If MOVE-to-SR lands
  cleanly with time to spare, revisit STOP as the SR-write + a halt loop; else STOP-and-report.)
- **RESET (0x4E70):** asserts the external RESET line (resets peripherals), NO CPU-state change,
  NOPs internally on the ISS. Privileged. Musashi treats it as a ~no-op (132-cycle stall). It is
  trivially a privileged NOP. ASSESS: lock-steppable as "privileged NOP" (S=1 → no state change,
  PC advances; S=0 → vector-8 trap). DECISION: **DEFER unless cheap** — it rides the exact same
  privilege-check + no-op-commit path as a degenerate system op; include it ONLY if the system-op
  privilege path is already built and adding a zero-effect sysOp kind is a 1-line ROM/selector add.
  Otherwise defer + report.
- **MOVES (0x0E00) + ext word:** moves to/from an ALTERNATE address space (FC = SFC/DFC). Needs
  the SFC/DFC function-code regs to drive a real alternate-space access — but this core has no
  function-code-qualified bus + the MMU is off. DECISION: **DEFER** — MOVES without a functioning
  SFC/DFC-qualified access path would be a fake (it would just be a normal move, diverging from
  Musashi's alt-space semantics under an MMU). Honest defer; document SFC/DFC as inert stubs.

---

## New / changed shared structs + Ctx fields (RECONCILE-LESSON: every builder must assign ALL)

If any of these is added, GREP every builder (`MicroOpAssembler` op-µop + the load/store/RMW/
divl/divrem builders, `Microcode.resolve`, `movemMoveUop`, `movemAnUpdUop`) AND assign it, or the
FullCoreDut gets an unassigned-field LATCH (only the full-core lock-step + 65536-opword
PredecodeWordSpec catch it, NOT fastTest).

- **DecodedUop + RenamedUop:** `sysOp : Bool` (this µop is a commit-time system op),
  `sysKind : UInt` (small enum: MOVE_TO_SR / MOVE_USP_WR / MOVE_USP_RD / MOVEC_WR / MOVEC_RD /
  RESET-nop), `sysRc : UInt(12 bits)` (the MOVEC control-reg id; 0 otherwise), `sysReadDir : Bool`
  (read system→Rn vs write Rn→system), `sysDstArch : UInt(5 bits)` (the Rn for the read direction).
  Some of these can be PACKED/derived to minimize new fields — prefer reusing `faultVector`/`imm`
  where the semantics fit. FINALIZE the minimal field set in Task 1 and list it in the report.
- **ROB:** `sysOpStore`/`sysKindStore`/`sysRcStore`/`sysDstArchStore` Vecs (RegInit, reset
  per-alloc like `faultedStore`), + `sysValStore` (the captured source VALUE for a write-direction,
  from a NEW or reused completion port — try to REUSE the EU writeback the ALU already produces +
  the existing per-entry value capture rather than a brand-new port).
- **ExceptionUnit:** a new `sysTrigger`/`sysKind`/`sysVal`/`sysRc`/`sysDstArch`/`sysPc` input set +
  the FSM states (S_APPLY) that write SystemState + drive the generalized arch-reg PRF write +
  the channel-2 obs (post-state sysByte + re-banked a7) + the redirect. Mirror the RTE path.

---

## Tasks (TDD; commit per task)

- [ ] **Task 1 — Struct fields + decode classification (no behavior yet).** Add the minimal
  `sysOp`/`sysKind`/... fields to DecodedUop + RenamedUop (assign in EVERY builder — grep
  `movemMoveUop`, `movemAnUpdUop`, `Microcode.resolve`, the MicroOpAssembler op/load/store/rmw/
  divl/divrem builders). Classify the opwords in OperationDecoder (opmode-6 0x46C0 = MOVE-to-SR;
  0x4E60/0x4E68 = MOVE-USP; 0x4E7A/0x4E7B = MOVEC) + thread through RenameStage. TEST:
  OperationDecoderSpec asserts the new opwords decode non-illegal with the right sysKind +
  PredecodeWordSpec 65536-parity stays green (predecode must FRAME these — add predecode cases so
  nextPc != pc; MOVE-USP/MOVEC are 2 words for MOVEC ext, MOVE-to-SR is 1 word + EA-ext-sized).
  COMMIT.

- [ ] **Task 2 — RTD (0x4E74) µcode crack, NON-system, NON-privileged.** Add a MOVEC/RTD-independent
  RTD ROM sequence (or a MicroOpAssembler crack if ≤3 µops): [pop PC ← (A7) load → T0][A7 += 4 +
  disp16 ADD → A7][ibranch to T0]. Reuse the RTS pop + the ibranch + the A7-add machinery (search
  the existing RTS/0x4E75 crack — RTD = RTS + a disp). Predecode frames RTD as 2 words (opword +
  disp16). TEST: lock-step `rtd #disp` (push a return addr, RTD, land at it; A7 = old+4+disp) vs
  Musashi (PC + A7 step-for-step). COMMIT.

- [ ] **Task 3 — Commit-time system-op INFRA + privilege trap.** ROB: `sysOpStore` Vecs + the
  serializing-retire gate (a sysOp head retires ALONE, like faultRetire/rteRetire — add `sysRetire
  = headReady && sysOpStore(h0) && excIdle`; gate retire0/retire1/interrupt off it). PRIVILEGE: a
  sysOp head with `exc.ss.s == 0` ⇒ drive `exceptionPending` with vector 8 (format-$0) INSTEAD of
  `sysTrigger` (reuse the entry path). ExceptionUnit: add the `sysTrigger` input + an S_APPLY FSM
  state that (for now) does NOTHING but pulse the channel-2 obs (post-state == current state) +
  redirect to nextPc (serialize). TEST: lock-step a privileged sysOp in USER mode (S=0) → vector-8
  trap → handler → RTE (PC/SR/A7); + a sysOp in SUPER mode that is a structural no-op still
  serializes + commits (PC advances). COMMIT.

- [ ] **Task 4 — MOVE USP (0x4E60 An→USP / 0x4E68 USP→An).** WRITE (An→USP): capture the An source
  VALUE (the µop reads srcA=An via the ALU EU MOVE; `sysValStore` captures the writeback); S_APPLY
  drives `ss.setUsp := sysVal`. READ (USP→An): S_APPLY reads `ss.usp` and writes int PRF arch-An
  via the generalized arch-reg write port; obs carries the An result. TEST: lock-step (super mode)
  `move %a3,%usp ; move %usp,%a4` round-trip (USP written then read back into A4 == A3); + the
  banking proof: set USP, switch to user via MOVE-to-SR (Task 5) — deferred to Task 5's test.
  COMMIT.

- [ ] **Task 5 — MOVE to SR (0x46C0|ea) — SR write + A7 banking + serialization.** Capture the EA
  source .W VALUE (ALU EU MOVE result → `sysValStore`). S_APPLY: `ss.setSrSys := sysVal[15:8]`,
  CCR `committedCcr := sysVal[4:0]`, and re-bank A7 (write int PRF arch-15 with the NEW-S bank's
  value; obs sysByte = new system byte, obsA7 = re-banked a7). Redirect to nextPc (serialize).
  TEST (the heavy one): lock-step S=1→S=0 (set SSP+USP distinct, MOVE-to-SR clearing S → A7 banks
  to USP; verify a7 == USP) + S=0→S=1 path (set S via MOVE-to-SR in super... note user-mode
  MOVE-to-SR raising S re-enters super: actually a user-mode MOVE-to-SR TRAPS (priv) — so the
  S=0→S=1 transition is exercised by the EXCEPTION entry, already tested; the MOVE-to-SR S=1→S=0
  user-switch is the new path) + the privilege trap (a MOVE-to-SR executed at S=0 → vector 8).
  Lock-step FULL arch state (A7/SR incl CCR). COMMIT.

- [ ] **Task 6 — MOVEC (0x4E7A Rc→Rn / 0x4E7B Rn→Rc): VBR + USP + CACR (RAZ-WI) + inert SFC/DFC.**
  Decode the ext word (A/D, reg#, Rc). WRITE (Rn→Rc): S_APPLY routes `sysVal` by Rc to
  `ss.setVbr`/`ss.setUsp`/CACR-discard/SFC-DFC-discard. READ (Rc→Rn): S_APPLY muxes the committed
  reg (vbr/usp/CACR=0/SFC=DFC=0) → int PRF arch-Rn. TEST: lock-step `movec %d0,%vbr` then take a
  trap (handler PC = new VBR + vec*4) → proves VBR; `movec %a0,%usp ; movec %usp,%a1` round-trip;
  `movec %cacr,%d1` reads 0 (RAZ) and `movec %d2,%cacr` is inert (write-then-readback reads what
  Musashi reads — VERIFY Musashi CACR readback: if Musashi returns the written value, do NOT
  write-then-readback CACR; only test RAZ on a fresh CACR or a value Musashi also masks). COMMIT.

- [ ] **Task 7 — RESET (0x4E70) IF cheap (privileged NOP), else DEFER + document.** A zero-effect
  sysKind that only privilege-checks + serializes + commits PC. Add ONLY if Task 3's infra makes
  it a ~1-line add. TEST: lock-step `reset` in super (no state change, PC advances) + in user
  (vector-8 trap). Else SKIP + report the defer reason. COMMIT (or skip).

- [ ] **Task 8 — Decode/parity/fastTest + ≥200 OOC synth gate.** Run OperationDecoderSpec +
  PredecodeWordSpec (65536) + fastTest + the full ExecuteLockStepSpec (×2 reseeded if the harness
  reseeds). Then the OOC synth gate: `pgrep -af vivado` FIRST (Track C may be gating — WAIT; never
  two vivados, never Verilator+vivado concurrently). Report FMax + worst path. COMMIT any synth tcl.

---

## Validation matrix (lock-step vs Musashi, FULL arch state A7/SR/USP/VBR observable in the trace)

| Scenario | What it proves | Observable in trace |
|---|---|---|
| RTD `#disp` | pop PC + A7 += 4+disp + jump | PC + a7 step-for-step |
| MOVE-to-SR S=1→S=0 | S-bit write + A7 banks to USP | a7 switches to USP value; sr system byte |
| MOVE-to-SR @ S=0 | privilege trap vector 8 | handler PC (VBR+8*4) + sr S=1 + a7=SSP-frame |
| MOVE-USP both ways | usp bank read/write | a4 == a3 after round-trip; a7 after user-switch |
| MOVEC VBR + trap | VBR drives the vector fetch | handler PC = newVBR + vec*4 |
| MOVEC USP round-trip | usp via MOVEC | an == written value |
| MOVEC CACR | RAZ-WI inert | reads 0 (or Musashi's masked value) |
| RESET (if done) | privileged NOP | PC advances (super) / vector-8 (user) |

---

## Rules / hazards (carried from the dispatch)

- REAL impl only — NEVER weaken/relabel a test. The banking/SR ops are exactly where a subtle
  wrong result hides; lock-step the FULL architectural state.
- NO const-folding the MMU/SR.
- RECONCILE-LESSON: every new struct/Ctx field assigned in EVERY builder (grep them all).
- Each `-z` sbt subset runs in its OWN `JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt …` JVM,
  logging to /home/qwertyoruiop/tmp.
- ≥200 OOC gate: `pgrep -af vivado` FIRST (Track C may be gating — WAIT, never 2 vivados, never
  Verilator+vivado).
- Do NOT merge/delete the worktree — the controller reviews + reconciles with Track C.
- COMMIT EARLY + OFTEN (plan first, then each task) — the controller resumes from the last commit.
