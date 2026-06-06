# Task 1 — Diagnosis & mechanism decisions (call/return)

Resolves the spec §5 open items. Grounded in a read of the actual decode/rename/IQ/EU
pipeline (not the spec's notational cracks, which assume capabilities the pipeline does
not yet have).

## Hard constraints discovered (drive every decision)

1. **Per-instruction µop budget.** `MicroOpAssembler.AssembledUops` is `Vec(DecodedUop, 2)`
   (count 1..2): an instruction may crack into AT MOST 2 µops today. `DecodeStage` packs
   the 2 decode slots (≤2 µops each) into the 4-wide `MicroOpQueue` push. RTR needs >2 µops,
   so the budget MUST widen.
2. **One int dest per µop.** `RenameStage` allocates exactly one int freelist pop / one
   `pdst` / one `dstReg` per slot. A µop CANNOT write two int registers. => a postincrement
   *load* (`(A7)+`) cannot write BOTH the loaded value AND the updated A7 in one µop.
3. **Store has a spare int dest.** A store µop writes no int register today, so a
   *predecrement store* (`-(A7)`) CAN carry exactly one int dest = the updated A7 (no 2nd
   port needed). The store DATA path reads a register (`rdData`); there is no immediate
   store-data path yet.
4. **Branch EU reads no int reg today.** It only reads NZVC (`pNzvcSrc`). `RenamedUop`
   already carries `psrcA`/`psrcAValid`; the IQ's `trigInit`/`lsDepInit`/`lsWakeMatch` are
   uop-class-agnostic, so a BRANCH that reads `psrcA` produced by a load WILL correctly
   wait on `lsWakeup` (dynamic-completion). Confirmed: the RTS load→branch dependency is
   handled by the existing variant-A wakeup with no IQ change.
5. **A7 is just int arch reg 15**, renamed normally. The ExceptionUnit's special commit
   A7-write is only because exceptions are commit-side; the OoO call/return path updates
   A7 through ordinary renamed µops — no special gating.
6. **RedirectService already accepts any 32-bit nextPc** via `completionPort.nextPc` +
   `mispredict` (the BranchEU already drives these; ROB does commit-time recovery).
7. Toolchain: `m68k-linux-gnu-as -m68040` assembles BSR/JSR/RTS/RTR/JMP; Musashi executes
   them — the oracle side is ready.

## Decisions

### (a) Indirect / computed-target branch — a FLAG on BRANCH (`ibranch`), target = psrcA + imm
Lowest churn (no new DecOp, no new EU, no new IQ class). The BranchEU gains ONE int read
port: when `ibranch`, `target = psrcA + imm` and the branch is UNCONDITIONAL
(`mispredict := s1Valid`, `nextPc := target`) → drives the existing
`completionPort.{mispredict,nextPc}` → RedirectService. Folding `+imm` into the branch EU
makes it a tiny AGU so JSR/JMP need NO separate "compute EA" µop:
  - `(An)`      → psrcA=An, imm=0
  - `(d16,An)`  → psrcA=An, imm=d16
  - `(xxx).W/.L`→ psrcAValid=false (base 0), imm=absolute
  - `(d16,PC)`  → psrcAValid=false, imm = pc+2+d16 (assembler folds pc, as the load crack does)
  - RTS/RTR     → psrcA=T0 (popped value), imm=0

### (b) Stack push/pop — predecrement STORE carries the A7 write; postincrement is a separate ALU µop
- **PUSH (BSR/JSR)** = a single side-effect predecrement store µop:
  - addr `va = A7 - sz` (LS EU uses `imm = -sz` as the displacement, base = A7),
  - int dst = A7, written with `va` (= A7 - sz) — the store's lone int dest (constraint 3),
  - store DATA = retPC (= nextPc) via a NEW immediate-store-data path on the LS EU
    (`useStoreImm` → data = imm-data field). retPC is a constant, so no extra move µop.
- **POP (RTS/RTR)** = a normal disp-addressed load to a temp + the A7 increment folded into
  the trailing `ibranch` µop. The BranchEU gains ONE int WRITE port: an `ibranch` may carry
  `dstReg = A7`, writing `A7_old + n` (psrcB = A7, imm carries the postinc). This keeps the
  load to ONE int dest (the popped value) — respecting constraint 2 — and needs no extra
  ALU µop.
- **RTR CCR restore** = the first pop load writes the NZVC + X flag regs (reusing the LS
  EU's existing NZVC write port, added for MOVE-to-mem store flags) from the loaded byte —
  restoring ONLY CCR (SR low byte), never the system byte.

### (c) RTS load→branch dependency
Handled by the existing variant-A dynamic wakeup (constraint 4): the pop load is an LS
producer (tracked in `lsBusy`), the `ibranch` reads its `psrcA` → `lsWait` set at push →
cleared on `lsWakeup`. No IQ change.

### (d) µop budget — widen `AssembledUops` to 3, decode ONE instr/cycle when count == 3
`Vec(DecodedUop, 3)`, count 1..3. `DecodeStage`: when slot0 cracks to 3 µops, suppress
slot1 that cycle (push count = 3 ≤ 4; never the 3+2=5 overflow). All other instructions
stay ≤2 so the 2-wide decode is unchanged for them.

## Resulting cracks (all ≤ 3 µops)

```
BSR  : [store.l  #retPC -> -(A7)        ; dst A7 = A7-4]            + [bra pc+2+disp]                          = 2
JMP  : [ibranch  target = An + disp]                                                                          = 1
JSR  : [store.l  #retPC -> -(A7)        ; dst A7 = A7-4]            + [ibranch target = An + disp]             = 2
RTS  : [load.l   (A7) -> T0]                                       + [ibranch target = T0 ; dst A7 = A7+4]    = 2
RTR  : [load.w   (A7) -> CCR (flags)]   + [load.l (A7+2) -> T0]    + [ibranch target = T0 ; dst A7 = A7+6]    = 3
```

retPC = the post-instruction PC (`nextPc`). A7 predecrement is folded into the PUSH store's
address+dst; A7 postincrement is folded into the trailing ibranch's int dst. RTR's word/long
pops are at (A7) and (A7+2); +6 total.

## New mechanism surface (small, contained, reuses existing ports)
- BranchEU: 1 int read port (ibranch target base) + 1 int write port (ibranch An postinc);
  `ibranch` unconditional redirect with `target = psrcA + imm`.
- LS EU: immediate store-data path (PUSH); flag-restore load that writes NZVC+X from the
  loaded byte (RTR CCR).
- `AssembledUops` Vec(3) + DecodeStage 3-µop single-instruction gating.
- `DecodedUop`/`RenamedUop`: an `ibranch` bit (+ reuse psrcA/psrcB/dst/imm); a `storeImm`
  bit; a `ccrRestore` bit. (Field names finalized per task during implementation.)

## Verification posture
Directed specs (JMP, stack push/pop round trip, BSR/RTS, JSR modes, RTR) → then the
ExecuteLockStep gate vs Musashi ×2 seeds → then the honest MMU-live synth gate (≥250,
non-regress vs ~266; new logic is decode-crack + an indirect-target mux + a store-imm/An
write — off the D-cache cone, limiter is the fetch FSM).
