# CMP2 / CHK2 (020+ bounds-check against a memory pair) — Design

**Status:** Draft — controller-driven (ISA-completion bounded-ops phase, slice 3).
**Date:** 2026-06-15
**Parent:** [[isa-completion-roadmap]]. Reuses the LS-EU load crack + the CHK `EuFault` vector-6 trap ([[exception-subsystem]]) + the indexed-EA `psrcC` 3rd-source threading + the ALU/CCR read-modify-write.

## 1. Instruction
CMP2/CHK2 — compare a register against a pair of bounds in memory (68020/030/040).
- **Opword:** `0000 0ss0 11 mmm rrr` — bits[15:12]=0000, bit11=0, bits[10:9]=`ss` (size: 00=.B,01=.W,10=.L), bit8=0, bits[7:6]=`11`, bits[5:3]=`mmm` (EA mode), bits[2:0]=`rrr` (EA reg). The EA is a **CONTROL addressing mode** (the pointer to the bounds pair).
- **Extension word:** `A/D(bit15) | Rn(bits[14:12]) | R/M(bit11) | 00000000000`. The 4-bit field `(word2>>12)&15` indexes D0-7 (A/D=0) or A0-7 (A/D=1) = the register compared. `R/M`=0 → CMP2, =1 → CHK2.
- **Semantics** (the EA points to: LOWER bound @ `[ea]`, UPPER bound @ `[ea + size]`, size=1/2/4):
  - **Z bit := (Rn == lower) || (Rn == upper)**.
  - **C bit := (Rn < lower) || (Rn > upper)** — SIGNED comparison (out-of-bounds).
  - **N, V: UNCHANGED.** X: unchanged.
  - **CHK2** (R/M=1): if C (out-of-bounds) → TRAP **vector 6** (CHK exception). CMP2 (R/M=0): no trap, flags only.
  - **Sign/zero-extension (transcribe Musashi per-size VERBATIM):** the bounds are loaded and sign-extended: `lower=(int{8,16,32})mem`, `upper=(int{8,16,32})mem`. The compared register: `compare = Rn & {0xff,0xffff,0xffffffff}`; THEN for **.B/.W only**, `if A/D==0 (data reg): compare = sign-extend(compare)` — for an ADDRESS reg (.B/.W) it stays masked (NOT sign-extended); .L uses the full 32 bits. (Musashi `m68k_op_chk2cmp2_{8,16,32}_*` in `tools/musashi/musashi/m68kops.c` — replicate the exact per-size masking + sign-extension; the harness compares the FULL CCR incl. the preserved N/V, so this must be byte-exact.)

## 2. The crack — fits the 3-µop fast budget (NO µcode)
The second bound is at `ea + size`, reachable by adding `size` to the EA displacement (the base/index are shared) — so NO extra ADD µop:
1. **µop1 — LOAD.size @[EA] → T0** (the lower bound). Normal LS load with the instruction's EA (base/index/disp).
2. **µop2 — LOAD.size @[EA + size] → T1** (the upper bound). SAME base/index, `disp := EA_disp + size`. The assembler bakes `+size` into this load's disp/imm. (For abs/PC-rel, the disp is the absolute/PC-relative target; load2 = that + size. PC-rel base = pc+2, per the indexed-EA slice.)
3. **µop3 — the bounds COMPARE** (a new `DecOp.CMP2CHK2` or two DecOps): reads the three operands, computes Z/C per §1, writes the CCR (RMW: `{oldN, newZ, oldV, newC}` — readsNzvc + writesNzvc), and for **CHK2** raises `EuFault{vector=6}` when C (out-of-bounds). Carries: `size`, `isChk2` (R/M), `adReg` (A/D for the Rn-extension rule).

### Operand mapping (IMPORTANT — IQ LS-wakeup):
Both bounds (T0,T1) are LS-produced; Rn is a normal reg. Put the **two LS-produced sources on the IQ-LS-wakeup-tracked ports** and Rn on the third:
- `srcAReg := T0` (lower, LS-produced), `srcBReg := T1` (upper, LS-produced), `srcCReg := Rn` (normal; rides `psrcC` — already threaded decode→rename→IQ for DIV.L-64 / indexed-EA).
- The compare reads two just-loaded regs (T0,T1) → the **multi-LS-source IQ wakeup** (the MOVEM fix: `lsWait` clears only when no other LS source remains busy) must cover BOTH srcA and srcB. VERIFY the IQ LS-wakeup tracks srcA AND srcB for whichever EU hosts this µop; if Rn-as-srcC needs LS-wakeup it does NOT (Rn is a normal reg, statically tracked).
(If the chosen EU can't read 3 sources or the LS-wakeup doesn't cover both bound sources, that's the slice's core integration work — see §3.)

## 3. EU / execute (`execute/...`)
The compare needs: 3 source reads (Rn + 2 bounds), a CCR RMW (Z/C, preserve N/V), and an `EuFault{vec=6}` (CHK2). The **CHK trap lives on the DivEu** (`execute/DivEuPlugin.scala:156-210,340-344`: `chkTrap → captureFault(U(6,8 bits), …)`), and the DivEu already has the `psrcC` 3rd-source port → it is the natural home. Implementer to wire:
- The bounds-compare datapath (transcribe the Musashi flag algo per size; signed compares; the per-size mask + the A/D-conditional sign-extension of Rn; lower/upper sign-extended from the loaded bytes — the loads bring raw size-values, the compare sign-extends).
- **CCR write = RMW preserving N/V:** read old NZVC (`readsNzvc`), write `{oldN, computedZ, oldV, computedC}`. (CHK currently writes N + zeros; CMP2/CHK2 must instead preserve N/V — a partial-flag write. Mirror the to-CCR RMW pattern if the EU doesn't already support partial NZVC.)
- **EuFault for CHK2:** raise `EuFault{robId, vector=6}` iff `isChk2 && C` (out-of-bounds). Identical to CHK's trap path, different condition. CMP2 never raises.
- **IQ LS-wakeup on the DivEu's bound sources:** confirm/extend the cplx-port wakeup so the compare waits for T0 AND T1 (both LS-produced). If the DivEu port lacks multi-LS-source wakeup, that is required slice work (reuse the MOVEM `lsWait`/`lsWakeup` infra).
- (Alternative home = the ALU EU, which already reads LS-produced srcA/srcB + does CCR RMW, but lacks `psrcC` + the EuFault port. Implementer picks the lower-friction integration; the DivEu's CHK-trap + psrcC reuse is recommended.)

## 4. Decode (`OperationDecoder.scala` + `MicroOpAssembler.scala`)
- **Carve-out (ZERO collision — confirmed):** `isCmp2Chk2 = (line==0) && (opmode(op[11:9])==0) && (op[8]==0) && (op[7:6]==3 /*ss=11*/) && (mode(op[5:3]) >= 2)`. The `ss==3` blocks the immediate path (which requires `ss=/=3`); bit8=0 blocks dyn-bit-ops; opmode 0 (≠4) blocks static-bit-ops; mode≥2 blocks MOVEP (mode 001) and excludes Dn/An-direct. **EA must be a CONTROL mode** — reject mode 0/1 (Dn/An), mode 3 (postinc), mode 4 (predec), mode 7-reg≥4 (imm/invalid) → illegal (control modes = (An)=2, (d16,An)=5, (d8,An,Xn)=6, (xxx).W/.L=7/0,7/1, (d16,PC)=7/2, (d8,PC,Xn)=7/3).
- **The crack** (in MicroOpAssembler, like the mem-to-mem / MOVEM 2-load cracks): emit the two loads (T0@EA, T1@EA+size) + the compare µop. Capture from the extension word: `Rn = (word2>>12)&15` (5-bit arch: A/D=0 → 0-7=Dn, A/D=1 → 8-15=An), `isChk2 = word2(11)`, `adReg = word2(15)`. The compare's `psrcC` = Rn. The loads' size = the opword size; the compare's size = same (for the sign-extension).
- Add `DecOp.CMP2CHK2` (+ a `isChk2` field, or reuse an existing 1-bit — e.g. a sub-kind; check `DecodedUop`/`RenamedUop` for a free bit; prefer reusing `bcdSub`-style or a new minimal field threaded decode→rename).

## 5. Predecode framing (`PredecodeWord.scala` + `PredecodeRef`)
Line-0, `opmode 0, bit8=0, ss=3, mode≥2` → **len = 1 (opword) + 1 (extension word) + eaExt(mode,reg)**. The `eaExt` helper already computes EA extension words ((An)=0, (d16,An)=1, indexed=1, abs.W=1, abs.L=2, PC-rel=1). So CMP2/CHK2 = `2 + eaExt`. Mirror in `PredecodeRef`; 65536 parity must hold. (Frame ONLY mode≥2 as CMP2/CHK2; mode 0/1 with ss=3 stays whatever it was — verify it doesn't steal a valid encoding. The #1 bug class: a wrong len → nextPc=pc.)

## 6. Verification
- **Lock-step vs Musashi** (gate): CMP2 in-bounds (Z=0,C=0), Rn==lower (Z=1,C=0), Rn==upper (Z=1), out-of-bounds low (C=1) + high (C=1); CHK2 in-bounds (no trap) + out-of-bounds (→ trap vector 6 → handler → RTE → resume); data-reg AND address-reg Rn; .B/.W/.L; a few EA modes ((An), (d16,An), (d8,An,Xn)). **Assert the FULL CCR incl. N/V UNCHANGED** (set N/V to a sentinel before the op, confirm preserved). Musashi (68040) implements all 18 variants; gas `-m68040`: `cmp2.w (%a0),%d1` / `chk2.l (%a0),%a2`. Confirm gas+Musashi accept the encoding first; if rejected/diverged, STOP + report.
- **Directed decode** `Cmp2Chk2DecodeSpec`: the crack (2 loads + compare) with the right Rn/size/isChk2; control-mode-only (Dn/An/predec/postinc/imm → illegal).
- **Parity** `PredecodeWordSpec` 65536 GREEN; `OperationDecoderSpec` (a new live op may flip a stale assert).
- **fastTest** green.
- **Synth gate (controller):** post-route ≥200; expect a small impact (a compare datapath + possibly a wakeup tweak); honest report (FMax varies ±15 at the placement equilibrium — check the limiter is a pre-existing arc).

## 7. Out of scope
Nothing for CMP2/CHK2 itself (all sizes + control EA modes in). If the 3-source-compare EU integration proves to need large new infra (a fresh EU port + LS-wakeup that doesn't reuse existing infra cleanly), report BLOCKED rather than ballooning — we'd then reconsider the crack (e.g. a µcode 2-subtraction form). The fast 3-µop crack is the target.
