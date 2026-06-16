# MOVEP (move peripheral data, alternating bytes) — Design

**Status:** Draft — controller-driven (ISA-completion bounded-ops phase, slice 4 — the last bounded op; meatier than the others, a micro-sequencer).
**Date:** 2026-06-16
**Parent:** [[isa-completion-roadmap]]. Reuses the **MOVEM micro-sequencer precedent** (a `DecodeStage` FSM that holds the instruction and emits a variable-length µop sequence via `pushProduced`) + existing LOAD/STORE/SHIFT/AND/OR µops. ZERO new EU datapath / DecOp.

## 1. Instruction
MOVEP — move data between a data register and alternating (even) memory bytes (68000+; present on 68040). Encoding (line-0): `0000 rrr 1 oo 001 aaa` + a 16-bit displacement ext word.
- `rrr` (op[11:9]) = Dx (data reg). bit8=1. `oo` (op[7:6]): 00=.W mem→reg, 01=.L mem→reg, 10=.W reg→mem, 11=.L reg→mem. mode (op[5:3]) = 001. `aaa` (op[2:0]) = Ay. EA = `Ay + disp16`.
- Big-endian, alternating EVEN bytes (verified vs Musashi `m68k_op_movep_{16,32}_{er,re}`):
  - **reg→mem .L:** `[ea]:=Dx[31:24]; [ea+2]:=Dx[23:16]; [ea+4]:=Dx[15:8]; [ea+6]:=Dx[7:0]`. (.W: `[ea]:=Dx[15:8]; [ea+2]:=Dx[7:0]`.)
  - **mem→reg .L:** `Dx := ([ea]<<24)|([ea+2]<<16)|([ea+4]<<8)|[ea+6]`. (.W: `Dx[15:0] := ([ea]<<8)|[ea+2]`, **Dx[31:16] PRESERVED**.)
- **No CCR effect.**

## 2. Mechanism — a dedicated micro-sequencer (MOVEM-style), NO generic µcode-engine change
Add a MOVEP FSM to `decode/DecodeStage.scala` modeled on the MOVEM sequencer (latch Dx/Ay/disp/size/dir; hold `fed`; emit µops via `pushProduced`; `pipeFlush` aborts; all intermediate Dx writes `divRem`-dropped so the macro = 1 oracle step, like MOVEM). The emitted µops use EXISTING DecOps:

**reg→mem** (store bytes, big-endian): for k in {0,2,4,6} (.L) or {0,2} (.W), byte position `bytePos = 24-? ` (ea→Dx[31:24], etc.):
- `SHIFT (LSR) Dx by shift → Ttmp` (shift = the byte's position: 24/16/8/0; the last byte=0 can store Dx directly), then `STORE.B Ttmp → [ea+k]` (a normal byte store at base=Ay, disp=disp16+k). The store data is the low byte of Ttmp.
- The Ay write-back: MOVEP does NOT auto-update Ay (it's (d16,Ay), no predec/postinc) — so each store is base=Ay, disp=disp16+k, no An write.

**mem→reg** (load bytes + assemble into Dx): build the result in a temp accumulator `Tacc`, write Dx once at the end:
- `.L`: `LOAD.B [ea]→T0; SHIFT(LSL) T0 by 24 → Tacc; LOAD.B [ea+2]→T0; SHIFT(LSL) T0 by 16 → T0; OR Tacc|T0 → Tacc; LOAD.B [ea+4]→T0; SHIFT T0<<8→T0; OR→Tacc; LOAD.B [ea+6]→T0; OR Tacc|T0→Tacc; MOVE Tacc → Dx` (.L: full write).
- `.W`: `AND Dx & 0xFFFF0000 → Tacc (preserve Dx[31:16]); LOAD.B [ea]→T0; SHIFT T0<<8→T0; OR→Tacc; LOAD.B [ea+2]→T0; OR→Tacc; MOVE Tacc→Dx[15:0]` — actually write the FULL Tacc to Dx (Tacc already has Dx[31:16] preserved via the AND + the low word assembled). So `.W` final write = full Dx := Tacc.
- The loaded `.B` must land in T0[7:0] with T0[31:8]=0 (zero-extended) so the shift+OR assembles cleanly — confirm the LS-EU .B load zero-extends (or mask in the shift µop).
- Temps: T0 (load scratch) + Tacc (accumulator). If the rename int-RAT has only T0/T1 (depth 18 = D0-7/A0-7/T0/T1), use T0 + T1 (T1 as Tacc). Confirm 2 temps suffice (they do — load into T0, accumulate in T1).

The FSM emits these in order (≤2 µops/cycle via the MOVEM pushProduced machinery, or 1/cycle — match MOVEM). The intermediate Dx/Tacc writes are dropped; only the final `Dx := Tacc` (mem→reg) or the stores (reg→mem) are architectural. reg→mem writes NO register (Dx unchanged) — macro = the stores; the oracle step is the single MOVEP.

## 3. Decode (`OperationDecoder.scala` + `MicroOpAssembler.scala` / `DecodeStage.scala`)
- MOVEP is already carved OUT of the dyn-bit-op path (`isDynBit = opword(8) && (mode =/= 001)` excludes mode 001). Add: detect `isMovep = (line==0) && op[8] && (mode(op[5:3])==1) && (opmode(op[8:6]) >= 4)` (bit8=1 + mode 001 → opmode is 4/5/6/7). Route it to the MOVEP FSM (like MOVEM is routed to its sequencer). Capture Dx=op[11:9], Ay=op[2:0], disp16=ext word, dir=op[7], size=op[6] (0=.W,1=.L).
- The OperationDecoder leaves it for the sequencer (like MOVEM); the DecodeStage FSM owns the emission.

## 4. Predecode framing (`PredecodeWord.scala` + `PredecodeRef`)
Line-0, `bit8=1 && mode==001 && opmode>=4` → **len = 2** (opword + disp16), for ALL 4 variants. Mirror in `PredecodeRef`; 65536 parity. (Currently MOVEP/mode-001 falls to COMPLEX; frame it len-2.) Don't disturb the dyn-bit-op framing (mode≠001).

## 5. Verification
- **Lock-step vs Musashi** (gate): all 4 variants — MOVEP.W/.L reg→mem (verify the memory bytes via checkMem at ea/ea+2/ea+4/ea+6) and MOVEP.W/.L mem→reg (verify Dx assembled correctly; for .W verify **Dx[31:16] PRESERVED** with a sentinel). Use distinct byte values per position so a byte-order/position swap diverges. Confirm CCR UNCHANGED (sentinel). Musashi (68040) has all 4; gas `-m68040`: `movep.l %d0,(16,%a0)` / `movep.w (16,%a0),%d0`. Confirm gas+Musashi accept the encoding first; if rejected/diverged on the encoding, STOP + report.
- **RUN EACH LOCK-STEP TEST ≥2× (different seeds)** — per the CMP2/CHK2 lesson, per-seed flakiness can hide a real bug (esp. for a multi-load/store sequence touching the SQ → re-check the same-line-load-after-store path holds; the recent SQ same-line-stall fix should cover MOVEP's nearby byte stores+loads, but verify).
- **Directed** `MovepDecodeSpec`: the 4 variants route to the sequencer with the right Dx/Ay/disp/dir/size; mode-001 non-MOVEP (opmode<4, i.e. bit8=1 opmode 0-3 — those are dyn-bit-ops with mode≠001, so n/a) unaffected; the dyn-bit-ops (mode≠001) still decode.
- **Parity** `PredecodeWordSpec` 65536 GREEN; `OperationDecoderSpec`.
- **fastTest** green.
- **Synth gate (controller):** post-route ≥200; expect FMax-neutral (a decode-stage FSM + reused µops; no new EU datapath). The FSM adds front-end logic — watch the DecodeStage cone (the current FMax limiter); report honestly.

## 6. Out of scope
Nothing for MOVEP itself (all 4 variants). If the DecodeStage FSM emission proves to need machinery the MOVEM sequencer doesn't provide (e.g. emitting SHIFT/AND/OR µops vs MOVEM's load/store-only moves), that is the slice's core work — report BLOCKED only if it needs a fundamentally new emission path. The byte loads/stores reuse the LS-EU; the shift/and/or reuse the ALU EU.
