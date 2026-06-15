# PACK / UNPK (020+ BCD pack/unpack, register forms) — Design

**Status:** Draft — controller-driven (ISA-completion bounded-ops phase, slice 2).
**Date:** 2026-06-15
**Parent:** [[isa-completion-roadmap]]. Reuses the line-8 ABCD/SBCD decode band + the IMMEXT machinery + the ALU-EU partial-merge writeback.

## 1. Instruction (register forms only this slice)
Line-8, `1000 yyy ooo 00 R xxx` + a 16-bit **adjustment** extension word:
- `ooo` (op[8:6]) = `101` → PACK, `110` → UNPK.
- `op[5:4] = 00`; `R` (op[3]) = R/M: **0 = register** (this slice), 1 = memory `-(Ax),-(Ay)` (DEFERRED — see §6).
- `xxx` (op[2:0]) = Dx (destination); `yyy` (op[11:9]) = Dy (source).
- **PACK Dy,Dx,#adj:** `src = (Dy + adj) & 0xffff`; `Dx[7:0] := ((src >> 4) & 0xF0) | (src & 0x0F)` (pack the two BCD digits in src[11:8],src[3:0] into one byte); **Dx[31:8] preserved**.
- **UNPK Dy,Dx,#adj:** `src = Dy & 0xffff`; `Dx[15:0] := (((src << 4) & 0x0F00) | (src & 0x000F)) + adj` (unpack the two nibbles src[7:4],src[3:0] into two bytes, then add adj), masked to 16 bits; **Dx[31:16] preserved**.
- **No CCR effect** (PACK/UNPK do not touch X/N/Z/V/C — unlike ABCD/SBCD).
(Datapath transcribed verbatim from Musashi `m68k_op_pack_16_rr` / `m68k_op_unpk_16_rr`, CPU_TYPE 68040.)

## 2. Decode (`OperationDecoder.scala` + `MicroOpAssembler.scala`)
- **Carve-out (no OR collision):** `isPackUnpkReg = (line==0x8) && (opmode(op[8:6])∈{5,6}) && (op[5:3]==000)` (i.e. op[5:4]==00 AND op[3]==0 = register). `op==5`→PACK, `op==6`→UNPK. OR.W/OR.L `Dn,<ea>` use opmode 5/6 only with a memory-alterable EA (mode≥2), so opmode 5/6 with eaMode 000/001 is NOT a valid OR — clean carve-out. The ABCD/SBCD band is opmode-4 (untouched). **Memory form** `op[5:3]==001` (op[3]=1) → leave ILLEGAL this slice (DEFERRED).
- **The µop (fast path, single):** a new `DecOp.PACK` and `DecOp.UNPK` (or one `DecOp` + a 1-bit sub-kind — implementer's choice; mirror the ABCD/SBCD `bcdSub` precedent if unifying). Fields: `srcAReg := Dy (op[11:9])`, `srcAValid := True`; `useImm := True; imm := adj16` (the extension word, sign-agnostic — added as 16-bit; capture it like the line-0 IMMEXT / ADDI immediate path, `pkt.words(1)`); `dstReg := Dx (op[2:0])`, `dstValid := True`; `size := BYTE` (PACK, byte-merge into Dx[7:0]) / `WORD` (UNPK, word-merge into Dx[15:0]); `readsNzvc := False; writesNzvc := False; readsX := False; writesX := False; cluster := INT; memOp := NONE`. NO srcB. The dst partial-merge (.B/.W into Dx, preserving upper) reuses the EXISTING ALU-EU size-merge writeback (the same one ADDQ.B/MOVE.B use — verify it preserves upper bytes; the [[isa-completion-roadmap]] QUEUED-BUG note about MOVE.B upper-preservation should be confirmed fixed, else PACK's .B merge would clobber Dx[31:8]).

## 3. Execute (`execute/AluEuPlugin.scala`)
Add a PACK/UNPK datapath (a few muxes, off the BCD path — PACK/UNPK are NOT decimal-adjust):
```
val packSrc = (s1Src1(15 downto 0).asUInt + u1.imm(15 downto 0).asUInt)   // PACK: Dy + adj (16-bit)
val packRes8 = ((packSrc(11 downto 8) ## packSrc(3 downto 0)))            // -> Dx[7:0]
val unpkSrc  = s1Src1(15 downto 0).asUInt                                 // UNPK: Dy
val unpkRes16 = (((unpkSrc(7 downto 4) ## U(0,4 bits) ## unpkSrc(3 downto 0)) ... ) + u1.imm(15 downto 0))  // -> Dx[15:0]
```
(Exact bit-slicing to match Musashi: PACK byte = `((src>>4)&0xF0)|(src&0xF)` = `src[11:8] ## src[3:0]`; UNPK word = `(src[7:4]<<8)|(src[3:0])` then `+adj` masked 16. Implement in 32-bit UInt lanes, mask explicitly.) Route the result through the normal Dn writeback with size-merge (.B for PACK → Dx[7:0], preserve [31:8]; .W for UNPK → Dx[15:0], preserve [31:16]). No flag write. `s1Src1` = the renamed Dy; `u1.imm` carries adj.

## 4. Predecode framing (`PredecodeWord.scala` + `PredecodeRef`)
Line-8 (and ONLY line-8) opmode∈{5,6} with `op[5:3]∈{000,001}` → **len = 2** (opword + adjustment word) for BOTH the register (000) and the (deferred-but-still-2-word-for-framing) memory (001) forms — framing the length is independent of whether decode accepts it; a deferred memory form is illegalised by decode while its length stays correctly framed so the front-end doesn't run off. (cf. ABCD opmode-4 = len 1; PACK/UNPK = len 2 like LINK/MOVEC.) Mirror in `PredecodeRef`; the 65536-opword parity must hold. **Do NOT frame opmode 5/6 with eaMode≥2 as PACK** (those are OR.W/OR.L — leave their existing framing).

## 5. Verification
- **Lock-step vs Musashi** (gate): PACK Dy,Dx,#adj and UNPK Dy,Dx,#adj with several Dy/adj values incl. carry-into-the-pack and a non-zero adj; assert Dx (with upper bytes/words preserved) + that CCR is UNCHANGED step-for-step. Musashi (68040) implements both (`m68k_op_pack/unpk_16_rr`). gas `-m68040`: `pack %d0,%d1,#imm` / `unpk %d0,%d1,#imm`.
- **Directed decode** `PackUnpkDecodeSpec`: the reg forms decode to the PACK/UNPK µop (srcA=Dy, imm=adj, dst=Dx, no CCR, right size); memory form (op[3]=1) ILLEGAL (deferred); opmode 5/6 with a real EA still decodes as OR.
- **Parity** `PredecodeWordSpec` 65536-opword GREEN (new len-2 framing). Run `OperationDecoderSpec` (a new live op may flip a stale "illegal" assert).
- **fastTest** green.
- **Synth gate (controller):** post-route ≥200, expect FMax-neutral (a small ALU datapath + decode).

## 6. Out of scope (deferred)
- **PACK/UNPK memory forms** `-(Ax),-(Ay),#adj` (op[3]=1): predec byte accesses with **asymmetric** load/store sizes (PACK: 2-byte load → 1-byte store; UNPK: 1-byte → 2-byte). The µcode engine (`Microcode.scala`) currently does symmetric sizes — these need a µcode-asymmetric-size extension. A clean follow-up (group with revisiting the µcode engine; same shape as the ABCD/SBCD-mem µcode crack but with per-step byte counts). Leave ILLEGAL + DOCUMENT (no regression — they were already illegal). The A7-byte (`_ax7`/`_ay7`) Musashi variants matter only for the memory forms.
