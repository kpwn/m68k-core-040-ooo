# 68040 MSP/ISP three-stack banking + live M-bit (Slice A) — Design

**Status:** Draft — user-approved shape (split: banking slice first; airtight lock-step via extended OracleStep).
**Date:** 2026-06-14
**Parent:** the exception subsystem ([[exception-subsystem]]). One of the "Other fast-follows (deferred)" — MSP/ISP split.
**Scope note:** This is **Slice A** of the M-bit feature. **Slice B** (the interrupt-with-M=1 throwaway frame, format $1, + the RTE-from-$1 re-RTE loop) is explicitly OUT and deferred to its own brainstorm→spec→plan.

## 1. Purpose & motivation

The 68040 has a **three-stack model**: A7 (architectural address register 7) selects one of three stack pointers by the SR `S` (supervisor) and `M` (master) bits:

| S | M | A7 selects | name |
|---|---|------------|------|
| 0 | x | USP | User Stack Pointer |
| 1 | 0 | ISP | Interrupt Stack Pointer |
| 1 | 1 | MSP | Master Stack Pointer |

This core today implements only a **two-bank** model — `a7 = Mux(s, ssp, usp)` (`SystemState.scala:46`) — with the M-bit stored in the SR system byte (bit 4) but **inert** (`SystemState.scala:14` comment: "(M=4 unused this slice)"). So an OS that sets M=1 to run tasks on a master stack while keeping interrupts on a separate interrupt stack cannot be modelled, and a MOVE-to-SR / RTE that changes M does not re-bank A7.

This slice makes the M-bit **live**: A7 banks across all three stacks, M is settable by MOVE-to-SR and restored by RTE, and faults/traps stack on whichever supervisor stack is current. The reference (Musashi) already models all three stacks (`m68ki_set_sm_flag`, `m68kcpu.h:1552`), so the behavior is precisely defined and lock-steppable.

## 2. Background — what already works, and what is M-ready

- **Fault/trap entry already preserves M.** Entry computes the new system byte as `newSysBase = (srSys | 0x20) & 0x3f` (`ExceptionUnit.scala:460`). `0x3f` keeps bits[5:0], which **includes M (bit 4)** — so M is already preserved across fault/trap entry, matching Musashi's `m68ki_init_exception` (sets only S, `m68kcpu.h:1632`). No `newSys` change is needed.
- **MOVE-to-SR already writes the full system byte.** `ExceptionUnit.scala:554` does `ss.setSrSys.payload := sysCapVal(15 downto 8)` — bit 4 (M) is already writable. STOP does the same (`:591`).
- **MOVE-to-SR / RTE already re-bank A7 from `ss.a7`.** S_REDIR writes the int-PRF arch-reg-15 with `obsA7 := ss.a7` (`ExceptionUnit.scala:608`); RTE writes `obsA7 := Mux(popSr(13), newSsp, ss.usp)` (`:539`). Once `ss.a7` (and the RTE re-bank expression) become **(S,M)-aware**, the re-banking works with no new control logic.
- **Musashi reference fully models M/MSP/ISP.** `m68ki_set_sm_flag` banks `sp[]` by index `S | ((S>>1)&M)` → USP(0)/ISP(4)/MSP(6) (`m68kcpu.h:1552-1561`); `--initial-sr` sets the full 16-bit SR including M (`musashi_run.cpp:74,162`); `MusashiRef` already exposes `REG_USP/REG_ISP/REG_MSP` (`m68k_ref.h:52-54`, mapped in `m68k_ref.cpp:268-270`).

The implication: the RTL change is **localized** — extend every S-only bank selection to (S,M). The bulk of the *new* surface is the airtight lock-step harness extension.

## 3. Scope

**In (Slice A):**
- A third committed stack-pointer bank (MSP) in `SystemState`; the existing `ssp` is **renamed `isp`** (it is the ISP — the M=0 supervisor bank used at reset).
- A live M-bit: A7 banks by (S,M); M settable by MOVE-to-SR (privileged), restored by RTE, preserved across fault/trap entry.
- Fault/trap exception entry stacks the frame on the **current** supervisor bank `Mux(m, msp, isp)`; RTE pops it and re-banks A7 by the **restored** (S,M).
- Interrupt entry **only for the M=0 case** — frame on ISP, no throwaway (this is the existing behavior, now expressed through the (S,M) bank selection; verified unchanged).
- Airtight lock-step: extend the oracle trace + `OracleStep` + the DUT whitebox to carry MSP and ISP, compared **every step** (both active and inactive banks).

**Out (Slice B / future):**
- The interrupt-with-M=1 **throwaway frame** (format $1): the double-frame entry (`$0` on MSP → clear M → `$1` on ISP) and the RTE-from-$1 re-RTE loop (`m68kcpu.h:2230-2236`). Slice A must **not** combine M=1 with an interrupt; the DUT keeps M-preserving interrupt entry, which is correct only for M=0.
- Any change to fault/trap/MMU frame formats ($0/$2/$7), SSW, or vector logic.
- MOVEC access to MSP/ISP (they are **not** MOVEC registers on the 68040 — only USP is, via MOVE USP; MSP/ISP are reached by being A7 in the appropriate mode). No MOVEC change.

## 4. Architecture — one principle

**Everywhere the code selects or writes the A7 bank by `S` alone, extend it to `(S, M)`.** Concretely, in `SystemState`:

```
val msp = RegInit(U(0, 32 bits))          // new MSP bank
val isp = RegInit(U(0, 32 bits))          // renamed from `ssp` (the M=0 supervisor bank)
val m   = srSys(M_BIT)                     // M_BIT = 4 (SR bit 12)
val supBank = Mux(m, msp, isp)            // the active supervisor stack
val a7      = Mux(s, supBank, usp)        // full three-way bank
```

and a **single write-routing rule** for the FSM's SP-set and for `writeA7`: route the value to `usp` / `isp` / `msp` by committed `(s, m)`. This keeps the change DRY and makes "which bank" decided in exactly one place.

The committed banks stay coherent with the renamed int-reg-15 by the *existing* mechanism (the FSM writes the re-banked A7 into arch-15 on every serializing change; normal A7-modifying instructions sync the committed bank via `writeA7`). Slice A only widens the bank index of that mechanism from S to (S,M) — it does not invent new coherence machinery.

## 5. Component changes

### 5.1 `src/main/scala/m68k040/exception/SystemState.scala`
- Add `val M_BIT = 4` and `val m = srSys(M_BIT)` (simPublic).
- Add `val msp = RegInit(U(0,32 bits))` (simPublic). **Rename `ssp` → `isp`** throughout (reg, `setSsp` → `setIsp`, the `when(setSsp.valid)` commit). Add a `setMsp` Flow port + its commit `when`.
- Change `a7 = Mux(s, Mux(m, msp, isp), usp)` (simPublic).
- Change the `writeA7` commit to route by (s,m): `when(s){ when(m){msp := …} otherwise {isp := …} } otherwise { usp := … }`, preserving the existing later-`when`-wins priority so an explicit `setIsp`/`setMsp`/`setUsp` in the same cycle overrides.
- Keep all idle-default/`allowOverride` drivers for the new ports (standalone test DUTs drive them).

### 5.2 `src/main/scala/m68k040/exception/ExceptionUnit.scala`
- **Entry frame base** (`:383-386`): replace `ss.ssp` with the current supervisor bank `Mux(ss.m, ss.msp, ss.isp)`.
- **Entry post-stack SP write** (`:454,474` — `ss.setSsp`): route to the current supervisor bank (`setMsp` when `ss.m`, else `setIsp`). M is preserved across entry, so this is the same bank the frame was stacked on.
- **RTE SP restore + A7 re-bank** (`:529,539`): write the popped `newSsp` back to the bank that was active during the exception (`ss.m` is unchanged for fault/trap, so route by current `ss.m`); change the A7 re-bank to `obsA7 := Mux(popSr(13), Mux(popSr(12), ss.msp, ss.isp), ss.usp)` (restored S=bit13, restored M=bit12). Note the popped `newSsp` must reach `obsA7` for the active case — preserve the existing data path, just widen the bank mux.
- **MOVE-to-SR / STOP / S_REDIR** (`:554,591,608`): no new code — `ss.a7` is now (S,M)-aware, so the existing `obsA7 := ss.a7` re-bank in S_REDIR handles an M-flip automatically.
- **Interrupt entry**: the frame base + SP write now read the current supervisor bank, which is ISP when M=0 — identical to today's single-supervisor behavior. **Guard:** Slice A does not implement the M=1 interrupt path; no code asserts M=1+interrupt, and existing interrupt tests run M=0.

### 5.3 Lock-step harness (airtight inactive-bank compare)
- **`tools/musashi/musashi_run.cpp`** (per-step trace `:209-223`): append `msp=0x%08x isp=0x%08x` to the format string, supplying `ref.get_reg(MusashiRef::REG_MSP)` and `REG_ISP`. (REG enums already exist; confirm `REG_MSP` is mapped in `m68k_ref.cpp` alongside `REG_ISP` at `:269` — add the `case REG_MSP: mr = M68K_REG_MSP; break;` if absent.) Rebuild the oracle binary.
- **`src/test/scala/m68k040/oracle/OracleStep.scala`**: add `msp: Long, isp: Long` to the case class; extend `parseTrace` to read the two new `key=val` tokens. Default/back-compat: if a trace lacks the tokens (older binary), fail loudly rather than silently defaulting — the rebuilt binary always emits them.
- **DUT whitebox** (the lock-step comparator, in `ExecuteLockStepSpec` / its shared harness): read committed `dut.…exc.ss.msp` and `dut.…exc.ss.isp` (already simPublic) and compare against `OracleStep.msp/isp` every step, alongside the existing SR/A7 compare. Seed support: allow an initial MSP/ISP in the test setup (extend the existing `srSys`/bootA7 seeding) so a test can start with non-zero banks.

## 6. Data flow (the win)

```
supervisor, M=0 (ISP active): A7 == ISP
  MOVE #(sr with M=1),SR  ->  ss.srSys.M := 1  ->  ss.a7 mux selects MSP
                          ->  S_REDIR writes arch-15 := MSP  ->  handler (A7) sees master stack
  TRAP #n                 ->  entry frameBase = MSP - 8  ->  $0 frame stacked on MSP (M preserved)
  RTE                     ->  pop $0  ->  restore SR (M=1)  ->  A7 re-bank Mux(S,Mux(M,MSP,ISP),USP) = MSP
```

Each transition is observable: SR carries M, A7 carries the active bank, the frame lands in memory, and the **inactive** bank (ISP here) is compared every step via the extended OracleStep.

## 7. Verification

### 7.1 Directed unit (SystemState)
- Bank selection truth table: drive `srSys` for all (S,M) combinations, assert `a7` selects USP/ISP/MSP correctly.
- Write routing: `writeA7` with each (S,M) lands in the right bank and leaves the others intact; `setMsp`/`setIsp`/`setUsp` priority over `writeA7` in the same cycle.

### 7.2 Lock-step (the gate) — vs Musashi, ×2 seeds
A new directed program (and additions to `ExecuteLockStepSpec`) that:
- Starts in supervisor M=0; MOVE-to-SR sets M=1; writes a distinct sentinel to A7 (= MSP); MOVE-to-SR clears M=0; writes a distinct sentinel to A7 (= ISP) — proving both banks hold independent values and A7 reveals each.
- With M=1: TRAP (or a privilege/illegal fault) → frame on MSP → handler → RTE → resume; assert the frame bytes (memory), A7 movement, SR (M-bit), **and** the inactive ISP each step.
- Symmetric M=0 case: trap → frame on ISP → RTE (ensures the M=0 path is unchanged).
- **Regression:** ALL existing exception / interrupt / trap / MMU-fault / RTE lock-step UNCHANGED ×2 seeds (they run reset M=0; old `ssp` semantics == new `isp`). Specifically re-run InterruptEntrySpec, MmuFaultOracleSpec, ItlbFaultOracleSpec, and the format-$0/$2/$7 + RTE groups.
- Pre-existing ITLB-flake caveat: repro on baseline before attributing any flake to this slice.

### 7.3 Synth gate
- Honest post-route (`synth/impl_FullCore.tcl`), ≥200 MHz gate (per [[frontend-fmax-250-campaign]] the gate is ≥200; baseline ~247). Expect **neutral**: this adds one 32-bit register (MSP) and widens an S-mux to a 3:1 (S,M) mux on the **serializing** commit-side path (not a hot datapath). Report WNS/FMax vs the master baseline.

## 8. Open items / Slice B handoff
- **Slice B (deferred):** interrupt-with-M=1 throwaway frame (format $1) + RTE-from-$1 re-RTE loop. The (S,M) banking + the OracleStep MSP/ISP compare built here are its prerequisites. Reference: `m68kcpu.h:2228-2236` (entry double-frame), and Musashi's RTE format-$1 handling (to be located in Slice B).
- **Naming:** `ssp`→`isp` rename is recommended for PRM correctness; if the churn in the harness is undesirable, the fallback is to keep the name `ssp` documented as "the ISP (M=0 supervisor bank)". Chosen: **rename to `isp`**.
- **Harness seeding:** confirm whether any existing exception test seeds a non-zero supervisor SP that must now map to `isp` (it does — bootA7 for supervisor programs); ensure the rename + optional MSP seed leaves those green.
