# Bit-field slice 3a — MEMORY-operand LOAD-only ops (BFTST/BFEXTU/BFEXTS/BFFFO), static offset+width

Branch: `feat/bitfield-mem-load` (worktree, off master 28344d4)

## Scope

The four LOAD-ONLY memory bit-field ops with a MEMORY effective address, STATIC
offset (0..31) and STATIC width (1..32, encoded 0->32):

- BFTST  (no dest write; N/Z only)
- BFEXTU (zero-extended field -> Dn2)
- BFEXTS (sign-extended field -> Dn2)
- BFFFO  (Dn2 := offset + leading-zero count within the field)

EA modes (control addressing): (An) m2, (d16,An) m5, (d8,An,Xn) m6 brief,
(xxx).W 7/0, (xxx).L 7/1, plus PC-relative (d16,PC) 7/2 and (d8,PC,Xn) 7/3
(load-only ops permit PC-rel; the existing EA infra gives them for free via the
crackLoad path's pcRel folding).

DEFERRED: RMW mem ops BFCHG/BFCLR/BFSET/BFINS (slice 3b); DYNAMIC offset/width on
mem forms; (An)+ / -(An) (NOT valid for bit-fields -> decode illegal).

## Encoding

Line-E opword `1110 1ooo 11 mmm rrr`: op[7:6]==11 (ss=3), op[11]==1, op[10:8]=bfOp
(real interleaved order 0=BFTST,1=BFEXTU,2=BFCHG,3=BFEXTS,4=BFCLR,5=BFFFO,6=BFSET,
7=BFINS; slice 3a handles {0,1,3,5}), op[5:3]=mode (>=2 for memory; mode 000 is the
merged register form — untouched), op[2:0]=reg.

bf-ext word (immediately after opword): Do=ext[11], offset=ext[10:6], Dw=ext[5],
width=ext[4:0], Dn2=ext[14:12]. Static => Do=0, Dw=0. Then the EA's own ext words
follow per mode.

## Semantics (authoritative, from tools/musashi/musashi/m68k_in.c)

```
offset = ext[10:6]                         # static, 0..31
width  = ((ext[4:0] - 1) & 31) + 1         # 1..32  (0 encodes 32)
byteAddr = EA + (offset >> 3)              # offset>>3 in 0..3
bitOff   = offset & 7                       # 0..7
lo    = read_32(byteAddr)                  # MISALIGNED long (LS handles via cross-line)
field = mask32(lo << bitOff)
if (bitOff + width) > 32:                  # statically known at decode
    field |= (read_8(byteAddr+4) << bitOff) >> 8
# funnel:  field32 = (lo << bitOff) | (hi >> (8 - bitOff)),  hi = read_8(byteAddr+4) or 0
N = field32[31]
```
Per op (V=C=0, X untouched):
- BFTST:  N=field32[31]; Z=(field32 & (0xffffffff<<(32-width)))==0; no reg write.
- BFEXTU: Dn2 := field32 >> (32-width) (logical); N=field32[31]; Z=(result==0).
- BFEXTS: Dn2 := (sint)field32 >> (32-width) (arith); N=field32[31]; Z=(result==0).
- BFFFO:  Dn2 := offset + (#leading zeros in the width-bit field) (= offset+width if
          all-zero); N=field32[31]; Z=(field's width bits == 0). The additive base is
          the ORIGINAL offset (0..31), NOT bitOff.

## KEY REUSE — feed the existing register-form datapath

`Bitfield.scala` already does rotL=ROL_32(dy,offset) then extracts the top `width`
bits + N/Z/FFO. Feeding `dy=field32, offset=0, rawWidth=width` yields
BFTST/BFEXTU/BFEXTS correctly with NO datapath change (ROL by 0 = identity).

The ONE exception is BFFFO: the datapath computes `ffoRes = cmd.offset + clz`. With
offset=0 that gives 0+clz, but the mem form needs original_offset + clz. So
BitfieldCmd gains a separate `ffoBase` field (the FFO additive base): register form
sets ffoBase=offset (unchanged behavior), memory form sets ffoBase=original_offset
while the rotate offset=0. Line ~83 changes from `offset` to `cmd.ffoBase`.

## Mechanism (CHOSEN: misaligned-LONG fast crack)

INVESTIGATION: the LS EU (LsEuPlugin.scala) ALREADY supports misaligned LONG loads.
The AGU detects cross-line / cross-page (`s1TwoAccess`, `s1CrossLine`/`s1CrossPage`)
and the FSM does two cache accesses (WAIT_A / WAIT_B) merged by
`DcacheByteLane.extractCross`. A LONG load at ANY byte address is native — no new LS
datapath needed. So the PREFERRED misaligned-LONG fast crack is viable; we do NOT do
byte-granular (which would add more uops) and add NO new LS datapath.

Crack (<=3 uops, like the existing crackLoad [load->T0][op reads T0]):
1. `LOAD.L byteAddr -> T0`         (misaligned long; byteAddr = EA + (offset>>3) folded into disp)
2. only when (bitOff+width)>32:    `LOAD.B byteAddr+4 -> T1`  (statically known)
3. compute uop `BITFIELD` (bfMem): funnel field32 from T0/T1, run the datapath -> Dn2.

Common case (bitOff+width<=32) is 2 uops (load + compute). The +1 byte case is 3.

`offset>>3` is folded into the EA displacement at decode/assemble time (no AGU
change). The funnel (`field32 = (lo<<bitOff) | (needHi ? hi>>(8-bitOff) : 0)`) lives
in the ALU EU's BITFIELD path (the existing SLOW pipe S1->S3, off the IQ scoreboard
cone), gated by a new `bfMem` marker. The compute uop reads:
- srcA = T0 (lo)
- srcB = T1 (hi byte), srcBValid only when needHi
- imm packs: bitOff(3b @ imm[2:0]), width(5b @ imm[9:5]... reuse the static layout
  imm[4:0]=offset(=0 for mem rotate), imm[9:5]=rawWidth), needHi(1b), original_offset(5b).
  Concretely: imm[4:0]=0 (rotate offset), imm[9:5]=rawWidth, imm[12:10]=bitOff,
  imm[13]=needHi, imm[18:14]=original_offset.

bfMem path in the ALU EU: field32 = (T0 << bitOff) | (needHi ? (T1[7:0] >> (8-bitOff)) : 0);
bfCmd.dy=field32, bfCmd.offset=0, bfCmd.rawWidth=rawWidth, bfCmd.ffoBase=original_offset.

## FMax

Funnel is on the existing slow BITFIELD pipe (lat-matched S1->S3), NOT the IQ
scoreboard nor the fast S1 cone. The extra LOAD.B uop reuses the existing LS path.
ffoBase is a 5-bit add input replacing the existing offset add input (no new depth).
POST-ROUTE gated >= 200.

RESULT (post-route, xcku5p-ffvb676-2 OOC @ 250MHz target): WNS = -0.678 ns ->
FMax = 213.77 MHz. ABOVE the >= 200 gate. The worst path is now the new funnel:
s1Src2[12] (= imm bitOff) -> s2BfRes[5] (the bit-field result pipe), i.e. the
(lo << bitOff) funnel deepened the slow BITFIELD S1 cone. Still above gate with
headroom; if a later slice needs >213 here, register the funnel into an extra
slow-pipe stage (the funnel is latency-agnostic on the single-outstanding slow path).

## Files

- decode/OperationDecoder.scala: line-E ss=3 op[11]=1 mode>=2 {0,1,3,5} -> mem bit-field load-only.
- decode/MicroOpAssembler.scala: the crack (load + optional byte-load + compute), bfMem packing.
- frontend/PredecodeWord.scala: frame len = opword + bf-ext + EA-ext (eaExt per mode).
- execute/Bitfield.scala: BitfieldCmd gains ffoBase; the datapath uses it for FFO.
- execute/AluEuPlugin.scala: bfMem funnel feeding bfCmd; ffoBase wiring.
- decode/DecodedUop.scala: new `bfMem` marker + the wider imm packing (existing 32-bit imm).
- Tests: BitfieldDecodeSpec (decode), ExecuteLockStepSpec (lock-step vs Musashi).
