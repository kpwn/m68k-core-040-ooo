# Line-5: ADDQ / SUBQ / Scc / DBcc — Design

**Status:** Implemented. Amended through the memory-RMW and TRAPcc slices, including
task #160 memory-destination Scc.
**Date:** 2026-06-07
**Parent:** [[decode-matrix-framework]], [[isa-completion-roadmap]], [[branch-handling-direction]] (DBcc reuses the branch EU).

## 1. Purpose

The line-5 (`0101`) family: **ADDQ/SUBQ** (quick add/subtract of an immediate 1-8), **Scc** (set a byte to all-1s/all-0s on a condition), and **DBcc** (decrement-and-branch — the 68k counted-loop primitive). High-frequency. ADDQ/SUBQ reuse the ALU; Scc/DBcc reuse the branch EU's condition eval. Lock-stepped vs Musashi. Gate: lock-step + OOC sanity (FMax-neutral).

## 2. Scope

**In:**
- **ADDQ/SUBQ** (`0101 ddd 0 ss mmmrrr` = ADDQ, `…ddd 1 ss…` = SUBQ): data = `ddd` (1-8, 0→8); `ss` = .B/.W/.L. Dn/An and memory-alterable destinations are implemented. **Dn/memory:** NZVCX flags. **An dest (ADDQ/SUBQ #n,An):** full-32 operation, NO flags, .W operates on the full 32 bits (no size restriction for An — like ADDA). Memory destinations use the shared load-op-store RMW crack.
- **Scc** (`0101 cccc 11 mmmrrr`, mode ≠ 001): set the destination BYTE to 0xFF if condition `cccc` is true, else 0x00. Data-register and memory-alterable destinations are implemented. Reuses the branch EU's 16 condition codes (cccc incl. ST=true/SF=false). A memory form cracks into `[condition -> T1][STORE.B T1 -> EA]`; it deliberately performs no leading load because the result is independent of old memory. NO flags affected.
- **DBcc** (`0101 cccc 11001 rrr` + disp16): if condition `cccc` is FALSE → decrement `Dn.W` by 1 (word, the low 16 bits, wrapping); if the decremented `Dn.W != -1` (0xFFFF) → branch to `pc+2+disp16`; else fall through. If condition TRUE → fall through (NO decrement, NO branch). **DBRA/DBF** (cc=F) is the common counted loop (always decrements). NO flags affected. The counter is `Dn[15:0]` (partial register — preserve Dn upper 16). Cracks into: condition eval (branch EU) + a conditional `Dn.W -= 1` (partial-reg write, like the MOVE.B/.W merge) + a conditional branch on `Dn.W != -1`.
- **TRAPcc** (`0101 cccc 11 111 ttt`, `ttt` 2/3/4) is implemented by its owning follow-on design. It remains disjoint from Scc absolute destinations (`ttt` 0/1).
- **Verification:** lock-step vs Musashi — ADDQ/SUBQ .B/.W/.L to Dn (flags), An (no flags, full-32), and memory; Scc register and memory forms (true/false); DBcc/DBRA loops; and TRAPcc. The exhaustive predecode reference partitions all 16 conditions across every 6-bit EA field.

**Out:** reserved mode-7 conditional encodings (`ttt` 5/6/7) and non-alterable memory destinations.

## 3. Components & dataflow

```
ADDQ/SUBQ #n,Dn : decode -> ADD/SUB {Dn, #n(1-8)} -> Dn + NZVCX (size-merged for .B/.W)
ADDQ/SUBQ #n,An : decode -> full-32 add/sub -> An, NO flags
Scc Dn          : decode -> cond-eval (branch EU codes) -> Dn[7:0] := cond ? 0xFF : 0x00, no flags
Scc <mem>       : decode -> cond-eval -> T1 -> STORE.B T1 to EA, no leading load, no flags
DBcc Dn,disp    : decode -> [cond eval; if !cond: Dn.W -= 1 (partial-reg, preserve upper16);
                  branch to pc+2+disp if Dn.W != -1]; if cond: fall through. no flags.
```

## 4. Verification
- **Directed:** decode each ADDQ/SUBQ size/destination; Scc register and two-µop memory shapes; DBcc; TRAPcc; DBcc expiry/preserve behavior; and ADDQ/SUBQ #8 (ddd=0→8).
- **Lock-step (the gate), ×2:** ADDQ/SUBQ .B/.W/.L Dn, An, and memory; Scc register/memory true and false cases; DBRA and a real-condition DBcc — value + NZVCX + PC/register/memory step-for-step vs Musashi.
- **`make test-fast`** + targeted verilator `-z` subsets.
- **OOC-synth sanity** (FMax-neutral; post-route non-deterministic — not gated): 0 err, no UNASSIGNED REGISTER. Keep plugin boundaries registered (standing rule — DBcc's branch reuse must not add a cross-module combinational crossing).

## 5. Resolved implementation choices
- DBcc is one branch-EU µop: it evaluates the condition, preserves/decrements Dn.W, and redirects when required.
- Scc uses the branch EU's condition mux. The register form byte-merges into Dn; the memory form writes T1 and follows with a byte store.
- ADDQ/SUBQ to An uses the full-width, no-flags address-register path.
- DBcc preserves Dn[31:16] while conditionally updating Dn[15:0].
