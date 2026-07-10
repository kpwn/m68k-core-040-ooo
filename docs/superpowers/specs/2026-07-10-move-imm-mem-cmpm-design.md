# MOVE #imm,&lt;mem&gt; + CMPM — Design Spec

**Date:** 2026-07-10
**Status:** approved (design), pre-implementation
**Scope:** Two fuzzer-found ISA gaps, both currently gated as EXPLICIT illegal traps (vector 4) — not silent corruption, so no urgency shortcuts. Required for full-040-integer-ISA completion.

---

## 0. Summary

Both gaps are cleanly isolated by the existing decode-illegal machinery (`MicroOpAssembler.scala`'s `bad`/`dstOk` gate for MOVE-imm; the `!pkt.simple` COMPLEX-illegal path for CMPM). Fixing them is additive — no existing behavior changes for any currently-working op. Full evidence dossier: agent `a26f5239884ee51aa` transcript (file:line citations for every claim below).

---

## 1. MOVE #imm,&lt;mem&gt;

### Root cause (confirmed, `MicroOpAssembler.scala`)
`crackStore` (`:456`) requires `srcIsReg` — an immediate source (`EaClass.IMM`) never satisfies it, and `crackMemMem` requires `srcIsMem` (also excluded) — so `dstOk` (`:903`) is False for any memory dest, and the op traps ILLEGAL (vector 4) at `:1132-1142`. Predecode framing is ALREADY CORRECT for this case (confirmed via `eaExt`'s uniform mode-7/reg-4 handling + gas objdump) — no predecode changes needed.

### The fix — materialize-then-store (2-µop crack for the immediate case only)
The STORE µop's `useImm/imm` fields are already dedicated to the dest EA's ADDRESS displacement (`stUop.imm := dstEa.disp`), so immediate DATA cannot reuse that path (unlike BSR/JSR's push-data trick). Extend the existing `crackClr`-style materialize pattern:

1. **`crackStore` gate** (`:456`): also fire when `srcEa.klass === EaClass.IMM`.
2. **New immediate-materialize step**: emit `opUop` with `dstReg := T1, dstValid := True` (override the generic `EADST` dst-routing which is meaningless for a MEMSIMPLE dest); `useImm/imm` are ALREADY correctly populated via the existing srcB-slot switch (`:568`); `writesNzvc` already correctly resolves False for non-DATAREG dest via the existing `writesNzvcIfDataDst` machinery (`:696`) — no new flag logic.
3. **`stUop.srcBReg`** (`:820`): extend the mux to also read `T1` for this new case: `Mux(crackMemMem, T0, Mux(immToMemCase, T1, srcEa.reg))`.
4. **Emission** (`:2437-2442`, the `crackStore` branch): emit `[opUop, stUop]` (count 2) for the immediate case; keep the existing 1-µop `[stUop]` fast path for the register-source case (do not regress it).
5. **`stUop.firstOfInstr`** (`:850`): False for the new 2-µop case (it's now the trailing µop).
6. `stUop.writesNzvc := True` stays unconditional (already correct per Musashi: NZVC from the moved value, V=C=0, X untouched — confirmed `m68k_in.c:5649-5660`).

### Sizes/EA
`.B/.W/.L`, all memory-alterable dest EA modes (reg-direct dest is unreachable here — that's the already-working register-form MOVE). No archDepth bump (reuses T1, the existing crackClr/crackRmw temp).

### Testing (lock-step vs Musashi, ×2)
`move.b/.w/.l #imm,<ea>` for `(An)`, `(An)+`, `-(An)`, `(d16,An)`, `(d8,An,Xn)`, abs.W, abs.L — verify mem write + NZVC (incl. N=1/Z=1/edge-value cases) + no-X-touch. Confirm the register-source MOVE-to-mem fast path (1-µop) is UNCHANGED (regression, not just new tests).

---

## 2. CMPM

### Encoding (confirmed via gas objdump + Musashi)
`1011 xxx1 ss001 yyy` (line B, `Ax=op[11:9]`, `op[8]=1`, `size=op[7:6]`, `op[5:3]=001` — the same opmode band as EOR, disjoint only by `mode===1`), `Ay=op[2:0]`. Single opword, NO extension words. Already cleanly excluded from EOR (`OperationDecoder.scala:602-607`, the `opword(5 downto 3) =/= 1` guard) and from EOR's memDestExt framing (`PredecodeWord.scala`, `memDestExt` has no mode-1 case) — currently falls through to the COMPLEX/illegal path (vector 4). Not aliased to any other op.

### Semantics (Musashi verbatim, `m68k_in.c:4209-4245`)
`src = read(Ay)+; Ay+=size` (read+postinc FIRST); `dst = read(Ax)+; Ax+=size` (SECOND); `res = dst - src`; set N/Z/V/C from res; **X untouched, no register/memory write** (flags-only, like register CMP).

### The crack — 5-row microcode entry (template: `BCD_MEM_ENTRY`, `Microcode.scala:149-167`)
Reuse the existing `-(Ay),-(Ax)` dual-address-register template (ABCD/SBCD/ADDX/SUBX), swapped from predecrement to postincrement and with a flags-only compute tail instead of a store:
```
e0: LOAD.sz (Ay) -> T0, auto=POSTINC(Ay)     (isFirst)
e1: Ay := Ay + delta                          (postinc write-back, dropped — mirrors the existing anUpd pattern, MicroOpAssembler.scala:2204-2207)
e2: LOAD.sz (Ax) -> T1, auto=POSTINC(Ax)
e3: Ax := Ax + delta                          (postinc write-back, dropped)
e4: CMP.sz srcA=T1(Ax), srcB=T0(Ay) -> flags only, no dst write   (isLast)
```
- New `Auto` cases: `APostincAy`, `APostincAx` (positive delta; mirror `APredecAy`/`APredecAx`, `Microcode.scala:101-102,630-631`).
- New `Sel` immediates: `SDeltaAy`/`SDeltaAx` (mirror `SNegDeltaAy`/`SNegDeltaAx`, unnegated).
- **Compute row must NOT touch X** (unlike BCD/ADDX/SUBX's `writesFlags` which conflates `readsX/writesX`, `Microcode.scala:601-609`) — add a flags-only variant (new `Desc` bool e.g. `writesNzvcOnly`, or a `resolve()`-side branch keyed on `ctx.op===DecOp.CMP` that skips the X read/write). The ALU compute itself needs NO new datapath — `AluDatapath.scala`'s existing `DecOp.CMP` subtractor path (`:43,84-97`) is directly reusable by setting `ctx.op := DecOp.CMP`.
- `OperationDecoder.scala`: new `isCmpm = (line===0xB) && isRmw && (opword(5 downto 3)===1)` arm (same shape as the existing `isBcdMem`/`isAddxSubxMem` carve-outs, `:661-698`) — `illegal:=False; op:=DecOp.CMP; microcoded:=True; ucEntry:=CMPM_ENTRY; size:=<per opmode>; writesNzvc:=True`. No `srcA/srcB/dst` needed (microcoded ops bypass normal operand routing entirely, confirmed via `DecodeStage.scala:320,492`).
- `PredecodeWord.scala`: explicit CMPM rule in the line-B arm — `r.simple:=True; r.lenWords:=U(1,3 bits)` (single opword, no ext — mirrors the existing `isAddxSubxReg||isBcdReg` carve-out, `:605-607`).
- `ucEntry` fits in the existing 6-bit field (current ROM occupies 0-48; CMPM_ENTRY at 49 is free). No new `Microcode.Ctx` fields needed (only generic `opword/op/size` already populated for every microcoded entry).

### No archDepth bump
T0/T1 only.

### Testing (lock-step vs Musashi, ×2)
`.B/.W/.L`, various Ay/Ax register pairs (incl. distinct and edge cases), verify both postincrements land correctly, flags match (incl. V/C edge cases), no register/memory write beyond the two An postincrements, X untouched (via a real ADDX consumer, per the project convention established in bf3c/CAS).

---

## 3. Build order

1. MOVE #imm,&lt;mem&gt; (simpler, decode-only + a 2-µop crack) — lock-step ×2 incl. regression on the existing register-source fast path.
2. CMPM (new microcode entry, more moving parts) — lock-step ×2.
3. Regression: full existing MOVE/CMP/EOR/BCD-mem/ADDX-mem families byte-identical.
4. POST-ROUTE gate — multi-regen, no floor regression vs current master (the recurring discipline this campaign established). Neither change touches the front-end hot path; expect low risk, but the gate is mandatory.

## 4. Non-goals
- No changes to the register-form MOVE or register-form CMP (both already correct and unaffected).
- No changes to EOR/BCD-mem/ADDX-mem (the templates being reused, read-only reference).
