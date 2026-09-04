# Part 131 — PAGE-scope CPUSHP/CINVP must follow `TC.P`, not a hardcoded 4 KB granule

**Date:** 2026-09-04
**Branch:** `fix/p131-cpushp-page-granule` (worktree
`/home/qwertyoruiop/m68k-core-040-ooo-worktrees/p131-page-granule`), based on
`fmax-closure-fanout` @ `afbabdd`. **NOT merged.**
**Simulation and synthesis only.** The SD card, the JTAG lease and the board were not
touched by this work; every hardware number quoted here is inherited from Part 130's
own capture, not re-measured.

---

## 0. TL;DR

`DcachePlugin`'s PAGE-scope match predicate compared `paddr[31:12]` — a **fixed 4 KB
page granule** — while task #195 had given the rest of the core a real `TC.P`-selected
page size. It was the one consumer of a page granule that `TC.P` was never routed to;
the plugin had no MMU handle at all. On the 8 KB-page machine the ROM configures
(`TC = 0x0000c000`, P=1) a `CPUSHP`/`CINVP` therefore inspected only the 4 KB half
containing the operand address.

Fixed. `DcachePlugin` now takes the same optional `host.get[MmuControlService]` handle
`IcachePlugin` already uses, latches `TC.P` at maintenance-command accept, and selects
the 8 KB tag prefix accordingly. `CINVP` shares the identical predicate and is fixed by
the same change. **The I-cache did not share the defect** — it has no page-scope
maintenance at all.

Five directed tests. **They discriminate in both directions, by experiment:** with the
new input forced to 4 KB the three 8 KB tests fail and the two 4 KB controls pass; with
the granule widened unconditionally the two 4 KB controls fail and the 8 KB tests pass.
Only the real fix passes all five.

**This does not claim to fix the boot.** It is a real defect with measured exposure on
the boot path, but the livelock it might contribute to did not reproduce on any of
Part 130's three boots (all wedged elsewhere, in a 53C96 status poll at `0x40899706`,
with `DE = 1` and a valid vector table). Fixing a real bug is the deliverable; boot
progress is a separate question that this work does not answer.

---

## 1. The defect

`src/main/scala/m68k040/cache/DcachePlugin.scala:2338` (pre-fix):

```scala
val pageMatch = rdTag(curWay)(tagBits - 1 downto 1) === target(31 downto 12)
```

Geometry is `lineBytes=16, sets=128, ways=4` → `offBits=4`, `setBits=7`, `tagBits=21`,
so the tag is `paddr[31:11]` and `rdTag[tagBits-1:1]` is `paddr[31:12]`. A **4 KB**
granule, hardcoded. Pre-fix, `grep -n "is8K\|pageSize8K\|pageMask" DcachePlugin.scala`
returned nothing — there was no page-size input to get wrong; there was no input.

On a real MC68040, `CPUSHP`/`CINVP` act on *the page containing the address*, and the
page size is a `TC` property (MC68040UM §4). `TC.P = 1` means 8 KB.

### 1.1 Why this is a gap in task #195, not a design decision

Task #195 threaded `TC.P` correctly through every other consumer and got each one right:

| site | what #195 did |
|---|---|
| `TableWalker.scala:174-180` | narrows the pointer→page index 6 → 5 bits in 8 K mode |
| `DtlbPlugin.scala:131` / `ItlbPlugin.scala:120` | masks VA[12] out of the TLB key so two VAs in one 8 K page collide to one entry |
| `LsEuPlugin.scala:640,650` | re-joins PA as `ppn[19:1] ## VA[12:0]` |
| `ExceptionUnit.scala:1991` | PTEST's page mask is `Mux(pageSize8K, 0xFFFFE000, 0xFFFFF000)` |

`DcachePlugin` was simply never wired. Part 128 had even parked the exact predicate:
*"PAGE walks the same `walkSet 0..sets-1` iteration as ALL with an extra tag-prefix
compare, but its `pageMatch` term has no directed test in this work."* That extra
tag-prefix compare is the bug.

### 1.2 Measured exposure (inherited from Part 130 §5.1 — not re-measured here)

A hardware breakpoint on the ROM's own `cpushp bc,%a1@` at `0x40887122` fired. Across
20 consecutive invocations from one boot, the machine's own registers at each hit
(`D4 = 0x0000E000`, `D5 = 0x00001FFF`) show the ROM had derived an **8 KB** page mask at
run time from `TC`. **3 of the 20** requested a flush range that crosses the 4 KB
boundary the match stopped at:

| `A1` | lines left uninspected |
|---|---|
| `0x0000EFD0` | 13 |
| `0x00010F40` | 4 |
| `0x00012D30` | 23 |

All in low RAM, all on `CM = 01` (copyback) pages — the mode Part 130 measured directly
off the ROM's own page tables.

Part 130's own characterisation is preserved because it is the honest one and it
narrows the claim: the ROM **over-asks** (it requests the whole page) but only *needs*
`[A1, A1 + D2·16)`, and our 4 KB block always contains `A1`. **The exposure is
exactly the straddling case — 15 % of that sample — not every page.** Part 130 also
states plainly what it did *not* establish: whether those missed tails actually held
dirty lines was never probed, so "up to 13 / 4 / 23 lines unpushed" is an upper bound on
the damage, not a measurement of it. Nothing here changes that.

---

## 2. The fix

`src/main/scala/m68k040/cache/DcachePlugin.scala`:

1. **A page-size input.** `val mmuCtrl = host.get[m68k040.services.MmuControlService]`
   / `val is8K = mmuCtrl.map(_.pageSize8K).getOrElse(False)`, byte-for-byte the optional
   pattern `IcachePlugin.scala:144-145` already uses. A standalone DUT with no
   `MmuControlPlugin` resolves to `None` → `False` → 4 KB, so every pre-existing test
   sees exactly its pre-fix behaviour.

   Reading the service through the `logic` **Handle** is what makes this safe against
   the cross-Fiber-task ordering race documented at `MmuControl.scala:55-88` (the race
   that once crashed `GenFullCoreSynthVerilog` with a Scala-`null` `Bool`): the getter
   *blocks* the calling build body until `MmuControlPlugin`'s own body has run.
   `MmuControlPlugin`'s build body reads nothing from `host`, so the new dependency
   cannot deadlock. **Verified, not assumed:** `runMain m68k040.top.GenFullCoreSynthVerilog`
   elaborates cleanly and the generated netlist contains
   `DcachePlugin_logic_maint_cmdIs8K <= MmuControlPlugin_logic_pageSize8K`.

2. **Latched at accept.** `cmdIs8K := is8K` in the maintenance FSM's `IDLE` accept arm.
   A PAGE walk spans hundreds of cycles; latching pins one consistent granule
   end-to-end without relying on "a `MOVEC` to `TC` cannot retire mid-sysOp", and keeps
   a long cross-plugin route out of the `CHECK`-stage comparator's cone.

3. **The predicate.**

```scala
val pageHiMatch = rdTag(curWay)(tagBits - 1 downto 2) === target(31 downto 13)
val pageLoMatch = rdTag(curWay)(1) === target(12)
val pageMatch   = pageHiMatch && (cmdIs8K || pageLoMatch)
```

   One wide compare plus a conditionally-qualified extra bit, rather than a `Mux` of two
   full comparators. `pageHiMatch` *is* the 8 KB predicate; 4 KB mode additionally
   qualifies it with `paddr[12]`.

`CINVP` reaches the same `pageMatch` through the same `scopeHit` mux and is fixed by the
same change; there is no second predicate.

Nothing else changed: the walk itself, `touchesDc`, the quiesce gate, the writeback leg
and the completion handshake are untouched.

---

## 3. Are there other missed consumers? — swept, and no

**`CINVP`: yes, same predicate, fixed together** (§2). It is not a separate site.

**The I-cache: NO, and for a structural reason.** `IcachePlugin` implements no
page-scope maintenance at all. `ExceptionUnit.scala:2157-2158` pulses
`icMaintPulse` whenever the CPUSH/CINV cache selector names IC (10) or BC (11),
*regardless of scope*, and `IcachePlugin.scala:232`'s `anyInvalidate = invalidateAll ||
maintInvalidateAll` clears every valid bit. That is **over**-invalidation, never
under-invalidation, so it is architecturally safe at any page size and has no granule to
get wrong. (The I-cache is read-only, so there is no dirty data to lose.) Its
`is8K` at `IcachePlugin.scala:145` is used only for physical-address formation, which
#195 already got right.

**Everything else with a 4 KB slice** — a full `grep -rn "downto 12)" src/main` sweep,
each hit classified:

| site | verdict |
|---|---|
| `DtlbPlugin:161`, `ItlbPlugin:136`, `MmuTypes:54`, `ExceptionUnit`'s `dtoVpn` sites | the deliberate 4 KB-granule TLB-**key** convention; #195's 8 K `tlbKey` mask makes it correct |
| `LsEuPlugin:527,536` | MOVEM page-cross check — conservative **over**-splitting at 4 KB; on an 8 KB machine it splits more often than needed, never less |
| `IcachePlugin:1417,1419,1426,1460` | prefetch clamp at the 4 KB boundary — conservative **under**-prefetch |
| `UmWriteQueue:72` | page query; at worst a duplicate U/M entry |
| `MmuTypes:78` `pgPpn` | descriptor PPN field; `LsEuPlugin:640,650` re-joins it correctly per `TC.P` |
| `MmuTypes:53` `pageIdx` | **dead** in `src/main` — `TableWalker:178-179` computes its own `off4k`/`off8k`; the only other references are in tests |
| `DecodedUop:265`'s `An & 0xFFFFF000` | a **comment** only; the live code is `ExceptionUnit:1991`'s `TC.P`-selected `ptestPageMask` |
| `DcachePlugin:46` `geo.requireViptSafe(4096, "L1D")` | a VIPT-aliasing constraint against the **minimum** page size; 4096 is the conservatively correct argument and must stay |

This corroborates Part 130's own sweep independently. `DcachePlugin`'s `pageMatch` was
the only missed page-granule consumer.

---

## 4. The tests, and why they are not a "test written after the fix"

Part 130 stated the difficulty precisely and it is real: before this change
`DcachePlugin` had **no page-size input at all**, so "a `CPUSHP` covers 4 KB" was the
only behaviour the RTL could be asked for — and it is the *correct* behaviour for a
4 KB machine. No test could demand the other one until the input existed. The missing
input and the missing test are the same defect, so they land together.

The bar held instead is **discrimination, established by experiment rather than by
inspection**. `DcacheSpec`'s DUT gains `MmuControlPlugin` (self-contained; `pageSize8K`
is `RegInit(False)`, so every pre-existing test in the file is unchanged), and one
helper `p131Coverage(name, seed, is8K, operand, push, invalidate)` runs an identical
body with **only `is8K` differing**.

Geometry, taken from the live capture: an 8 KB page based at `0xE000`; `P131_LOW =
0xEFD0` in its lower 4 KB half (the measured `A1`); `P131_HIGH = 0xF0C0` in its upper
half (where the measured range ran to); plus `P131_BELOW = 0xDF00` and `P131_ABOVE =
0x10040` as controls in the adjacent 8 KB pages. Set index is `paddr[10:4]`, so the four
land in sets `0x7D / 0x0C / 0x70 / 0x04` — distinct, so there is no way pressure and no
eviction to confound the result. Each line is warmed clean, then dirtied with a distinct
`0xC0DE….` mark, and the mark's arrival in memory is a positive identification.

| # | test | `is8K` | operand |
|---|---|---|---|
| a | `CPUSHP covers the WHOLE 8 KB page when TCR.P=1 (operand in the lower half)` | 1 | `0xEFD0` (lower half) — **the measured straddle** |
| b | `CPUSHP from the UPPER half of an 8 KB page also covers the lower half` | 1 | `0xF0C0` (upper half) — the symmetric straddle |
| c | `4 KB CONTROL: with TCR.P=0 a CPUSHP covers exactly 4 KB and no more` | 0 | `0xEFD0` |
| d | `CINVP shares the TCR.P granule: TCR.P=1 invalidates both 4 KB halves` | 1 | `0xEFD0` |
| e | `4 KB CONTROL: with TCR.P=0 CINVP must NOT invalidate the neighbouring 4 KB block` | 0 | `0xEFD0` |

All five also assert that the adjacent-page control lines are **not** touched, so a
granule that widened to 16 KB would fail too.

Each run asserts the `TC.P` poke read back before proceeding, so a poke clobbered by
reset (this project's task #194 trap) fails loudly instead of leaving the test vacuous.
Explicit sim seeds are used rather than `doSim`'s default draw, both for determinism and
so these tests do not consume from the shared `scala.util.Random` that the suite's
seed-dependent flakes ride on.

### 4.1 Both discrimination directions, measured

**Fixed RTL:** 5 / 5 pass.

**Input forced to 4 KB** (`cmdIs8K := False`, i.e. exactly the pre-fix behaviour, with
everything else identical) — 2 succeeded, 3 failed:

```
- CPUSHP covers the WHOLE 8 KB page when TCR.P=1 (operand in the lower half) *** FAILED ***
    r.pushed(P131_HIGH) was false
- CPUSHP from the UPPER half of an 8 KB page also covers the lower half     *** FAILED ***
- 4 KB CONTROL: with TCR.P=0 a CPUSHP covers exactly 4 KB and no more        (passed)
- CINVP shares the TCR.P granule: TCR.P=1 invalidates both 4 KB halves       *** FAILED ***
    r.dirty(P131_LOW) was false, but r.dirty(P131_HIGH) was true
- 4 KB CONTROL: with TCR.P=0 CINVP must NOT invalidate the neighbouring block (passed)
```

**Granule widened unconditionally** (`pageMatch = pageHiMatch`, i.e. always 8 KB) —
3 succeeded, 2 failed:

```
- 4 KB CONTROL: with TCR.P=0 a CPUSHP covers exactly 4 KB and no more *** FAILED ***
    r.pushed(P131_HIGH) was true
- 4 KB CONTROL: with TCR.P=0 CINVP must NOT invalidate the neighbouring 4 KB block *** FAILED ***
    r.dirty(P131_HIGH) was false
```

Only the real fix passes all five. The 4 KB controls are not decoration: the
over-invalidation direction they guard is *worse* than the bug being fixed, because it
discards dirty data rather than merely failing to write it back.

---

## 5. Verification

Host discipline: `free -g` checked before every JVM; **one JVM at a time**; the
Vivado gate blocks on `flock /var/tmp/m68k-ooo-vivado.lock` and was queued behind the
`PLACE_DIRECTIVE=Explore` reseed build that was already holding it.

### 5.1 `make test-fast` (= `sbt fastTest`)

**337 succeeded, 0 failed, 2 ignored, 214 suites.** Clean. (The new tests are
`VerilatorTest`-tagged and therefore *excluded* from `fastTest`, so this count is
expected to be untouched — and is.)

### 5.2 `m68k040.ls.*` + `m68k040.cache.*`, both arms, same session

| arm | run | succeeded | failed |
|---|---|---|---|
| baseline `afbabdd` (fresh worktree) | 292 | 281 | **11** |
| `fix/p131-cpushp-page-granule` | 297 | 286 | **11** |

The failure **lists are name-for-name identical** (`LsEuCrossSpec` ×6,
`DtlbCrossPageSplitSpec` ×2, `StackOpSpec`, `LsBackendInjectSpec`, `DcacheSpec`'s VIPT
D2). Arithmetic reconciles exactly: `281 + 5 = 286`, `292 + 5 = 297`. **Zero
regressions; +5 new passing tests.** Neither arm hit the two seed-dependent flakes the
brief warns about (`AguCrossSpec`, `RobPluginSpec`'s `debugPcApply`) on these runs — so
this is a clean comparison, not a lucky one that needed reconciling.

### 5.3 `ExecuteLockStepSpec` (excluded from `test-fast`; run separately)

**468 run, 466 succeeded, 2 failed** — exactly the known pre-existing pair, the
phantom-RAS wrong-path `bsr` test and `CMP2.W (d8,An,Xn)`. No baseline arm was run for
this suite and none is needed: the change adds no tests here, so the suite's seed
sequence is unchanged and the pair is the documented standing list.

### 5.4 200-seed fuzz

`tools/fuzz/sweep.sh 0 200 25 20`, 8 batches of 25 seeds, one JVM per batch, run
serially. All 200 seeds ran (8 × 25 `[fuzz] seed=` lines, verified per batch).

**3 divergences: seeds 80, 109, 127** — byte-for-byte the standing set. **It did not
rise.** The signatures are also unchanged in kind: seed 80 is the silent `D5` clobber
(`dut=0x00000000 oracle=0x0000007e`, the known `Scc <memory-indirect EA>` gap), 109 and
127 are the known wild-PC pair.

```
[fuzz] seed=80  DIVERGED[STEP]: idx=79 reg D5: dut=0x00000000 oracle=0x0000007e
[fuzz] seed=109 DIVERGED[STEP]: idx=64 pc: dut=0x7bca4112 oracle=0x4080013e
[fuzz] seed=127 DIVERGED[STEP]: idx=70 pc: dut=0x3d973c90 oracle=0x4080015e
```

### 5.5 Full-core postroute synth gate

**SYNTH_RESULT_PLACEHOLDER**

---

## 6. What was NOT run / NOT established

* **The board.** No JTAG lease was taken, no SD card touched, no bitstream loaded. The
  §1.2 hardware numbers are Part 130's, quoted, not re-measured.
* **Whether the missed tails actually held dirty lines.** Part 130 explicitly did not
  probe this and neither did I. The fix is justified against the manual and against the
  measured coverage shortfall, not against a demonstrated data loss.
* **Any claim about the boot.** This is not asserted to fix, improve, or change the
  boot in any way. Part 130's three boots all wedged at a 53C96 status poll
  (`0x40899706`) with `DE = 1` and a valid vector table — the Part 129 livelock this
  defect might have contributed to was not on the board to observe.
* **The full `sbt test`** (every suite including `SlowTest`/`BoardTest`) — not run; the
  brief's bar named specific suites and those were run.
* **A second synth arm.** The `afbabdd` baseline is taken from the measurement a
  sibling agent made earlier today under the identical flow, not re-run here. One
  Vivado run per arm is not a distribution.
* **`FuzzLockStepSpec` beyond 200 seeds**, and the ported-test corpus — out of scope.

---

## 7. Flagged for someone else

**`macqd700-soc/tools/build_bitstream.sh:121` is broken on mainline and the fix is
stranded.** The build-contention guard is

```sh
if pgrep -af 'vivado.*-mode batch' >/dev/null; then ... exit 1; fi
```

which **self-matches**: `pgrep`'s own command line contains the pattern, so on a
completely idle host it reports contention against itself. It falsely blocked 8
consecutive attempts on 2026-09-04 and burned an experiment's entire retry budget. The
correct probe is the real mutex,
`flock -n /var/tmp/m68k-ooo-vivado.lock -c true` (non-zero ⇒ held).

That fix currently exists **only as an uncommitted diff in the `p123-reseed`
worktree** (`/home/qwertyoruiop/macqd700-soc-worktrees/p123-reseed`, guard rewritten at
line ~198). It needs committing and upstreaming to `macqd700-soc` mainline. I did not
touch that worktree — a sibling agent's uncommitted work is not mine to move. This
repo's own `tools/run_p131_synth_gate.sh` uses the `flock` form and documents why.
