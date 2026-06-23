# Full-extension addressing modes — design

**Goal:** Implement the 68020+ full-format extension-word addressing modes (currently
decode-illegal; only the brief 8-bit format is decoded). Drives integer-ISA completeness.

The full extension word (modes 6 `(...,An,Xn)` and 7/3 `(...,PC,Xn)`, ext bit8=1) covers:
- **No-memory-indirect** (`I/IS=000`): `base' + bd + index'` with suppressible base (BS) /
  index (IS), a word/long base displacement (BD), and a scaled index — single-pass, no load.
- **Memory-indirect pre-index** `([bd,An,Xn],od)`: load the pointer at `base+bd+index`, then
  `+od`.
- **Memory-indirect post-index** `([bd,An],Xn,od)`: load the pointer at `base+bd`, then
  `+index+od`.

Authoritative semantics = Musashi `m68ki_get_ea_ix` (`tools/musashi/musashi/m68kcpu.h`); the
full-format extension word layout + the I/IS decode table are quoted in §3. Scope = ALL in one
spec (both halves). Lock-steppable vs Musashi (gas + Musashi both implement these on 68040).
Gate: post-route ≥200 MHz, ≥2 fresh regens. Area is NOT a constraint (~41% LUT, huge headroom).

---

## 1. Predecode variable-length framing (SHARED — the critical correctness fix)

`frontend/PredecodeWord.scala` `eaExt` currently frames modes 6 / 7-3 as **brief = 1 ext word**
unconditionally (it can't see bit8 → assumes brief; full-format is illegal today so a mis-frame
was harmless). Now full-format is LEGAL, so predecode MUST compute the variable length from the
extension word (`words(1)` for the EA — the word at the EA's ext-word position):

```
if ext.bit8 == 0:  len = 1                       // brief (unchanged)
else (full):       bdWords = ext[5:4] match { 00|01 -> 0 ; 10 -> 1 ; 11 -> 2 }   // BD size
                   odWords = if ext[1] (OD present) { if ext[0] (OD long) 2 else 1 } else 0
                   len = 1 + bdWords + odWords    // 1..5 ext words
```
Where `ext` is the extension word at the correct offset (after the opword + any prior ext words
for the instruction). Predecode reads it from the fetched window. This frames `nextPc` correctly;
a wrong length cascades misalignment through the whole fetch pipeline. Mode 6 and mode 7/3 both
use this. (BD size `00` is "reserved" — Musashi treats it as null/0 words; match that.)

The same length logic must be applied consistently in `EaDecoder` (which consumes the ext words)
and anywhere the per-mode EA length is computed (the assembler's EA-extension consumption).

---

## 2. EaDecoder full-format decode (`decode/EaDecoder.scala`)

Today (modes 6 / 7-3): `briefIsFull = extW(8)` → if set, `MEMCOMPLEX` (illegal), no fields
extracted. Replace with full-format field extraction when `extW(8)`:

```
idxDA   = extW(15)              // 1=An, 0=Dn  (index reg type)
idxXn   = extW(14:12)
idxReg  = idxDA ? (8+idxXn) : idxXn
idxLong = extW(11)              // index .W/.L (sign-extend if .W)
idxScale= extW(10:9)            // 1/2/4/8
bs      = extW(7)               // base suppress  -> baseValid = !bs (mode 6); pcRel base also suppressible
is_     = extW(6)               // index suppress -> indexValid = !is_
bdSize  = extW(5:4)             // 00/01=null(0), 10=word(sext16), 11=long(32)
iis     = extW(2:0)             // I/IS — the memory-indirect selector (table in §3)
bd      = (bdSize==10) ? sext16(words(2)) : (bdSize==11) ? words(2..3) : 0
od      = (od present per iis) ? (odLong ? words(N..N+1) : sext16(words(N))) : 0
```
The trailing BD/OD words follow the ext word in order (BD first, then OD). `EaDecoder` outputs a
new EA class set:
- `iis==000` → **no-memory-indirect**: emit MEMSIMPLE-like with baseValid=!bs, disp=bd (32b),
  indexValid=!is_, indexReg/Long/Scale, pcRel for 7/3. (The existing AGU/LS path, §4.)
- `iis` pre-index (`001/010/011`, bit2=0) or post-index (`101/110/111`, bit2=1) → a new
  **MEMINDIRECT** class with all the resolved fields (base/bs, bd, index/is_/scale, od, pre/post)
  → routed to the microcode-v2 engine (§5). bit2 = pre(0)/post(1); od size from bits1:0.

`EaDecoder` is a pure function — extend it; keep the brief path byte-identical.

---

## 3. Musashi reference (the lock-step target — VERBATIM, `m68kcpu.h`)

```c
/* Full extension format word: D/A(15) REG(14:12) W/L(11) SCALE(10:9) 1(8) BS(7) IS(6)
 * BD-SIZE(5:4) 0(3) I/IS(2:0) ; then bd (0/16/32), then od (0/16/32) */
/* I/IS table (IS=bit6, I/IS=bits2:0):
 *  IS=0 000 No Memory Indirect
 *  IS=0 001 indir preindex, null outer
 *  IS=0 010 indir preindex, word outer
 *  IS=0 011 indir preindex, long outer
 *  IS=0 101 indir postindex, null outer
 *  IS=0 110 indir postindex, word outer
 *  IS=0 111 indir postindex, long outer
 *  IS=1 001 mem indir, null outer  (index suppressed -> pre==post)
 *  IS=1 010 mem indir, word outer
 *  IS=1 011 mem indir, long outer  */
if(BIT_7(extension)) An = 0;                      // BS: suppress base
if(!BIT_6(extension)) {                            // IS: index present
    Xn = REG_DA[extension>>12];
    if(!BIT_B(extension)) Xn = MAKE_INT_16(Xn);    // W/L sign-extend
    Xn <<= (extension>>9) & 3;                     // scale
}
if(BIT_5(extension))                               // BD present
    bd = BIT_4(extension) ? read_imm_32() : (uint32)MAKE_INT_16(read_imm_16());
if(!(extension&7)) return An + bd + Xn;            // No Memory Indirect
if(BIT_1(extension))                               // OD present
    od = BIT_0(extension) ? read_imm_32() : (uint32)MAKE_INT_16(read_imm_16());
if(BIT_2(extension))                               // postindex
    return m68ki_read_32(An + bd) + Xn + od;
/* preindex */
return m68ki_read_32(An + bd + Xn) + od;
```
Note the ordering precisely: **pre** loads at `An+bd+Xn` then `+od`; **post** loads at `An+bd`
then `+Xn+od`. When IS=1 (index suppressed), Xn=0 so pre==post (still one load). The pointer load
is always a 32-bit read.

---

## 4. No-memory-indirect → the existing AGU/LS path

`iis==000`. EA = `base' + bd + index'` is single-pass — the existing S1 AGU (`s1Va = s1Base +
s1Disp + s1Index`, `LsEuPlugin.scala`) already computes this. EaDecoder emits the normal LS µop:
- `baseValid = !bs` (mode 6: An base; mode 7/3: pc-rel base, also suppressible — when BS set on a
  PC-rel, base=0, i.e. an absolute-ish `bd+index`).
- `imm = bd` (32-bit, sign-extended for word) — the AGU disp is already 32-bit.
- `indexValid = !is_`, indexReg/indexLong/indexScale as today.
- PC-rel: base = pc + (offset to the ext word), as the brief 7/3 path already computes.

No microcode, no new datapath. Just the wider/suppressible operands from EaDecoder.

---

## 5. Memory-indirect → microcode-v2 engine (`decode/Microcode.scala`)

A mid-EA pointer load. The engine resolves the EA into a temp address, then re-emits the **host
op** (the instruction whose EA this is — MOVE/ALU/etc.) as a normal register-indirect access on
that temp.

**Crack shapes** (T = a microcode temp holding the loaded pointer):
- **Pre-index**: `[LOAD.L (base + bd + index) → T]` ; then `<host op>` with EA = `(T + od)`
  (base=T, disp=od, no index).
- **Post-index**: `[LOAD.L (base + bd) → T]` ; then `<host op>` with EA = `(T + index + od)`
  (base=T, index kept, disp=od).
- IS=1 (index suppressed) collapses to the pre form with Xn=0.

The host op µop is constructed from the ORIGINAL op (DecOp, size, the other operand, src-vs-dst
EA) with its EA replaced by the `(T)`-relative access. Examples:
- `MOVE.L ([8,A0,D1.L*4],16),D2` → `[LOAD.L (A0+8+D1*4)→T0]` `[LOAD.L (T0+16)→D2]`.
- `MOVE.L D2,([8,A0],D1,16)` (dst-EA, post) → `[LOAD.L (A0+8)→T0]` `[STORE.L D2→(T0+D1+16)]`.
- `ADD.L ([8,A0,D1*4],16),D2` (ALU mem-source) → `[LOAD.L (A0+8+D1*4)→T0]` `[LOAD.L (T0+16)→T1]`
  `[ADD.L T1,D2]` (the ALU mem-source's own load chains after the EA-resolve).

**Engine Ctx (extend `Microcode.Ctx`):** carry the host op identity (op/size/other-operand/
src-or-dst-EA) + the EA fields (base/bs, bd, index/is_/scale, od, pre-vs-post). New ROM
entries/chains for pre-index and post-index, mirroring the bit-field-RMW chain pattern (the
engine already emits load→use sequences with EA-derived addresses + a temp). The host op µop is
emitted as the LAST row(s) of the chain, reading the resolved temp as its base.

**DecodeStage routing:** when an EA-taking op's EA decodes to MEMINDIRECT (EaDecoder §2), set
`microcoded` + the appropriate `ucEntry` (pre/post), populate the Ctx with the host op + EA
fields, and let the engine emit the chain (the existing `ucActive`/`ucBegin` path, as for
bit-field-mem and BCD-mem). The op's normal (non-microcoded) crack is suppressed.

---

## 6. Faults & precision

The mid-EA pointer LOAD is an ordinary load µop → it faults precisely via the existing LS/ROB
exception machinery (a bus/MMU fault on the indirect pointer is handled exactly like any load
fault; the host op's later access faults likewise). No new exception logic. The fault PC is the
host instruction's PC (the whole instruction re-executes on resume — but these are non-restartable
mid-EA loads in real 68040; for our precise-exception model the instruction is atomic at retire,
so a fault on any µop squashes the whole instruction and reports its PC — matches the lock-step
oracle, which faults at the instruction boundary).

---

## 7. Scope of host ops

Every general EA-taking op gains these modes. Implement + verify across: **MOVE (src-EA + dst-EA,
B/W/L), the line-8/9/B/C/D ALU ops (OR/SUB/CMP/AND/ADD) src-EA, the line-0 immediate ops
(ADDI/SUBI/ANDI/ORI/EORI/CMPI) with a full-format dst-EA, and single-EA ops (CLR/TST/NEG/NOT)**.
PC-relative full-format (mode 7/3) for the read-only ops. This is the representative set; the
decode routing is op-agnostic (it keys on the EA class), so coverage is about TESTING breadth, not
per-op code.

---

## 8. Testing (lock-step vs Musashi — gas assembles all these)

- **Decode spec:** a new `FullExtDecodeSpec` — the predecode length for every BD/OD-size combo
  (brief=1, no-indirect bd-null/word/long, pre/post with od-null/word/long = 1..5 words);
  EaDecoder field extraction (BS/IS suppress, scale, pre vs post); the MEMINDIRECT vs MEMSIMPLE
  classification; the illegal cases (BD-size=00 reserved handling per Musashi).
- **Lock-step (×2+, byte-identical vs Musashi):** no-indirect (word/long BD, BS-suppressed base,
  IS-suppressed index, each scale); pre-index `([bd,An,Xn],od)` all OD sizes; post-index
  `([bd,An],Xn,od)`; index-suppressed mem-indirect; PC-relative full-format; MOVE src- and dst-EA;
  an ALU op + an immediate op with a full-format EA; **a faulting indirect pointer** (the mid-EA
  load takes an MMU fault → precise exception, architectural state matches). Seed memory with
  known pointer tables so the indirect loads have deterministic targets.
- Confirm the brief-format indexed modes + everything else stay byte-identical (EaDecoder +
  predecode touched).

## 9. FMax gate

Post-route `synth/impl_FullCore.tcl`, **≥200 MHz, ≥2 fresh regens** (the front-end band is marginal
and predecode is touched, though this work is mostly decode/EaDecoder/microcode/LS — off the fetch
hot path). Check the limiter didn't move onto the new EaDecoder/predecode logic. If <200, recover
before merge.

## 10. Build order (within the one spec)

1. **Predecode variable-length framing** (§1) + **EaDecoder full-format field decode** (§2) +
   **no-memory-indirect path** (§4) — the single-pass half. Gate: decode spec + no-indirect
   lock-step ×2; everything else byte-identical. (Self-contained, no microcode.)
2. **Memory-indirect pre/post-index** (§5) via the microcode-v2 engine + the DecodeStage routing +
   the host-op-to-temp cracking. Gate: pre/post lock-step ×2 + the faulting-pointer test.
3. Full verification + the ≥2-regen FMax gate.

## 11. Risks

- **Predecode length** (§1) is the top correctness hazard — a wrong full-format length corrupts
  nextPc. Test every BD/OD-size combo's length explicitly in the decode spec.
- **The host-op-to-temp cracking** (§5) is the meatiest integration — every EA-op must route
  cleanly through the engine when its EA is mem-indirect. Verify src-EA, dst-EA, and ALU-mem-source
  (which adds its own load) all chain correctly.
- **Temp pressure:** the pointer-load temp (T) — confirm the microcode temp pool (T0/T1/T2,
  archDepth 19) suffices for the deepest chain (pre-index ALU-mem-source = pointer-load T0 +
  operand-load T1 + the ALU = 2 temps; post-index similar). Likely fits; confirm no archDepth bump.
- **FMax:** EaDecoder grows (full-format fields); it feeds the decode cone (recently offloaded).
  Watch the gate.
