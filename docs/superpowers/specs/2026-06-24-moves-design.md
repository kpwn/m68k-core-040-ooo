# MOVES — Privileged Move to/from Alternate Address Space — Design Spec

**Date:** 2026-06-24
**Status:** approved (design), pre-implementation
**Scope:** MOVES `.B/.W/.L` (68010+ privileged) + real SFC/DFC registers. Penultimate integer-ISA slice (then bit-field-mem 3c).

---

## 0. Summary & the lock-steppability resolution

MOVES moves a register to/from a memory EA, qualifying the bus access with the SFC/DFC
function-code registers. **It is FULLY lock-step-able**: Musashi's `m68ki_read/write_*_fc`
do `(void)fc` — the FC is *stored* in `REG_SFC`/`REG_DFC` but **never used to redirect
address space**; the access is flat memory, identical to a normal MOVE
(`tools/musashi/musashi/m68kcpu.h:1163-1212`). So a functional **flat-space** MOVES in this
core trace-matches Musashi exactly (data movement + An side effect + register state +
privilege trap). The FC-qualified-bus path is unmodeled by **both** — not a divergence,
just an aspect neither models. No decode-only / defer.

Oracle: Musashi `moves` (`m68k_in.c:7260-7357`), quoted §2. Gate ≥200, **multi-regen
surfacing both `_zz_` orderings** (the CAS lesson — MOVES adds decode-area logic too).

---

## 1. Musashi semantics — VERBATIM (the oracle)

`m68k_in.c:7260-7357` (`.L` shown; `.B`/`.W` identical mod size):
```c
if(CPU_TYPE_IS_010_PLUS(CPU_TYPE)) {
  if(FLAG_S) {                              // PRIVILEGED: else m68ki_exception_privilege_violation()
    uint word2 = OPER_I_16();               // 1 ext word
    uint ea    = M68KMAKE_GET_EA_AY_32;     // memory-alterable EA (op[5:0]); incl (An)+/-(An)
    if(BIT_B(word2))                        // ext bit 11 = dr; 1 => Register -> memory (WRITE)
      m68ki_write_32_fc(ea, REG_DFC, REG_DA[(word2 >> 12) & 15]);   // store REG_DA[ext15:12], DFC ignored (flat)
    else                                    // dr=0 => Memory -> register (READ)
      REG_DA[(word2 >> 12) & 15] = m68ki_read_32_fc(ea, REG_SFC);   // load -> REG_DA[ext15:12], SFC ignored (flat)
    return;
  }
  m68ki_exception_privilege_violation();    // vector 8 if S==0 (user mode)
}  else m68ki_exception_illegal();          // <68010
```
- **`.B`/`.W` READ split by destination type** (`BIT_F(word2)` = ext bit 15 = A/D):
  - **An** (bit15=1): `REG_A[ext14:12] = MAKE_INT_8/16(read)` → **sign-extend to 32**.
  - **Dn** (bit15=0): `REG_D[ext14:12] = MASK_OUT_BELOW_8/16(REG_D) | read` → **size-merge** (keep upper bits).
  - `.L`: full 32 to `REG_DA[ext15:12]` (no merge / sign-ext needed).
- **WRITE** (all sizes): store sized `REG_DA[ext15:12]` (= the A/D + reg as a 0-15 index) to (ea).
- **No CCR effect.** FC is stored-but-unused (flat). EA side effect (auto-inc/dec) happens once.

### Ext word (confirmed via gas objdump)
`bit15 = A/D` (1=An, 0=Dn), `bits14:12 = reg#`, `bit11 = dr` (1=write Rn→ea, 0=read ea→Rn).
E.g. `moves.l %d0,(%a0)` = `0e90 0800` (dr=1, D0); `moves.l (%a0),%a3` = `0e90 b000` (dr=0, A3).

### EA-mode mask (opcode table `m68k_in.c:720-722`): `A+-DXWL...`
Memory-alterable **INCLUDING `(An)+` (mode 3) and `-(An)` (mode 4)** — same as CAS. Legal:
modes 2,3,4,5,6,7-0,7-1. Illegal: Dn(0)/An(1)/PC-rel(7-2,7-3)/imm(7-4). **Match this mask
exactly (the CAS `(An)+`/`-(An)` bug lesson).**

---

## 2. SFC/DFC registers (new architectural state)

Today MOVEC RAZ-WI's SFC (id 0x000) / DFC (id 0x001) (`ExceptionUnit.scala:622-632`) — a
**latent divergence**: `movec d0,sfc; movec sfc,d1` should round-trip but reads 0.

- Add **`sfc` + `dfc` as 3-bit registers** to `SystemState` (`exception/SystemState.scala`),
  committed architectural state (banked/restored like the other control regs as appropriate;
  they are simple — no banking needed, just committed regs).
- **MOVEC** reads/writes them: id 0x000→SFC, 0x001→DFC; write takes data[2:0] (upper bits
  ignored on write); read returns the 3-bit value zero-extended to 32 (upper bits read 0).
  Replace the RAZ-WI default for these two ids.
- This both enables MOVES to reference them (even though the access is flat) and **fixes the
  MOVEC SFC/DFC round-trip vs Musashi**. (Musashi `REG_SFC`/`REG_DFC` are 3-bit, masked `&7`.)

Note: because the access is flat, the SFC/DFC *values* do NOT affect MOVES data movement —
but they must round-trip through MOVEC. A MOVES after `movec d0,sfc` reads the same flat
memory regardless of the SFC value, matching Musashi.

---

## 3. Privilege trap — reuse existing

Mark the MOVES µop `needsSupervisor := True` at decode (`MicroOpAssembler.scala`, as
MOVE-from-SR/MOVEC do). The ROB commit-time check (`RobPlugin.scala:300-306`) converts a
`needsSupervisor` head retiring with committed S==0 into a **vector-8 privilege-violation**
(format-$0) — the op does NOT execute, frame stacks entry PC + SR, redirect to vector 8.
No new machinery. (MOVES is illegal on <68010, but this core is a 68040 → always legal-or-
privileged; no CPU-type gate needed.)

---

## 4. The crack (microcode-v2, ~2 µops)

Host = `decode/Microcode.scala` + `DecodeStage.scala`, reusing the CAS auto-inc/dec EA
machinery (`AEaCasLoad`/`AEaCasStore`, `eaAuto`/`eaDelta`, the single-side-effect An
writeback — merged `896d97b`). Temps T0. **No archDepth bump.**

- **WRITE form** (dr=1): `STORE.sz REG_DA[ext15:12] → (ea)` — one store µop. EA auto-inc/dec
  writeback rides the store (like CAS store). Source reg = the A/D+reg 0-15 index.
- **READ form** (dr=0): `LOAD.sz (ea) → T0`; then `MOVE T0 → Rn`:
  - An (ext15=1): `An := signExtend.sz(T0)` (full 32; `.L` = T0).
  - Dn (ext15=0): `Dn := merge.sz(Dn, T0)` (`.B`/`.W` keep upper bits; `.L` = T0).
  - EA auto-inc/dec writeback (postinc for `(An)+`, predec for `-(An)`) once.
- The access is a normal sized LS load/store (FC flat → no bus FC plumbing). No CCR write.
- Exact µop split is the implementer's call (the engine writes one int dst per µop); observable
  state (memory, Rn, An side effect, SFC/DFC, no-CCR) must match §1.

---

## 5. Encoding / decode / predecode

- Line-0 `op[15:8]=0x0E`, `op[7:6]=ss` (00=.B,01=.W,10=.L; 11 is NOT moves), opmode 7
  (currently rejected illegal in OperationDecoder). **Disjoint from CAS** (CAS `op[7:6]=11`).
- Ext word per §1. Decode: dr=ext[11], rnIsA=ext[15], rn=ext[14:12].
- Predecode (`PredecodeWord.scala` + `PredecodeRef.scala`): `len = 2 + eaExt(mode,reg)`,
  `simple`. EA memory-alterable per the §1 mask (reject Dn/An-direct/PC-rel/imm → illegal).
  Mind the variable-length predecode window (route COMPLEX if an EA word is out of window).

---

## 6. Testing (lock-step vs Musashi, supervisor boot SR=0x2700, ×2)

`ExecuteLockStepSpec` (seed mem, check mem+regs):
- **WRITE** Dn→(ea) and An→(ea), all sizes (.B/.W/.L), + read-back (verify the sized store).
- **READ** (ea)→Dn (size-merge: verify upper bits preserved on .B/.W) and (ea)→An (sign-extend
  to 32 on .B/.W; full on .L).
- **`(An)+` / `-(An)`** auto-inc/dec (read and write forms; An updates once by size).
- **Multi-word EA** ((d16,An)/(d8,An,Xn)/abs.l) — nextPc framing + correct address.
- **Privilege trap**: user mode (`initialSr=0x0000`, S=0) → MOVES → **vector 8** (privilege
  violation), op does not execute. (Match Musashi's privilege-violation frame.)
- **MOVEC SFC/DFC round-trip** (NEW, fixes the RAZ-WI gap): `movec d0,%sfc; movec %sfc,d1`
  → d1 == d0&7; same for DFC. Lock-step vs Musashi (Musashi stores 3-bit SFC/DFC).
- **MOVEC SFC then MOVES** (sanity: SFC value doesn't change the flat MOVES result).

New `MovesDecodeSpec`: the three sizes decode to the crack; `(An)+`/`-(An)` legal; Dn/An-
direct/PC-rel/imm illegal; opmode-7 line-0 no longer blanket-illegal.

---

## 7. Build order (staging)

1. **SFC/DFC state + MOVEC** (add regs to SystemState; MOVEC read/write 0x000/0x001;
   MOVEC SFC/DFC round-trip lock-step ×2). Independently testable.
2. **MOVES encoding + predecode + decode detect** (`MovesDecodeSpec` green; privilege flag set).
3. **MOVES crack** (write + read forms, all sizes, An sign-ext / Dn merge; auto-inc/dec) —
   lock-step write/read/auto-inc/dec ×2.
4. **Privilege trap** lock-step (user-mode → vector 8) + multi-word EA framing.
5. **Regression** (existing lock-step byte-identical ×2: CAS/full-ext/bit-field/MOVEM/branch/MOVEC).
6. **POST-ROUTE FMax gate ≥200, MULTI-regen surfacing both `_zz_` orderings** (≥4-6 regens,
   every one ≥200; the CAS lesson — a deterministic 2-regen can hide a sub-200 ordering).

---

## 8. Non-goals

- FC-qualified-bus / true alternate-address-space access (Musashi doesn't model it → untestable;
  documented). If ever added, MOVES would diverge from Musashi and need oracle FC-filtering.
- MOVEC of other control regs beyond what exists (only SFC/DFC are added here).
