# Line-5: ADDQ / SUBQ / Scc / DBcc — Design

**Status:** Draft (feature-completion slice 5). User: "you pick / batch it, features first."
**Date:** 2026-06-07
**Parent:** [[decode-matrix-framework]], [[isa-completion-roadmap]], [[branch-handling-direction]] (DBcc reuses the branch EU).

## 1. Purpose

The line-5 (`0101`) family: **ADDQ/SUBQ** (quick add/subtract of an immediate 1-8), **Scc** (set a byte to all-1s/all-0s on a condition), and **DBcc** (decrement-and-branch — the 68k counted-loop primitive). High-frequency. ADDQ/SUBQ reuse the ALU; Scc/DBcc reuse the branch EU's condition eval. Lock-stepped vs Musashi. Gate: lock-step + OOC sanity (FMax-neutral).

## 2. Scope

**In:**
- **ADDQ/SUBQ** (`0101 ddd 0 ss mmmrrr` = ADDQ, `…ddd 1 ss…` = SUBQ): data = `ddd` (1-8, 0→8); `ss` = .B/.W/.L; **data-register OR address-register destination** (mode 0/1; memory dest deferred to mem-RMW). Reuses ADD/SUB. **Dn dest:** NZVCX flags. **An dest (ADDQ/SUBQ #n,An):** full-32 operation, NO flags, .W operates on the full 32 bits (no size restriction for An — like ADDA).
- **Scc** (`0101 cccc 11 mmmrrr`, mode ≠ 001): set the destination BYTE to 0xFF if condition `cccc` is true, else 0x00. **Data-register destination** (mode 0; memory dest deferred). Reuses the branch EU's 16 condition codes (cccc incl. ST=true/SF=false). NO flags affected.
- **DBcc** (`0101 cccc 11001 rrr` + disp16): if condition `cccc` is FALSE → decrement `Dn.W` by 1 (word, the low 16 bits, wrapping); if the decremented `Dn.W != -1` (0xFFFF) → branch to `pc+2+disp16`; else fall through. If condition TRUE → fall through (NO decrement, NO branch). **DBRA/DBF** (cc=F) is the common counted loop (always decrements). NO flags affected. The counter is `Dn[15:0]` (partial register — preserve Dn upper 16). Cracks into: condition eval (branch EU) + a conditional `Dn.W -= 1` (partial-reg write, like the MOVE.B/.W merge) + a conditional branch on `Dn.W != -1`.
- **Verification:** lock-step vs Musashi — ADDQ/SUBQ .B/.W/.L to Dn (flags) + to An (no flags, full-32); Scc (a few conditions, true/false → 0xFF/0x00); DBcc/DBRA loops (condition true → fall through; false → decrement + branch; counter expiry at -1; the Dn.W partial-register counter preserving upper 16). ALL existing UNCHANGED.

**Out:** memory-destination ADDQ/SUBQ/Scc (deferred to mem-RMW); TRAPcc (line-5 mode 7 — separate); the line-5 memory forms generally.

## 3. Components & dataflow

```
ADDQ/SUBQ #n,Dn : decode -> ADD/SUB {Dn, #n(1-8)} -> Dn + NZVCX (size-merged for .B/.W)
ADDQ/SUBQ #n,An : decode -> full-32 add/sub -> An, NO flags
Scc Dn          : decode -> cond-eval (branch EU codes) -> Dn[7:0] := cond ? 0xFF : 0x00, no flags
DBcc Dn,disp    : decode -> [cond eval; if !cond: Dn.W -= 1 (partial-reg, preserve upper16);
                  branch to pc+2+disp if Dn.W != -1]; if cond: fall through. no flags.
```

## 4. Verification
- **Directed:** decode each (ADDQ/SUBQ size/dest, Scc condition, DBcc crack); the DBcc counter (-1 expiry, Dn.W partial preserve); ADDQ/SUBQ #8 (ddd=0→8).
- **Lock-step (the gate), ×2:** ADDQ/SUBQ .B/.W/.L Dn (+ flags) and An (no flags, full-32); Scc (≥3 conditions, both true/false); a DBRA counted loop + a DBcc with a real condition (taken/not, expiry) — value + NZVCX + PC/Dn step-for-step vs Musashi. ALL existing UNCHANGED (ITLB seed flake → baseline-repro first).
- **`make test-fast`** + targeted verilator `-z` subsets.
- **OOC-synth sanity** (FMax-neutral; post-route non-deterministic — not gated): 0 err, no UNASSIGNED REGISTER. Keep plugin boundaries registered (standing rule — DBcc's branch reuse must not add a cross-module combinational crossing).

## 5. Open items
- DBcc crack: reuse the call/return `ibranch`/branch infrastructure? DBcc is PC-relative (target = pc+2+disp, known at decode) like Bcc — so the branch part is a conditional PC-relative branch whose condition is `!cc && (Dn.W-1 != -1)`. The decrement of Dn.W is a partial-register ALU write (reuse the MOVE.B/.W merge mechanism for the upper-16 preserve). Decide: one µop (branch EU reads Dn, decrements, evaluates) vs a crack (ALU decrement + branch). Pick the lower-churn; the branch EU already reads NZVC for conditions + can read Dn.
- Scc: the condition→0xFF/0x00 is a byte write to Dn[7:0] (partial register — preserve upper 24, reuse the merge). Which EU (branch EU has the condition eval; or ALU with a condition input).
- ADDQ/SUBQ to An: An writes are full-32 + no flags (like ADDA/SUBA) — route to the An path, not the flag-setting Dn path.
- DBcc Dn.W counter is a PARTIAL-register read+write (low 16) — reuse the just-merged MOVE.B/.W partial-register merge (read old Dn, merge the decremented low 16).
