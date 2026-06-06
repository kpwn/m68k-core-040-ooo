# Multiply (MULU/MULS, full 040) — Design

**Status:** Draft (feature-completion slice 2). User: "you pick / batch it" + "features first, post-route gate."
**Date:** 2026-06-06
**Parent:** the divider ([[exception-subsystem]] CPLX cluster), the decode matrix ([[decode-matrix-framework]]).

## 1. Purpose

Integer multiply MULU/MULS (all 68040 forms) on the CPLX cluster (the DivEu's home), reusing the divider's multi-cycle EU + 2nd-dest-crack patterns. No trap (no MUL-by-zero). Lock-stepped vs Musashi. **Gated on POST-ROUTE** (`impl_FullCore.tcl`) — the OOC-synth gate is unreliable (~15-25 MHz optimistic); real master baseline ≈ 240-243 post-route; MUL must NOT regress it.

## 2. Scope

**In:**
- **MULU.W / MULS.W** (`0xC0C0`/`0xC1C0` — line C, opmode 3/7): 16×16→32. `Dn[15:0] × ea.W → Dn[31:0]`. N/Z from the 32-bit product; V=0; C=0.
- **MULU.L / MULS.L** (`0x4C00 | ea`, ext word): 32×32. The ext word: bit11 = signed (MULS), bit10 = 64-bit-result, bits 14:12 = Dl, bits 2:0 = Dh.
  - `ea,Dl` (32×32→32): `Dl = (ea × Dl)[31:0]`. **V set if the full 64-bit product doesn't fit 32 bits** (signed: not a sign-extension of bit31; unsigned: high 32 ≠ 0); N/Z from the low 32; C=0.
  - `ea,Dh:Dl` (32×32→64): `Dh:Dl = ea × Dl` (full 64-bit product). V=0 (can't overflow); N/Z from the full 64-bit; C=0.
- **Multiplier datapath** (`execute/MulCore.scala` or fold into the CPLX EU): a DSP48-mappable multiply (let synthesis infer DSP48E2 from `a * b` — keep it pipelined/registered so it maps to hard blocks OFF the LUT fabric). Unsigned core + MULS via signed operands (or sign-normalize). Only **2 source operands** (32×32) — NO 3rd source port (unlike DIV 64÷32's `psrcC` — so this does NOT touch that post-route-limiting cone). Multi-cycle is fine (latency-agnostic); a registered DSP multiply (1-2 cycles) is the FMax-safe choice.
- **EU integration:** reuse the CPLX cluster (DivEu's IQ port / busy / dynamic-completion wakeup). The 64-bit `Dh:Dl` result = 2 dests → CRACK like the divider's 64÷32: a MUL µop writes Dl + a trailing MULHI crack µop writes Dh from the EU's latched high-product (mirror DIVREM). The .W and 32×32→32 forms write a single dest.
- **Flags:** N/Z from the result (32-bit for .W/.L32, 64-bit for .L64); V = overflow (.L32 only); C always 0; X unaffected.
- **Verification:** lock-step vs Musashi — MULU/MULS .W, .L32 (incl. overflow→V), .L64 (Dh:Dl) with normal/signed/edge operands (0, max, sign-boundary); N/Z/V step-for-step. ALL existing UNCHANGED. **POST-ROUTE gate** (impl_FullCore.tcl): report WNS/FMAX; must not meaningfully regress master's ~243 (default-directive) baseline.

**Out:** the trap family (MUL doesn't trap); MAC/EMAC (CPU32/ColdFire); FPU multiply.

## 3. Components & dataflow

```
MUL.W   : decode (line C opmode 3/7) -> mul uop {signed, 16x16} -> CplxEU (DSP) -> Dn[31:0], N/Z
MUL.L32 : decode (0x4C00, ext bit10=0) -> mul uop {signed, 32x32} -> Dl[31:0] + V(overflow), N/Z
MUL.L64 : decode (0x4C00, ext bit10=1) -> [mul uop -> Dl] + [mulhi crack -> Dh]  (2-dest like DIVREM)
CplxEU  : DSP-inferred a*b (registered, hard-block) -> {prodLo, prodHi}; dynamic-completion wakeup
```

## 4. Verification
- **Directed:** `MulCore` vs a Scala reference over random + edge operands (0, 1, 0xFFFFFFFF, INT_MIN, sign boundaries), signed + unsigned, .W/.L32/.L64; overflow-V for .L32.
- **Lock-step (the gate), ×2:** MULU/MULS .W, .L32 (normal + overflow), .L64 (Dh:Dl) vs Musashi — product + N/Z/V step-for-step. ALL existing UNCHANGED (ITLB seed flake → repro on baseline first).
- **`make test-fast`** + targeted verilator subsets (`-z`, avoid OOM).
- **POST-ROUTE synth gate** (`vivado -mode batch -source synth/impl_FullCore.tcl` after `GenFullCoreSynthVerilog`): report POSTROUTE WNS/FMAX; run the SAME flow on master for an apples-to-apples baseline (place&route has variance); MUL must not meaningfully regress it (~243 default). Confirm the multiply inferred DSP48 (not LUT-built) via the util report (DSP count > 0). Also report the quick OOC number as a sanity proxy.

## 5. Open items
- Fold MUL into `DivEuPlugin` (rename → `CplxEuPlugin`) vs a separate `MulEuPlugin` sharing the IQ CPLX port — pick the lower-churn that keeps the CPLX-EU operand-delivery cone (already near-critical post-route via the divider) from worsening. MUL adds no 3rd source, so reuse should be clean.
- DSP48 inference: ensure `a * b` is registered so Vivado maps it to DSP48E2 hard blocks (a wide LUT-built multiply would blow area + FMax). Verify in the util report.
- MULS.L overflow detection (the 32×32→32 form): V iff the 64-bit product isn't representable in 32 bits — signed (high 32 ≠ sign-extension of bit31) vs unsigned (high 32 ≠ 0).
- The .L64 2-dest crack reuses the DIVREM mechanism — confirm the MULHI crack reads the EU's latched high-product (no re-multiply).
