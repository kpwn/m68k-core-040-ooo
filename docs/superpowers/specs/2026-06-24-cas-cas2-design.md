# CAS / CAS2 — Atomic Compare-and-Swap — Design Spec

**Date:** 2026-06-24
**Status:** approved (design), pre-implementation
**Scope:** CAS `.B/.W/.L` + CAS2 `.W/.L` in one slice. The last meaty integer-ISA ops.

---

## 0. Summary

CAS/CAS2 are the 68020+ atomic compare-and-swap instructions (kept unchanged on the
68040). This slice implements both, cracked through the **microcode-v2 engine** (the same
host as bit-field-mem RMW / full-ext memory-indirect). The core's **in-order single-pipe
LS + store-queue** give atomicity essentially **for free** — no bus-lock primitive.

The one mechanism the engine lacks is a *conditional store*. We deliberately avoid building
one: CAS **always stores** the correct mux'd value (write `Du` on match, write the
just-loaded value back on mismatch — architecturally a no-op for RAM). This is a
**documented, reversible simplification** (see §6).

Correctness oracle: Musashi `cas`/`cas2` (`tools/musashi/musashi/m68k_in.c:3260-3434`),
quoted verbatim in §2. Lock-step byte-identical. Gate ≥200 MHz, ≥2 fresh regens.

---

## 1. Atomicity — why it is free (and its limits)

From the LS findings ([[ipc-and-deadlock-findings]] FINDING 3, [[ls-cluster]]):
- **LS issue is in-order on a single pipe.** `IssueQueuePlugin` issues the *oldest* occupied
  LS slot (`ohLoldest = OHMasking.first(lsPresent)`); a younger LS µop never bypasses an
  older one. So the CAS read µop and its store µop — program-order-adjacent within the
  crack — have **no other memory access between them**.
- **The store queue holds + forwards.** A store is buffered at exec, drains only at ROB
  commit, stays resident until `drainAck`, and SQ-forwards to younger loads throughout.
  So the CAS read sees the latest value (via SQ-forward), and the CAS store drains
  atomically; nothing younger interleaves.

**Conclusion:** read→compare→write is atomic per address with **zero new machinery**. No
bus-lock, no LOCK#/RMW-cycle, no barrier.

**CAS2 dual-address limit (matches real 68k + Musashi):** true cross-cache-line atomicity
is *not* a 68k guarantee. Musashi reads both, then writes both, with no interleave between
them — which our in-order single-pipe reproduces exactly (read1, read2, write1, write2 as
four consecutive in-order LS µops). We match Musashi's order; we do **not** claim
multiprocessor atomicity across two lines (neither does real hardware).

---

## 2. Musashi semantics — VERBATIM (the authoritative oracle)

### CAS (`m68k_in.c:3260-3344`, `.L` shown; `.B`/`.W` identical mod size)
```c
uint word2   = OPER_I_16();                 // the single extension word
uint ea      = M68KMAKE_GET_EA_AY_32;       // EA from opword[5:0] (memory-alterable)
uint dest    = m68ki_read_32(ea);           // (1) READ (unconditional)
uint* compare= &REG_D[word2 & 7];           //     Dc = ext[2:0]
uint res     = dest - *compare;             // (2) CMP: res = dest - Dc

FLAG_N = NFLAG_32(res);                      // (3) NZVC from res (a CMP, all four flags)
FLAG_Z = MASK_OUT_ABOVE_32(res);
FLAG_V = VFLAG_SUB_32(*compare, dest, res);
FLAG_C = CFLAG_SUB_32(*compare, dest, res);

if(COND_NE())                                // (4a) MISMATCH (dest != Dc):
    *compare = dest;                         //      Dc := dest  (.B/.W: MASK_OUT_BELOW | dest = merge)
else                                         // (4b) MATCH (dest == Dc):
{
    USE_CYCLES(3);
    m68ki_write_32(ea, REG_D[(word2 >> 6) & 7]);  //  write Du to ea;  Du = ext[8:6]
}
```
- `.B`: `*compare = MASK_OUT_BELOW_8(*compare) | dest;` write `MASK_OUT_ABOVE_8(REG_D[Du])`.
- `.W`: `*compare = MASK_OUT_BELOW_16(*compare) | dest;` write `MASK_OUT_ABOVE_16(REG_D[Du])`.
- `.L`: `*compare = dest;` write `REG_D[Du]` (full 32).
- **Ext word:** `Dc = ext[2:0]`, `Du = ext[8:6]`. Bits [15:9],[5:3] reserved.

### CAS2 (`m68k_in.c:3347-3434`, `.L` shown)
```c
uint word2    = OPER_I_32();                 // BOTH ext words as one 32-bit fetch (ext1 hi, ext2 lo)
uint* compare1= &REG_D[(word2 >> 16) & 7];   //  Dc1 = ext1[2:0]
uint  ea1     = REG_DA[(word2 >> 28) & 15];  //  Rn1 = ext1[15:12]  (REG_DA: 0-7=Dn, 8-15=An)
uint  dest1   = m68ki_read_32(ea1);          // (1a) READ addr1 (UNCONDITIONAL)
uint  res1    = dest1 - *compare1;
uint* compare2= &REG_D[word2 & 7];           //  Dc2 = ext2[2:0]
uint  ea2     = REG_DA[(word2 >> 12) & 15];  //  Rn2 = ext2[15:12]
uint  dest2   = m68ki_read_32(ea2);          // (1b) READ addr2 (UNCONDITIONAL — NOT conditional)
uint  res2;

FLAG_N=NFLAG_32(res1); FLAG_Z=MASK_OUT_ABOVE_32(res1);   // (2) flags from res1
FLAG_V=VFLAG_SUB_32(*compare1,dest1,res1); FLAG_C=CFLAG_SUB_32(*compare1,dest1,res1);

if(COND_EQ())                                // (3) IF dest1==Dc1:
{
    res2 = dest2 - *compare2;
    FLAG_N=NFLAG_32(res2); FLAG_Z=MASK_OUT_ABOVE_32(res2);   //  flags OVERWRITTEN by res2
    FLAG_V=VFLAG_SUB_32(*compare2,dest2,res2); FLAG_C=CFLAG_SUB_32(*compare2,dest2,res2);
    if(COND_EQ())                            // (4) IF BOTH match:
    {
        USE_CYCLES(3);
        m68ki_write_32(ea1, REG_D[(word2 >> 22) & 7]);   //  write Du1 = ext1[8:6]  -> addr1
        m68ki_write_32(ea2, REG_D[(word2 >>  6) & 7]);   //  write Du2 = ext2[8:6]  -> addr2
        return;
    }
}
*compare1 = dest1;                           // (5) MISMATCH (either): Dc1:=dest1 AND Dc2:=dest2
*compare2 = dest2;                           //     (BOTH updated, UNCONDITIONALLY, in the mismatch path)
```
- **`.W` CAS2** mismatch update uses sign-ext flags: `*compare1 = BIT_1F(word2) ? MAKE_INT_16(dest1) : MASK_OUT_BELOW_16(*compare1)|dest1;` and `*compare2 = BIT_F(word2) ? MAKE_INT_16(dest2) : MASK_OUT_BELOW_16(*compare2)|dest2;`. The implementer MUST look up `BIT_1F`/`BIT_F`/`MAKE_INT_16` in `m68kcpu.h` and match exactly (`.W` only; `.L` is the plain full assignment above).
- **Ext words:** ext1 → Rn1=`[15:12]` (D/A+reg), Du1=`[8:6]`, Dc1=`[2:0]`; ext2 → Rn2,Du2,Dc2 likewise. Encoded via the combined 32-bit `word2` as the shifts above.

### KEY semantic facts (corrects an earlier mis-read)
1. **Both CAS2 addresses are read UNCONDITIONALLY** (lines 3354+3358 / 3399+3403). There is NO conditional second read. → our "read both" crack matches Musashi exactly.
2. **On ANY mismatch, BOTH Dc1 and Dc2 are updated** (lines 3429-3430). Not "Dc2 unchanged if 1st mismatches."
3. **Final flags = res2 if dest1==Dc1, else res1.** (res2 overwrites only inside the `if(COND_EQ())`.)
4. **Writes happen only when BOTH match**; otherwise neither address is written.

---

## 3. Encoding & decode

Line-0, opword `[15:6]` patterns (confirm via gas `m68k-linux-gnu-as -m68040` byte output
AND Musashi `m68kops.h` — these are the guide, gas/Musashi are authoritative):
- CAS.B `0000101011`, CAS.W `0000110011`, CAS.L `0000111011`, with EA in `[5:0]`
  (memory-alterable control mode; An-direct / Dn / imm / PC-rel are ILLEGAL).
- CAS2.W / CAS2.L: opword `[5:0] = 111100` (mode 7 / reg 4) — no EA; the two addresses are
  register-indirect via `Rn1`/`Rn2` in the ext words.

**Predecode (`frontend/PredecodeWord.scala`):** new arm.
- CAS: `len = 2 + eaExt(mode,reg)` words (opword + 1 ext + EA extension words), `simple`.
  Reject non-memory-alterable EA (frame as illegal so it faults precisely).
- CAS2: fixed `len = 3` words (opword + 2 ext), `simple`.
- Mind the variable-length predecode window (the full-ext `.L`-imm lesson): if the relevant
  ext/EA word is beyond the per-word window, prefer routing as COMPLEX over mis-framing.

**OperationDecoder / DecodeStage:** CAS/CAS2 are COMPLEX (microcode-cracked), like MOVEM /
bit-field-mem / full-ext-mem-indirect. Add the detect + the crack routing.

---

## 4. The crack (microcode-v2 engine)

Host = `decode/Microcode.scala` + `decode/DecodeStage.scala`. Temps T0/T1/T2 (archDepth 19,
**no bump**). The new compute is a small **CAS datapath** reusing the existing CMP flag
logic (`AluDatapath` CMP path already computes NZVC = a−b for all sizes).

### CAS — 3 µops
```
µ0  LOAD.sz   (ea) -> T0                         (isFirst; EA via EaDecoder, mem-alterable)
µ1  CASCMP    T0 vs Dc  ->  NZVC                   (CMP flags: res = T0 - Dc)
            ->  storeData T1 = (Z ? Du : T0)       (Z==1 means match -> store Du; else write-back T0)
            ->  Dc  := (Z ? Dc : merge.sz(Dc,T0))  (mismatch -> Dc:=merge; match -> unchanged)
µ2  STORE.sz  T1 -> (ea)                          (isLast; UNCONDITIONAL store of the mux'd value)
```
- `merge.sz`: `.B`/`.W` keep Dc's upper bits, replace low byte/word with T0; `.L` = T0.
- Flags exactly per §2 CAS: N/Z/V/C from `T0 - Dc`, size-correct.
- The store of T0-on-mismatch writes the SAME bytes already in memory → architecturally a
  no-op for RAM (see §6 for the consequence + walk-back).
- `µ1` writes TWO destinations (NZVC and Dc) plus produces a temp. Implement as the engine
  finds cleanest (e.g. a CASCMP op that writes Dc + NZVC, and the store reads a separately
  mux'd data temp; or split into compute + a Dc-writeback µop). The exact µop split is an
  implementation choice; the **observable** semantics (memory, Dc, NZVC) must match §2.

### CAS2 — ~6 µops
```
µ0  LOAD.sz   (Rn1) -> T0                          (isFirst)
µ1  LOAD.sz   (Rn2) -> T1
µ2  CAS2CMP   eq1 = (T0==Dc1.sz), eq2 = (T1==Dc2.sz), bothEq = eq1 & eq2
            ->  NZVC = eq1 ? cmp(T1,Dc2) : cmp(T0,Dc1)     (Musashi flag-overwrite rule)
            ->  storeData1 = bothEq ? Du1 : T0   (into a temp / reuse T0)
            ->  storeData2 = bothEq ? Du2 : T1   (into a temp / reuse T1)
            ->  Dc1 := bothEq ? Dc1 : merge.sz(Dc1,T0)     (mismatch -> update BOTH per §2 fact 2)
            ->  Dc2 := bothEq ? Dc2 : merge.sz(Dc2,T1)
µ3  STORE.sz  storeData1 -> (Rn1)                  (always; no-op bytes on mismatch)
µ4  STORE.sz  storeData2 -> (Rn2)                  (isLast)
```
- Rn1/Rn2 are An-OR-Dn register-indirect (the full 32-bit reg as the address), per `REG_DA`.
- Reads are unconditional (matches §2 fact 1); the conditional *semantics* live entirely in
  the mux/flag logic of µ2.
- `.W` Dc-update sign-ext (`BIT_1F`/`BIT_F`) — match §2 / `m68kcpu.h`.
- 3 temps suffice (T0=read1, T1=read2, T2 for an intermediate / store-data); confirm no
  archDepth bump.

---

## 5. Faults & ordering

- CAS read/write and CAS2's reads/writes are **normal LS accesses** → translate + fault
  precisely via the existing LS/DTLB path (page fault, bus error). Frame stacked at the
  faulting µop's instruction PC (the CAS/CAS2 opword), per the precise-exception machinery.
- In-order issue keeps the four CAS2 accesses ordered read1,read2,write1,write2.
- **Consequence of always-store (§6):** a CAS to a *write-protected* page whose compare
  *mismatches* will take a **write fault** that a true-conditional-store 68040 would NOT
  (real CAS reads-only on mismatch). Irrelevant to the writable-RAM lock-step; called out
  as part of the documented simplification.

---

## 6. The always-store simplification — DOCUMENTED & REVERSIBLE

**Decision (user-approved):** the engine has no conditional store; rather than build one,
CAS/CAS2 **always** issue their store(s), writing the mux'd value: `Du` on match, the
just-loaded value on mismatch. For normal RAM this is **architecturally byte-identical** to
Musashi (memory ends with the same bytes; regs + flags identical), so lock-step passes.

**Known consequences (the cost of the simplification):**
1. A redundant bus write on mismatch (invisible to the architectural oracle; matters only
   to an external bus observer we don't model).
2. A CAS to a write-protected page that *mismatches* faults on the write where a real
   68040 would not (§5).
Neither affects the writable-RAM lock-step.

**Walk-back path (if real-bus fidelity is ever needed):** add a true conditional store — a
STORE µop carrying a CCR condition that reads NZVC and **suppresses the SQ alloc** when the
condition fails (analogous to Scc/Bcc reading NZVC, but gating the LS-EU store), plus a
conditional Dc-writeback. Then CAS/CAS2 emit a conditional store instead of the always-store
mux. This is localized to the LS-EU + the CAS µops; the decode/crack stay the same.

This is recorded in memory ([[isa-completion-roadmap]]) as a deliberate, reversible gap.

---

## 7. Testing (lock-step vs Musashi)

New decode spec (`CasDecodeSpec`): the three CAS sizes + CAS2 W/L encode to the right µop
crack; non-memory-alterable CAS EA → illegal; CAS2 mode/reg = 7/4.

New lock-step matrix (`ExecuteLockStepSpec`, seed memory + check mem + regs + flags ×2):
- **CAS match** (.B/.W/.L): mem==Dc → mem:=Du, Dc unchanged, Z set, NZVC correct.
- **CAS mismatch** (.B/.W/.L): mem!=Dc → Dc:=merge(Dc,mem), mem unchanged-value, flags from `mem-Dc`. Verify `.B`/`.W` upper-bits of Dc preserved.
- **CAS2 both-match** (.W/.L): both → both written, flags from res2.
- **CAS2 1st-mismatch**: flags from res1; Dc1:=dest1 AND Dc2:=dest2 (per §2 fact 2); no writes.
- **CAS2 2nd-mismatch** (1st matches): flags from res2; both Dc updated; no writes.
- **CAS2 Rn1==Rn2 (same address)** edge — match Musashi's read1,read2 (both read same loc).
- **CAS2 with An and Dn mixed** as Rn (REG_DA 0-15).
- A CAS inside a tight loop (lock-free counter) to exercise the atomic RMW under back-to-back drains.

Assemble all via gas (`m68k-linux-gnu-as -m68040`) — confirm gas emits CAS/CAS2 (it does for
-m68040); cross-check the bytes against the predecode/decode.

---

## 8. Build order (staging within the slice)

1. Encoding + predecode framing + decode detect (CAS first, then CAS2) — `CasDecodeSpec` green.
2. CAS crack (load → CASCMP → store) + the CASCMP datapath (reuse CMP flags + the mux'd
   store-data + merged Dc) — CAS lock-step match + mismatch, all sizes, ×2.
3. CAS2 crack (dual read → CAS2CMP → dual store) — CAS2 lock-step all paths, ×2.
4. Full regression (existing LS/RMW/bit-field/full-ext/branch/MOVEM lock-step) byte-identical ×2.
5. POST-ROUTE FMax gate ≥200, ≥2 fresh regens (decode/microcode/LS — off the front-end hot
   path; expect low risk, but the synth-gate rule is mandatory). Report.

---

## 9. Non-goals

- TAS-memory (deferred; same RMW family — could reuse this machinery later).
- A general conditional-store primitive (the walk-back, §6 — not built now).
- Multiprocessor / true cross-line CAS2 atomicity (not a 68k guarantee).
