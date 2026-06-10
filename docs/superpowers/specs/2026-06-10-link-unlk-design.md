# LINK / UNLK ISA slice — design

Date: 2026-06-10. Branch `feat/link-unlk` (off master c898892).

## Scope

Line-4 stack-frame ops (NO flags), reusing the existing call/return crack machinery
(BSR/JSR predecrement-store `stkPush`, the RTS/RTR pop-load, A7/An int writes):

- `LINK An,#disp16` — opword `0x4E50 | An` (`0100 1110 0101 0aaa`) + a disp16 ext word.
  Semantics (Musashi `link,16,.,.`): `push_32(An)` (A7 -= 4; mem[A7] := old An);
  `An := A7` (the new, predecremented A7 — the frame pointer); `A7 := A7 + sext(disp16)`.
- `UNLK An` — opword `0x4E58 | An` (`0100 1110 0101 1aaa`), single word. Semantics
  (Musashi `unlk,32,.,.`): `A7 := An`; `An := mem[A7]`; `A7 += 4` (pop the saved value
  into An). Net: `An := mem[old An]`, `A7 := old An + 4`.

DEFERRED (noted, not built):
- `LINK.L An,#disp32` (`0x4808 | An`) — 68020+, out of scope.
- `LINK A7,#disp16` (An == A7) — a degenerate/nonsensical encoding (A7 is the SP, not a
  frame pointer). Musashi's a7-special case pushes the ALREADY-DECREMENTED A7 and the
  general crack here would push the original A7 / overwrite the final A7. Documented as a
  known deviation; NOT exercised by lock-step. (`UNLK A7` IS handled correctly — see below.)

## The crack (mirrors BSR/JSR/RTS/RTR; no new EU datapath, no flags)

The macro instruction must produce EXACTLY ONE kept ROB commit record (oracle step) even
though LINK/UNLK each write TWO architectural registers (An + A7). The whitebox lock-step
comparator (LockStep.compare) validates, per kept step: pc, ccr/sr, A7 (always, via the
folded `a7Run`), and ONE arch reg (the kept µop's `dstArch`). The fold of A7 (`a7Run`)
accumulates over ALL µops in retire order — including DROPPED ones — so the kept step must
be the LAST µop (so a7Run is final), and it should write the OTHER arch reg (An) so BOTH
An (as the checked archReg) and A7 (as a7Run) are validated at that single step.

Drop mechanisms available (a dropped record's arch write still FOLDS):
- stkPush store -> `wbObs.divRem := compStkPush` (LS EU), already dropped.
- a TEMP write (arch >= 16) -> dropped by the whitebox (isTempOnly).
- NEW (this slice): the ALU EU surfaces `wbObs.divRem := u.divIsRem` (was hardcoded
  False). `divIsRem` is only ever set on the CPLX DIVREM µop (never an ALU op), so reusing
  it as the GENERIC "drop this ALU crack µop's commit record (its arch write still folds)"
  marker adds ZERO new threaded fields. The A7-fold ALU µop of LINK/UNLK sets divIsRem.

### LINK An,#disp16 — 3 µops

0. stkPush store, dst = An, data = old An (NEW: stkPush data from a register).
   base = A7 (psrcA), store data = An (psrcB), int dst = A7 (the predecrement: A7 := A7-4).
   Reuses stkPush (addr = A7 - 4, int write A7 := s1Va = A7-4). DROPPED (stkPush), folds
   a7Run := A7-4. Pushes the OLD An (psrcB read pre-write).
1. A7 := A7 + sext(disp16) — ALU ADD, srcA = A7 (reads the post-µop0 A7 = A7-4), imm =
   disp, dst = A7, LONG, no flags. DROPPED via `divIsRem`, folds a7Run := (A7-4)+disp = the
   FINAL A7.
2. An := A7 - disp — ALU ADD, srcA = A7 (reads the final A7), imm = -disp, dst = An,
   LONG, no flags -> An := A7-4 (the frame pointer). KEPT (writes An, the checked archReg;
   a7Run already final; carries the LINK pc).

### UNLK An — 3 µops

0. load.l (An) -> T0 — pop-load, addr = An + 0, dst = T0 (TEMP). DROPPED (temp).
   T0 = mem[old An] (the saved frame value).
1. A7 := An + 4 — ALU ADD, srcA = An (reads the ORIGINAL An), imm = 4, dst = A7, LONG,
   no flags. DROPPED via `divIsRem`, folds a7Run := old An + 4 = the FINAL A7.
2. An := T0 — MOVE T0 -> An. KEPT (writes An = mem[old An], the checked archReg; a7Run
   final; carries the UNLK pc).

UNLK A7 (An == A7): µop1 writes A7 := A7+4, µop2 writes A7(=An) := T0 = mem[A7] LAST -> final
A7 = mem[A7] = Musashi's `read_32(A7)`. Correct (µop2's later write wins). Hence UNLK A7 is
handled; only LINK A7 is deferred.

## RTL touch points

- `PredecodeWord` line-4: LINK (0x4E5, bit3=0) -> simple len 2 (opword + disp16); UNLK
  (0x4E5, bit3=1) -> simple len 1.
- `MicroOpAssembler`: recognize isLinkOp/isUnlkOp (op[15:4]==0x4E5, bit3 select); add to
  the `bad` exemption list; build the 3-µop cracks; add to the sequence-selection chain.
- `LsEuPlugin` S0 store-data mux: data0 = Mux(u0.stkPush && !u0.srcBValid, u0.imm, rdData.data)
  so a stkPush with a valid srcB pushes the REGISTER (LINK old An); BSR/JSR (srcB invalid)
  still push imm (retPC), unchanged.
- `AluEuPlugin` wbObs: divRem := RegNext(Mux(s3Valid, u3.divIsRem, u1.divIsRem)) (was False).

## Tests

- Decode spec (LinkUnlkDecodeSpec): LINK/UNLK µop counts (3 each) + per-µop fields + predecode lengths.
- Lock-step vs Musashi (each its OWN JVM, -z subset): LINK then UNLK round trip (checkMem on
  the pushed value); nested LINK/UNLK; positive + negative disp.
