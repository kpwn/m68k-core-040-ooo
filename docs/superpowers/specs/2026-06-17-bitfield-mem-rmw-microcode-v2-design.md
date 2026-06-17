# Bit-field slice 3b — MEMORY-operand RMW ops (BFCHG/BFCLR/BFSET/BFINS), static offset+width — DESIGN

Branch: `feat/exc-private-dcache-port` worktree (off the slice-3a base). DESIGN PHASE — no
production code until the controller signs off.

## Scope

The four READ-MODIFY-WRITE memory bit-field ops with a control-ALTERABLE EA, STATIC
offset (0..31) and STATIC width (1..32, encoded 0->32):

- BFCHG (field ^= maskBase)              bfOp=2
- BFCLR (field &= ~maskBase)             bfOp=4
- BFSET (field |= maskBase)              bfOp=6
- BFINS (field = (field&~maskBase)|insVal, insVal = Dn2<<(32-width))  bfOp=7

EA = control-ALTERABLE only: `(An)` m2, `(d16,An)` m5, `(d8,An,Xn)` m6-brief,
`(xxx).W` 7/0, `(xxx).L` 7/1. **PC-relative (7/2, 7/3) is ILLEGAL** for RMW (it is
read-only — a write to a PC-relative EA is not alterable). `(An)+`/`-(An)`/Dn/An/#imm
remain ILLEGAL (as in 3a). DEFERRED to 3c: dynamic offset/width.

## Semantics (authoritative — tools/musashi/musashi/m68k_in.c m68ki_store_bitfield)

```
offset = ext[10:6] (0..31); width = ((ext[4:0]-1)&31)+1 (1..32); Dn2 = ext[14:12]
byteAddr = EA + (offset>>3);  bitOff = offset&7;  needHi = (bitOff+width) > 32   # STATIC
lo = read_32(byteAddr);  hi = needHi ? read_8(byteAddr+4) : 0
field = (lo<<bitOff) | (hi>>(8-bitOff))                     # 3a forward funnel (reuse)
maskBase = 0xffffffff << (32-width)
# flags from the LOADED field, BEFORE modify (V=C=0, X untouched):
#   CHG/CLR/SET: N=field[31], Z=(field & maskBase)==0
#   BFINS:       insVal=Dn2<<(32-width); N=insVal[31], Z=(insVal==0)
res = CHG: field^maskBase | CLR: field&~maskBase | SET: field|maskBase
    | INS: (field&~maskBase)|insVal
# inverse funnel (store-back):
lomask = (bitOff==0) ? 0 : (0xffffffff << (32-bitOff))      # bitOff=0 -> 0 (<<32 must be 0)
lo'  = (lo  & lomask) | (res >> bitOff)
himask = 0xff >> bitOff
hi'  = (hi  & himask) | ((res << (8-bitOff)) & 0xff)
write byteAddr   <- lo' (LONG, possibly misaligned -> LS s1TwoAccess)
if needHi: write byteAddr+4 <- hi' (BYTE)
```

The datapath modify+flags are FREE: `Bitfield.scala` already computes chgRes/clrRes/
setRes/insRes + N/Z for bfOp 2/4/6/7 when fed `dy = field32, offset = 0`. The forward
funnel (`field32 = (lo<<bitOff)|(hi>>(8-bitOff))`) already exists in `AluEuPlugin`'s
`bfMem` block (3a). The NEW datapath work is the **inverse funnel** (lo'/hi' from res)
and exposing it as a "store form" of the BITFIELD µop.

---

## 0. MATERIALLY SIMPLER ALTERNATIVE DISCOVERED (controller decision requested)

The MicroOpAssembler **fast crack budget is 3 µops** (`AssembledUops.uops = Vec(DecodedUop(),3)`,
MicroOpAssembler.scala:29). Counting the RMW chains:

- **4-byte case** (needHi=False, the COMMON case): `load.L -> T0` ; `compute lo' + flags
  (reads T0)` ; `store.L T_lo' -> byteAddr` = **exactly 3 µops**. **Fits the fast crack
  path** — no microcode engine, no archDepth widening, no T2 (lo' overwrites/uses T0;
  the store reads the compute's dst).
- **5-byte case** (needHi=True, the RARE case — only when bitOff+width>32, i.e. bitOff>0
  AND width>32-bitOff): `load.L -> T0` ; `load.B -> T1` ; `compute res/lo'/hi'` ;
  `store.L -> byteAddr` ; `store.B -> byteAddr+4` = **5-6 µops** -> EXCEEDS 3 -> MUST be
  microcoded, and the {lo,hi,res} coupling needs **3 live temps** (T0=lo, T1=hi, T2=res).

So **the 3rd temp + the microcode-v2 engine are only required for the 5-byte span.** A
materially simpler structure is: keep the 4-byte common case as a 3-µop fast crack (like
3a's load-only path), and route ONLY the 5-byte case through the engine. That confines
the archDepth-19 blast radius to the 5-byte chain and keeps the hot path off the ROM.

**However**, the owner's stated choice is to route ALL RMW through the v2 engine for
uniformity (one code path, one place for the inverse funnel, simpler verification story).
This design documents BOTH and DEFAULTS to the owner's "all-through-engine" plan, but the
**recommendation is the hybrid**: it removes the FMax risk of archDepth-19 entirely for
the common case and is less code. The controller should pick. The two sub-designs share
the datapath (inverse funnel) and the EA-carrying Ctx; only the routing differs.

The rest of this doc specifies the **all-through-engine** plan (the owner's choice) in
full, and notes where the hybrid would differ.

---

## 1. The v2 microcode `Ctx`

The v1 Ctx (opword/pc/nextPc/op/bcdSub/size/sizeBytesLog) is built entirely around
**An-register operands** (Ay/Ax derived from opword bit-fields). The bit-field RMW needs
the **full decoded EA** carried in, because the selector vocabulary has no notion of an
arbitrary base+index+disp. v2 ADDS a bit-field context group; v1's fields are UNCHANGED
(so BCD/ADDX/SUBX resolve identically — see §6).

New fields (all STATIC, latched at `ucBegin`):

```
// --- EA (mirrors EaDecoder output; the same fields the 3a bfm load/store µops carry) ---
val eaBase       : UInt(5 bits)   // base An reg id (8..15); valid per eaBaseValid
val eaBaseValid  : Bool           // (An)/(d16,An)/(d8,An,Xn) -> True; (xxx).W/.L -> False
val eaIndexReg   : UInt(5 bits)   // index reg (Dn/An) for m6-brief
val eaIndexValid : Bool
val eaIndexLong  : Bool
val eaIndexScale : UInt(2 bits)
val eaDispLo     : Bits(32 bits)  // resolved byteAddr disp = EA.disp + (offset>>3)
val eaDispHi     : Bits(32 bits)  // = eaDispLo + 4 (byteAddr+4, the spill byte)
// (PC-rel is illegal for RMW, so NO pcRel fold needed — base is always an An or absolute.)
// --- bit-field static params ---
val bfOp         : Bits(3 bits)   // 2/4/6/7
val bfRawWidth   : UInt(5 bits)   // ext[4:0] (0->32 normalized in the datapath)
val bfBitOff     : UInt(3 bits)   // offset & 7
val bfNeedHi     : Bool           // (bitOff+width) > 32  (selects the entry point, §2)
val bfDn2        : UInt(5 bits)   // ext[14:12] (BFINS insert source) — a 3rd OPERAND
val bfImm        : Bits(32 bits)  // the packed bfMem imm (offset/width/bitOff/needHi/origOff),
                                  //   identical layout to the 3a bfmImm (AluEu already decodes it)
```

**Population at `ucBegin`** (DecodeStage): currently `ucEntryCtx` is filled from
`ucEntrySpec` (the OperationDecoder OpResult) + the entry packet's opword/pc. The EA
fields are NOT in OpResult. TWO options:

- (A) **Run EaDecoder in DecodeStage** on `ucEntryPkt.words` shifted past the bf-ext word
  (`Vec(words(0), words(2), words(3))`, exactly the 3a `bfmEaDec`), and fold `offset>>3`
  into the disp there. This is new combinational logic in DecodeStage's `ucBegin` path.
- (B) **Carry the resolved EA fields through OpResult** (add eaBase/eaDisp/… to the decode
  contract) so DecodeStage just copies them into Ctx. Heavier contract change.

CHOICE: **(A)** — EaDecoder is a pure function already imported by the assembler; running
it in DecodeStage's ucBegin (a once-per-instruction, off-the-hot-path mux) mirrors how the
3a `bfm` block re-decodes the EA. The bf-ext word is `words(1)`; the EA ext words are
`words(2..)`. offset/width/Dn2/bitOff/needHi come from `words(1)` (= bf-ext). This keeps
OpResult unchanged.

---

## 2. Variable length WITHOUT runtime ROM branching (entry-point selection)

`needHi` is STATIC (decode-time). Select the chain by ENTRY POINT — the engine stays
strictly straight-line per chain (v1 contract: nextUpc=µPC+1, single isLast). Two new
entry constants:

```
val BF_RMW_4B_ENTRY = <row index of the 4-byte chain start>
val BF_RMW_5B_ENTRY = <row index of the 5-byte chain start>
```

OperationDecoder sets `ucEntry := needHi ? BF_RMW_5B_ENTRY : BF_RMW_4B_ENTRY`. (Note:
`ucEntry` is `UInt(4 bits)`, DecodeContracts.scala:147. The ROM grows from 6 rows to
~6+3+6 = ~15 rows; **15 < 16 fits 4 bits**. If a future customer pushes romSize past 16,
widen ucEntry — flag, not now.)

### 4-byte chain (needHi=False) — 3 rows

```
µPCa0: LOAD.L  [eaBase + eaDispLo (+index)] -> T0        (isFirst; the misaligned lo)
µPCa1: BITFIELD bfMem(STORE-form) srcA=T0, srcB=Dn2(BFINS only) -> T_lo'  (+NZ flags; macro-commit)
µPCa2: STORE.L T_lo' -> [eaBase + eaDispLo (+index)]      (isLast)
```

Temp assignment: T0 = lo (loaded), the compute writes **T1 = lo'** (kept distinct from
the loaded T0 so the store's dst != its address-base region; either T0-reuse or T1 works
here since lo is dead after compute — pick **T1** for symmetry with the 5-byte chain and
to keep the store's data temp uniform). 3 rows, 1 µop/cycle, fits the engine + push.

**Macro-commit / flag-writer:** µPCa1 (the BITFIELD compute) is `firstOfInstr`? No —
µPCa0 is `isFirst` (the macro boundary, matching BCD's µPC0). The flag write
(writesNzvc, from the loaded field) rides µPCa1. The store µPCa2 writes no arch reg
(its dst is the memory, no An write-back for a non-auto EA).

### 5-byte chain (needHi=True) — 6 rows

```
µPCb0: LOAD.L  [eaBase + eaDispLo] -> T0                 (isFirst; lo)
µPCb1: LOAD.B  [eaBase + eaDispHi] -> T1                 (hi, the spill byte)
µPCb2: BITFIELD bfMem(RES-form)  srcA=T0, srcB=T1, srcC=Dn2(BFINS) -> T2  (=res; +NZ flags)
µPCb3: BITFIELD bfMem(LO-form)   srcA=T0(lo), srcB=T2(res)         -> T1  (=lo'; no flags)
µPCb4: STORE.L T1(lo') -> [eaBase + eaDispLo]
µPCb5: BITFIELD bfMem(HI-form)   srcA=T1?... see NOTE; uses hi(T1 dead) + res(T2) -> Thi
       then  STORE.B Thi -> [eaBase + eaDispHi]          (isLast)
```

PROBLEM: µPCb5 needs BOTH a compute (hi') AND a store in one row, but a row = one µop.
And hi' needs the ORIGINAL hi (T1), but µPCb3 overwrote T1 with lo'. So the temp
schedule must keep lo (T0), hi (T1), res (T2) all live until the LATER computes. Correct
6-µop schedule with **3 temps {T0=lo, T1=hi, T2=res}** and the two stored values landing
in T0/T1 AFTER their source is consumed:

```
µPCb0: LOAD.L  -> T0 (lo)                                (isFirst)
µPCb1: LOAD.B  -> T1 (hi)
µPCb2: BITFIELD RES-form  srcA=T0(lo), srcB=T1(hi), srcC=Dn2 -> T2 (res; +NZ flags) [macro-commit]
µPCb3: BITFIELD LO-form   srcA=T0(lo), srcB=T2(res)          -> T0 (lo' overwrites lo, now dead)
µPCb4: BITFIELD HI-form   srcA=T1(hi), srcB=T2(res)          -> T1 (hi' overwrites hi, now dead)
µPCb5: STORE.L T0(lo') -> [byteAddr]                         (1st store)
µPCb6: STORE.B T1(hi') -> [byteAddr+4]                       (isLast)
```

That is **7 rows** (b0..b6). 3 live temps {T0,T1,T2} simultaneously across b2..b4. 7
µops, 1/cycle — fits the engine (the BCD chain is already 6). romSize = 6 (BCD) + 3 (4B)
+ 7 (5B) = 16 rows -> **ucEntry is UInt(4 bits) = 0..15, exactly full**. To stay strictly
< 16 (so 4-bit ucEntry is unambiguous and leaves a row of headroom), MERGE the two stores'
addresses by noting we could store both from a single misaligned 5-byte... NO — the LS EU
does LONG (s1TwoAccess) + a separate BYTE; two store µops are required. MITIGATION:
either widen `ucEntry` to 5 bits (one-line contract change, FMax-neutral — it is a decode
flop) OR adopt the hybrid (§0) which removes the 4-byte chain from the ROM (romSize = 6 +
7 = 13 < 16). **Recommend widening ucEntry to 5 bits** regardless, as cheap future-proofing.

NOTE on the three BITFIELD compute forms (RES/LO/HI): these are three different outputs of
the SAME inverse-funnel datapath, selected by a 2-bit "bfStoreForm" marker on the µop
(see §4). RES = the existing chgRes/clrRes/setRes/insRes (fed dy=field32, the forward
funnel from T0/T1). LO = `(lo & lomask) | (res>>bitOff)`. HI = `(hi & himask) | ((res<<
(8-bitOff)) & 0xff)`. All combinational, on the existing slow BITFIELD pipe.

---

## 3. The 3rd temp T2 (archDepth 18 -> 19) — EXACT change list

The reg-id is **5 bits** everywhere (`UInt(5 bits)`, handles 0..31), so widening the
arch COUNT 18->19 does **NOT** change any id width. Only the RAT/Freelist depth and the
init loops grow. Confirmed: T2 = 18 is still `>= 16`, so the whitebox temp filter
(`WhiteboxCapture.scala:61 wb.dstArch >= 16`) drops T2 with no test change.

Files / lines:

1. **decode/MicroOpAssembler.scala:21-22** — add `val T2 = 18` (next to T0=16, T1=17).
2. **decode/Microcode.scala:32-35** — uncomment/replace the "no 3rd temp" note with
   `val T2 = MicroOpAssembler.T2 // 18`; add the new ROM rows + entry constants + the
   bfStoreForm selectors (§4); extend Ctx (§1). The v1 BCD rows are UNCHANGED.
3. **rename/RenameStage.scala:29** — `intRat … archDepth = 18` -> `19`.
4. **rename/RenameStage.scala:34** — `intFree … archCount = 18` -> `19` (physCount=50 has
   ample headroom: 50-19 = 31 free phys regs, was 32; the ROB depth / in-flight pressure
   is unchanged — flag a quick free-pool sanity check in the test phase but no math risk).
5. **rename/RenameStage.scala:50** — `initCounter = Reg(UInt(5 bits)) init 0 // 0..17`
   comment + the terminal compare **`when(initCounter === U(17))`** -> `U(18)`; the
   counter still fits 5 bits (0..18). This seeds the int RAT committed RAM identity for
   arch 0..18 (D0-7/A0-7/T0/T1/**T2**).
5b. **isa/Isa.scala:10** — `val ARCH_INT_REGS = 18` -> `19`. This is the CANONICAL arch-int
   count; it feeds `log2Up(ARCH_INT_REGS)` width for `MicroOp.archDst` (types/MicroOp.scala:24)
   and `RobEntry.intArchDst` (types/RobEntry.scala:18). `log2Up(18)=log2Up(19)=5` so those
   WIDTHS are unchanged, but the constant is the single source of truth and MUST move to 19
   for consistency. NOTE the inconsistency: RenameStage hard-codes `18` (items 3/4/5) instead
   of referencing `ARCH_INT_REGS`; the cleanest Phase-B change is to make RenameStage USE the
   constant (`archDepth = Isa.ARCH_INT_REGS`, `archCount = Isa.ARCH_INT_REGS`, init compare
   `=== U(Isa.ARCH_INT_REGS - 1)`), so bumping the constant alone drives the whole widening.
   `committedPhysA7 = committedPhys(15)` (A7) is UNAFFECTED.

6. **rename/RatTable.scala** — NO source change: `archDepth` is a parameter;
   `log2Up(18)=5` and `log2Up(19)=5` -> the addr width (`UInt(log2Up(archDepth) bits)`,
   lines 35/41/46/74/79) is UNCHANGED at 5 bits. `specReg/commReg/location` Vecs
   (lines 67/68/88) grow to 19 entries automatically. The `committedPhys` out-Vec
   (line 52) grows to 19 — confirm the ROB/whitebox consumer of committedPhys (if any)
   iterates `archDepth`, not a hard 18.
7. **rename/Freelist.scala** — NO source change: `archCount`/`physCount` are parameters;
   the init counter / pointers derive from them.
8. Grep sweep to do in Phase B: `grep -rn "\b18\b\|archDepth\|archCount" src/main` to
   confirm NO other hard-coded 18 (e.g. a test harness that builds a RAT with depth 18,
   or a `Vec(...,18)` outside RatTable). Found NONE in main on first pass except the two
   RenameStage literals + the init compare.

**FMax-sensitive spots:** the int RAT is a multi-write/multi-read bypass structure
(8 read ports). Growing the depth 18->19 adds ONE more `specReg` cell to the per-port
address-compare mux (lines 72-83: `for (a <- 0 until archDepth) when(io.writes(i).addr
=== U(a))`). This is a +1 fan-in on the rename read-mux / write-bypass, on the rename
critical cone. Risk is LOW (a 19-way vs 18-way priority mux is a ~0.05ns leaf delta) but
rename is NOT the current critical path (the bit-field slow pipe at 213.77 is). **The 3rd
temp is NOT on a NEW rename arc — it widens an EXISTING 18-way mux to 19.** Contingency
if rename regresses below the 200 gate (very unlikely): the hybrid (§0) does not touch
archDepth, so falling back to it removes this risk entirely.

---

## 4. New ROM selectors / Desc fields + datapath

### Selectors (Microcode.Sel)
Add EA-aware selectors so a row can address the bit-field EA and pick a temp:

```
case object SEaBase   extends Sel   // (eaBase, eaBaseValid)
case object SEaIndex  extends Sel   // (eaIndexReg, eaIndexValid) — rides srcC
case object ST2       extends Sel   // (T2, True)
case object SDn2      extends Sel   // (bfDn2, True) — BFINS insert source
case object SNone … (existing)
```

`selImm` gains `SEaDispLo`/`SEaDispHi` (the byteAddr / byteAddr+4 disp). The load/store
rows set `useImm=True, imm=SEaDispLo|SEaDispHi`, `srcA=SEaBase`, and carry the index via a
NEW Desc field (the v1 rows never used srcC/index):

```
case class Desc( … existing … ,
  srcC:        Sel = SNone,        // index reg (AGU) for LS rows; Dn2 for the RES compute
  indexFromEa: Boolean = false,    // this row's srcC + indexLong/indexScale come from Ctx EA
  bfStoreForm: Int = 0,            // 0=none, 1=RES, 2=LO, 3=HI (the BITFIELD compute form)
  bfMemRow:    Boolean = false     // this row is a BITFIELD bfMem compute (sets op=BITFIELD, bfMem, imm=bfImm)
)
```

`resolve` extensions:
- For `bfMemRow`: `u.op := DecOp.BITFIELD`, `u.cluster := INT`, `u.size := LONG`,
  `u.bfOp := ctx.bfOp`, `u.bfMem := True`, `u.useImm := True`, `u.imm := ctx.bfImm`,
  `u.bfStoreForm := bfStoreForm` (NEW DecodedUop marker), flags only on the RES row.
- For LS rows with `indexFromEa`: `u.srcCReg/Valid := ctx.eaIndex…`, `u.indexLong :=
  ctx.eaIndexLong`, `u.indexScale := ctx.eaIndexScale`, `u.srcAReg/Valid := ctx.eaBase…`,
  `u.useImm := True`, `u.imm := selImm(disp)`.

### DecodedUop
Add `val bfStoreForm = UInt(2 bits)` (0/1/2/3). Default `0` everywhere (one line per
builder, mirroring the existing `bfMem := False` defaults — there are ~14 sites; the
grep list is the same as the existing `bfMem` initializers).

### Datapath — Bitfield.scala / AluEuPlugin.scala
The forward funnel (field32) + chgRes/clrRes/setRes/insRes + N/Z ALREADY exist and are
correct for the RES form. ADD the **inverse funnel** as a store-form output of the bfMem
block in AluEuPlugin (NOT in Bitfield.scala — it is mem-form-specific, like the forward
funnel already living in AluEuPlugin):

```
// inputs: bfLo=T0 (srcA), bfRes/bfHi as needed (srcB), bitOff (from imm)
lomask = (bitOff==0) ? 0 : (0xffffffff << (32-bitOff))
loStore  = (bfLo & lomask) | (res >> bitOff)          // LO form
himask = 0xff >> bitOff
hiStore  = (bfHi & himask) | ((res << (8-bitOff)) & 0xff)   // HI form (low 8 bits)
result = bfStoreForm.mux( RES -> bfRsp.result, LO -> loStore, HI -> hiStore, none -> bfRsp.result )
```

`res` for the LO/HI forms is the RES temp (T2) read as srcB. The RES form's `bfRsp.result`
is the existing datapath output (chg/clr/set/ins). All three forms reuse the existing
S1->S1a->S2->S3 slow pipe; the inverse funnel is a shallow mux+shift added at the funnel
stage (§6 places it).

---

## 5. Decode / predecode

### OperationDecoder (line-E, the `is(0xE)` ss==3 arm, near line 478)
Extend `isBitfieldMem` to ALSO accept the RMW ops, but split routing:
- LOAD-only {0,1,3,5} -> the 3a path (unchanged: `o.op:=BITFIELD`, NOT microcoded).
- RMW {2,4,6,7} at a control-ALTERABLE EA (mode 2/5/6/7-0/7-1, **NOT** 7-2/7-3 PC-rel) ->
  `o.microcoded := True`, `o.ucEntry := needHi ? BF_RMW_5B_ENTRY : BF_RMW_4B_ENTRY`,
  `o.op := BITFIELD`, `o.writesNzvc := True`, NON-illegal placeholder (the engine owns
  emission, like the BCD-mem arm at line 575). PC-rel or (An)+/-(An)/Dn/An -> stays
  illegal (mode test). `needHi`/`ucEntry` are derived from `words(1)` (bf-ext) at decode —
  OperationDecoder already sees the packet words? **CHECK**: OperationDecoder decodes from
  the opword only; `needHi` needs the bf-ext word. If OperationDecoder lacks the ext word,
  emit ONE entry constant and compute the 4B-vs-5B SPLIT inside the engine via a Ctx flag
  — but the engine is straight-line (no branch). RESOLUTION: pass `bfNeedHi` into Ctx and
  pick the ENTRY in DecodeStage's `ucBegin` (which DOES have the packet words), i.e.
  `ucEntrySpec.ucEntry` is overridden when `bfNeedHi`: `ucPc := Mux(ctxNeedHi,
  BF_RMW_5B_ENTRY, BF_RMW_4B_ENTRY)`. OperationDecoder just sets `microcoded:=True` + a
  default ucEntry; DecodeStage's ucBegin computes the real entry from the latched Ctx
  (it already computes ucEntryCtx from the packet words there). This keeps OperationDecoder
  ext-word-free. Document this in the ucBegin latch.

### Predecode (PredecodeWord.scala line-E arm, ~603)
Extend `bfMemLoadOnly` framing to cover the RMW ops too: change the length gate to accept
bfOp {0,1,2,3,4,5,6,7} at mode 2/5/6/7-0/7-1 (NOT 7-2/7-3 for RMW). Same length formula
`lenWords = 2 (opword+bf-ext) + eaExt`. PC-rel modes (7-2/7-3) must frame as LOAD-only
(allowed) but NOT as RMW — but predecode only computes LENGTH (same for both), so the
existing `eaExt` length is correct; the LEGALITY split (PC-rel illegal for RMW) is enforced
in the decoder/assembler, not predecode. So predecode just drops the `bfMemLoadOnly`
restriction (frame all 8 bfOps); flag this clearly.

### MicroOpAssembler
The RMW ops now go through the ENGINE (microcoded), so the assembler's 3a `bfm` block must
NOT crack them (the `isBfMemSpec` gate currently catches all BITFIELD-mem; restrict it to
load-only, or let `microcoded` short-circuit assembly as MOVEM/BCD do). The illegal-EA
gating for RMW (PC-rel etc.) is in OperationDecoder. (Hybrid variant: the assembler DOES
crack the 4-byte RMW as a 3-µop fast crack and only the 5-byte is microcoded.)

---

## 6. FMax plan

Current post-route critical path (3a): the slow BITFIELD pipe `s1Src2[12](imm bitOff) ->
s2BfRes[5]`, 213.77 MHz (WNS -0.678 @250), ~14 above the >=200 gate. Two new threats:

1. **The inverse funnel** (lo'/hi' computation). It is the SAME shape as the forward
   funnel (a variable shift by bitOff + a mask-merge) that already set the 3a critical
   path. Adding it in SERIES with the forward funnel + the datapath in ONE S1 stage would
   roughly DOUBLE the cone and blow the gate. **PLAN: pipeline the inverse funnel into a
   LATER slow-pipe stage.** The forward funnel + RES compute land at S1->S1a (as today);
   register `res` (T2 equivalent) and `lo`/`hi`; do the inverse funnel (LO/HI) at S1a->S2
   or S2->S3 off the registered res. Because the RMW chain is single-outstanding on the
   slow pipe and the LO/HI computes are SEPARATE µops (µPCb3/b4), each gets its OWN full
   S1..S3 traversal — the inverse funnel for the LO µop is that µop's S1 work, NOT stacked
   on the RES µop's funnel. So **the inverse funnel does not deepen any single µop's cone
   beyond the existing forward-funnel depth** — each compute µop does one funnel's worth of
   work. This is the key FMax argument: routing through the engine (one funnel per µop)
   AVOIDS the serial-funnel blowup the hybrid 3-µop crack would risk if it tried to do
   forward+modify+inverse in one compute µop. (If the hybrid is chosen, its single compute
   µop does forward-funnel -> modify -> inverse-funnel in the slow pipe; that IS a deeper
   cone and would need the funnel split across S1/S1a — note this as the hybrid's FMax cost.)

2. **archDepth 18->19** widens the rename int-RAT 18-way mux to 19-way (§3). Rename is not
   the critical path; +1 mux leg is ~0.05ns. Contingency: fall back to the hybrid (no
   archDepth change). If BOTH the funnel and rename somehow regress, the slow pipe gains a
   stage (latency-agnostic on the single-outstanding path) — the documented 3a escape hatch.

POST-ROUTE gate: >= 200 MHz, reported in Phase B. `pgrep -x vivado` before synth.

---

## 7. Test plan (Phase B, TDD)

### Decode (BitfieldDecodeSpec, no Musashi)
- The 4 RMW ops (BFCHG/BFCLR/BFSET/BFINS) at each control-alterable mode (An)/(d16,An)/
  (d8,An,Xn)/(xxx).W/(xxx).L -> assert `microcoded`+`ucEntry` (4B vs 5B by span) OR (hybrid)
  the 3-µop crack shape.
- 5-byte span (offset/width chosen so bitOff+width>32) -> 5B/7-row entry.
- BFINS reads Dn2 -> assert the RES compute's srcC = Dn2.
- ILLEGAL: PC-rel (7-2/7-3), (An)+, -(An), Dn, An, #imm -> vector-4 illegal.

### Lock-step vs Musashi (ExecuteLockStepSpec; run SINGLY, one -z, >=2x, JAVA_OPTS=-Xmx10g)
RMW then READ-BACK THROUGH MEMORY (a following load of the modified bytes into a Dn) to
catch stored-byte divergence:
- bitOff = 0 and bitOff > 0 (exercise the lomask=0 corner and the >0 funnel).
- span 1..5 bytes: width 1/8/16/32 at offsets that produce 4-byte AND 5-byte spans
  (the 5-byte hi' BYTE store is the key new path).
- BFINS with various Dn2 (0, all-ones, partial), widths 1/16/32.
- A BFSET-then-BFEXTU read-after-write THROUGH MEMORY (3a load-only reads back the 3b
  store) — cross-validates 3a and 3b share the funnel correctly.
- Misaligned LONG store crossing a cache line (s1TwoAccess) — pick byteAddr near a line
  boundary.

### Regressions (MUST stay green — the shared engine/Ctx is touched)
- 3a load-only (BFTST/BFEXTU/BFEXTS/BFFFO mem) — unchanged path, re-run.
- Register-form bit-field (slice 1/2).
- **v1 microcode users: BCD-mem ABCD/SBCD + ADDX/SUBX -(Ay),-(Ax)** — the engine/Ctx/ROM
  are shared; their ROM rows + Ctx fields are UNCHANGED (§6) but the Ctx Bundle grew and
  new selectors/Desc fields exist. Re-run their lock-step to prove the additive Ctx/ROM
  change did not perturb the BCD chain (the BCD rows reference only v1 selectors; the new
  fields default inert in resolve).

---

## Summary of decisions for the controller

1. **Hybrid vs all-through-engine** (§0): the 4-byte common case fits a 3-µop fast crack
   with NO archDepth-19 and NO ROM; only the 5-byte case structurally needs the engine +
   3rd temp. RECOMMEND the hybrid (less code, removes the archDepth FMax risk for the hot
   path). Documented the owner's all-through-engine plan as the default if the controller
   prefers uniformity.
2. **archDepth 18->19** touches exactly: MicroOpAssembler.T2, Microcode (Ctx/ROM/selectors),
   RenameStage:29/34/50-52 (3 literals + init compare). RatTable/Freelist are
   parameterized (no source change). Reg-id stays 5 bits. Whitebox temp filter (>=16)
   already covers T2=18. FMax risk: a +1 leg on the rename 18->19-way mux (LOW; rename is
   not critical).
3. **v1 BCD-mem users**: NOT functionally affected — Ctx/ROM grow ADDITIVELY, BCD rows +
   selectors unchanged; re-run their lock-step to confirm.
4. **`ucEntry` width**: romSize hits 16 with both chains; recommend widening ucEntry 4->5
   bits (FMax-neutral decode flop) regardless of hybrid/all-engine.
5. **Inverse funnel FMax**: routing each compute as its OWN µop keeps one funnel-depth per
   µop (no serial forward+inverse blowup); pipeline LO/HI off a registered `res`.
```
