# Bit-Field Memory — Dynamic Offset/Width (Slice 3c) — Design Spec

**Date:** 2026-06-30
**Status:** approved (design), pre-implementation
**Scope:** Dynamic (Dn-sourced) offset and/or width for the 8 MEMORY bit-field ops. The LAST integer-ISA slice. Completes the integer 68040 ISA.

---

## 0. Summary & governing constraint

Static-offset/width memory bit-field (BFTST/BFEXTU/BFEXTS/BFFFO + BFCHG/BFCLR/BFSET/BFINS
on memory EAs) shipped in 3a/3b. 3c adds the **dynamic** forms (ext `Do=bit11` → offset is a
Dn; `Dw=bit5` → width is a Dn). The register-form dynamic (Do/Dw on a Dn EA) already works
(`BFRESOLVE` + `bfDynamic` funnel). Predecode already frames the dynamic memory forms (Do/Dw
add no ext words). **CORRECTION (found during investigation): the dynamic memory forms are
NOT gated illegal — they currently SILENTLY MIS-CRACK as static** (the static-mem path reads
the Do/offset-Dn/Dw/width-Dn ext fields as *literal* static offset/width → a SILENT WRONG
RESULT for a valid instruction; untested — no dynamic-mem lock-step exists). This is a latent
correctness bug. 3c must **detect Do/Dw on a memory bit-field and route it AWAY from the
static crack** to the correct dynamic crack (not merely "un-gate").

**GOVERNING CONSTRAINT (user directive): this is a COLD instruction — FMax-neutrality
dominates µop/latency efficiency.** Spend extra µops/cycles freely; add NO new FMax-critical
logic; prefer NO archDepth bump. The gate is **no floor regression vs master** (not merely
≥200) — the front-end is the binding limiter and decode-area additions tipped it sub-floor in
the CAS slice. If the cold path perturbs placement, **shrink the decode footprint / restructure
the cold chain** (we have total freedom — it's cold), do NOT retime a hot path.

Oracle: Musashi bit-field memory ops (`m68k_in.c`), §3 verbatim. Lock-step byte-identical ×2.

---

## 1. The crux — runtime byte base (signed) without special-casing

Static folds `offset>>3` (a 0..3 constant) into the dispatch disp at decode time. Dynamic
computes the byte base at runtime from a full **signed 32-bit** Dn offset.

Musashi (`m68k_in.c`, the memory bit-field ops): `offset = MAKE_INT_32(REG_D[..])` (full
signed); then `ea += offset/8; offset %= 8; if(offset<0){offset += 8; ea--;}`.

**This is exactly two's-complement** `byteBase = ea + (offset >>signed 3)` (arithmetic right
shift = floor(offset/8) for negatives too) and `bitOff = offset & 7` (gives 0..7 for negative
offsets too). **No special-case** — the arithmetic shift + mask reproduce Musashi's signed
floor + negative-remainder adjustment directly. (Verify against Musashi for a couple of negative
offsets in the lock-step, e.g. offset=-1 → byteBase=ea-1, bitOff=7.)

Width: `width = ((widthSrc - 1) & 31) + 1` (0→32), where `widthSrc` = `Dw? REG_D[Dw#] : imm5`.

---

## 2. The crack (microcode-v2, cold — reuse existing structures only)

Reuse: the existing `bfDynamic` funnel (built for the register form — indifferent to whether
lo/hi data came from memory or a reg), `BFRESOLVE` (resolves `{bitOff, width}` → a temp fed as
`srcC`), existing ALU shift/add ops, existing LS load/store µops. **Add no new hot datapath.**

### Always-5-byte span (straight-line engine)
`needHi` (bitOff+width>32) is now runtime, but the engine is straight-line (no data-dependent
branch). So dynamic **always** processes the 5-byte (spill) window: read the spill byte
unconditionally; for RMW, write it back (the funnel leaves it unchanged when the field doesn't
reach it → a no-op write, same principle as always-store CAS). Read-only ops skip all stores.

### Byte base — pre-µop into a temp
A cold ALU step computes `Tbase = eaBase + (offsetDn >>>signed 3)`; `bitOff = offsetDn & 7`
feeds the resolve. The load/store µops address via `Tbase` (lo) and `Tbase+4` (spill).

### Temp budget — FMax-FIRST (no archDepth bump preferred)
The values in flight: byteBase (address), resolved {bitOff,width}, lo, hi, res. Peak naive
liveness is 4-5. **Default (no bump): recompute `byteBase` for the store phase** so the address
temp is dead across the funnel and its slot is reused — keeping the live set within the existing
`T0/T1/T2` (+ the resolve temp). Trades extra cold µops (free) for zero archreg growth. ONLY
hold a dedicated `Tbase` (archDepth 19→20) if the multi-regen gate proves it FMax-neutral
(no floor regression, no field widening). The implementer picks the concrete schedule; the
binding requirement is **FMax-neutral**, then **observable-correct** (§3).

### Sub-chains
- **Read-only** (BFTST/BFEXTU/BFEXTS/BFFFO): compute base + resolve → LOAD lo (+ LOAD spill
  byte) → funnel extract → write Dn result + flags. No store.
- **RMW** (BFCHG/BFCLR/BFSET/BFINS): + funnel lo/hi recompute → STORE lo (+ STORE spill byte).

---

## 3. Musashi semantics — VERBATIM (the oracle)

BFEXTU memory (representative; `m68k_in.c`):
```c
sint offset = (word2>>6)&31; uint width = word2; uint ea = M68KMAKE_GET_EA_AY_8;
if(BIT_B(word2)) offset = MAKE_INT_32(REG_D[offset&7]);   // Do: full signed 32-bit
if(BIT_5(word2)) width  = REG_D[width&7];                 // Dw
ea += offset / 8; offset %= 8; if(offset < 0){ offset += 8; ea--; }   // signed byte base
width = ((width-1) & 31) + 1;                              // 0->32
data = m68ki_read_32(ea); data = MASK_OUT_ABOVE_32(data << offset);
if((offset+width) > 32) data |= (m68ki_read_8(ea+4) << offset) >> 8;  // spill byte
FLAG_N = NFLAG_32(data); data >>= (32 - width); FLAG_Z = data; FLAG_V=FLAG_C=0;
REG_D[(word2>>12)&7] = data;
```
Per-op specifics (match each verbatim from `m68k_in.c`):
- **BFEXTU**: zero-extended field → Dn; N/Z from the left-aligned field; V=C=0.
- **BFEXTS**: sign-extended field → Dn.
- **BFFFO**: result Dn = `offset + (index of first set bit)`; N/Z from the field; the offset
  base is the (possibly dynamic, signed) offset.
- **BFTST**: flags only, no Dn write.
- **BFCHG/BFCLR/BFSET**: flags from the *original* field; write the field back toggled/
  cleared/set.
- **BFINS**: insert `Dn` (low `width` bits) into the field; N/Z from the *inserted* value.

The implementer MUST read each op's exact Musashi body (flags, sign-extend, BFFFO offset add,
the bytewise spill read/write) and match it — the dynamic path only changes how offset/width
+ byte base are *derived*; the field extract/insert/flags are identical to 3a/3b.

---

## 4. Decode / predecode

- **Detect + route** the dynamic memory forms. They currently SILENTLY MIS-CRACK as static
  (NOT illegal): `MicroOpAssembler.isBfMemSpec` runs the 3a static crack with `bfDynamic=False`,
  and `DecodeStage` ucBegin computes offset/needHi from the static fields — both misread the
  Do/Dw fields. T1 must detect `ext[11]||ext[5]` on a memory bit-field and route to the dynamic
  crack (read-only: a `DecodeStage` hook like `slot0IsMemInd`; RMW: a dynamic `ucBegin` entry).
  Reuse the register-form's dynamic offset/width-Dn latching path to keep the
  **decode-area footprint minimal** (the CAS lesson: decode-area growth perturbs the front-end
  floor).
- Legality unchanged from 3b: control-alterable EA only for RMW; PC-rel (7-2/7-3) illegal for
  RMW; read-only ops allow the read EA modes already permitted in 3a.
- **Predecode is already correct** (frames all 8 bfOps; Do/Dw add no ext words) — do not change
  it; just confirm via the exhaustive `PredecodeWordSpec`/`PredecodeRefSpec`.

---

## 5. Testing (lock-step vs Musashi, ×2)

Decode spec (`BitfieldDecodeSpec` or a new one): the dynamic memory forms decode to the crack
(no longer illegal); legality (PC-rel-RMW illegal) preserved.

Lock-step matrix (seed mem, check mem+regs+flags, ×2) — all **8 ops** × {Do-only, Dw-only,
both-dynamic}, including:
- **Small in-byte** offset (0..7) — base unchanged, bitOff only.
- **Large offset** crossing into a higher byte (e.g. offset=20 → byteBase+2, bitOff=4).
- **Negative offset** (e.g. offset=-1, -9) — the signed byte base (`byteBase=ea-1`/`ea-2`),
  the case the two's-complement shift must match Musashi on.
- **Spill** (bitOff+width>32, e.g. bitOff=6,width=30) — the 5-byte window + spill read/write.
- **Dynamic width** incl width-Dn=0 → 32, and a mid width.
- BFINS dynamic (insert), BFFFO dynamic (offset-based result), BFEXTS dynamic (sign-extend).
Template: the existing 3a/3b memory bit-field lock-step tests.

---

## 6. FMax gate — NO FLOOR REGRESSION (the binding gate)

POST-ROUTE, **≥4-6 regens surfacing BOTH `_zz_` orderings; EVERY regen ≥200 AND the floor must
not regress vs master's current floor** (~206-209 on the front-end ibuf/decodePc/bf-funnel
arcs). Because this adds decode/microcode-area logic (cold), the risk is the CAS-style
placement perturbation tipping the front-end sub-floor. If observed:
1. FIRST shrink the cold path's decode/area footprint (reuse more of the existing register-form
   path; drop any added temp/field) — the op is cold, so restructure freely.
2. Only if structurally unavoidable, apply a margin lever — but the goal is for the cold slice
   to be FMax-INVISIBLE.
Report every regen's md5 + FMax + limiting net; confirm no new limiter is in the 3c logic.

---

## 7. Build order

1. Un-gate dynamic memory forms + decode/crack routing (decode spec green; minimal decode footprint).
2. The dynamic crack: byteBase pre-µop + resolve + reuse the bfDynamic funnel; read-only ops first.
3. RMW ops (load→funnel→store, always-5-byte, store-phase byteBase).
4. Lock-step matrix ×2 (the §5 cases, incl. negative/large offset + spill).
5. Regression byte-identical ×2 (static bit-field 3a/3b, register-form dynamic, CAS/MOVES/MOVEM/full-ext).
6. POST-ROUTE multi-regen gate — **no floor regression** (§6).

---

## 8. Non-goals

- No new ALU/funnel/AGU hardware (reuse existing; cold path is all microcode sequencing).
- No archDepth bump unless gate-proven FMax-neutral.
- Dynamic offset/width were already done for the register form — not re-touched.
