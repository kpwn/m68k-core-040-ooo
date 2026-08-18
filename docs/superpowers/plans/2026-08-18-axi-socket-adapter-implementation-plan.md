# `axi_i` / `axi_d` Socket Adapter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make this core's AXI master surface conform to `macqd700-soc`'s authoritative CPU-socket contract (`macqd700-soc/rtl/soc/cpu_socket.vh`) by implementing every **DECIDED** item of `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md`: the boundary byte-order permutation (D1-D3), real MMIO sizing with a bounded exact-cover sequencer (D4-D6, D24-D26, D30), the owner-tag serializing master merge with fail-closed walker guards and a bounded-grant watchdog (D7-D10, D19-D20, D27), the kind-coded halt-reason channel (D28), the real vector-0 boot fetch (D12-D16), `ipl_ack`/autovector (D17-D18), `cpu_peripheral_reset` (D22), and a new `M68kSocketTop` presenting exactly the socket's port surface (D11, D21, D23, D29).

**Architecture:** No existing core convention changes. A new `m68k040.socket` package adds two pure helper objects (`SocketByteOrder`, `MmioCover`), three `FiberPlugin`s (`AxiDMergePlugin`, `ResetVectorPlugin`, `PeripheralResetPlugin`) and the flat socket bundles (`SocketAxiI`, `SocketAxiD`). A new `M68kSocketTop` `Component` (`src/main/scala/m68k040/top/SocketTop.scala`) wraps an `M68kCore` built from the existing `GenFullCoreSynthVerilog` plugin list plus the socket-only plugins, and connects the core's two remaining masters to the socket bundles field-by-field, applying the §2 permutation to exactly `w.data`, `w.strb` and `r.data`. `M68kFullCoreSynth` keeps its exact port surface and stays the OOC/FMax gate target; every socket-only plugin defaults off and elaborates to nothing there.

**Tech Stack:** SpinalHDL 1.14.1 (`spinalhdl-core`, `spinalhdl-lib`, `spinalhdl-sim`, `spinalhdl-idsl-plugin`), Scala 2.13.16, ScalaTest 3.2.19 (`AnyFunSuite`), SpinalSim (default backend — specs must stay **untagged** to be visible to `fastTest`, which excludes `VerilatorTest`/`SlowTest`/`BoardTest`), Python 3 standard library only (no pytest in this repo), Vivado for the post-route gate.

## Global Constraints

- Branch is `fmax-closure-fanout`; the plan's parent commit is whatever `git rev-parse HEAD` reports at execution start (must be `ca7587f` or a descendant). Never `git checkout <sha>` in the shared tree — use `git worktree add` for any isolated before/after verification (project standing rule, task #199 collision).
- **The standing no-SoC-address-map rule, verbatim from spec §3.3 ("Standing-rule conformance"):** *"Nothing in this derivation consults an address range. The only input that says 'this is a device, not memory' is `cacheMode === INHIBITED`, which comes from the MMU's page/TTR attributes on the access itself (`DcachePlugin.scala:351-353,663,701`). This satisfies the project's standing rule that the core may only reason from MMU-configured attributes and contemporaneous bus responses, never from a cached or assumed SoC decode map."* No file under `src/main/scala` may gain an address comparison, range table, or "this address was proven backed" bit as a result of this plan. The one address-shaped table that exists, `AxiMemModel.decoded`, is a **test-harness** map whose own comment forbids `src/main` from consulting anything of that shape; that rule is unchanged here.
- **Spec §13 "Gates", verbatim:** *"the project's standing rules apply unchanged: `make SBT=~/sbt/bin/sbt test-fast`, full lock-step, and an **uncontended** post-route gate for the socket top (§9.1 changes the reset net's fanout, and FMax on this machine is unreliable under concurrent Vivado/JTAG sessions — a repeatedly confirmed hazard). `git worktree add` is mandatory for any before/after comparison."*
- **Area budget.** The spec states **no** numeric LUT/FF budget of its own. This plan therefore adopts the debug-ctrl plan's precedent verbatim and **proposes** it as this plan's gate (Task 14 records it as a proposal and then measures against it): **≤ +1.0% CLB LUTs and ≤ +1.0% CLB Registers** versus the reference netlist, **zero DSP and zero BRAM delta**, and **achieved FMax no worse than 2%** below the current uncontended reference. Justification for adopting rather than inventing: every RTL tranche on this branch has been judged against that same triple, and this tranche is smaller in logic terms than the debug slave that set it.
- **The FMax/area reference is re-read at execution time, never hardcoded.** The most recent recorded uncontended post-route numbers on this branch are **197.278 MHz, CLB LUTs 124263, CLB Registers 55861**, from the netlist generated at `e267df3` and recorded in `.superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md:715-731` (slice commit `5aae2c5`, task #219). Task 14 Step 4 re-reads the current value; if a later gate superseded it, the value read there wins.
- **Synth-gate-every-slice.** A full Vivado post-route run per task is not affordable. This plan discharges the standing rule the way the debug-ctrl plan did: every RTL task runs the **structural netlist checker** (Task 2) plus `make SBT=~/sbt/bin/sbt test-fast`, and Task 14 runs the one real post-route gate for the whole tranche. Any task whose implementer suspects a timing-path change (in practice Tasks 5, 7 and 8) must say so in its report so Task 14 looks for it specifically.
- **Never run two Vivado sessions concurrently**, and never launch a gate while a JTAG session is live. Check `pgrep -a vivado` and `pgrep -af 'jtag|xsdb|hw_server'` first. FMax on this machine is unreliable under contention (repeatedly confirmed: 214.3 vs 163.9 MHz for an identical commit).
- **Machine budget:** 29 GB RAM total, at most 2 concurrent heavy JVMs, none at all while Vivado runs. `free -g` before starting an `sbt` task.
- **`M68kFullCoreSynth`'s port surface must not change.** Spec §10, verbatim: *"Any change to `M68kFullCoreSynth`'s port surface or behaviour. It stays exactly as it is as the OOC-synth/FMax-gate target (§9.3): no port added, none removed, none tied off. Its *wiring file* is not frozen — D28 widens the halt seam it drives at `FullCoreSynth.scala:364`, and D27 touches the walkers it instantiates — but every such change must be behaviour-identical with the socket-only plugins absent."* Task 2 makes this machine-checked, not merely intended, and every later task re-runs that check.
- **No `Global.scala` key may be added.** `src/main/scala/m68k040/Global.scala` holds exactly 13 `Database.blocking[Int]()` keys (`ROB_DEPTH` … `PHT_ENTRIES`) and must be byte-identical at the end of this plan. Socket parameters are constructor values, following `DebugCtrlPlugin`'s precedent.
- **`AxiIds.scala` is the single source of truth for AXI IDs.** Its own rule (`AxiIds.scala:32`): *"never write a numeric AXI ID literal anywhere else in `src/main`."* This plan adds exactly one constant (`RESET_VEC = 5`, spec §6.2) and **renumbers nothing** — spec §4.3: *"The one thing the implementation **must not** do is renumber `WALK_READ`/`WALK_WRITE` to 'fix' the collision."*
- **Socket parameters are fixed by `/home/qwertyoruiop/macqd700-soc/rtl/soc/cpu_socket.vh:78-87`:** `CPU_SOCKET_AXI_AW 32`, `CPU_SOCKET_AXI_IW 4`, `CPU_SOCKET_AXI_DW` 128 (and 256 for `axi_i` only, once SOC-1 lands).
- **The `axi_i` / `axi_d` port lists are fixed by `cpu_socket.vh:98-142`** and contain **no** `prot`/`cache`/`lock`/`qos`/`region` (D29), and `axi_i` is AR/R only (no AW/W/B, no WSTRB).
- **Cross-repo blockers.** `SOC-1` (split `CPU_SOCKET_AXI_DW` into `..._AXI_I_DW = 256` / `..._AXI_D_DW = 128` and widen the `axi_i` fabric path), `SOC-2` (add `cpu_peripheral_reset` to `cpu_socket.vh` §6), `SOC-3` (instantiate this core in place of v1, dropping `if_to_axi.v` and `axi_narrow_to_wide.v`) and `SOC-4` (the `peripheral_bus.v` multi-hot-WSTRB defect) all live in `macqd700-soc`, **not** here. **No task in this plan is blocked on any of them**, and none may be attempted here. Task 14 records their status. Note **SOC-4's prescribed fix has been superseded** by `docs/superpowers/specs/2026-08-18-v1-shared-infra-fixes-design.md` §2.2 (decision `SI2`): the *finding* stands; the *prescription* (generalise the ASC byte-address-incrementing FSM to every slot) would introduce a worse bug on the VIA/IWM/SCSI slots whose device-local address ignores `addr[1:0]`. This plan cites the finding and points at `SI2`; it never restates SOC-4's prescription.
- **Verification obligations are enumerated in spec §13** and each maps to a task in the Self-Review coverage table. A task may not be marked done with one of its §13 obligations unimplemented.
- **Citation drift is expected and must be re-checked, not trusted.** The spec's `DcachePlugin.scala` line citations were written against an earlier revision: the REFILL AR emission it cites as `1194-1243` is at **1231-1291** at `ca7587f`, the store AW/W it cites as `1911-1916` is at **1909-1924**, the B demux it cites as `1957` is at **1957** (exact), and `DcachePlugin.scala` lives under `m68k040/cache/`, not `m68k040/lsu/`. Every task below quotes the **current** line numbers; an implementer who finds them moved again must re-locate by the quoted code, never by the number.

---

## Task 1: `SocketByteOrder` — the boundary permutation (D1, D2)

**Files:**
- Create: `src/main/scala/m68k040/socket/SocketByteOrder.scala`
- Test: `src/test/scala/m68k040/socket/SocketByteOrderSpec.scala`

**Interfaces:**
- Consumes: nothing (first task).
- Produces, relied on verbatim by Tasks 9, 13 and 14:
  - `object m68k040.socket.SocketByteOrder` with
    - `def permuteData(d: Bits): Bits` — per-32-bit-lane byte reversal, width-parametric, requires `d.getWidth % 32 == 0`.
    - `def permuteStrb(s: Bits): Bits` — per-4-bit-nibble reversal, requires `s.getWidth % 4 == 0`.
    - `def modelData(bytes: Seq[Int]): Seq[Int]` — the same permutation as a pure Scala function over a byte sequence, for golden-model tests and for any future host tool.
    - `def modelStrb(bits: Seq[Boolean]): Seq[Boolean]` — likewise for the strobe.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/SocketByteOrderSpec.scala`:

```scala
package m68k040.socket

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 2.2 (D2) and section 13 "Byte order", first and last bullets.
  *
  * The transform is: reverse the four bytes inside each 32-bit lane, reverse each
  * 4-bit strobe nibble, LEAVE LANE ORDER ALONE. It is NOT a full 128-bit byte
  * reverse and NOT a word-order reversal. Spec section 2.3: "Applying it twice
  * would be a no-op that looks like a fix" -- so involution is tested explicitly,
  * and so is the property that distinguishes it from a full reverse (a value that
  * is symmetric under one but not the other).
  *
  * Deliberately UNTAGGED so `make test-fast` runs it: build.sbt's `fastTest`
  * excludes m68k040.VerilatorTest / SlowTest / BoardTest. */
class SocketByteOrderSpec extends AnyFunSuite {

  /** Tiny DUT: hardware permutation of one beat, both widths the socket uses. */
  class ByteOrderDut(w: Int) extends Component {
    val din  = in  Bits (w bits)
    val sin  = in  Bits (w / 8 bits)
    val dout = out Bits (w bits)
    val sout = out Bits (w / 8 bits)
    dout := SocketByteOrder.permuteData(din)
    sout := SocketByteOrder.permuteStrb(sin)
  }

  /** Byte i of a BigInt beat, little-endian byte indexing (byte i = bits [8i+7:8i]),
    * which is the core's own convention (DcacheTypes.scala:169-171). */
  private def byteOf(v: BigInt, i: Int): Int = ((v >> (8 * i)) & 0xff).toInt
  private def fromBytes(bs: Seq[Int]): BigInt =
    bs.zipWithIndex.foldLeft(BigInt(0)) { case (a, (b, i)) => a | (BigInt(b & 0xff) << (8 * i)) }

  test("the Scala model is a per-32-bit-lane byte reversal, not a full reverse") {
    // 8 bytes = 2 lanes. Lane order must be preserved.
    val in  = Seq(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x13)
    val out = SocketByteOrder.modelData(in)
    assert(out == Seq(0x03, 0x02, 0x01, 0x00, 0x13, 0x12, 0x11, 0x10),
      s"got $out")
    // A FULL reverse would give 0x13,0x12,...,0x00 -- lane order swapped. Assert
    // explicitly that we are NOT that, because the two agree on many inputs.
    assert(out != in.reverse, "permutation must not be a full byte reverse")
  }

  test("the Scala model is an involution at both socket widths") {
    for (nBytes <- Seq(16, 32)) {
      val in = (0 until nBytes).map(i => (i * 37 + 11) & 0xff)
      assert(SocketByteOrder.modelData(SocketByteOrder.modelData(in)) == in,
        s"not an involution at $nBytes bytes")
      val s = (0 until nBytes).map(i => ((i * 5) & 3) == 0)
      assert(SocketByteOrder.modelStrb(SocketByteOrder.modelStrb(s)) == s,
        s"strobe permutation not an involution at $nBytes bits")
    }
  }

  test("the strobe permutation reverses each nibble and preserves popcount") {
    val s = Seq(true, false, false, false, false, true, false, false)
    assert(SocketByteOrder.modelStrb(s) ==
      Seq(false, false, false, true, false, false, true, false), "nibble reversal wrong")
    val r = SocketByteOrder.modelStrb(s)
    assert(r.count(identity) == s.count(identity), "popcount not preserved")
  }

  test("hardware matches the Scala model exhaustively over single-byte positions, 128b") {
    SimConfig.compile(new ByteOrderDut(128)).doSim("perm128", seed = 1) { dut =>
      for (pos <- 0 until 16) {
        val bytes = (0 until 16).map(i => if (i == pos) 0xA5 else 0x00)
        dut.din #= fromBytes(bytes)
        dut.sin #= BigInt(1) << pos
        sleep(1)
        val gotD = (0 until 16).map(i => byteOf(dut.dout.toBigInt, i))
        assert(gotD == SocketByteOrder.modelData(bytes),
          s"data mismatch at byte $pos: hw=$gotD model=${SocketByteOrder.modelData(bytes)}")
        val sIn  = (0 until 16).map(_ == pos)
        val gotS = (0 until 16).map(i => ((dut.sout.toBigInt >> i) & 1) == 1)
        assert(gotS == SocketByteOrder.modelStrb(sIn),
          s"strobe mismatch at bit $pos: hw=$gotS")
      }
    }
  }

  test("hardware matches the Scala model on random beats, 256b (axi_i width)") {
    SimConfig.compile(new ByteOrderDut(256)).doSim("perm256", seed = 2) { dut =>
      val rnd = new scala.util.Random(7)
      for (_ <- 0 until 64) {
        val bytes = (0 until 32).map(_ => rnd.nextInt(256))
        dut.din #= fromBytes(bytes)
        dut.sin #= BigInt(0)
        sleep(1)
        val got = (0 until 32).map(i => byteOf(dut.dout.toBigInt, i))
        assert(got == SocketByteOrder.modelData(bytes), s"256b mismatch: $got")
      }
    }
  }

  test("hardware permutation is an involution (apply twice == identity)") {
    SimConfig.compile(new ByteOrderDut(128)).doSim("involution", seed = 3) { dut =>
      val rnd = new scala.util.Random(11)
      for (_ <- 0 until 32) {
        val bytes = (0 until 16).map(_ => rnd.nextInt(256))
        dut.din #= fromBytes(bytes)
        sleep(1)
        val once = (0 until 16).map(i => byteOf(dut.dout.toBigInt, i))
        dut.din #= fromBytes(once)
        sleep(1)
        val twice = (0 until 16).map(i => byteOf(dut.dout.toBigInt, i))
        assert(twice == bytes, s"not an involution in hardware: $twice vs $bytes")
      }
    }
  }

  test("a socket-convention word read back at its own address is the byte we wrote") {
    // Spec section 13: "A byte written at address A through axi_d is the byte a
    // socket-convention model reads at address A." Address-to-lane relationship,
    // stated arithmetically: core byte offset o = 4W + j must land at socket bit
    // 32W + 24 - 8j, i.e. socket byte index 4W + (3 - j).
    for (o <- 0 until 16) {
      val bytes = (0 until 16).map(i => if (i == o) 0x5A else 0x00)
      val out   = SocketByteOrder.modelData(bytes)
      val w = o / 4; val j = o % 4
      assert(out(4 * w + (3 - j)) == 0x5A,
        s"core byte offset $o did not land at socket index ${4 * w + (3 - j)}")
      assert(out.count(_ == 0x5A) == 1, "the byte was duplicated")
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.SocketByteOrderSpec" 2>&1 | tail -20
```

Expected: a compilation error — `not found: value SocketByteOrder`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/scala/m68k040/socket/SocketByteOrder.scala`:

```scala
package m68k040.socket

import spinal.core._

/** The `axi_i` / `axi_d` socket boundary byte-order permutation (design spec
  * `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md`, D1/D2/D3, section 2).
  *
  * ==The two conventions==
  * THIS CORE is byte-address-invariant: for a beat carrying the bytes at physical
  * addresses `base+0 .. base+N-1`, the byte at offset `o` is `data[8*o +: 8]`
  * (`DcacheTypes.scala:169-171`, `IcacheTypes.scala:85`, `TableWalker.scala:83-85`).
  *
  * THE SoC presents 32-bit words in ascending address order, big-endian WITHIN each
  * 32-bit word: the byte at offset `4W + j` occupies `data[32W + 24 - 8j +: 8]`
  * (`if_to_axi.v:113-145`, `m68k_mem_lane.vh:14-30,60-80`, and `axi_xbar.v:133-141`,
  * which states the transform in exactly these terms for its own S3 shim: "byte-swaps
  * each 32-bit word and reverses each 4-bit WSTRB nibble").
  *
  * ==The transform==
  * {{{
  *   socketWord(W)          = byteReverse32( coreWord(W) )   for every W
  *   socketStrb[4W + (3-j)] = coreStrb[4W + j]               for every W, j
  * }}}
  * Reverse the four bytes inside each 32-bit lane; reverse each 4-bit strobe nibble;
  * LEAVE LANE ORDER ALONE. It is NOT a full 128-bit byte reverse and NOT a word-order
  * reversal.
  *
  * ==Three properties the callers rely on==
  *  1. '''It is an involution.''' One function serves core->socket and socket->core;
  *     there is no forward/inverse pair to keep in sync.
  *  2. '''It is width-parametric.''' 4 lanes at 128 bit (`axi_d`), 8 at 256 (`axi_i`).
  *  3. '''It has zero logic depth.''' It is a renaming of wires: it cannot appear on a
  *     timing path and cannot move the post-route FMax result.
  *
  * ==Two ways to misuse it, both stated at the call site as well (D3)==
  *  - '''Applying it to an address is a bug.''' Only `w.data`, `w.strb` and `r.data`
  *    are ever permuted. Never addr/id/len/size/burst/resp/last.
  *  - '''Applying it twice is a no-op that looks like a fix.''' Because it is an
  *    involution, a double application silently restores the WRONG convention. It is
  *    applied EXACTLY ONCE PER MASTER, in `M68kSocketTop` and nowhere else;
  *    `tools/socket/check_socket_netlist.py` is the machine-checked form of that.
  *
  * ==Ordering hazard with the D5 store derivation==
  * The MMIO store sizing of spec section 3.3.1 derives its byte range from the CORE-SIDE
  * (pre-permutation) strobe, because nibble reversal preserves popcount and contiguity
  * but NOT the offset a run starts at. That derivation lives inside `DcachePlugin` and
  * this permutation is applied strictly afterwards, at the socket boundary. The two
  * transforms are order-dependent. See `MmioCover`'s doc comment for the other half. */
object SocketByteOrder {

  /** Per-32-bit-lane byte reversal. Width must be a whole number of 32-bit lanes. */
  def permuteData(d: Bits): Bits = {
    val w = d.getWidth
    require(w % 32 == 0, s"SocketByteOrder.permuteData needs a multiple of 32 bits (got $w)")
    val lanes = for (l <- 0 until w / 32) yield {
      val word = d(32 * l + 31 downto 32 * l)
      // byteReverse32: b0##b1##b2##b3 with b0 the LOW byte, so the low byte becomes
      // the high byte of the emitted lane -- a per-lane endianness flip.
      word(7 downto 0) ## word(15 downto 8) ## word(23 downto 16) ## word(31 downto 24)
    }
    // Cat the lanes back in ASCENDING index order (lane order is untouched): SpinalHDL's
    // `Cat` puts its FIRST argument in the HIGH bits, so reverse the Scala sequence.
    val out = Cat(lanes.reverse)
    require(out.getWidth == w)
    out
  }

  /** Per-4-bit-nibble reversal of a byte strobe. Width must be a multiple of 4. */
  def permuteStrb(s: Bits): Bits = {
    val w = s.getWidth
    require(w % 4 == 0, s"SocketByteOrder.permuteStrb needs a multiple of 4 bits (got $w)")
    val nibbles = for (n <- 0 until w / 4) yield {
      val nib = s(4 * n + 3 downto 4 * n)
      nib(0) ## nib(1) ## nib(2) ## nib(3)
    }
    val out = Cat(nibbles.reverse)
    require(out.getWidth == w)
    out
  }

  /** The same permutation as a pure Scala function over a byte sequence indexed by
    * BYTE OFFSET (element 0 = the byte at the beat's base address). Used by the golden
    * model in `SocketByteOrderSpec` and by every socket-convention simulation model in
    * this plan; keeping one definition is what stops the model and the hardware from
    * drifting apart the way `if_to_axi.v:135-141` records happening on the v1 core. */
  def modelData(bytes: Seq[Int]): Seq[Int] = {
    require(bytes.length % 4 == 0, s"modelData needs whole 32-bit lanes (got ${bytes.length})")
    bytes.grouped(4).flatMap(_.reverse).toSeq
  }

  /** Strobe form of `modelData`: element i = "byte offset i is enabled". */
  def modelStrb(bits: Seq[Boolean]): Seq[Boolean] = {
    require(bits.length % 4 == 0, s"modelStrb needs whole nibbles (got ${bits.length})")
    bits.grouped(4).flatMap(_.reverse).toSeq
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.SocketByteOrderSpec" 2>&1 | tail -20
```

Expected: `Tests: succeeded 7, failed 0`.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/SocketByteOrder.scala \
        src/test/scala/m68k040/socket/SocketByteOrderSpec.scala
git commit -m "$(cat <<'EOF'
socket(D1/D2): boundary byte-order permutation + exhaustive self-check

Adds m68k040.socket.SocketByteOrder: a per-32-bit-lane byte reversal on
WDATA/RDATA and a per-4-bit-nibble reversal on WSTRB, with inter-word order
untouched -- the transform axi_xbar.v:133-141 already states in those exact
terms for its own S3 shim.

Ships the SAME permutation twice on purpose: as SpinalHDL (permuteData /
permuteStrb) and as pure Scala (modelData / modelStrb). The spec's own history
is why: if_to_axi.v:135-141 records what a missing/one-sided byte-order
transform looked like on the v1 core ("every opword the CPU saw was garbage").
A single definition means the golden model and the hardware cannot drift.

The suite pins the three properties the callers rely on -- involution, width
parametricity at 128 and 256 bit, and the address-to-lane relationship -- plus
the negative one that matters most: it is NOT a full byte reverse. Those two
agree on many inputs, so the difference is asserted explicitly rather than
assumed.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Port-surface freeze checker (D23) and the netlist structural harness

**Files:**
- Create: `tools/socket/check_socket_netlist.py`
- Create: `tools/socket/fullcore_ports.golden`
- Test: `python3 tools/socket/check_socket_netlist.py`

**Interfaces:**
- Consumes: `generated/M68kFullCoreSynth.v` (regenerated by `sbt "runMain m68k040.top.GenFullCoreSynthVerilog"`).
- Produces, re-run by **every** later RTL task and by Task 14:
  - `tools/socket/fullcore_ports.golden` — the frozen, sorted `name direction width` list of `M68kFullCoreSynth`'s top-level ports at this plan's parent commit.
  - `tools/socket/check_socket_netlist.py` with two modes: default (check `M68kFullCoreSynth` against the golden), and `--socket <path>` (additionally check `M68kSocketTop`'s surface — inert until Task 13 creates it).

**Why this task is second, not twelfth.** The debug-ctrl plan's Task 12 checker caught a real whole-core interface regression (a top-level reset-port rename) that simulation could not have caught. The equivalent highest-risk regression class *here* is the opposite shape: this plan wires an arbiter between three plugins and their former top-level masters, adds three plugins, and widens a seam in `FullCoreSynth.scala` — every one of which can silently **rename, remove or add** an `M68kFullCoreSynth` port, which spec §10 forbids outright and which no simulation observes. So the golden baseline has to be captured **before** the first RTL change, not after the last one.

- [ ] **Step 1: Write the failing test**

Create `tools/socket/check_socket_netlist.py`:

```python
#!/usr/bin/env python3
"""Structural checks on the generated Verilog for the AXI socket adapter.

Two properties that simulation cannot prove, and that this plan can plausibly
break:

  1. FREEZE.  `M68kFullCoreSynth`'s top-level port surface is EXACTLY what it was
     at this plan's parent commit -- no port added, removed, renamed, or resized.
     Design spec section 10: "Any change to M68kFullCoreSynth's port surface or
     behaviour ... It stays exactly as it is as the OOC-synth/FMax-gate target
     (section 9.3): no port added, none removed, none tied off."  Tasks 3-12 all
     edit files that M68kFullCoreSynth instantiates, and a plugin whose port
     declaration changes shape silently re-pins the whole netlist.

  2. SOCKET CONFORMANCE (--socket, inert until the socket top exists).  The
     elaborated `M68kSocketTop` exports ONLY socket ports (D23), carries NO
     prot/cache/lock/qos/region on either master (D29), presents axi_i at 256 bit
     and axi_d at 128 (D11), and has NO AW/W/B group on axi_i at all.

Standard library only -- this repository has no pytest.  Run:
    python3 tools/socket/check_socket_netlist.py [--regen] [--socket generated/M68kSocketTop.v]

Exit 0 on success, 1 on the first failing class (with a diagnostic on stderr).
"""

import argparse
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FULLCORE = os.path.join(REPO, "generated", "M68kFullCoreSynth.v")
GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fullcore_ports.golden")

PORT_RE = re.compile(
    r"^\s*(input|output)\s+(?:wire|reg)?\s*(?:\[\s*(\d+)\s*:\s*0\s*\])?\s*(\w+)\s*,?\s*$")

FAILURES = []


def check(label, cond, detail=""):
    if cond:
        print("PASS  " + label)
    else:
        FAILURES.append(label)
        print("FAIL  " + label + (("  -- " + detail) if detail else ""))


def parse_ports(path, module):
    """name -> (direction, width). Only `module`'s own port list is scanned; the
    scan stops at the first ');' that closes it."""
    out = {}
    started = False
    head = re.compile(r"^\s*module\s+" + re.escape(module) + r"\b")
    with open(path) as fh:
        for line in fh:
            if not started:
                if head.match(line):
                    started = True
                continue
            if re.match(r"^\s*\)\s*;", line):
                break
            m = PORT_RE.match(line)
            if m:
                direction, msb, name = m.group(1), m.group(2), m.group(3)
                out[name] = (direction, int(msb) + 1 if msb is not None else 1)
    return out


def fmt(ports):
    return "".join("%s %s %d\n" % (n, d, w) for n, (d, w) in sorted(ports.items()))


def check_fullcore(regen):
    if not os.path.exists(FULLCORE):
        sys.stderr.write("no netlist at %s -- run:\n"
                         "  sbt \"runMain m68k040.top.GenFullCoreSynthVerilog\"\n" % FULLCORE)
        return False
    ports = parse_ports(FULLCORE, "M68kFullCoreSynth")
    check("M68kFullCoreSynth's port list was parsed", len(ports) > 100,
          "only %d ports found" % len(ports))
    if regen:
        with open(GOLDEN, "w") as fh:
            fh.write("# tools/socket/fullcore_ports.golden -- FROZEN port surface of\n"
                     "# M68kFullCoreSynth, captured at the parent commit of the axi-socket\n"
                     "# adapter plan. Design spec section 10 forbids this plan from changing\n"
                     "# it: no port added, none removed, none renamed, none resized.\n"
                     "# Regenerate ONLY with an explicit, reviewed decision:\n"
                     "#   python3 tools/socket/check_socket_netlist.py --regen\n")
            fh.write(fmt(ports))
        print("REGENERATED %s with %d ports" % (GOLDEN, len(ports)))
        return True
    if not os.path.exists(GOLDEN):
        sys.stderr.write("no golden at %s -- capture it with --regen\n" % GOLDEN)
        return False
    want = {}
    with open(GOLDEN) as fh:
        for line in fh:
            if line.startswith("#") or not line.strip():
                continue
            n, d, w = line.split()
            want[n] = (d, int(w))
    missing = sorted(set(want) - set(ports))
    extra = sorted(set(ports) - set(want))
    changed = sorted(n for n in set(want) & set(ports) if want[n] != ports[n])
    check("no M68kFullCoreSynth port was removed or renamed away", not missing, repr(missing))
    check("no M68kFullCoreSynth port was added", not extra, repr(extra))
    check("no M68kFullCoreSynth port changed direction or width", not changed,
          repr([(n, want[n], ports[n]) for n in changed]))
    # The 37 probe/test anchors named in spec section 9.3 must still be there: they are
    # what keeps synthesis from pruning the retire path on the FMax gate target.
    probes = [n for n in ports if n.startswith("BackendWiringPlugin_logic_traceOut_")
              or n.startswith("BackendWiringPlugin_logic_fireOut_")
              or n in ("BackendWiringPlugin_logic_eu0Res", "BackendWiringPlugin_logic_eu1Res",
                       "FetchAlignPlugin_logic_slot1ValidOut",
                       "IcachePlugin_logic_invalidateAll",
                       "RobPlugin_logic_flush_valid",
                       "FetchAlignPlugin_logic_redirect_valid",
                       "FetchAlignPlugin_logic_redirect_payload",
                       "FetchAlignPlugin_logic_resume_valid",
                       "FetchAlignPlugin_logic_resume_payload")]
    check("the 37 probe/test anchors of spec section 9.3 are all present",
          len(probes) == 37, "found %d" % len(probes))
    return not FAILURES


def check_socket(path):
    if not os.path.exists(path):
        print("SKIP  no socket netlist at %s (expected until Task 13)" % path)
        return True
    ports = parse_ports(path, "M68kSocketTop")
    check("M68kSocketTop's port list was parsed", len(ports) > 20,
          "only %d ports found" % len(ports))

    # D29: the sidebands the socket does not declare must NOT EXIST at the boundary.
    sidebands = sorted(n for n in ports
                       if re.search(r"(prot|cache|lock|qos|region)$", n))
    check("D29: no prot/cache/lock/qos/region port on the socket boundary",
          not sidebands, repr(sidebands))

    # cpu_socket.vh:98-113 -- axi_i is AR/R ONLY.
    i_write = sorted(n for n in ports if re.match(r"^axi_i_(aw|w|b)", n))
    check("axi_i carries no AW/W/B group", not i_write, repr(i_write))

    want_i = {
        "axi_i_arid": ("output", 4), "axi_i_araddr": ("output", 32),
        "axi_i_arlen": ("output", 8), "axi_i_arsize": ("output", 3),
        "axi_i_arburst": ("output", 2), "axi_i_arvalid": ("output", 1),
        "axi_i_arready": ("input", 1), "axi_i_rid": ("input", 4),
        "axi_i_rdata": ("input", 256),  # D11 -- native 256, SOC-1 widens the SoC side
        "axi_i_rresp": ("input", 2), "axi_i_rlast": ("input", 1),
        "axi_i_rvalid": ("input", 1), "axi_i_rready": ("output", 1),
    }
    want_d = {
        "axi_d_awid": ("output", 4), "axi_d_awaddr": ("output", 32),
        "axi_d_awlen": ("output", 8), "axi_d_awsize": ("output", 3),
        "axi_d_awburst": ("output", 2), "axi_d_awvalid": ("output", 1),
        "axi_d_awready": ("input", 1), "axi_d_wdata": ("output", 128),
        "axi_d_wstrb": ("output", 16), "axi_d_wlast": ("output", 1),
        "axi_d_wvalid": ("output", 1), "axi_d_wready": ("input", 1),
        "axi_d_bid": ("input", 4), "axi_d_bresp": ("input", 2),
        "axi_d_bvalid": ("input", 1), "axi_d_bready": ("output", 1),
        "axi_d_arid": ("output", 4), "axi_d_araddr": ("output", 32),
        "axi_d_arlen": ("output", 8), "axi_d_arsize": ("output", 3),
        "axi_d_arburst": ("output", 2), "axi_d_arvalid": ("output", 1),
        "axi_d_arready": ("input", 1), "axi_d_rid": ("input", 4),
        "axi_d_rdata": ("input", 128), "axi_d_rresp": ("input", 2),
        "axi_d_rlast": ("input", 1), "axi_d_rvalid": ("input", 1),
        "axi_d_rready": ("output", 1),
    }
    for name, want in list(want_i.items()) + list(want_d.items()):
        got = ports.get(name)
        check("socket port %s is %s[%d]" % (name, want[0], want[1]), got == want,
              "got %r" % (got,))

    # Clock/reset naming (D21): `clk` and `rst`, not SpinalHDL's default `reset`.
    check("D21: the socket top's reset port is named rst", "rst" in ports,
          "ports named like reset: %r" % sorted(n for n in ports if "rst" in n or "reset" in n))
    check("D21: there is no port named `reset` on the socket top", "reset" not in ports)

    # D23: every port is a member of a cpu_socket.vh group.
    allowed = re.compile(
        r"^(clk|rst|axi_i_\w+|axi_d_\w+|dbg_axi_\w+|cpu_ipl|ipl_ack|"
        r"cpu_cold_reset_pulse|cpu_cold_reset_hold|cpu_ram_window_lg2|cpu_mon_sense|"
        r"init_done_seen|cpu_peripheral_reset)$")
    strays = sorted(n for n in ports if not allowed.match(n))
    check("D23: the socket top exports ONLY cpu_socket.vh ports", not strays, repr(strays))
    return not FAILURES


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--regen", action="store_true",
                    help="rewrite the golden port list from the current netlist")
    ap.add_argument("--socket", default=os.path.join(REPO, "generated", "M68kSocketTop.v"))
    args = ap.parse_args()
    check_fullcore(args.regen)
    if not args.regen:
        check_socket(args.socket)
    if FAILURES:
        sys.stderr.write("\n%d check(s) FAILED\n" % len(FAILURES))
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
python3 tools/socket/check_socket_netlist.py; echo "exit=$?"
```

Expected: `no golden at .../fullcore_ports.golden -- capture it with --regen`, `exit=1`.

- [ ] **Step 3: Capture the golden baseline from a freshly generated netlist**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
free -g | head -2
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py --regen
head -8 tools/socket/fullcore_ports.golden
grep -c '^[A-Za-z]' tools/socket/fullcore_ports.golden
```

Expected: `REGENERATED ... with 199 ports` and a count of `199` from the grep. **If the
count is not 199, do not "fix" the checker — stop and report.** 199 is the port count at
`ca7587f`: 135 AXI-master ports, 22 `dbg_axi_*`/SoC-fabric-control ports landed by the
debug-ctrl Stage-1 plan, 37 probe/test anchors, and 5 real ports (`clk`, `reset`,
`iplInPort`, `iackAvecIn`, `iackVectorIn`). Note this **supersedes spec §9.3's "177 total /
42 non-AXI / 37 probe" enumeration**, which was written before `DebugCtrlPlugin` landed;
the 37-probe figure it gives is still exactly right and is asserted above.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
python3 tools/socket/check_socket_netlist.py
```

Expected: `PASS` on all five `M68kFullCoreSynth` checks, `SKIP  no socket netlist at ...
(expected until Task 13)`, and `All checks passed.`

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add tools/socket/check_socket_netlist.py tools/socket/fullcore_ports.golden
git commit -m "$(cat <<'EOF'
socket(D23): freeze M68kFullCoreSynth's port surface before touching anything

Design spec section 10 forbids this plan from changing M68kFullCoreSynth's port
surface: it stays exactly as it is as the OOC-synth/FMax-gate target. Every
remaining task in this plan edits a file that M68kFullCoreSynth instantiates --
three plugins wired behind a new arbiter, a widened halt seam, fail-closed ID
guards on both walkers -- and a plugin whose port declaration changes shape
silently re-pins the whole netlist. Simulation never sees it.

So the golden baseline is captured FIRST, not last: 199 ports at the parent
commit (135 AXI-master, 22 dbg_axi/SoC-fabric-control from the debug-ctrl Stage
1 plan, 37 probe/test anchors, 5 real). This supersedes spec section 9.3's
"177 total" enumeration, which predates DebugCtrlPlugin; its 37-probe figure is
still exact and is asserted here rather than quoted.

The same script grows a --socket mode that is inert (SKIP) until Task 13 exists,
covering D29 (no prot/cache/lock/qos/region at the boundary), D11 (axi_i at 256,
axi_d at 128), D21 (the port is `rst`, not `reset`) and D23 (only cpu_socket.vh
ports).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: The kind-coded halt-reason channel (D28)

**Files:**
- Create: `src/main/scala/m68k040/socket/HaltReason.scala`
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (the halt seam at `:366-376`)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (the drive at `:364`)
- Test: `src/test/scala/m68k040/socket/HaltReasonSpec.scala`

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces, relied on verbatim by Tasks 5 and 9:
  - `object m68k040.socket.HaltReason` with `val W = 3` and `val NONE = 0`, `DCACHE_DIAG = 1`, `FS_XLATE = 2`, `RESET_VECTOR = 3`, `ARBITER_WEDGE = 4`, plus `def name(code: Int): String`.
  - `RobPlugin.logic.haltReasonIn : UInt(HaltReason.W bits)` — a directionless, `allowOverride`, default-`0` wire, exactly mirroring the existing `coreHaltedIn` idiom at `RobPlugin.scala:373`. `simPublic`.
  - `RobPlugin.logic.haltReason : UInt(HaltReason.W bits)` — the sticky, **first-wins** register. `simPublic`.
- **Does NOT produce** a CSR or a `dbg_axi` register. Spec §6.4: *"Wherever this reason becomes CSR-observable it is a `dbg_axi` register, and §10 already establishes that the debug-ctrl spec owns that port and register map. This spec does **not** design the debug-ctrl side."* The VIO spec's `V21` likewise reserves `halt_reason[3:0]` reading zero and states it *consumes* this channel. Simulation observability via `simPublic` is sufficient for every §13 obligation.

**Why this is Task 3, i.e. before the arbiter and before boot.** Spec §11.1 item 2: *"§6.4 halt-reason channel (D28) — small, and both 3 and 5 below report through it, so it comes first rather than being retrofitted twice. This is the one item of *new scope* the design review added; it is not a reuse of anything existing."*

**The seam as it exists today** (`RobPlugin.scala:373-376`, verbatim):

```scala
    val coreHaltedIn = Bool(); coreHaltedIn.allowOverride; coreHaltedIn := False
    coreHaltedIn.simPublic()   // pokable from a standalone DUT, same convention as preciseDrainBusyIn
    val coreHalted = RegInit(False); coreHalted.simPublic()
    when(coreHaltedIn) { coreHalted := True }
```

and its only driver (`FullCoreSynth.scala:364`, verbatim):

```scala
    rob.logic.coreHaltedIn := dc.diagFault || exc.fsXlateFault
```

`coreHalted`'s three consumers are `headReady` (`RobPlugin.scala:585`), `interruptPending`
(`:1447`) and the frontend-quiesce service (`:1487-1488`). **None of them may change.** This
task adds an *observation* alongside the latch, not a control path — which is what makes
"`coreHalted`'s existing control behaviour is bit-identical" (spec §13) a checkable claim.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/HaltReasonSpec.scala`:

```scala
package m68k040.socket

import m68k040.M68kSim
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.rob.RobPlugin
import m68k040.M68kParams
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Halt-reason channel (section 6.4, D28)":
  *   - "Each of the four producers ... latches its own distinct reason."
  *   - "First-wins: a second halt cause after the first does not overwrite the
  *      recorded reason."
  *   - "coreHalted's existing control behaviour is bit-identical to before the
  *      widening (regression, not a new property)."
  *
  * The producers themselves land in Tasks 5 and 9; what this suite pins is the
  * CHANNEL -- distinctness, stickiness, first-wins, and the untouched latch. */
class HaltReasonSpec extends AnyFunSuite {

  /** Minimal ROB-only host: enough for `coreHaltedIn`/`haltReasonIn` to be pokable,
    * nothing else. Same shape as the ROB's own directed specs. */
  class HaltDut extends Component {
    val core = new M68kCore(Seq[FiberPlugin](new ParamPlugin(M68kParams()), new RobPlugin()))
  }

  private def rob(dut: HaltDut): RobPlugin =
    dut.core.plugins.collectFirst { case r: RobPlugin => r }.get

  test("reason codes are distinct, dense from zero, and fit the declared width") {
    val codes = Seq(HaltReason.NONE, HaltReason.DCACHE_DIAG, HaltReason.FS_XLATE,
                    HaltReason.RESET_VECTOR, HaltReason.ARBITER_WEDGE)
    assert(codes.distinct == codes, s"duplicate reason code in $codes")
    assert(codes == codes.indices.toList, s"codes must be dense from 0, got $codes")
    assert(codes.forall(c => c >= 0 && c < (1 << HaltReason.W)),
      s"a code does not fit ${HaltReason.W} bits")
    assert(codes.map(HaltReason.name).distinct.length == codes.length,
      "two codes share a name")
  }

  test("an idle core reports reason NONE and is not halted") {
    M68kSim().compile(new HaltDut).doSim("idle", seed = 1) { dut =>
      val r = rob(dut)
      dut.clockDomain.forkStimulus(10)
      r.logic.coreHaltedIn #= false
      r.logic.haltReasonIn #= HaltReason.NONE
      dut.clockDomain.waitSampling(8)
      assert(!r.logic.coreHalted.toBoolean, "halted with no producer")
      assert(r.logic.haltReason.toInt == HaltReason.NONE, "reason set with no producer")
    }
  }

  test("each producer latches its own distinct reason, and it is sticky") {
    for (code <- Seq(HaltReason.DCACHE_DIAG, HaltReason.FS_XLATE,
                     HaltReason.RESET_VECTOR, HaltReason.ARBITER_WEDGE)) {
      M68kSim().compile(new HaltDut).doSim(s"latch-$code", seed = 2) { dut =>
        val r = rob(dut)
        dut.clockDomain.forkStimulus(10)
        r.logic.coreHaltedIn #= false
        r.logic.haltReasonIn #= HaltReason.NONE
        dut.clockDomain.waitSampling(4)
        r.logic.coreHaltedIn #= true
        r.logic.haltReasonIn #= code
        dut.clockDomain.waitSampling()
        r.logic.coreHaltedIn #= false
        r.logic.haltReasonIn #= HaltReason.NONE
        dut.clockDomain.waitSampling(2)
        assert(r.logic.coreHalted.toBoolean, s"coreHalted not latched for reason $code")
        assert(r.logic.haltReason.toInt == code,
          s"reason ${r.logic.haltReason.toInt} latched, expected $code")
        // Sticky: still there many cycles later, with the producer long gone.
        dut.clockDomain.waitSampling(20)
        assert(r.logic.coreHalted.toBoolean, "coreHalted lost its stickiness")
        assert(r.logic.haltReason.toInt == code, "reason lost its stickiness")
      }
    }
  }

  test("first-wins: a second cause does not overwrite the recorded reason") {
    M68kSim().compile(new HaltDut).doSim("first-wins", seed = 3) { dut =>
      val r = rob(dut)
      dut.clockDomain.forkStimulus(10)
      r.logic.coreHaltedIn #= false
      r.logic.haltReasonIn #= HaltReason.NONE
      dut.clockDomain.waitSampling(4)
      r.logic.coreHaltedIn #= true
      r.logic.haltReasonIn #= HaltReason.RESET_VECTOR
      dut.clockDomain.waitSampling()
      // A DIFFERENT producer fires later. The FIRST cause is what an operator needs.
      r.logic.haltReasonIn #= HaltReason.ARBITER_WEDGE
      dut.clockDomain.waitSampling(6)
      r.logic.coreHaltedIn #= false
      dut.clockDomain.waitSampling(2)
      assert(r.logic.haltReason.toInt == HaltReason.RESET_VECTOR,
        s"first-wins violated: got ${r.logic.haltReason.toInt}")
    }
  }

  test("a halt whose producer supplies NONE still halts, and records NONE") {
    // Regression guard for the widening itself: coreHaltedIn's behaviour must not
    // become conditional on the reason being non-zero. A legacy driver that only
    // knows about the Bool must still halt the core.
    M68kSim().compile(new HaltDut).doSim("legacy-driver", seed = 4) { dut =>
      val r = rob(dut)
      dut.clockDomain.forkStimulus(10)
      r.logic.coreHaltedIn #= false
      r.logic.haltReasonIn #= HaltReason.NONE
      dut.clockDomain.waitSampling(4)
      r.logic.coreHaltedIn #= true
      dut.clockDomain.waitSampling(2)
      r.logic.coreHaltedIn #= false
      dut.clockDomain.waitSampling(2)
      assert(r.logic.coreHalted.toBoolean,
        "widening the seam made the halt itself conditional on a non-zero reason")
      assert(r.logic.haltReason.toInt == HaltReason.NONE)
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.HaltReasonSpec" 2>&1 | tail -20
```

Expected: a compilation error — `not found: value HaltReason` and `value haltReasonIn is not a member of ...`.

- [ ] **Step 3a: Write the reason-code object**

Create `src/main/scala/m68k040/socket/HaltReason.scala`:

```scala
package m68k040.socket

/** Reason codes for the core's sticky halt (design spec D28, section 6.4).
  *
  * ==Why this exists at all==
  * `RobPlugin.scala:373-376` declares the halt seam as a plain, undiscriminated
  * `Bool`, and `FullCoreSynth.scala:356-363` says why that was right at the time:
  * *"nothing downstream distinguishes WHICH producer fired, so a plain OR is exactly
  * right and a priority/first-wins encoding would buy nothing."* That was true with two
  * producers which both meant "diagnostic crash". It stops being true here: D15 (a
  * non-OKAY reset-vector response) and D20 (a merge-arbiter bounded-grant expiry) are
  * exactly the two halts an operator most needs to tell apart, and today's seam cannot
  * express either.
  *
  * ==Scope boundary==
  * This object and `RobPlugin`'s sticky register ARE the channel. Making it readable
  * over `dbg_axi` is a debug-ctrl register and belongs to
  * `docs/superpowers/specs/2026-08-09-debug-ctrl-jtag-repl-design.md`; the VIO spec's
  * `V21` reserves `halt_reason[3:0]` in its bundle for the same reason. Until one of
  * those lands, the reason is observable in simulation (`simPublic`, matching
  * `coreHaltedIn`'s existing treatment) and that is sufficient for every section 13
  * obligation.
  *
  * ==Relationship to `DcachePlugin`'s private kinds==
  * `DcachePlugin`'s `diagFaultKind` (`DcachePlugin.scala:2030-2031`: 0 = WT-beat /
  * INHIBITED-drain, 1 = drain-miss write-allocate refill, 2 = eviction writeback,
  * 3 = CPUSH maintenance writeback) STAYS PRIVATE and stays 3 bits. It is a SUB-code
  * under `DCACHE_DIAG`, not a peer -- spec section 6.4 states this outright. Do not
  * flatten the two encodings together; they answer different questions ("which
  * subsystem halted the core" vs "which of the D-cache's four AXI write issuers saw a
  * non-OKAY response"). */
object HaltReason {
  /** Width of the reason field. Three bits leaves room for three more producers
    * without a re-pin; the VIO spec's reserved `halt_reason[3:0]` is wider still. */
  val W = 3

  /** No halt has been recorded. */
  val NONE = 0
  /** `DcachePlugin`'s async diagnostic-fault channel (`dc.diagFault`) -- a non-OKAY AXI
    * response on a trusted-cacheable-path transaction. Sub-coded by that plugin's own
    * private `diagFaultKind`. */
  val DCACHE_DIAG = 1
  /** A DTLB translation fault taken while the commit-side sequencer is transferring an
    * FSAVE/FRESTORE state frame (`exc.fsXlateFault`). */
  val FS_XLATE = 2
  /** D15: a non-OKAY response to the reset-vector fetch at physical 0. Hardware-faithful
    * -- a bus fault during reset exception processing is a double bus fault on a real
    * 68040 and the part halts. */
  val RESET_VECTOR = 3
  /** D20: the `axi_d` merge arbiter held a grant for its full bounded-grant window with
    * no progress on any channel of that direction. Something structural is wrong; the
    * arbiter never fabricates a response. */
  val ARBITER_WEDGE = 4

  def name(code: Int): String = code match {
    case NONE          => "NONE"
    case DCACHE_DIAG   => "DCACHE_DIAG"
    case FS_XLATE      => "FS_XLATE"
    case RESET_VECTOR  => "RESET_VECTOR"
    case ARBITER_WEDGE => "ARBITER_WEDGE"
    case other         => s"UNKNOWN($other)"
  }
}
```

- [ ] **Step 3b: Widen the seam in `RobPlugin`**

In `src/main/scala/m68k040/rob/RobPlugin.scala`, replace the block at `:373-376` (the exact
current text is quoted in this task's preamble) with:

```scala
    val coreHaltedIn = Bool(); coreHaltedIn.allowOverride; coreHaltedIn := False
    coreHaltedIn.simPublic()   // pokable from a standalone DUT, same convention as preciseDrainBusyIn
    // D28 (axi-socket adapter spec section 6.4): the halt seam carries a KIND alongside the
    // Bool. "Halts" is only half a diagnostic; an operator staring at a wedged core has to
    // know why. Deliberately an OBSERVATION, not a control path -- `coreHalted`'s three
    // consumers (`headReady` below, `interruptPending`, and the frontend-quiesce service)
    // are untouched, so this cannot perturb the halt semantics any existing test depends on.
    //
    // FIRST-WINS, not last: the first cause is the one that explains the machine. A later
    // producer firing against an already-halted core is a CONSEQUENCE, not a second bug, and
    // overwriting would hide the real one.
    val haltReasonIn = UInt(m68k040.socket.HaltReason.W bits)
    haltReasonIn.allowOverride
    haltReasonIn := U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits)
    haltReasonIn.simPublic()
    val coreHalted = RegInit(False); coreHalted.simPublic()
    val haltReason = RegInit(U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits))
    haltReason.simPublic()
    when(coreHaltedIn) {
      coreHalted := True
      // `!coreHalted` is the first-wins guard. It reads the REGISTER, not `coreHaltedIn`,
      // so two producers asserting on the SAME cycle resolve by whatever the shared
      // `haltReasonIn` wire carries that cycle -- which is the priority the driver in
      // FullCoreSynth encodes, not an accident of elaboration order.
      when(!coreHalted) { haltReason := haltReasonIn }
    }
```

- [ ] **Step 3c: Drive it from the two existing producers**

In `src/main/scala/m68k040/top/FullCoreSynth.scala`, replace the single line at `:364`
(`rob.logic.coreHaltedIn := dc.diagFault || exc.fsXlateFault`) with:

```scala
    rob.logic.coreHaltedIn := dc.diagFault || exc.fsXlateFault
    // D28: same two producers, now each carrying its own kind. The priority when both
    // fire on the same cycle is stated here rather than left to elaboration order:
    // the D-cache's diagnostic fault wins, because it is the one with a sub-code
    // (`DcachePlugin`'s private `diagFaultKind`) that further localises the failure.
    rob.logic.haltReasonIn := Mux(dc.diagFault,
      U(m68k040.socket.HaltReason.DCACHE_DIAG, m68k040.socket.HaltReason.W bits),
      Mux(exc.fsXlateFault,
        U(m68k040.socket.HaltReason.FS_XLATE, m68k040.socket.HaltReason.W bits),
        U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits)))
```

- [ ] **Step 4: Run test to verify it passes, and prove the control path is unchanged**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.socket.HaltReasonSpec" 2>&1 | tail -20
grep -n 'coreHalted' src/main/scala/m68k040/rob/RobPlugin.scala
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 5, failed 0` for the new suite; the `grep` shows `coreHalted`
still read at exactly three consumer sites (`headReady` ~`:585`, `interruptPending` ~`:1447`,
`_frontendQuiesceActive`/`_frontendQuiesceNext` ~`:1487-1488`) **plus** the new first-wins
guard inside the `when(coreHaltedIn)` block, and at no fourth place; `check_socket_netlist.py`
reports `All checks passed.` (this is the D23 freeze — the widening adds no port); and
`test-fast` reports the pre-existing count **plus 5** (225 → 230 relative to the last recorded
run at `5b3cc74`; re-read the actual pre-existing number rather than trusting 225). Any
pre-existing failure must be reproduced on the parent commit before being accepted as
unrelated.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/HaltReason.scala \
        src/main/scala/m68k040/rob/RobPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/socket/HaltReasonSpec.scala
git commit -m "$(cat <<'EOF'
socket(D28): kind-coded halt-reason channel from the ROB halt seam outward

The halt seam was a plain undiscriminated Bool (RobPlugin.scala:373-376), and
FullCoreSynth.scala:356-363 documented why that was right: with two producers
that both meant "diagnostic crash", nothing downstream distinguished them. That
stops being true here. D15 (a non-OKAY reset-vector response) and D20 (a merge
arbiter bounded-grant expiry) are precisely the two halts an operator most needs
to tell apart, and today's seam cannot express either.

Adds a {valid, reason} pair with a sticky FIRST-WINS reason register: a later
producer firing against an already-halted core is a consequence, not a second
bug, and overwriting would hide the real one. The two existing producers each
carry their own kind, with the same-cycle priority stated explicitly at the
driver rather than left to elaboration order.

This is an OBSERVATION, not a control path: coreHalted's three consumers
(headReady, interruptPending, the frontend-quiesce service) are untouched, which
is what makes the spec's "bit-identical existing behaviour" a checkable claim
rather than an intention. DcachePlugin's private diagFaultKind stays private and
stays a SUB-code under DCACHE_DIAG, per spec section 6.4.

Deliberately NOT included: any dbg_axi register. Spec section 10 assigns that to
the debug-ctrl plan, and the VIO spec's V21 already reserves halt_reason[3:0]
for it. simPublic is sufficient for every section 13 obligation here.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Fail-closed walker ID guards (D27) and the `RESET_VEC` ID (§6.2)

**Files:**
- Modify: `src/main/scala/m68k040/cache/AxiIds.scala`
- Modify: `src/main/scala/m68k040/mmu/ItlbPlugin.scala` (`:75`)
- Modify: `src/main/scala/m68k040/mmu/DtlbPlugin.scala` (`:80`)
- Modify: `src/main/scala/m68k040/mmu/TableWalker.scala` (`:114`)
- Test: `src/test/scala/m68k040/socket/WalkerIdGuardSpec.scala`

**Interfaces:**
- Consumes: nothing from Tasks 1-3.
- Produces, relied on by Tasks 5 and 9: `AxiIds.RESET_VEC = 5`, and the property that all
  three walker response consumers reject a foreign ID rather than acking it.

**Why it is separate from the arbiter.** Spec §4.3 (D27) is explicit that the arbiter is
*"strictly additive"* for the D-cache but is *"the **only** [protection]"* for the walkers,
because they have no ID check at all today. That asymmetry is the finding; landing the guard
on its own commit, before the arbiter exists, makes the "before" behaviour directly testable
and keeps the arbiter's own commit about arbitration.

**The three sites as they exist today** (verbatim):

- `ItlbPlugin.scala:75` — `walkerAxi.b.ready  := True`, with the U drain ack taken straight off that handshake at `:271` (`umq.io.drainAck := walkerAxi.b.valid && walkerAxi.b.ready`).
- `DtlbPlugin.scala:80` — `walkerAxi.b.ready  := True`, same ack shape at `:340`.
- `TableWalker.scala:114` — `io.axi.r.ready := True`, inside `issueRead()`, asserted whenever the walk is waiting for a descriptor.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/WalkerIdGuardSpec.scala`:

```scala
package m68k040.socket

import m68k040.M68kSim
import m68k040.cache.AxiIds
import m68k040.mmu.TableWalker
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Merge arbiter", D27 bullet, verbatim: "a `B` or `R` beat carrying a
  * non-walker ID presented to a walker is *not* acked -- the walker hangs loudly rather
  * than mis-completing a page-table walk. Directed, since no legal stimulus produces it
  * once the arbiter is correct; this checks the second line of defence exists at all."
  *
  * The property gained is the D-cache's own, verbatim (DcachePlugin.scala:1948-1956):
  * "an unrecognized id simply not ack anything -- a hung drain, which is loud and
  * debuggable, instead of a silent spurious ack." */
class WalkerIdGuardSpec extends AnyFunSuite {

  /** Thin wrapper: `TableWalker` is a plain Component with a `master(Axi4ReadOnly)`, so
    * it can be driven directly with no plugin host at all. */
  class WalkerDut extends Component {
    val w = new TableWalker()
    val io = new Bundle {
      val start = in Bool ()
      val req   = in(m68k040.mmu.WalkReq())
      val busy  = out Bool ()
      val done  = out Bool ()
    }
    w.io.start := io.start
    w.io.req   := io.req
    io.busy := w.io.busy
    io.done := w.io.done
    // AR is accepted immediately; R is driven by the test.
    w.io.axi.ar.ready := True
    w.io.axi.r.valid  := False
    w.io.axi.r.payload.assignDontCare()
    w.io.axi.r.valid.allowOverride
    w.io.axi.r.payload.id.allowOverride
    w.io.axi.r.payload.data.allowOverride
    w.io.axi.r.payload.resp.allowOverride
    w.io.axi.r.payload.last.allowOverride
    w.io.axi.r.valid.simPublic()
    w.io.axi.r.ready.simPublic()
    w.io.axi.r.payload.id.simPublic()
    w.io.axi.ar.valid.simPublic()
  }

  test("RESET_VEC is a distinct ID outside every live D-side ARID") {
    // Spec section 6.2: "it must not alias a live D-side ARID, so it goes outside the
    // D-refill reserved range 0-3 and the walkers' AR=2: 5."
    assert(AxiIds.RESET_VEC == 5, s"RESET_VEC must be 5, got ${AxiIds.RESET_VEC}")
    val liveArIds = (0 until 4).map(AxiIds.dRefill) :+ AxiIds.WALK_READ
    assert(!liveArIds.contains(AxiIds.RESET_VEC),
      s"RESET_VEC ${AxiIds.RESET_VEC} aliases a live ARID in $liveArIds")
    assert(AxiIds.RESET_VEC < (1 << AxiIds.ID_W), "RESET_VEC does not fit ID_W bits")
    // And nothing was renumbered (spec section 4.3 forbids it explicitly).
    assert(AxiIds.WALK_READ == 2 && AxiIds.WALK_WRITE == 3,
      "WALK_READ/WALK_WRITE were renumbered -- spec section 4.3 forbids this")
    assert(AxiIds.D_STORE == 1 && AxiIds.D_PUSH == 2 && AxiIds.D_EVICT == 4)
  }

  test("TableWalker does not accept an R beat carrying a foreign ID") {
    M68kSim().compile(new WalkerDut).doSim("foreign-r", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.req.vpn     #= 0x00100
      dut.io.req.rootPtr #= 0x00001000L
      dut.io.req.write   #= false
      dut.io.req.sup     #= true
      dut.clockDomain.waitSampling(4)
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false
      // Wait for the walker to present its descriptor AR.
      dut.clockDomain.waitSamplingWhere(dut.w.io.axi.ar.valid.toBoolean)
      dut.clockDomain.waitSampling()
      // Present a beat carrying the D-cache's refill ID. It is NOT ours.
      dut.w.io.axi.r.valid   #= true
      dut.w.io.axi.r.payload.id   #= AxiIds.dRefill(0)
      dut.w.io.axi.r.payload.data #= BigInt("0" * 32, 16)
      dut.w.io.axi.r.payload.resp #= 0
      dut.w.io.axi.r.payload.last #= true
      for (i <- 0 until 8) {
        dut.clockDomain.waitSampling()
        assert(!dut.w.io.axi.r.ready.toBoolean,
          s"walker acked a foreign R id at cycle $i -- fail-OPEN, not fail-closed")
        assert(!dut.io.done.toBoolean, s"walk completed off a foreign beat at cycle $i")
      }
      // Its OWN id is accepted, so the guard is not simply wedged shut.
      dut.w.io.axi.r.payload.id #= AxiIds.WALK_READ
      dut.clockDomain.waitSampling()
      assert(dut.w.io.axi.r.ready.toBoolean,
        "walker rejected its own WALK_READ id -- the guard is inverted or over-tight")
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.WalkerIdGuardSpec" 2>&1 | tail -25
```

Expected: the first test fails with `RESET_VEC must be 5` (an unresolved symbol, i.e. a
compile error), and once that is added, the second fails with `walker acked a foreign R id at
cycle 0 -- fail-OPEN, not fail-closed`. **Record both failures**; the second is the real
red-before-green evidence that today's walker has no ID check at all.

- [ ] **Step 3a: Add the `RESET_VEC` constant**

In `src/main/scala/m68k040/cache/AxiIds.scala`, append inside `object AxiIds`, after the
`WALK_WRITE` declaration at `:69`:

```scala

  // -- reset-vector reader (axi-socket adapter spec D12/D13, section 6.2) --------
  /** The boot vector-0 read, issued as a fourth READ owner on the `axi_d` merge
    * arbiter -- never as a third socket master, because `axi_xbar.v:1178-1192`'s
    * `apply_cpu_overlay` aliases low addresses into the ROM mirror only for reads whose
    * master index is `XBAR_M_CPU`/`XBAR_M_CPUI`.
    *
    * Its VALUE is architecturally irrelevant under D8 (routing is by the arbiter's
    * latched owner tag, never by ID), but it must not alias a live D-side ARID, so it
    * sits outside the D-refill reserved range 0-3 and outside the walkers' AR=2. */
  val RESET_VEC = 5
```

- [ ] **Step 3b: Guard the two TLB plugins' `B` channel**

In `src/main/scala/m68k040/mmu/ItlbPlugin.scala`, replace `:75`
(`walkerAxi.b.ready  := True`) with:

```scala
    // D27 (axi-socket adapter spec section 4.3): FAIL-CLOSED on the response ID, matching
    // the D-cache's own discipline verbatim (DcachePlugin.scala:1948-1956: "an
    // unrecognized id simply not ack anything -- a hung drain, which is loud and
    // debuggable, instead of a silent spurious ack").
    //
    // Today this walker is a physically separate master and the only responses reaching it
    // are its own, so the unconditional `True` was safe. After the D8 merge it is the
    // arbiter's owner latch, and NOTHING ELSE, that stands between this walker and a
    // response belonging to the D-cache or the reset-vector reader. This guard is the
    // second line of defence that makes the safety argument uniform across all three
    // merged masters instead of resting on the arbiter alone.
    //
    // It does NOT disambiguate ITLB from DTLB -- they share AR=2/AW=3 (AxiIds.scala:67,69)
    // and D8's owner latch is what separates them. It fail-closes the CLASS boundary
    // between walker traffic and everything else. The drain ack at :271 is already gated on
    // this handshake, so a rejected beat simply does not ack.
    walkerAxi.b.ready  := walkerAxi.b.payload.id === U(m68k040.cache.AxiIds.WALK_WRITE,
                                                       m68k040.cache.AxiIds.ID_W bits)
```

Apply the identical replacement to `src/main/scala/m68k040/mmu/DtlbPlugin.scala:80`.

- [ ] **Step 3c: Guard `TableWalker`'s `R` channel**

In `src/main/scala/m68k040/mmu/TableWalker.scala`, inside `def issueRead()`, replace `:114`
(`io.axi.r.ready := True`) with:

```scala
      // D27: fail-closed on the descriptor-read ID, for the same reason and with the same
      // property as the ItlbPlugin/DtlbPlugin `b.ready` guards. A foreign beat is not
      // consumed, so the walk hangs loudly instead of resolving a page-table descriptor
      // out of somebody else's data. Cost is one 4-bit compare and no state.
      io.axi.r.ready := io.axi.r.payload.id === U(m68k040.cache.AxiIds.WALK_READ,
                                                  m68k040.cache.AxiIds.ID_W bits)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.socket.WalkerIdGuardSpec" 2>&1 | tail -20
~/sbt/bin/sbt "testOnly m68k040.mmu.*" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 2, failed 0` for the new suite; **every pre-existing MMU spec
still passes** (this is the regression that matters — the walkers' own test memories must
already be presenting the right ID, and if one is not, that is a test-harness bug to fix here
rather than a reason to loosen the guard); `check_socket_netlist.py` reports `All checks
passed.`; `test-fast` count rises by 2.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/cache/AxiIds.scala \
        src/main/scala/m68k040/mmu/ItlbPlugin.scala \
        src/main/scala/m68k040/mmu/DtlbPlugin.scala \
        src/main/scala/m68k040/mmu/TableWalker.scala \
        src/test/scala/m68k040/socket/WalkerIdGuardSpec.scala
git commit -m "$(cat <<'EOF'
socket(D27): fail-closed ID guards on both walkers + the RESET_VEC ARID

Spec section 4.3's asymmetry, made concrete. The D-cache demultiplexes its own
three write issuers by ID and does so deliberately fail-closed, so for it the
coming merge arbiter is strictly additive. The walkers have NO ID check at all
today -- ItlbPlugin.scala:75 and DtlbPlugin.scala:80 both take every B beat
unconditionally, and TableWalker.scala:114 takes every R beat while waiting for
a descriptor. That is safe only because each walker is a physically separate
master. After the merge, the arbiter's owner latch is the ONLY thing between a
walker and somebody else's response.

Adds the missing guard at all three sites, gaining the D-cache's own stated
property verbatim: an unrecognised ID hangs loudly instead of silently acking.
Cost is one 4-bit compare per site and no state. It does NOT disambiguate ITLB
from DTLB -- they share AR=2/AW=3 and the owner latch is what separates them --
it fail-closes the class boundary between walker traffic and everything else.

Nothing is renumbered: spec section 4.3 forbids it outright, because renumbering
would be churn with no correctness content AND would make AxiIds.scala look as
though routing depended on the values, which under D8 it does not.

Also adds AxiIds.RESET_VEC = 5 for the D12 boot fetch: outside the D-refill
reserved range 0-3 and outside the walkers' AR=2, so it aliases no live ARID.

The red-before-green evidence is worth recording: before this commit the
directed test showed the walker accepting a beat carrying the D-cache's own
refill ID and completing a page-table walk off it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `AxiDMerge` — the owner-tag serializing arbiter and its bounded-grant watchdog (D7-D10, D19, D20)

**Files:**
- Create: `src/main/scala/m68k040/socket/AxiDMerge.scala` (the arbiter as a plain `Component`)
- Create: `src/main/scala/m68k040/socket/AxiDMergePlugin.scala` (the `FiberPlugin` that instantiates and wires it)
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` (`:41` class signature, `:95` `axi` declaration)
- Modify: `src/main/scala/m68k040/mmu/ItlbPlugin.scala` (`:70` `walkerAxi` declaration)
- Modify: `src/main/scala/m68k040/mmu/DtlbPlugin.scala` (`:74` `walkerAxi` declaration)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (fold the wedge halt into the D28 drive)
- Test: `src/test/scala/m68k040/socket/AxiDMergeSpec.scala`

**Interfaces:**
- Consumes: `m68k040.socket.HaltReason.{W, ARBITER_WEDGE}` (Task 3); `AxiIds.{RESET_VEC, ID_W, WALK_READ, WALK_WRITE, D_STORE, D_PUSH, D_EVICT, dRefill}` (Task 4).
- Produces, relied on verbatim by Tasks 9 and 13:
  - `class AxiDMerge(axiCfg: Axi4Config, grantTimeout: BigInt)` `extends Component`, with `io` fields `dc: slave(Axi4)`, `itlb: slave(Axi4)`, `dtlb: slave(Axi4)`, the flat reset-vector read port `rvArValid/rvArAddr/rvArReady/rvRValid/rvRData/rvRResp/rvRReady`, `out: master(Axi4)`, and `wedge: out Bool()` / `wedgeIsRead: out Bool()`.
  - `class AxiDMergePlugin(val grantTimeout: BigInt = AxiDMerge.V1_TIMEOUT_CYCLES)` `extends FiberPlugin`, whose `logic` Area exposes `merge: AxiDMerge`, `axi: Axi4` (the single merged `master`, the one that becomes core-level IO), and the four flat reset-vector wires re-exported as `rvArValid/rvArAddr/rvArReady/rvRValid/rvRData/rvRResp/rvRReady` for Task 9 to drive.
  - `object AxiDMerge` with `val V1_TIMEOUT_CYCLES: BigInt = BigInt(2000000000)` and `object Owner { val DCACHE = 0; val ITLB = 1; val DTLB = 2; val RESETVEC = 3 }`.
  - Constructor flags `DcachePlugin(socketMerged: Boolean = false)`, `ItlbPlugin(..., socketMerged: Boolean = false)`, `DtlbPlugin(..., socketMerged: Boolean = false)`: **false** keeps today's `master(Axi4)` top-level port (so `M68kFullCoreSynth` is byte-identical), **true** declares the same bundle **directionless** so a sibling plugin may drive its response side.
- **Plugin ordering requirement, load-bearing:** in any plugin list containing it, `AxiDMergePlugin` must appear **after** `DcachePlugin`/`ItlbPlugin`/`DtlbPlugin` (it reads their `logic` Areas) and **before** `ResetVectorPlugin` (Task 9 drives its `rv*` wires) and **before** `BackendWiringPlugin` (which folds `wedge` into the halt drive).

**The hierarchy constraint that shapes this task, and a deviation from the spec's wording.**
The spec draws the arbiter as `AxiDMergePlugin` consuming `DcachePlugin.axi` / `itlbAxi` /
`dtlbAxi` directly. In SpinalHDL a sibling plugin **cannot** do that as those ports stand
today: `master(Axi4(...))` declared inside a plugin makes `ar.ready`/`r.valid`/`b.valid`
*inputs of the enclosing `M68kCore` Component*, and an input cannot be driven from inside.
This repository already documents the constraint in its own words at
`FetchAlignPlugin.scala:64-66`: *"feed is a plain directionless Stream (service convention:
plain wires so a sibling plugin in the same Component can drive feed.ready without hierarchy
violation)."* Hence the `socketMerged` flag: it is the minimum change that makes D7 buildable
at all, it is default-off so `M68kFullCoreSynth` cannot move, and it changes no logic — only
whether `master()` is applied. The arbitration itself is factored into a plain `Component`
(`AxiDMerge`) rather than living in the plugin, following `TableWalker`'s precedent, because
that is what makes it directly testable against `AxiMemModel` without elaborating the whole
2092-line D-cache.

**D19, restated so the implementer does not "helpfully" add a timer.** Spec §8.2: the core
does **not** rebuild the 20 s transaction-abandonment timer. That obligation is discharged by
the fabric's own layered watchdogs plus the stated core-side invariant that *every* AXI
transaction terminates on a response of any resp code and no FSM treats SLVERR/DECERR as a
retry. The watchdog added **here** is a different thing with a different justification (D20):
it protects against **arbiter starvation**, a wedge mode this design creates by putting three
owners on one port, which did not exist when they were physically separate masters.

**D20's bound is copied, never re-derived.** Spec §8.3: *"Bound: inherit v1's final
`TIMEOUT_CYCLES` value verbatim — 2,000,000,000 core-clk cycles (`32'h7735_9400`,
`axi_narrow_to_wide.v:263`). Do **not** re-derive it from the xbar's `WD_LOG2_S1 = 2^27`."*
The v1 header records that bug being introduced twice; a third repetition is avoidable only
by copying the value rather than the derivation.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/AxiDMergeSpec.scala`:

```scala
package m68k040.socket

import m68k040.M68kSim
import m68k040.cache.AxiIds
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Merge arbiter". Covers the four section 4.4 assertions, the D9
  * independence property, the D8 identical-ID case, and D20's bounded-grant watchdog. The
  * D-cache's own three-write-issuer behaviour through the arbiter is covered by the
  * existing D-cache suites once Task 13 wires it in; here the owners are driven directly
  * so each property is isolated. */
class AxiDMergeSpec extends AnyFunSuite {

  private val cfg = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = AxiIds.ID_W,
                               useId = true, useRegion = false, useBurst = true,
                               useLock = false, useCache = false, useSize = true,
                               useQos = false, useLen = true, useLast = true,
                               useResp = true, useProt = false, useStrb = true)

  /** A short watchdog so the D20 test finishes; the real bound is 2e9 (D20). */
  class MergeDut(timeout: BigInt = 64) extends Component {
    val m = new AxiDMerge(cfg, timeout)
    val io = new Bundle {
      val dc   = slave(Axi4(cfg))
      val itlb = slave(Axi4(cfg))
      val dtlb = slave(Axi4(cfg))
      val out  = master(Axi4(cfg))
      val rvArValid = in Bool ()
      val rvArAddr  = in UInt (32 bits)
      val rvArReady = out Bool ()
      val rvRValid  = out Bool ()
      val rvRData   = out Bits (128 bits)
      val rvRResp   = out Bits (2 bits)
      val rvRReady  = in Bool ()
      val wedge     = out Bool ()
      val wedgeIsRead = out Bool ()
    }
    io.dc   <> m.io.dc
    io.itlb <> m.io.itlb
    io.dtlb <> m.io.dtlb
    io.out  <> m.io.out
    m.io.rvArValid := io.rvArValid
    m.io.rvArAddr  := io.rvArAddr
    io.rvArReady   := m.io.rvArReady
    io.rvRValid    := m.io.rvRValid
    io.rvRData     := m.io.rvRData
    io.rvRResp     := m.io.rvRResp
    m.io.rvRReady  := io.rvRReady
    io.wedge       := m.io.wedge
    io.wedgeIsRead := m.io.wedgeIsRead
    io.wedge.simPublic(); io.wedgeIsRead.simPublic()
  }

  private def idleAll(dut: MergeDut): Unit = {
    for (p <- Seq(dut.io.dc, dut.io.itlb, dut.io.dtlb)) {
      p.ar.valid #= false; p.aw.valid #= false; p.w.valid #= false
      p.r.ready  #= true;  p.b.ready  #= true
      p.ar.payload.len #= 0; p.ar.payload.size #= 4; p.ar.payload.burst #= 1
      p.aw.payload.len #= 0; p.aw.payload.size #= 4; p.aw.payload.burst #= 1
      p.w.payload.last #= true; p.w.payload.strb #= BigInt("FFFF", 16)
      p.w.payload.data #= 0
    }
    dut.io.rvArValid #= false; dut.io.rvArAddr #= 0; dut.io.rvRReady #= true
  }

  /** Issue one read on `p` and return the 128-bit beat. Bounded so a wedge fails loudly. */
  private def read(dut: MergeDut, p: Axi4, addr: Long, id: Int): BigInt = {
    p.ar.payload.addr #= addr
    p.ar.payload.id   #= id
    p.ar.valid #= true
    var g = 0
    while (!(p.ar.ready.toBoolean) && g < 2000) { dut.clockDomain.waitSampling(); g += 1 }
    assert(g < 2000, f"AR never granted for id $id at 0x$addr%08X")
    dut.clockDomain.waitSampling()
    p.ar.valid #= false
    g = 0
    while (!p.r.valid.toBoolean && g < 2000) { dut.clockDomain.waitSampling(); g += 1 }
    assert(g < 2000, f"R never returned for id $id at 0x$addr%08X")
    val d = p.r.payload.data.toBigInt
    assert(p.r.payload.id.toInt == id, s"R id ${p.r.payload.id.toInt} routed to the wrong owner")
    dut.clockDomain.waitSampling()
    d
  }

  test("D8: ITLB and DTLB reads with IDENTICAL ids route to the right consumer") {
    M68kSim().compile(new MergeDut()).doSim("same-id", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      val mem = AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      for (i <- 0 until 16) mem.pokeByte(0x1000 + i, 0x10 + i)
      for (i <- 0 until 16) mem.pokeByte(0x2000 + i, 0x20 + i)
      dut.clockDomain.waitSampling(4)
      // Both walkers use AxiIds.WALK_READ (== 2). Routing must NOT consult the ID.
      val a = read(dut, dut.io.itlb, 0x1000, AxiIds.WALK_READ)
      val b = read(dut, dut.io.dtlb, 0x2000, AxiIds.WALK_READ)
      assert((a & 0xff) == 0x10, f"ITLB got 0x$a%032X")
      assert((b & 0xff) == 0x20, f"DTLB got 0x$b%032X")
    }
  }

  test("section 4.4: an R beat is never presented to a non-owner") {
    M68kSim().compile(new MergeDut()).doSim("no-cross-talk", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      val mem = AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      for (i <- 0 until 16) mem.pokeByte(0x3000 + i, 0xC0 + i)
      dut.clockDomain.waitSampling(4)
      // Watch every owner's r.valid for the whole of one D-cache read.
      var violations = 0
      val watcher = fork {
        while (true) {
          dut.clockDomain.waitSampling()
          val hot = Seq(dut.io.dc.r.valid.toBoolean, dut.io.itlb.r.valid.toBoolean,
                        dut.io.dtlb.r.valid.toBoolean, dut.io.rvRValid.toBoolean)
          if (hot.count(identity) > 1) violations += 1
          if (hot(1) || hot(2) || hot(3)) violations += 1   // only the D-cache asked
        }
      }
      read(dut, dut.io.dc, 0x3000, AxiIds.dRefill(0))
      dut.clockDomain.waitSampling(4)
      assert(violations == 0, s"$violations cycles presented R to a non-owner")
    }
  }

  test("D9: a read grant held with r.ready LOW does not block an unrelated write") {
    // The D9 deadlock case, directed: DcachePlugin.scala:1258 sets
    // `axi.r.ready := !refillWriteHold`, i.e. a refill deliberately refuses its R beat
    // until a colliding store drain's window closes -- and that store drain needs the
    // WRITE channel. Under a single global grant token this deadlocks.
    M68kSim().compile(new MergeDut()).doSim("independent-grants", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      val mem = AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      dut.clockDomain.waitSampling(4)
      // D-cache read, with r.ready held LOW (the refillWriteHold shape).
      dut.io.dc.r.ready #= false
      dut.io.dc.ar.payload.addr #= 0x4000
      dut.io.dc.ar.payload.id   #= AxiIds.dRefill(0)
      dut.io.dc.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.io.dc.ar.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.dc.ar.valid #= false
      // Now a walker write. It MUST complete while the read grant is still held.
      dut.io.itlb.aw.payload.addr #= 0x5000
      dut.io.itlb.aw.payload.id   #= AxiIds.WALK_WRITE
      dut.io.itlb.w.payload.data  #= BigInt("A5", 16)
      dut.io.itlb.aw.valid #= true
      dut.io.itlb.w.valid  #= true
      var g = 0
      while (!dut.io.itlb.b.valid.toBoolean && g < 500) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 500, "walker write never completed while a read grant was held -- D9 deadlock")
      dut.io.itlb.aw.valid #= false; dut.io.itlb.w.valid #= false
      // And the read still completes once its owner accepts.
      dut.io.dc.r.ready #= true
      g = 0
      while (!dut.io.dc.r.valid.toBoolean && g < 500) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 500, "the held read never completed after r.ready rose")
    }
  }

  test("section 4.4: arBusy does not clear without r.last, and awBusy not without b") {
    M68kSim().compile(new MergeDut()).doSim("busy-discipline", seed = 4) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      AxiMemModel.attachFull(dut.io.out, dut.clockDomain,
        AxiMemModelConfig(checkIdUnique = false, crossbarSingleOutstanding = true))
      dut.clockDomain.waitSampling(4)
      // Hold r.ready low across a granted read: a second owner must NOT be granted.
      dut.io.dc.r.ready #= false
      dut.io.dc.ar.payload.addr #= 0x6000
      dut.io.dc.ar.payload.id   #= AxiIds.dRefill(0)
      dut.io.dc.ar.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.io.dc.ar.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.dc.ar.valid #= false
      dut.io.itlb.ar.payload.addr #= 0x7000
      dut.io.itlb.ar.payload.id   #= AxiIds.WALK_READ
      dut.io.itlb.ar.valid #= true
      for (i <- 0 until 20) {
        dut.clockDomain.waitSampling()
        assert(!dut.io.itlb.ar.ready.toBoolean,
          s"a second read owner was granted at cycle $i while the first was outstanding")
      }
      dut.io.dc.r.ready #= true
      var g = 0
      while (!dut.io.itlb.ar.ready.toBoolean && g < 200) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 200, "the read grant never released after r.last")
    }
  }

  test("D20: a grant held with no progress trips the bounded-grant watchdog") {
    M68kSim().compile(new MergeDut(timeout = 40)).doSim("watchdog", seed = 5) { dut =>
      dut.clockDomain.forkStimulus(10)
      idleAll(dut)
      // NO memory model attached: the fabric never answers, which is precisely the
      // "structurally absent wide side" case D20 exists for. `out` is left dangling,
      // so ar.ready is never asserted.
      dut.io.out.ar.ready #= false; dut.io.out.aw.ready #= false
      dut.io.out.w.ready  #= false; dut.io.out.r.valid  #= false
      dut.io.out.b.valid  #= false
      dut.clockDomain.waitSampling(4)
      assert(!dut.io.wedge.toBoolean, "wedge asserted before any grant")
      dut.io.dc.ar.payload.addr #= 0x8000
      dut.io.dc.ar.payload.id   #= AxiIds.dRefill(0)
      dut.io.dc.ar.valid #= true
      var g = 0
      while (!dut.io.wedge.toBoolean && g < 400) { dut.clockDomain.waitSampling(); g += 1 }
      assert(g < 400, "the bounded-grant watchdog never fired")
      assert(dut.io.wedgeIsRead.toBoolean, "the wedge was reported on the wrong direction")
      // It NEVER fabricates a response (spec section 8.3).
      for (_ <- 0 until 20) {
        dut.clockDomain.waitSampling()
        assert(!dut.io.dc.r.valid.toBoolean, "the watchdog fabricated an R beat")
        assert(!dut.io.dc.b.valid.toBoolean, "the watchdog fabricated a B response")
      }
    }
  }

  test("D20's real bound is v1's value verbatim, not a re-derivation") {
    // Spec section 8.3: "inherit v1's final TIMEOUT_CYCLES value verbatim -- 2,000,000,000
    // core-clk cycles (32'h7735_9400, axi_narrow_to_wide.v:263). Do NOT re-derive it from
    // the xbar's WD_LOG2_S1 = 2^27." The v1 header records that bug being introduced twice.
    assert(AxiDMerge.V1_TIMEOUT_CYCLES == BigInt(2000000000),
      s"got ${AxiDMerge.V1_TIMEOUT_CYCLES}")
    assert(AxiDMerge.V1_TIMEOUT_CYCLES != BigInt(1) << 28,
      "2^28 is the SUPERSEDED 2026-08-02 derivation, not the value")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.AxiDMergeSpec" 2>&1 | tail -20
```

Expected: a compilation error — `not found: type AxiDMerge`.

- [ ] **Step 3a: Write the arbiter Component**

Create `src/main/scala/m68k040/socket/AxiDMerge.scala`:

```scala
package m68k040.socket

import m68k040.cache.AxiIds
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

object AxiDMerge {
  /** D20's bound, COPIED from v1 rather than re-derived: `TIMEOUT_CYCLES` at
    * `axi_narrow_to_wide.v:263` (`32'h7735_9400`).
    *
    * That file's header contains TWO generations of sizing rationale and the earlier one
    * reads as current: `:205-228` derives 2^28 from the xbar's `WD_LOG2_S1 = 2^27`, and
    * `:245-262` SUPERSEDES it with 2e9 because `sd_ctrl`'s per-request watchdog (~10.07 s)
    * is the larger downstream bound -- a 2^28 bound (~2.68 s) pre-empted a graceful,
    * retryable SCSI recovery with a fatal bus error by 3.75x. The v1 header records that
    * bug being introduced TWICE. A third repetition is avoidable only by copying the value
    * rather than the derivation, which is what this constant is.
    *
    * The invariant travels with the number, in v1's own words (`:259-262`): *this value
    * MUST exceed every downstream per-slave watchdog; if you raise one of those, raise
    * this one too, or the graceful recovery path you just tuned becomes unreachable.* */
  val V1_TIMEOUT_CYCLES: BigInt = BigInt(2000000000)

  /** Owner tags. The VALUE is arbitrary -- routing is by this latched tag, never by the
    * AXI ID (D8) -- but the read side has four owners and the write side three, and the
    * reset-vector reader is deliberately last so the write side can use the same encoding
    * truncated. */
  object Owner {
    val DCACHE   = 0
    val ITLB     = 1
    val DTLB     = 2
    val RESETVEC = 3
  }
}

/** The `axi_d` master merge (design spec D7-D10, D19-D20, section 4).
  *
  * ==Topology (D7)==
  * {{{
  *   DcachePlugin.axi (128b) --+
  *   itlbAxi          (128b) --+-- AxiDMerge --> axi_d
  *   dtlbAxi          (128b) --+   (owner-tag,
  *   ResetVectorPlugin (RO)  --+    serializing)
  * }}}
  * `axi_i` needs no arbiter: the I-cache is its only user and is already read-only. The
  * ITLB walker cannot join it -- `ItlbPlugin.scala:255-270` genuinely issues AXI WRITES
  * (the U-bit descriptor writeback) and `cpu_socket.vh:99` declares `axi_i` AR/R only.
  * That is the forcing constraint which puts BOTH walkers here.
  *
  * ==Owner tag, not ID demux (D8)==
  * Responses route by the grant machine's LATCHED OWNER. The returned AXI ID is forwarded
  * as a fabric hint and is NEVER consulted for routing. This is what makes G3's "ITLB and
  * DTLB both emit AR=2/AW=3" collision inert: two owners with identical IDs are
  * indistinguishable to the fabric but perfectly distinguishable to the arbiter, and being
  * single-outstanding per direction, at most one walker transaction exists at a time
  * anyway. A full ID demux would need the deliberately-unbuilt V2a.2/V2a.3 infrastructure
  * (`AxiIds.scala:21-30`) for zero end-to-end gain on a fabric that is single-outstanding
  * per master port regardless (`axi_narrow_to_wide.v:72-75`).
  *
  * ==Independent per-direction grants (D9), and why a single token deadlocks==
  * `DcachePlugin.scala:1258` sets `axi.r.ready := !refillWriteHold`: a refill deliberately
  * holds off ACCEPTING its R beat until a colliding same-set store drain's S1/S2 window
  * closes -- and that store drain needs the WRITE channel. Under one global grant token the
  * D-cache would hold the read grant while waiting for a write it cannot get. With
  * independent grants the dependency graph is acyclic: a walker's read and a walker's write
  * each depend on nothing else in the core, a refill's R acceptance may wait on a store
  * drain waiting on the write grant, and nothing on the write side ever waits on the read
  * side.
  *
  * ==What this watchdog is, and is NOT (D19 vs D20)==
  * It is NOT a reinstatement of v1's 20 s transaction-abandonment timer. D19 declines to
  * rebuild that: the fabric's own layered watchdogs (`peripheral_bus.v`'s
  * `PB_WATCHDOG_LOG2 = 24`, `axi_xbar.v`'s `WD_LOG2_S1 = 27`) already provide it OUTSIDE
  * the socket, and `axi_narrow_to_wide.v:205-228` says its own timer is by design a
  * never-firing last resort on an xbar-connected instance.
  *
  * This watchdog covers a DIFFERENT mode, one this design CREATES: three owners now share
  * one port, so an owner that never completes starves the other two indefinitely.
  * Previously they were physically separate masters and could not affect each other.
  *
  * On expiry it does NOT fabricate a response. Synthesising a `B` would be caught by the
  * D-cache's fail-closed ID demux in the best case and would silently ack a store that
  * never landed in the worst; synthesising an `R` would inject garbage into a refill.
  * Instead it raises `wedge`, which the core's wiring turns into a sticky `coreHalted` with
  * `HaltReason.ARBITER_WEDGE` -- the reason D28's channel has to exist at all. Note the
  * asymmetry with v1's choice to return SLVERR: v1 sat between a *host* (JTAG) and the
  * fabric, where informing an external restartable master is right. This arbiter sits
  * between core FSMs, where there is no external master to inform.
  *
  * @param grantTimeout cycles a grant may be held with NO progress on any channel of that
  *                     direction before `wedge` rises. Any accepted beat restarts the
  *                     count, so a legitimately slow transaction never trips it -- the same
  *                     "without making progress" formulation `axi_narrow_to_wide.v:97-101`
  *                     uses. Parameterised ONLY so directed tests can use a short value;
  *                     production is `AxiDMerge.V1_TIMEOUT_CYCLES`. */
class AxiDMerge(axiCfg: Axi4Config,
                grantTimeout: BigInt = AxiDMerge.V1_TIMEOUT_CYCLES) extends Component {
  import AxiDMerge.Owner
  require(grantTimeout >= 2, s"grantTimeout must be >= 2 (got $grantTimeout)")

  val io = new Bundle {
    val dc   = slave(Axi4(axiCfg))
    val itlb = slave(Axi4(axiCfg))
    val dtlb = slave(Axi4(axiCfg))
    // The reset-vector reader (D13) is a FLAT port rather than a fourth Axi4 bundle: its
    // transaction shape is fixed (one 16-byte read, len=0, size=4, INCR, id=RESET_VEC), so
    // the only fields it can vary are the address and the handshake. Keeping it flat means
    // ResetVectorPlugin cannot accidentally emit a differently-shaped transaction.
    val rvArValid = in Bool ()
    val rvArAddr  = in UInt (axiCfg.addressWidth bits)
    val rvArReady = out Bool ()
    val rvRValid  = out Bool ()
    val rvRData   = out Bits (axiCfg.dataWidth bits)
    val rvRResp   = out Bits (2 bits)
    val rvRReady  = in Bool ()
    val out         = master(Axi4(axiCfg))
    val wedge       = out Bool ()
    val wedgeIsRead = out Bool ()
  }

  val timeoutBits = log2Up(grantTimeout + 1)

  // ── Read side: four owners, single outstanding ────────────────────────────────────
  val rd = new Area {
    val owner = Reg(UInt(2 bits)) init U(Owner.DCACHE, 2 bits)
    val busy  = RegInit(False)
    val rr    = Reg(UInt(2 bits)) init 0      // round-robin rotation base

    val req = Vec(Bool(), 4)
    req(Owner.DCACHE)   := io.dc.ar.valid
    req(Owner.ITLB)     := io.itlb.ar.valid
    req(Owner.DTLB)     := io.dtlb.ar.valid
    req(Owner.RESETVEC) := io.rvArValid

    // Round-robin pick: iterate k from 3 down to 0 so SpinalHDL's last-assignment-wins
    // gives the ROTATED-FIRST requester (k = 0) priority. No priority encoder chain.
    val pick = UInt(2 bits); pick := U(0, 2 bits)
    val any  = Bool();       any  := False
    for (k <- 3 to 0 by -1) {
      val idx = rr + U(k, 2 bits)
      when(req(idx)) { pick := idx; any := True }
    }

    val grant = !busy && any

    // AR payload is forwarded VERBATIM (D8) -- addr, id, len, size, burst -- so the
    // fabric's L2 ID logic (`l2c_ctrl.v:140-142,151`) and any future ID-aware behaviour
    // see exactly what the plugin intended. The reset-vector owner's shape is fixed here.
    io.out.ar.valid := grant
    io.out.ar.payload.addr  := io.dc.ar.payload.addr
    io.out.ar.payload.id    := io.dc.ar.payload.id
    io.out.ar.payload.len   := io.dc.ar.payload.len
    io.out.ar.payload.size  := io.dc.ar.payload.size
    io.out.ar.payload.burst := io.dc.ar.payload.burst
    when(pick === U(Owner.ITLB, 2 bits)) { io.out.ar.payload := io.itlb.ar.payload }
    when(pick === U(Owner.DTLB, 2 bits)) { io.out.ar.payload := io.dtlb.ar.payload }
    when(pick === U(Owner.RESETVEC, 2 bits)) {
      io.out.ar.payload.addr  := io.rvArAddr
      io.out.ar.payload.id    := U(AxiIds.RESET_VEC, axiCfg.idWidth bits)
      io.out.ar.payload.len   := U(0, 8 bits)
      io.out.ar.payload.size  := U(4, 3 bits)          // 16 bytes -- the whole vector line
      io.out.ar.payload.burst := Axi4.burst.INCR
    }

    io.dc.ar.ready   := grant && (pick === U(Owner.DCACHE,   2 bits)) && io.out.ar.ready
    io.itlb.ar.ready := grant && (pick === U(Owner.ITLB,     2 bits)) && io.out.ar.ready
    io.dtlb.ar.ready := grant && (pick === U(Owner.DTLB,     2 bits)) && io.out.ar.ready
    io.rvArReady     := grant && (pick === U(Owner.RESETVEC, 2 bits)) && io.out.ar.ready

    when(io.out.ar.fire) { owner := pick; busy := True; rr := pick + 1 }

    // R fans out to the LATCHED owner only. Payload is broadcast (cheaper than a mux and
    // harmless -- `valid` is what gates a consumer), `valid` is not.
    io.dc.r.payload   := io.out.r.payload
    io.itlb.r.payload := io.out.r.payload
    io.dtlb.r.payload := io.out.r.payload
    io.rvRData := io.out.r.payload.data
    io.rvRResp := io.out.r.payload.resp

    io.dc.r.valid   := io.out.r.valid && busy && (owner === U(Owner.DCACHE,   2 bits))
    io.itlb.r.valid := io.out.r.valid && busy && (owner === U(Owner.ITLB,     2 bits))
    io.dtlb.r.valid := io.out.r.valid && busy && (owner === U(Owner.DTLB,     2 bits))
    io.rvRValid     := io.out.r.valid && busy && (owner === U(Owner.RESETVEC, 2 bits))

    io.out.r.ready := busy && io.out.r.valid && (
      ((owner === U(Owner.DCACHE,   2 bits)) && io.dc.r.ready)   ||
      ((owner === U(Owner.ITLB,     2 bits)) && io.itlb.r.ready) ||
      ((owner === U(Owner.DTLB,     2 bits)) && io.dtlb.r.ready) ||
      ((owner === U(Owner.RESETVEC, 2 bits)) && io.rvRReady))

    when(io.out.r.fire && io.out.r.payload.last) { busy := False }

    // D20 bounded-grant watchdog: any accepted beat on EITHER channel of this direction
    // restarts the count, so a legitimately slow transaction never trips it.
    val progress = io.out.ar.fire || io.out.r.fire
    val wdog = Reg(UInt(timeoutBits bits)) init 0
    when(!busy || progress) { wdog := 0 } elsewhen (wdog =/= U(grantTimeout, timeoutBits bits)) {
      wdog := wdog + 1
    }
    val wedge = busy && (wdog === U(grantTimeout, timeoutBits bits))
  }

  // ── Write side: three owners, AW and W granted as a PAIR ──────────────────────────
  val wr = new Area {
    val owner = Reg(UInt(2 bits)) init U(Owner.DCACHE, 2 bits)
    val busy  = RegInit(False)
    val rr    = Reg(UInt(2 bits)) init 0

    // A write owner is "asking" as soon as either half of its pair is presented. The pair
    // is then held for the WHOLE transaction (D8): today every D-side write is len=0, but
    // the rule is stated as an invariant so a future burst writeback cannot interleave two
    // owners' W beats.
    val req = Vec(Bool(), 4)
    req(Owner.DCACHE)   := io.dc.aw.valid   || io.dc.w.valid
    req(Owner.ITLB)     := io.itlb.aw.valid || io.itlb.w.valid
    req(Owner.DTLB)     := io.dtlb.aw.valid || io.dtlb.w.valid
    req(Owner.RESETVEC) := False            // read-only owner; never asks for the W side

    val pick = UInt(2 bits); pick := U(0, 2 bits)
    val any  = Bool();       any  := False
    for (k <- 3 to 0 by -1) {
      val idx = rr + U(k, 2 bits)
      when(req(idx)) { pick := idx; any := True }
    }

    val grant = !busy && any
    val sel   = Mux(busy, owner, pick)
    val open  = busy || grant

    io.out.aw.valid   := open && io.dc.aw.valid
    io.out.aw.payload := io.dc.aw.payload
    io.out.w.valid    := open && io.dc.w.valid
    io.out.w.payload  := io.dc.w.payload
    when(sel === U(Owner.ITLB, 2 bits)) {
      io.out.aw.valid   := open && io.itlb.aw.valid
      io.out.aw.payload := io.itlb.aw.payload
      io.out.w.valid    := open && io.itlb.w.valid
      io.out.w.payload  := io.itlb.w.payload
    }
    when(sel === U(Owner.DTLB, 2 bits)) {
      io.out.aw.valid   := open && io.dtlb.aw.valid
      io.out.aw.payload := io.dtlb.aw.payload
      io.out.w.valid    := open && io.dtlb.w.valid
      io.out.w.payload  := io.dtlb.w.payload
    }

    io.dc.aw.ready   := open && (sel === U(Owner.DCACHE, 2 bits)) && io.out.aw.ready
    io.itlb.aw.ready := open && (sel === U(Owner.ITLB,   2 bits)) && io.out.aw.ready
    io.dtlb.aw.ready := open && (sel === U(Owner.DTLB,   2 bits)) && io.out.aw.ready
    io.dc.w.ready    := open && (sel === U(Owner.DCACHE, 2 bits)) && io.out.w.ready
    io.itlb.w.ready  := open && (sel === U(Owner.ITLB,   2 bits)) && io.out.w.ready
    io.dtlb.w.ready  := open && (sel === U(Owner.DTLB,   2 bits)) && io.out.w.ready

    when(grant) { owner := pick; busy := True; rr := pick + 1 }

    io.dc.b.payload   := io.out.b.payload
    io.itlb.b.payload := io.out.b.payload
    io.dtlb.b.payload := io.out.b.payload
    io.dc.b.valid   := io.out.b.valid && busy && (owner === U(Owner.DCACHE, 2 bits))
    io.itlb.b.valid := io.out.b.valid && busy && (owner === U(Owner.ITLB,   2 bits))
    io.dtlb.b.valid := io.out.b.valid && busy && (owner === U(Owner.DTLB,   2 bits))
    io.out.b.ready := busy && io.out.b.valid && (
      ((owner === U(Owner.DCACHE, 2 bits)) && io.dc.b.ready)   ||
      ((owner === U(Owner.ITLB,   2 bits)) && io.itlb.b.ready) ||
      ((owner === U(Owner.DTLB,   2 bits)) && io.dtlb.b.ready))
    when(io.out.b.fire) { busy := False }

    val progress = io.out.aw.fire || io.out.w.fire || io.out.b.fire
    val wdog = Reg(UInt(timeoutBits bits)) init 0
    when(!busy || progress) { wdog := 0 } elsewhen (wdog =/= U(grantTimeout, timeoutBits bits)) {
      wdog := wdog + 1
    }
    val wedge = busy && (wdog === U(grantTimeout, timeoutBits bits))
  }

  io.wedge       := rd.wedge || wr.wedge
  io.wedgeIsRead := rd.wedge

  // ── Section 4.4's required assertions ─────────────────────────────────────────────
  // Sim-only. `GenerationFlags.simulation` needs `.includeSimulation` on the enclosing
  // SpinalConfig to elaborate at all -- see M68kSim.scala.
  GenerationFlags.simulation {
    assert(!(rd.grant && rd.busy),
      "AxiDMerge: a read grant was issued while one was already outstanding", FAILURE)
    assert(!(wr.grant && wr.busy),
      "AxiDMerge: a write grant was issued while one was already outstanding", FAILURE)
    val rHot = io.dc.r.valid.asUInt +^ io.itlb.r.valid.asUInt +^
               io.dtlb.r.valid.asUInt +^ io.rvRValid.asUInt
    assert(rHot <= U(1), "AxiDMerge: an R beat was presented to more than one owner", FAILURE)
    val bHot = io.dc.b.valid.asUInt +^ io.itlb.b.valid.asUInt +^ io.dtlb.b.valid.asUInt
    assert(bHot <= U(1), "AxiDMerge: a B beat was presented to more than one owner", FAILURE)
    assert(!(RegNext(rd.busy) init False) || rd.busy || RegNext(io.out.r.fire && io.out.r.payload.last),
      "AxiDMerge: the read grant cleared without an r.last fire", FAILURE)
    assert(!(RegNext(wr.busy) init False) || wr.busy || RegNext(io.out.b.fire),
      "AxiDMerge: the write grant cleared without a b fire", FAILURE)
    // A granted owner's AR payload must not change while granted (an owner that mutates
    // its request mid-grant would make the forwarded payload and the latched owner
    // describe different transactions).
    val arHeld = RegNext(io.out.ar.valid && !io.out.ar.ready) init False
    assert(!arHeld || (RegNext(io.out.ar.payload.addr) === io.out.ar.payload.addr),
      "AxiDMerge: a granted owner changed its AR address across a stalled handshake", FAILURE)
  }
}
```

- [ ] **Step 3b: Write the plugin wrapper and the `socketMerged` flags**

Create `src/main/scala/m68k040/socket/AxiDMergePlugin.scala`:

```scala
package m68k040.socket

import m68k040.cache.DcachePlugin
import m68k040.mmu.{DtlbPlugin, ItlbPlugin}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.misc.plugin.FiberPlugin

/** Hosts `AxiDMerge` and connects it to the three D-side plugins' now-directionless AXI
  * bundles, presenting ONE merged `master(Axi4)` that becomes core-level IO.
  *
  * ==Ordering requirement, load-bearing==
  * In any plugin list, this plugin must appear AFTER `DcachePlugin`/`ItlbPlugin`/
  * `DtlbPlugin` (it reads their `logic` Areas, which are null until they have built) and
  * BEFORE `ResetVectorPlugin` (which drives the `rv*` wires below) and BEFORE
  * `BackendWiringPlugin` (which folds `wedge` into the halt drive). This mirrors the
  * `MmuControlPlugin`/`FpuControlPlugin`-before-`RobPlugin` constraint that
  * `FullCoreSynth.scala:496-499` already documents for the same reason.
  *
  * ==Why the three plugins need a flag at all==
  * `master(Axi4(...))` declared inside a plugin makes `ar.ready`/`r.valid`/`b.valid`
  * INPUTS of the enclosing `M68kCore`, and an input cannot be driven from inside it. This
  * repository states the constraint in its own words at `FetchAlignPlugin.scala:64-66`.
  * `socketMerged = true` therefore declares the identical bundle DIRECTIONLESS; it changes
  * no logic. Default false keeps `M68kFullCoreSynth`'s port surface byte-identical, which
  * `tools/socket/check_socket_netlist.py` enforces. */
class AxiDMergePlugin(val grantTimeout: BigInt = AxiDMerge.V1_TIMEOUT_CYCLES)
    extends FiberPlugin {

  val logic = during build new Area {
    val dc = host[DcachePlugin]
    val it = host[ItlbPlugin]
    val dt = host[DtlbPlugin]
    require(dc.socketMerged && it.socketMerged && dt.socketMerged,
      "AxiDMergePlugin requires DcachePlugin/ItlbPlugin/DtlbPlugin to be constructed with " +
      "socketMerged = true, otherwise their AXI bundles are top-level master ports the " +
      "arbiter cannot drive the response side of")

    val merge = new AxiDMerge(dc.axiCfg, grantTimeout)

    merge.io.dc   <> dc.logic.axi
    merge.io.itlb <> it.logic.walkerAxi
    merge.io.dtlb <> dt.logic.walkerAxi

    /** The single merged D-side master. This is the bundle `M68kSocketTop` permutes and
      * presents as `axi_d`. */
    val axi = master(Axi4(dc.axiCfg)).setName("axiDMerged")
    axi <> merge.io.out

    // Reset-vector read owner (D13). Idle-defaulted with CONCRETE values and
    // `allowOverride` -- NOT `assignDontCare`, which would hide `ResetVectorPlugin`'s drive
    // from the consumer (a documented SpinalHDL trap this project has hit before). With
    // no ResetVectorPlugin in the list these stay idle and the fourth owner never asks.
    val rvArValid = Bool();  rvArValid.allowOverride; rvArValid := False
    val rvArAddr  = UInt(dc.axiCfg.addressWidth bits)
    rvArAddr.allowOverride;  rvArAddr := U(0, dc.axiCfg.addressWidth bits)
    val rvRReady  = Bool();  rvRReady.allowOverride;  rvRReady := False
    merge.io.rvArValid := rvArValid
    merge.io.rvArAddr  := rvArAddr
    merge.io.rvRReady  := rvRReady
    val rvArReady = merge.io.rvArReady
    val rvRValid  = merge.io.rvRValid
    val rvRData   = merge.io.rvRData
    val rvRResp   = merge.io.rvRResp

    /** D20: raised when either direction held a grant for its whole bounded window with no
      * progress. `BackendWiringPlugin` folds this into the D28 halt drive; this plugin
      * deliberately does NOT reach into `RobPlugin` itself, so the core keeps exactly one
      * driver for `coreHaltedIn`. */
    val wedge = merge.io.wedge
    val wedgeIsRead = merge.io.wedgeIsRead
    wedge.simPublic(); wedgeIsRead.simPublic()
  }
}
```

Then apply the three declaration changes:

1. `src/main/scala/m68k040/cache/DcachePlugin.scala:41` — change
   `class DcachePlugin extends FiberPlugin with DcacheService {` to
   `class DcachePlugin(val socketMerged: Boolean = false) extends FiberPlugin with DcacheService {`
   and `:95` — change `val axi         = master(Axi4(axiCfg))` to:

```scala
    // `socketMerged` (axi-socket adapter plan, Task 5): when this plugin's AXI is merged
    // onto the single socket `axi_d` by AxiDMergePlugin, the bundle must be DIRECTIONLESS
    // so a sibling plugin in the same Component can drive its response side --
    // `master(...)` would make ar.ready/r.valid/b.valid inputs of M68kCore, which cannot
    // be driven from inside (see FetchAlignPlugin.scala:64-66). No logic changes; the
    // default (false) is today's behaviour and today's top-level port, exactly.
    val axi         = if (socketMerged) Axi4(axiCfg) else master(Axi4(axiCfg))
```

2. `src/main/scala/m68k040/mmu/ItlbPlugin.scala:70` — add `socketMerged: Boolean = false` as
   the last constructor parameter (declared `val`) and change the declaration to:

```scala
    walkerAxi = (if (socketMerged) Axi4(axiCfg) else master(Axi4(axiCfg))).setName("itlbAxi")
```

3. `src/main/scala/m68k040/mmu/DtlbPlugin.scala:74` — the identical change with
   `.setName("dtlbAxi")`.

- [ ] **Step 3c: Fix any parameterless `new DcachePlugin` call sites**

Adding a parameter list to `DcachePlugin` makes `new DcachePlugin` (no parens) a compile
error in Scala 2.13. Find and fix every such site:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -rn 'new DcachePlugin\b' src/ | grep -v 'new DcachePlugin('
grep -rn 'new ItlbPlugin\b'   src/ | grep -v 'new ItlbPlugin('
grep -rn 'new DtlbPlugin\b'   src/ | grep -v 'new DtlbPlugin('
```

Every hit gets `()` appended. Do **not** pass `socketMerged = true` anywhere yet — Task 13 is
the only place that does.

- [ ] **Step 3d: Fold the wedge into the halt drive**

In `src/main/scala/m68k040/top/FullCoreSynth.scala`, replace the two statements Task 3 left
at `:364`ff with:

```scala
    // D20: the merge arbiter's bounded-grant expiry is a THIRD halt producer, present only
    // in a socket build. `host.get` keeps M68kFullCoreSynth (which has no arbiter)
    // bit-identical -- the same Option shape FetchAlignPlugin uses for
    // FrontendQuiesceService at :89-99.
    val arbWedge = host.get[m68k040.socket.AxiDMergePlugin] match {
      case Some(a) => a.logic.wedge
      case None    => False
    }
    rob.logic.coreHaltedIn := dc.diagFault || exc.fsXlateFault || arbWedge
    // D28: priority when several fire on the same cycle is stated here rather than left to
    // elaboration order. The D-cache's diagnostic fault wins because it is the one with a
    // sub-code (DcachePlugin's private diagFaultKind) that further localises the failure;
    // the arbiter wedge is last because it is the most likely CONSEQUENCE of the others.
    rob.logic.haltReasonIn := Mux(dc.diagFault,
      U(m68k040.socket.HaltReason.DCACHE_DIAG, m68k040.socket.HaltReason.W bits),
      Mux(exc.fsXlateFault,
        U(m68k040.socket.HaltReason.FS_XLATE, m68k040.socket.HaltReason.W bits),
        Mux(arbWedge,
          U(m68k040.socket.HaltReason.ARBITER_WEDGE, m68k040.socket.HaltReason.W bits),
          U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits))))
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.socket.AxiDMergeSpec" 2>&1 | tail -25
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 6, failed 0` for the new suite; `check_socket_netlist.py` reports
`All checks passed.` — **this is the load-bearing check for this task**, because
`socketMerged = false` must leave `DcachePlugin_logic_axi_*`, `itlbAxi_*` and `dtlbAxi_*`
present, unrenamed and unresized on `M68kFullCoreSynth`; and `test-fast` rises by 6 with no
pre-existing failure.

**Report explicitly** whether this task is expected to move timing. It adds a 4-way mux and a
2-bit owner compare in front of the D-side AXI channels, but only in a socket build —
`M68kFullCoreSynth`, the FMax gate target, does not contain `AxiDMerge` at all, so Task 14's
gate measures the socket top separately if at all. Say so in the task report rather than
leaving Task 14 to rediscover it.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/AxiDMerge.scala \
        src/main/scala/m68k040/socket/AxiDMergePlugin.scala \
        src/main/scala/m68k040/cache/DcachePlugin.scala \
        src/main/scala/m68k040/mmu/ItlbPlugin.scala \
        src/main/scala/m68k040/mmu/DtlbPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/socket/AxiDMergeSpec.scala
git commit -m "$(cat <<'EOF'
socket(D7-D10,D20): owner-tag serializing axi_d merge + bounded-grant watchdog

4 masters -> 2. axi_i stays I-cache-only; D-cache + ITLB walker + DTLB walker
(+ the D13 reset-vector reader) merge onto one axi_d. The ITLB walker genuinely
issues AXI writes, so it categorically cannot ride the read-only axi_i -- that
is the forcing constraint that puts BOTH walkers here.

Owner tag, not ID demux (D8): responses route by the grant machine's latched
owner and the returned AXI ID is never consulted. That makes ITLB and DTLB both
emitting AR=2/AW=3 inert rather than something to renumber around, and it avoids
the deliberately-unbuilt V2a.2/V2a.3 ID-routing infrastructure for zero gain on
a fabric that is single-outstanding per master port anyway.

Read and write grants are INDEPENDENT state machines (D9). A single global token
deadlocks: DcachePlugin.scala:1258 holds off accepting its refill R beat until a
colliding store drain's window closes, and that drain needs the write channel.
The directed test for exactly that case is in the suite.

D20's watchdog is NOT a reinstatement of the 20 s abandonment timer D19 declines
to rebuild. It covers a mode this design CREATES -- three owners on one port, so
one that never completes starves the others. Its bound is v1's final value
copied verbatim (2e9), never re-derived from the xbar's WD_LOG2_S1: that
derivation is the superseded 2026-08-02 reasoning and the v1 header records the
resulting bug being introduced twice. On expiry it never fabricates a response;
it raises `wedge`, which becomes a sticky coreHalted with ARBITER_WEDGE on D28's
channel.

The socketMerged flag on the three plugins is the minimum change that makes any
of this buildable: master(Axi4) inside a plugin makes ar.ready/r.valid/b.valid
inputs of M68kCore, which a sibling cannot drive (FetchAlignPlugin.scala:64-66
states the same constraint in its own words). It is default-off, changes no
logic, and the netlist checker proves M68kFullCoreSynth's port surface did not
move.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `MmioCover` — the bounded exact-cover math, proved exhaustively (D6, D25, D30)

**Files:**
- Create: `src/main/scala/m68k040/socket/MmioCover.scala`
- Test: `src/test/scala/m68k040/socket/MmioCoverSpec.scala`

**Interfaces:**
- Consumes: nothing from Tasks 1-5.
- Produces, relied on verbatim by Tasks 7 and 8:
  - `object m68k040.socket.MmioCover` with
    - `val MAX_SUBS = 3`
    - `def sizeBytes(size: Size.C): UInt` — 1/2/4 as a 3-bit `UInt`.
    - `def clampedEnd(off: UInt, n: UInt): UInt` — the **D25 clamp**, `min(off + n, 16)`, 5 bits.
    - `def stepLog2(p: UInt, end: UInt): UInt` — the D30 greedy choice at pointer `p`, as an `AxSIZE` (`0`/`1`/`2`), 2 bits.
    - `def strbRunStart(strb: Bits): UInt` / `def strbRunEnd(strb: Bits): UInt` — lowest set bit, and highest set bit plus one, of a 16-bit line-relative strobe (the D26 store-side range).
    - `def model(start: Int, end: Int): Seq[(Int, Int)]` — the identical greedy loop as pure Scala, returning `(lineRelativeOffset, nBytes)` pairs.

**This task builds no datapath.** It builds the arithmetic and *proves it*, so that Tasks 7 and
8 are wiring exercises against an already-proved primitive rather than two independent chances
to get the same maths wrong. Spec §13's D30 obligation is exactly this shape: *"for every `off`
in 0-15 and every `size`, on both directions, the emitted sub-transactions (a) are each
naturally aligned for their own `AxSIZE`, (b) have byte extents that partition the
architectural byte range exactly — no gap, no overlap, and **nothing outside it** — and (c)
number at most three. 48 load cases and 48 store cases; small enough to enumerate rather than
sample, which is what makes 'no byte outside the access is ever touched' a proved property
rather than a spot check."*

**The rule, verbatim from spec §3.4:**

```
p = start
while (p != end) {
  sz = the largest of {4,2,1} with (p % sz == 0) && (p + sz <= end)
  emit (address = p, AxSIZE = log2(sz))
  p += sz
}
```

with `start = off` and `end = min(off + n, 16)` (D25), `p` line-relative so its low bits *are*
the emitted address's low bits (the containing line is 16-byte aligned).

**Why ≤3 is a proof, not an observation** (spec §3.4). `Size` has exactly three members —
`BYTE`, `WORD`, `LONG` (`m68k040/isa/Isa.scala`) — so `n ∈ {1,2,4}` and, after the clamp,
`end − start ≤ 4`. Within one 4-byte group, a range of length `L` at group offset `gs` covers
in: 1 piece for `(L=1, any gs)`, `(L=2, gs∈{0,2})`, `(L=4, gs=0)`; 2 pieces for `(L=2, gs=1)`,
`(L=3, gs=0)`, `(L=3, gs=1)`. No other row can occur. Both groups needing 2 pieces would
require a 6-byte range, while `end − start ≤ 4`. Hence at most 3, with no loop bound to trust
and no data-dependent iteration count.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/MmioCoverSpec.scala`:

```scala
package m68k040.socket

import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "MMIO sizing", the D30 bullet: an EXHAUSTIVE, hard check over all 48
  * load cases and all 48 store cases -- "small enough to enumerate rather than sample,
  * which is what makes 'no byte outside the access is ever touched' a proved property
  * rather than a spot check."
  *
  * Deliberately UNTAGGED so `make test-fast` runs it. The integration checks (that the real
  * DcachePlugin emits exactly these transactions) are in Tasks 7 and 8 and are
  * Verilator-tagged alongside DcacheSpec; THIS is where the maths is proved. */
class MmioCoverSpec extends AnyFunSuite {

  private val sizes = Seq(("BYTE", 1), ("WORD", 2), ("LONG", 4))

  /** Tiny DUT exposing the two hardware primitives so they can be compared against the
    * Scala model over every reachable input. */
  class CoverDut extends Component {
    val p    = in  UInt (4 bits)
    val endI = in  UInt (5 bits)
    val off  = in  UInt (4 bits)
    val n    = in  UInt (3 bits)
    val strb = in  Bits (16 bits)
    val szL2 = out UInt (2 bits)
    val cEnd = out UInt (5 bits)
    val rSta = out UInt (4 bits)
    val rEnd = out UInt (5 bits)
    szL2 := MmioCover.stepLog2(p, endI)
    cEnd := MmioCover.clampedEnd(off, n)
    rSta := MmioCover.strbRunStart(strb)
    rEnd := MmioCover.strbRunEnd(strb)
  }

  // ── The model's own properties, over every reachable (off, size) ──────────────────
  test("D25/D30 model: every load case covers exactly, aligned, in at most 3 pieces") {
    var cases = 0
    for (off <- 0 until 16; (nm, n) <- sizes) {
      val end = math.min(off + n, 16)          // D25 -- the clamp
      val cov = MmioCover.model(off, end)
      cases += 1
      assert(cov.nonEmpty, s"$nm at off=$off produced no sub-transaction")
      assert(cov.length <= MmioCover.MAX_SUBS,
        s"$nm at off=$off needed ${cov.length} sub-transactions: $cov")
      // (a) each naturally aligned for its own AxSIZE
      for ((a, sz) <- cov) {
        assert(Seq(1, 2, 4).contains(sz), s"illegal sub-size $sz in $cov")
        assert(a % sz == 0, s"sub-transaction $a/$sz is not naturally aligned ($cov)")
      }
      // (b) an exact partition of [off, end): no gap, no overlap, nothing outside
      val touched = cov.flatMap { case (a, sz) => a until (a + sz) }
      assert(touched == touched.distinct, s"overlap in $cov")
      assert(touched.sorted == (off until end).toList,
        s"$nm at off=$off covered ${touched.sorted}, wanted ${(off until end).toList}")
      // and nothing leaves the containing 16-byte line -- the C1 regression
      assert(touched.forall(b => b >= 0 && b < 16),
        s"$nm at off=$off emitted a byte outside the line: $touched")
    }
    assert(cases == 48, s"expected 48 load cases, enumerated $cases")
  }

  test("D30: the three non-representable in-group shapes each cover in exactly 2 pieces") {
    // Spec section 3.4's table. These are the COMPLETE set of in-group ranges that no
    // single naturally-aligned AXI transfer covers exactly.
    assert(MmioCover.model(1, 3) == Seq((1, 1), (2, 1)), "L=2 gs=1")
    assert(MmioCover.model(0, 3) == Seq((0, 2), (2, 1)), "L=3 gs=0")
    assert(MmioCover.model(1, 4) == Seq((1, 1), (2, 2)), "L=3 gs=1")
  }

  test("D30: spec section 3.3.2's four worked examples, exactly") {
    // 0x6/WORD -> ONE sub-transaction, AxSIZE=1 at 0x6.
    assert(MmioCover.model(6, 8) == Seq((6, 2)))
    // 0x5/WORD -> TWO, AxSIZE=0 at 0x5 and 0x6. This is the case an earlier draft carried
    // as an accepted limitation and the user directed must not be.
    assert(MmioCover.model(5, 7) == Seq((5, 1), (6, 1)))
    // 0x2/LONG -> TWO, crossing the group boundary at 4.
    assert(MmioCover.model(2, 6) == Seq((2, 2), (4, 2)))
    // 0x1/LONG -> THREE. This is the worst case; nothing reaches four.
    assert(MmioCover.model(1, 5) == Seq((1, 1), (2, 2), (4, 1)))
    // 0xD/LONG slot A, AFTER the D25 clamp -> TWO, 3 bytes, no over-read.
    assert(MmioCover.model(13, 16) == Seq((13, 1), (14, 2)))
  }

  test("a naturally-aligned access still emits exactly one transaction") {
    for (off <- Seq(0, 4, 8, 12); n <- Seq(1, 2, 4)) {
      if (off % n == 0) {
        val cov = MmioCover.model(off, off + n)
        assert(cov == Seq((off, n)), s"aligned $n@$off split into $cov -- cost on the common case")
      }
    }
    for (off <- 0 until 16) assert(MmioCover.model(off, off + 1) == Seq((off, 1)))
    for (off <- 0 until 16 by 2) assert(MmioCover.model(off, off + 2) == Seq((off, 2)))
  }

  test("D25: the clamp is what keeps a cross-line access inside its own line") {
    // Spec section 3.4: off=13, size=LONG -> [13,17) unclamped -> group index 4, i.e. a
    // sub-transaction at line offset 16, in the NEXT physical page when the line is the
    // last of its page. Every cross-page access forces exactly this geometry.
    val unclamped = 13 + 4
    assert(unclamped > 16, "premise of the C1 regression case")
    val cov = MmioCover.model(13, math.min(unclamped, 16))
    assert(cov.flatMap { case (a, sz) => a until (a + sz) }.max == 15,
      s"the clamp did not hold: $cov")
  }

  // ── The hardware primitives, against the model, exhaustively ──────────────────────
  test("hardware stepLog2 matches the model at every reachable (p, end)") {
    SimConfig.compile(new CoverDut).doSim("stepLog2", seed = 1) { dut =>
      dut.strb #= 0; dut.off #= 0; dut.n #= 1
      for (off <- 0 until 16; (_, n) <- sizes) {
        val end = math.min(off + n, 16)
        var p = off
        for ((wantA, wantSz) <- MmioCover.model(off, end)) {
          assert(p == wantA, s"model desync at off=$off")
          dut.p #= p; dut.endI #= end
          sleep(1)
          val gotSz = 1 << dut.szL2.toInt
          assert(gotSz == wantSz,
            s"hw chose $gotSz at p=$p end=$end, model chose $wantSz")
          p += gotSz
        }
        assert(p == end, s"hardware walk did not terminate at end ($p vs $end)")
      }
    }
  }

  test("hardware clampedEnd implements min(off + n, 16)") {
    SimConfig.compile(new CoverDut).doSim("clamp", seed = 2) { dut =>
      dut.p #= 0; dut.endI #= 0; dut.strb #= 0
      for (off <- 0 until 16; (_, n) <- sizes) {
        dut.off #= off; dut.n #= n
        sleep(1)
        assert(dut.cEnd.toInt == math.min(off + n, 16),
          s"clampedEnd($off,$n) = ${dut.cEnd.toInt}")
      }
    }
  }

  test("D26 store side: the strobe run is the lowest set bit to the highest plus one") {
    SimConfig.compile(new CoverDut).doSim("strb-run", seed = 3) { dut =>
      dut.p #= 0; dut.endI #= 0; dut.off #= 0; dut.n #= 1
      // Exactly the 48 store cases: storeStrbA's run for every (off, size), which
      // DcacheTypes.scala:312-320 defines as bytes off .. min(off+n,16)-1.
      var cases = 0
      for (off <- 0 until 16; (nm, n) <- sizes) {
        val end = math.min(off + n, 16)
        val v = (off until end).foldLeft(BigInt(0))((a, i) => a | (BigInt(1) << i))
        dut.strb #= v
        sleep(1)
        assert(dut.rSta.toInt == off, s"$nm off=$off: run start ${dut.rSta.toInt}")
        assert(dut.rEnd.toInt == end, s"$nm off=$off: run end ${dut.rEnd.toInt}")
        // And the cover of THAT run is the same 48-case proof as the load side.
        val cov = MmioCover.model(off, end)
        assert(cov.length <= MmioCover.MAX_SUBS)
        assert(cov.flatMap { case (a, sz) => a until (a + sz) }.sorted == (off until end).toList)
        cases += 1
      }
      assert(cases == 48, s"expected 48 store cases, enumerated $cases")
      // storeStrbB's spilled remainder, the quantity `size` no longer carries
      // (StoreQueue.scala:264 forces slot B's size to LONG whatever its true extent).
      for (off <- 13 until 16; n <- Seq(4)) {
        val spill = off + n - 16
        if (spill > 0) {
          val v = (0 until spill).foldLeft(BigInt(0))((a, i) => a | (BigInt(1) << i))
          dut.strb #= v
          sleep(1)
          assert(dut.rSta.toInt == 0 && dut.rEnd.toInt == spill,
            s"slot-B run for off=$off: [${dut.rSta.toInt},${dut.rEnd.toInt}) wanted [0,$spill)")
        }
      }
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.MmioCoverSpec" 2>&1 | tail -20
```

Expected: a compilation error — `not found: value MmioCover`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/scala/m68k040/socket/MmioCover.scala`:

```scala
package m68k040.socket

import m68k040.isa.Size
import spinal.core._
import spinal.lib._

/** The bounded exact-cover arithmetic for INHIBITED (MMIO) AXI sizing (design spec D6,
  * D25, D30, sections 3.3-3.4).
  *
  * ==The problem==
  * The D-side emits `size=4` (16 bytes) at the line base for every access, INHIBITED
  * included, and relies on WSTRB alone for lane selection. Two independent failures against
  * this SoC: `ddr_ctrl`'s `align_error(size, addr)` correctly SLVERRs a size/address
  * mismatch, and -- far worse -- byte-addressed I/O registers are selected by ADDRESS, not
  * strobe (`peripheral_bus.v:68-72` picks the byte with `addr[1:0]`), so a line-aligned
  * 16-byte MMIO read touches four registers at once and fires read-to-clear side effects on
  * three the access never named. SCC, VIA, IWM, SCSI and ADB all live in that space.
  *
  * ==Why an EXACT cover and not just a legality fix (D30)==
  * A write's `size=2` + `WSTRB=0110` carries the full "which bytes are wanted" information
  * to the slave, so a conforming slave can get it right. A covering READ carries NOTHING:
  * AXI4 reads have no byte-enable field at all, so `ARSIZE=2` at the group base is
  * indistinguishable from a genuine longword read and no slave, however well written, can
  * recover which two bytes the CPU actually wanted. A downstream-only fix for the load case
  * is impossible IN PRINCIPLE, not merely unimplemented. That is why the fix is here.
  *
  * ==The rule (D30), verbatim from spec section 3.4==
  * {{{
  *   p = start
  *   while (p != end) {
  *     sz = the largest of {4,2,1} with (p % sz == 0) && (p + sz <= end)
  *     emit (address = p, AxSIZE = log2(sz))
  *     p += sz
  *   }
  * }}}
  * `p` is LINE-RELATIVE and the containing line is 16-byte aligned, so `p`'s low bits ARE
  * the emitted address's low bits: the alignment test needs no separate address arithmetic.
  *
  * ==Why it is bounded at three, as a proof rather than an observation==
  * `Size` has exactly three members, so `n` is in {1,2,4}, and after the D25 clamp
  * `end - start <= 4`. Within one 4-byte group a range of length `L` at group offset `gs`
  * needs at most 2 pieces, and the complete set of 2-piece rows is `(L=2,gs=1)`,
  * `(L=3,gs=0)`, `(L=3,gs=1)`. Both groups needing 2 would require a 6-byte range. Hence at
  * most 3, always -- no loop bound to trust, no data-dependent iteration count. The earlier
  * "a fix would need a general N-way byte sequencer" objection was wrong because the thing
  * it avoided does not exist.
  *
  * ==Two range derivations, one cover (D5, D24, D26)==
  * LOADS and `useStrb = false` stores derive `[start, end)` from `(paddr[3:0], size)`.
  * A `useStrb = true` store slot derives it from the RUN OF SET BITS in its 16-bit
  * line-relative strobe, and **`size` is not an input on that path**: `StoreQueue.scala:264`
  * drives slot B's `size` as a flat `Size.LONG` regardless of its true 1-3-byte extent, and
  * slot B's `paddr` is the next line base, so `paddr[3:0] = 0`. Deriving `[off, off+n)`
  * there would read `[0,4)` for a slot whose real extent is `[0,1)` -- and this cover would
  * then cover that over-wide range EXACTLY, and exactly wrongly, naming up to three
  * peripheral registers the architectural access never touched. The strobe is the only
  * field on `DStoreCmd` that still carries the true extent.
  *
  * ==Ordering hazard with the section 2 permutation (D5)==
  * The store derivation runs on the CORE-SIDE, pre-permutation strobe. Nibble reversal
  * preserves popcount and contiguity but NOT the offset a run starts at, so deriving from
  * the post-permutation strobe would produce the mirror-image address. This code therefore
  * lives inside the D-cache and `SocketByteOrder` is applied strictly afterwards, at the
  * socket boundary. The two transforms are order-dependent; see `SocketByteOrder`'s doc
  * comment for the other half of this statement. */
object MmioCover {

  /** The proved upper bound on sub-transactions per INHIBITED access. */
  val MAX_SUBS = 3

  /** 1 / 2 / 4 for BYTE / WORD / LONG. */
  def sizeBytes(size: Size.C): UInt = {
    val n = UInt(3 bits); n := U(1, 3 bits)
    switch(size) {
      is(Size.BYTE) { n := U(1, 3 bits) }
      is(Size.WORD) { n := U(2, 3 bits) }
      is(Size.LONG) { n := U(4, 3 bits) }
    }
    n
  }

  /** D25: `end = min(off + n, 16)`.
    *
    * THE CLAMP IS NOT DEFENSIVE PROGRAMMING; it is load-bearing, and omitting it creates a
    * wrong-address bus transaction that does not exist today. `LsEuPlugin.scala:759` issues
    * BOTH slots of a cross-boundary split at the FULL original size
    * (`Mux(useSplitCmd, llReg.size, alignedCmd.size)` has no slot-B arm) while switching
    * only the address, so slot A arrives with `off + n > 16`. Deriving group indices from
    * that unclamped range yields a sub-transaction at line offset 16 -- the NEXT line -- and
    * when the containing line is the last of its page, that is the first line of the next
    * PHYSICAL PAGE, an address the core never translated. And it is not a corner of a
    * corner: `lineOff = pageOff mod 16`, so `pageOff + n > 4096` FORCES `lineOff + n > 16`
    * (`LsEuPlugin.scala:441-443`) -- every cross-page access is one of these. */
  def clampedEnd(off: UInt, n: UInt): UInt = {
    val sum = off.resize(5 bits) +^ n.resize(5 bits)     // widening: off=15,n=4 -> 19
    val e = UInt(5 bits)
    e := Mux(sum > U(16, 6 bits), U(16, 5 bits), sum.resize(5 bits))
    e
  }

  /** The D30 greedy choice at line-relative pointer `p` against clamped `end`, expressed as
    * an `AxSIZE` (0 = 1 byte, 1 = 2 bytes, 2 = 4 bytes).
    *
    * Written as two independent predicates with last-assignment-wins rather than a
    * priority chain, so the "largest that fits and is aligned" rule is literal. */
  def stepLog2(p: UInt, end: UInt): UInt = {
    val pw   = p.resize(5 bits)
    val can2 = (p(0) === False) && ((pw +^ U(2, 5 bits)) <= end.resize(6 bits))
    val can4 = (p(1 downto 0) === U(0, 2 bits)) && ((pw +^ U(4, 5 bits)) <= end.resize(6 bits))
    val r = UInt(2 bits)
    r := U(0, 2 bits)
    when(can2) { r := U(1, 2 bits) }
    when(can4) { r := U(2, 2 bits) }
    r
  }

  /** Lowest set bit of a 16-bit line-relative strobe. Zero when the strobe is empty, which
    * the callers never present (a drained store always strobes at least one byte). */
  def strbRunStart(strb: Bits): UInt = {
    val s = UInt(4 bits)
    s := U(0, 4 bits)
    // Iterate DOWNWARD so the lowest set index is assigned last and therefore wins.
    for (i <- 15 to 0 by -1) when(strb(i)) { s := U(i, 4 bits) }
    s
  }

  /** Highest set bit of a 16-bit line-relative strobe, PLUS ONE (an exclusive end).
    *
    * The run is contiguous by construction -- a split slot is a contiguous sub-range of one
    * architectural access (`DcacheTypes.scala:312-320` / `:335-343`). A hypothetically
    * sparse strobe stays SAFE rather than correct: the cover spans the run's convex hull,
    * and each sub-transaction still carries the exact strobe bits, so no byte is written
    * that was not strobed. */
  def strbRunEnd(strb: Bits): UInt = {
    val e = UInt(5 bits)
    e := U(0, 5 bits)
    // Iterate UPWARD so the highest set index is assigned last and therefore wins.
    for (i <- 0 to 15) when(strb(i)) { e := U(i + 1, 5 bits) }
    e
  }

  /** The identical greedy loop as pure Scala, returning `(lineRelativeOffset, nBytes)`.
    *
    * Kept beside the hardware for the same reason `SocketByteOrder.modelData` is: it is the
    * golden model the exhaustive 48+48-case proof compares against, and one definition
    * cannot drift from itself. */
  def model(start: Int, end: Int): Seq[(Int, Int)] = {
    require(0 <= start && start < end && end <= 16,
      s"MmioCover.model: [$start,$end) is not a non-empty range inside one 16-byte line")
    val out = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    var p = start
    while (p != end) {
      val sz = if (p % 4 == 0 && p + 4 <= end) 4
               else if (p % 2 == 0 && p + 2 <= end) 2
               else 1
      out += ((p, sz))
      p += sz
    }
    require(out.length <= MAX_SUBS,
      s"MmioCover.model: [$start,$end) needed ${out.length} sub-transactions, " +
      s"which contradicts the spec's proof that the bound is $MAX_SUBS")
    out.toSeq
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.MmioCoverSpec" 2>&1 | tail -20
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 8, failed 0`, with the two exhaustive tests reporting 48 cases
each; `test-fast` rises by 8.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/MmioCover.scala \
        src/test/scala/m68k040/socket/MmioCoverSpec.scala
git commit -m "$(cat <<'EOF'
socket(D6/D25/D30): bounded exact-cover MMIO sizing math, proved exhaustively

Builds no datapath. Builds the arithmetic Tasks 7 and 8 both consume, and proves
it over all 48 load cases and all 48 store cases -- which is what makes "no byte
outside the access is ever touched" a proved property rather than a spot check,
and what stops the load path and the store path being two independent chances to
get the same maths wrong.

Three things the proof pins that prose alone would not:

* D25's clamp is load-bearing, not defensive. LsEuPlugin.scala:759 issues BOTH
  slots of a cross-boundary split at the FULL original size while switching only
  the address, so slot A arrives with off+n > 16. Without the clamp the
  derivation emits a sub-transaction at line offset 16 -- and since
  pageOff+n > 4096 FORCES lineOff+n > 16, every cross-page access lands that
  transaction in the next PHYSICAL page, an address the core never translated.
  That would have been a new wrong-address bus transaction introduced by the fix.

* D30's cover is exact, so the three in-group shapes no naturally-aligned AXI
  transfer can express -- (L=2,gs=1), (L=3,gs=0), (L=3,gs=1) -- each split into
  two pieces instead of being rounded up to a covering-but-over-wide transfer.
  The canonical case, a WORD at group offset 1, is what an earlier draft carried
  as an accepted limitation.

* The bound is 3 by proof, not by observation: Size has three members, the clamp
  gives end-start <= 4, a group needs at most 2 pieces, and both groups needing
  2 would require 6 bytes. The "a fix would need a general N-way byte sequencer"
  objection was wrong because the thing it avoided does not exist.

The store range comes from the STROBE RUN, never from `size`: StoreQueue.scala:264
forces slot B's size to a flat LONG whatever its true 1-3-byte extent, and slot
B's paddr is the next line base, so a size-derived range would read [0,4) for a
slot whose real extent is [0,1) -- and this cover would then cover that over-wide
range exactly, and exactly wrongly.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: INHIBITED **load** sequencer in `DcachePlugin` (D4, D24, D25, D30 load path)

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` (the `fsm` `REFILL` state at `:1231-1291`, plus the miss-latch site)
- Test: `src/test/scala/m68k040/cache/MmioLoadSizingSpec.scala`

**Interfaces:**
- Consumes: `MmioCover.{sizeBytes, clampedEnd, stepLog2, MAX_SUBS}` (Task 6).
- Produces: an INHIBITED load emits an exact naturally-aligned cover of its (clamped) byte range on `axi.ar`, merging each response into `missLine` at its own byte offset before the existing `REPLAY` path runs. The cacheable path is **bit-identical**.

**What exists today** (`DcachePlugin.scala:1231-1291`, verbatim, abridged to the load of it):

```scala
      REFILL.whenIsActive {
        busy := True
        val lineBase = (missPaddr(31 downto offBits) ## U(0, offBits bits)).asUInt
        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(AxiIds.dRefill(0), AxiIds.ID_W bits)
          axi.ar.payload.len   := U(0, 8 bits)
          axi.ar.payload.size  := U(4, 3 bits)  // 16 bytes
          axi.ar.payload.burst := Axi4.burst.INCR
          when(axi.ar.ready) { arSent := True }
        }
        refillNeedsStoreDrain := axi.r.valid && refillWriteHold
        axi.r.ready := !refillWriteHold
        when(axi.r.fire) {
          val respErr    = axi.r.payload.resp =/= Axi4.resp.OKAY
          val doAllocate = !respErr && (missCmode =/= CacheMode.INHIBITED)
          when(doAllocate) { /* ... array write, unchanged ... */ }
          missLine  := axi.r.payload.data
          missFault := respErr
          goto(REPLAY)
        }
      }
```

An INHIBITED load *always* reaches `REFILL`, because `ldS1Cacheable`
(`DcachePlugin.scala:353`) forces every hit bit low by construction. `REPLAY`'s
`elsewhen(missCmode === CacheMode.INHIBITED)` arm at `:1317` then delivers `inhibitedResp`
once, and `loadRspPort.payload.data`'s existing extraction at `:499-502`
(`DcacheByteLane.extract(missLine, missOff, missSize)`) is what turns `missLine` into the
architectural value. **That extraction is unchanged** — it reads the same byte offsets whether
`missLine` was filled by one 16-byte beat or by up to three narrow ones.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/cache/MmioLoadSizingSpec.scala`:

```scala
package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import m68k040.socket.MmioCover
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "MMIO sizing", the load half. The 48-case D30 proof lives in
  * `m68k040.socket.MmioCoverSpec` (untagged, `test-fast`); THIS suite proves the real
  * DcachePlugin emits exactly what that proof describes.
  *
  * Tagged VerilatorTest to match `DcacheSpec`, whose DUT this mirrors: `make test-fast`
  * excludes it, `make test-verilator` and `make test` run it. Task 14's gate runs both. */
class MmioLoadSizingSpec extends AnyFunSuite {

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val probe  = new DcacheProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe)) }
  }

  lazy val compiled = M68kSim().withVerilator.compile(new Dut)

  /** Every AR the D-cache presented, as (address, AxSIZE), in issue order. */
  private def watchAr(dut: Dut): scala.collection.mutable.ArrayBuffer[(Long, Int)] = {
    val log = scala.collection.mutable.ArrayBuffer[(Long, Int)]()
    val axi = dut.dcache.logic.axi
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          log += ((axi.ar.payload.addr.toLong, axi.ar.payload.size.toInt))
      }
    }
    log
  }

  private def load(dut: Dut, vaddr: Long, size: SpinalEnumElement[Size.type]): BigInt = {
    val p = dut.probe.logic
    p.loadCmdIn.valid #= true
    p.loadCmdIn.payload.vaddr #= vaddr
    p.loadCmdIn.payload.paddr #= vaddr
    p.loadCmdIn.payload.size  #= size
    p.loadCmdIn.payload.cacheMode #= CacheMode.INHIBITED
    p.loadCmdIn.payload.token #= 0
    dut.clockDomain.waitSamplingWhere(p.loadCmdIn.ready.toBoolean && p.loadCmdIn.valid.toBoolean)
    p.loadCmdIn.valid #= false
    p.loadProbeIn.valid #= false
    p.loadProbeCancelIn.valid #= false
    p.loadProbeCancelIn.payload.all #= false
    dut.clockDomain.waitSamplingWhere(p.loadRspOut.valid.toBoolean)
    p.loadRspOut.payload.data.toBigInt
  }

  private val BASE = 0x0400_0000L      // inside AxiMemModel.decoded, so no injected DECERR

  test("D30: every INHIBITED load emits an exact naturally-aligned cover", VerilatorTest) {
    compiled.doSim("mmio-load-cover", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      for (i <- 0 until 4096) mem.pokeByte(BASE + i, ((i * 5 + 0x23) & 0xff))
      dut.clockDomain.waitSampling(8)
      val log = watchAr(dut)
      for (off <- 0 until 16; (sz, nm, n) <- Seq((Size.BYTE, "BYTE", 1),
                                                 (Size.WORD, "WORD", 2),
                                                 (Size.LONG, "LONG", 4))) {
        // Each case gets its own line so `off` is the line offset, and its own 16-byte
        // line so a previous case's transactions cannot be confused with this one's.
        val lineBase = BASE + 0x100L * (off * 3 + n)
        for (i <- 0 until 32) mem.pokeByte(lineBase + i, ((lineBase + i) * 5 + 0x23).toInt & 0xff)
        log.clear()
        val got = load(dut, lineBase + off, sz)
        dut.clockDomain.waitSampling(8)

        val want = MmioCover.model(off, math.min(off + n, 16))
        assert(log.length == want.length,
          f"$nm at off=$off: emitted ${log.length} sub-transactions ${log.toList}, " +
          f"the cover says ${want.length} ($want)")
        assert(log.length <= MmioCover.MAX_SUBS, s"more than ${MmioCover.MAX_SUBS} sub-transactions")
        for (((gotAddr, gotSz), (wantOff, wantBytes)) <- log.zip(want)) {
          assert(gotAddr == lineBase + wantOff,
            f"$nm off=$off: address 0x$gotAddr%08X, wanted 0x${lineBase + wantOff}%08X")
          assert((1 << gotSz) == wantBytes,
            s"$nm off=$off at $wantOff: AxSIZE $gotSz (${1 << gotSz} B), wanted $wantBytes B")
          // AXI's own alignment rule, and spec section 13's hard check.
          assert(gotAddr % (1 << gotSz) == 0,
            f"$nm off=$off: 0x$gotAddr%08X is not naturally aligned for AxSIZE $gotSz")
          // Nothing outside the architectural access.
          val lo = wantOff; val hi = wantOff + wantBytes
          assert(lo >= off && hi <= math.min(off + n, 16),
            s"$nm off=$off: sub-range [$lo,$hi) leaves the access")
        }
        // And the assembled value is still right.
        val exp = (0 until n).foldLeft(BigInt(0))((a, i) =>
          (a << 8) | BigInt(((lineBase + off + i) * 5 + 0x23).toInt & 0xff))
        assert(got == exp, f"$nm off=$off: data 0x$got%08X, wanted 0x$exp%08X")
      }
    }
  }

  test("D25: an off=12..15 access never emits an address outside its own line", VerilatorTest) {
    // The C1 regression, directed and specifically adversarial (spec section 13's D25 bullet).
    compiled.doSim("mmio-load-clamp", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      for (i <- 0 until 8192) mem.pokeByte(BASE + i, ((i * 7 + 1) & 0xff))
      dut.clockDomain.waitSampling(8)
      val log = watchAr(dut)
      // Place the line at the LAST line of a 4 KiB page, so an escaping transaction would
      // land in the next physical page -- the case the spec says would otherwise be
      // introduced by the fix rather than found by it.
      val lineBase = BASE + 0x1000L - 16
      for (off <- 12 until 16; (sz, n) <- Seq((Size.WORD, 2), (Size.LONG, 4))) {
        log.clear()
        load(dut, lineBase + off, sz)
        dut.clockDomain.waitSampling(8)
        assert(log.nonEmpty, s"no transaction for off=$off n=$n")
        for ((a, s) <- log) {
          assert(a >= lineBase && a < lineBase + 16,
            f"off=$off n=$n emitted 0x$a%08X, outside the line [0x$lineBase%08X, +16)")
          assert(a + (1 << s) <= lineBase + 16,
            f"off=$off n=$n: a ${1 << s}-byte transfer at 0x$a%08X runs past the line")
          assert(a % (1 << s) == 0, f"0x$a%08X not aligned for AxSIZE $s")
        }
      }
    }
  }

  test("the CACHEABLE path is unchanged: one 16-byte AR at the line base", VerilatorTest) {
    compiled.doSim("cacheable-unchanged", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      for (i <- 0 until 4096) mem.pokeByte(BASE + i, ((i * 3 + 9) & 0xff))
      dut.clockDomain.waitSampling(8)
      val log = watchAr(dut)
      val p = dut.probe.logic
      for (off <- Seq(0, 1, 5, 13)) {
        log.clear()
        val addr = BASE + 0x800L + off
        p.loadCmdIn.valid #= true
        p.loadCmdIn.payload.vaddr #= addr
        p.loadCmdIn.payload.paddr #= addr
        p.loadCmdIn.payload.size  #= Size.WORD
        p.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        p.loadCmdIn.payload.token #= 0
        dut.clockDomain.waitSamplingWhere(p.loadCmdIn.ready.toBoolean && p.loadCmdIn.valid.toBoolean)
        p.loadCmdIn.valid #= false
        dut.clockDomain.waitSamplingWhere(p.loadRspOut.valid.toBoolean)
        dut.clockDomain.waitSampling(8)
        if (log.nonEmpty) {                       // a hit emits nothing at all
          assert(log.length == 1, s"cacheable off=$off emitted ${log.length} transactions")
          assert(log.head._2 == 4, s"cacheable AxSIZE ${log.head._2}, wanted 4")
          assert(log.head._1 % 16 == 0, "cacheable AR not at the 16-byte line base")
        }
      }
    }
  }

  test("a non-OKAY response on ANY sub-transaction raises the existing fault", VerilatorTest) {
    compiled.doSim("mmio-load-fault", seed = 4) { dut =>
      dut.clockDomain.forkStimulus(10)
      // injectBusErrors DECERRs anything outside AxiMemModel.decoded (tasks #189/#211).
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false,
                          injectBusErrors = true))
      dut.clockDomain.waitSampling(8)
      val p = dut.probe.logic
      val bad = 0x9000_0005L                       // top nibble 9 -> not decoded -> DECERR
      p.loadCmdIn.valid #= true
      p.loadCmdIn.payload.vaddr #= bad
      p.loadCmdIn.payload.paddr #= bad
      p.loadCmdIn.payload.size  #= Size.WORD       // off=5 -> TWO sub-transactions
      p.loadCmdIn.payload.cacheMode #= CacheMode.INHIBITED
      p.loadCmdIn.payload.token #= 0
      dut.clockDomain.waitSamplingWhere(p.loadCmdIn.ready.toBoolean && p.loadCmdIn.valid.toBoolean)
      p.loadCmdIn.valid #= false
      dut.clockDomain.waitSamplingWhere(p.loadRspOut.valid.toBoolean)
      assert(p.loadRspOut.payload.fault.toBoolean,
        "a DECERR on a split INHIBITED load did not raise busFaultResp")
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.cache.MmioLoadSizingSpec" 2>&1 | tail -30
```

Expected: the first test fails with `BYTE at off=1: emitted 1 sub-transactions
List((..., 4)), the cover says 1 (List((1,1)))` — i.e. **one AR of AxSIZE 4 at the line base**
where the cover wants AxSIZE 0 at offset 1. Record that output: it is the concrete statement
of the G5 gap.

- [ ] **Step 3a: Latch the sequencer's range at the miss**

In `src/main/scala/m68k040/cache/DcachePlugin.scala`, add two registers beside the existing
miss-latch registers (near `:183-195`, immediately after `val missSize  = Reg(Size())`):

```scala
    // D6/D25/D30: the INHIBITED sub-transaction sequencer's cursor and its CLAMPED end.
    // Both are LINE-RELATIVE, so `subP`'s low bits are the emitted address's low bits and
    // the natural-alignment test needs no separate address arithmetic (spec 3.4).
    val subP   = Reg(UInt(offBits bits)) init 0
    val subEnd = Reg(UInt(offBits + 1 bits)) init 0
```

Then, at the **single** site that latches the miss context — locate it with
`grep -n 'missCmode :=' src/main/scala/m68k040/cache/DcachePlugin.scala`, which has exactly one
assignment site inside the `fsm`'s `IDLE` arm — add, immediately after that line:

```scala
          // D25's clamp, applied where the range ENTERS the sequencer rather than where it
          // is consumed: `end = min(off + n, 16)`. The incoming range is NOT already
          // line-contained -- LsEuPlugin.scala:759 sends both split slots at the full
          // original size -- so trusting it would emit a transaction one line past the
          // access, which for a cross-page access is a page the core never translated.
          subP   := missPaddrIn(offBits - 1 downto 0)
          subEnd := m68k040.socket.MmioCover.clampedEnd(
                      missPaddrIn(offBits - 1 downto 0),
                      m68k040.socket.MmioCover.sizeBytes(missSizeIn))
```

where `missPaddrIn` / `missSizeIn` are whatever expressions the surrounding code already
assigns into `missPaddr` / `missSize` on that same line (do **not** read the registers — they
have not been written yet on that cycle).

- [ ] **Step 3b: Sequence the AR and merge the responses in `REFILL`**

Replace the body of `REFILL.whenIsActive` (`DcachePlugin.scala:1231-1291`) with:

```scala
      REFILL.whenIsActive {
        busy := True
        val lineBase = (missPaddr(31 downto offBits) ## U(0, offBits bits)).asUInt

        // ── D4/D24/D30: INHIBITED accesses get a REAL AxSIZE and a byte-granular address
        // The cacheable path below is bit-identical to before: one len=0/size=4 beat at the
        // line base. This arm is reachable only when `missCmode === INHIBITED`, and an
        // INHIBITED load ALWAYS reaches REFILL because `ldS1Cacheable` (:353) forces every
        // hit bit low by construction.
        //
        // WHY THE FIX IS HERE AND NOT IN LsEuPlugin's split predicate: `s1CrossLine` /
        // `s1CrossPage` are computed at S1 from `s1Va` (:439-444), BEFORE translation
        // resolves, so `cacheMode` is not known there. Making the predicate
        // cacheMode-independent would put every misaligned CACHEABLE access on the rare
        // two-pass replay FSM and cost real IPC on the hot path.
        val inhib   = missCmode === CacheMode.INHIBITED
        val subLog2 = m68k040.socket.MmioCover.stepLog2(subP, subEnd)
        val subBytes = (U(1, 4 bits) |<< subLog2).resize(4 bits)     // 1, 2 or 4
        val subAddr = (missPaddr(31 downto offBits) ## subP).asUInt
        val subLast = (subP +^ subBytes) === subEnd

        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := Mux(inhib, subAddr, lineBase)
          axi.ar.payload.id    := U(AxiIds.dRefill(0), AxiIds.ID_W bits)
          axi.ar.payload.len   := U(0, 8 bits)
          axi.ar.payload.size  := Mux(inhib, subLog2.resize(3 bits), U(4, 3 bits))
          axi.ar.payload.burst := Axi4.burst.INCR
          when(axi.ar.ready) { arSent := True }
        }

        refillNeedsStoreDrain := axi.r.valid && refillWriteHold
        axi.r.ready := !refillWriteHold
        when(axi.r.fire) {
          val respErr    = axi.r.payload.resp =/= Axi4.resp.OKAY
          val doAllocate = !respErr && (missCmode =/= CacheMode.INHIBITED)
          when(doAllocate) {
            for (w <- 0 until ways) when(victimWay === U(w, wayBits bits)) {
              wrEn(w)    := True
              wrSet(w)   := missSet
              wrData(w)  := axi.r.payload.data
              wrTagEn(w) := True
              wrTag(w)   := missTag
              valids(w)(missSet) := True
              dirtys(w)(missSet) := False
            }
            victim(missSet) := victim(missSet) + 1
          }
          when(inhib) {
            // Merge THIS sub-transaction's bytes into `missLine` at their own line offsets.
            // A narrow AXI read returns its bytes in the lanes matching its own address, so
            // the returned byte at line offset i is at data[8i +: 8] -- the same index it
            // occupies in `missLine`. No shifting, and the existing extraction at :499-502
            // (`DcacheByteLane.extract(missLine, missOff, missSize)`) stays correct
            // unchanged, whether missLine was filled by one 16-byte beat or by three narrow
            // ones.
            val rBytes   = axi.r.payload.data.subdivideIn(8 bits)
            val curBytes = missLine.subdivideIn(8 bits)
            val newBytes = Vec(Bits(8 bits), 1 << offBits)
            for (i <- 0 until (1 << offBits)) {
              val inSub = (U(i, offBits + 1 bits) >= subP) &&
                          (U(i, offBits + 1 bits) < (subP +^ subBytes))
              newBytes(i) := Mux(inSub, rBytes(i), curBytes(i))
            }
            missLine  := newBytes.asBits
            // Sticky across the sequence: a non-OKAY response on ANY sub-transaction raises
            // the existing busFaultResp, exactly as a single-beat refill error does today.
            missFault := missFault || respErr
            when(subLast || respErr) {
              goto(REPLAY)
            } otherwise {
              subP   := (subP +^ subBytes).resize(offBits bits)
              arSent := False                      // arm the next sub-transaction
            }
          } otherwise {
            missLine  := axi.r.payload.data
            missFault := respErr
            goto(REPLAY)
          }
        }

        // Spec section 13 turns the D30 cases into a HARD check: an INHIBITED
        // sub-transaction whose byte extent leaves the architectural access, or whose
        // AxADDR is not naturally aligned for its own AxSIZE, is a BUG, not a case to
        // absorb.
        GenerationFlags.simulation {
          when(inhib && axi.ar.valid) {
            assert((subP +^ subBytes) <= subEnd,
              "DcachePlugin: an INHIBITED sub-transaction runs past the access", FAILURE)
            assert(subP >= missPaddr(offBits - 1 downto 0),
              "DcachePlugin: an INHIBITED sub-transaction starts before the access", FAILURE)
            assert((subP.asBits & ((subBytes - 1).asBits.resize(offBits))) === B(0, offBits bits),
              "DcachePlugin: an INHIBITED AxADDR is not naturally aligned for its AxSIZE",
              FAILURE)
          }
        }
      }
```

**Note on `missFault`.** It must be cleared when the miss is latched, since it is now
accumulated across the sequence rather than assigned once. Add `missFault := False` beside the
`subP`/`subEnd` initialisation of Step 3a if the surrounding code does not already clear it
there; `grep -n 'missFault' src/main/scala/m68k040/cache/DcachePlugin.scala` shows every site.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.cache.MmioLoadSizingSpec" 2>&1 | tail -30
~/sbt/bin/sbt "testOnly m68k040.cache.DcacheSpec" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 4, failed 0` for the new suite; **`DcacheSpec` still fully passes**
(the cacheable path is the regression that matters here); `check_socket_netlist.py` passes;
`test-fast` unchanged in count (the new suite is Verilator-tagged) and still green.

**Report explicitly** whether this task is expected to move timing: it adds a small
combinational cover step and a 16-way byte mux in the `REFILL` `r.fire` arm, which is on the
refill path, not the hit path. Say so.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/cache/DcachePlugin.scala \
        src/test/scala/m68k040/cache/MmioLoadSizingSpec.scala
git commit -m "$(cat <<'EOF'
socket(D4/D24/D25/D30): exact-cover sub-transaction sequencer on INHIBITED loads

Before this commit an INHIBITED load issued ONE 16-byte AR at the line base and
byte-lane-extracted afterwards. Against this SoC that is two separate failures:
ddr_ctrl's align_error correctly SLVERRs a size/address mismatch, and -- far
worse -- byte-addressed I/O registers are selected by ADDRESS, not strobe
(peripheral_bus.v:68-72 picks the byte with addr[1:0]), so a 16-byte MMIO read
touches four registers at once and fires read-to-clear side effects on three the
access never named. SCC, VIA, IWM, SCSI and ADB all live there.

The load side is where the fix MUST be, not merely where it is convenient. A
covering write carries its own correction -- size=2 + WSTRB=0110 still says "bytes
1 and 2 only" on the wire. A covering READ carries nothing: AXI4 reads have no
byte-enable field, so ARSIZE=2 at the group base is indistinguishable from a
genuine longword read and no slave can recover which bytes were wanted. A
downstream-only fix is impossible in principle here.

Not in LsEuPlugin's split predicate either: s1CrossLine/s1CrossPage are computed
at S1 before translation resolves, so cacheMode is not known there, and making
the predicate cacheMode-independent would put every misaligned CACHEABLE access
on the two-pass replay FSM and cost real IPC on the hot path.

D25's clamp is applied where the range ENTERS the sequencer. The incoming range
is not line-contained (LsEuPlugin.scala:759 sends both split slots at the full
original size), so trusting it emits a transaction one line past the access --
and since pageOff+n > 4096 forces lineOff+n > 16, every cross-page access lands
that in a page the core never translated. The directed test places the line at
the last line of a page precisely to catch that.

The cacheable path is bit-identical, DcacheByteLane.extract at :499-502 is
untouched (a narrow read returns its bytes in the lanes matching its own address,
so the merge index is the same), and a non-OKAY response on ANY sub-transaction
raises the existing busFaultResp.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: INHIBITED **store** sequencer in `DcachePlugin` (D5, D26, D30 store path)

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` (the S3 latch at `:1829-1859`, the AW/W driver at `:1909-1924`, the B ack at `:1957-1959`)
- Test: `src/test/scala/m68k040/cache/MmioStoreSizingSpec.scala`

**Interfaces:**
- Consumes: `MmioCover.{sizeBytes, clampedEnd, stepLog2, strbRunStart, strbRunEnd, MAX_SUBS}` (Task 6).
- Produces: an INHIBITED store emits an exact naturally-aligned cover, each sub-transaction carrying **the strobe bits for its own bytes only**, `storeAck` asserted only after the **last** `B` handshake, and `storeErrReg` the OR of all responses. The cacheable / write-through path is **bit-identical**.

**What exists today:**

- `:1839-1841` — the S3 latch. `stAddrReg` is latched **line-aligned**:
  `stAddrReg := (stS3Payload.paddr(31 downto offBits) ## U(0, offBits bits)).asUInt`.
- `:1909-1924` — a single-beat AW/W driver at `size=4` with the full merged 16-bit strobe.
- `:1957-1959` — `storeBAck` (fail-closed on `AxiIds.D_STORE`), `storeErrReg`, `storeAckReg`.

**The range derivation, and the thing the implementer must not do (D26, spec §3.3.1).** For a
`useStrb = false` store the range is `[paddr[3:0], paddr[3:0] + sizeBytes(size))`; for a
`useStrb = true` split slot the range is **the run of set bits in the 16-bit line-relative
strobe**, and *`size` is not an input on that path and must not be fallen back to*.
`StoreQueue.scala:264` drives slot B's `size` as a flat `Size.LONG()` regardless of its true
1-3-byte extent, and slot B's `paddr` is the next **line base** (`s1AddrB = (s1Va & ~15) + 16`,
`LsEuPlugin.scala:448`) so `paddr[3:0] = 0`. A size-derived range would read `[0,4)` for a slot
whose real extent is `[0,1)` — and D30's cover would then cover that over-wide range
*exactly*, and exactly wrongly, naming up to three peripheral registers the architectural
access never touched.

**Why the ack must move (spec §3.4, "Stores").** *"issue AW/W/B for each sub-transaction in
turn, and assert `storeAck` only after the **last** `B` handshake. `storeErrReg` is the OR of
all responses. The existing fail-closed `=== AxiIds.D_STORE` B demux is preserved for each."*
`DcachePlugin.scala:1941-1946` explains why a premature ack is not cosmetic: `sq.io.drainAck
:= dcache.storeAck`, so an early ack pops the StoreQueue head before that store's own AW/W
have been accepted, letting the next drain re-kick the shared
`stAddrReg`/`stMergeReg`/`stStrbReg` mid-flight. The plugin's own sim asserts at `:1972-1992`
already enforce a one-ack-per-store-descriptor contract; this sequencer must preserve it while
looping over up to three physical `B` responses.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/cache/MmioStoreSizingSpec.scala`:

```scala
package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import m68k040.socket.MmioCover
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "MMIO sizing", the store half, plus the "touches exactly the registers
  * the access names, each exactly once, and no others" obligation -- checked against a
  * byte-write observer rather than a final memory image, so a byte written TWICE is caught.
  *
  * VerilatorTest-tagged to match DcacheSpec (whose DUT this mirrors). The 48-case cover
  * proof itself is in the untagged m68k040.socket.MmioCoverSpec. */
class MmioStoreSizingSpec extends AnyFunSuite {

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val probe  = new DcacheProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe)) }
  }

  lazy val compiled = M68kSim().withVerilator.compile(new Dut)
  private val BASE = 0x0400_0000L

  /** Every AW the D-cache presented, as (address, AxSIZE, WSTRB), in issue order. */
  private def watchAw(dut: Dut) = {
    val log = scala.collection.mutable.ArrayBuffer[(Long, Int, BigInt)]()
    val axi = dut.dcache.logic.axi
    var pendingAddr = -1L; var pendingSize = -1
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (axi.aw.valid.toBoolean && axi.aw.ready.toBoolean) {
          pendingAddr = axi.aw.payload.addr.toLong; pendingSize = axi.aw.payload.size.toInt
        }
        if (axi.w.valid.toBoolean && axi.w.ready.toBoolean && pendingAddr >= 0) {
          log += ((pendingAddr, pendingSize, axi.w.payload.strb.toBigInt))
          pendingAddr = -1L
        }
      }
    }
    log
  }

  private def store(dut: Dut, paddr: Long, data: BigInt, size: SpinalEnumElement[Size.type],
                    useStrb: Boolean = false, strb: BigInt = 0, lineData: BigInt = 0): Unit = {
    val p = dut.probe.logic
    p.storeIn.valid #= true
    p.storeIn.payload.paddr #= paddr
    p.storeIn.payload.data  #= data
    p.storeIn.payload.size  #= size
    p.storeIn.payload.useStrb #= useStrb
    p.storeIn.payload.strb    #= strb
    p.storeIn.payload.lineData #= lineData
    p.storeIn.payload.cacheMode #= CacheMode.INHIBITED
    p.storeIn.payload.precise #= false
    dut.clockDomain.waitSamplingWhere(p.storeIn.ready.toBoolean && p.storeIn.valid.toBoolean)
    p.storeIn.valid #= false
    dut.clockDomain.waitSampling(40)
  }

  test("D30: every INHIBITED store emits an exact naturally-aligned cover", VerilatorTest) {
    compiled.doSim("mmio-store-cover", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      val log = watchAw(dut)
      // Every byte the model actually commits, so a byte written TWICE is caught.
      val writes = scala.collection.mutable.ArrayBuffer[Long]()
      mem.setByteWriteObserver((a, _) => writes += a)

      for (off <- 0 until 16; (sz, nm, n) <- Seq((Size.BYTE, "BYTE", 1),
                                                 (Size.WORD, "WORD", 2),
                                                 (Size.LONG, "LONG", 4))) {
        val lineBase = BASE + 0x100L * (off * 3 + n)
        log.clear(); writes.clear()
        store(dut, lineBase + off, BigInt("11223344", 16), sz)

        val want = MmioCover.model(off, math.min(off + n, 16))
        assert(log.length == want.length,
          s"$nm off=$off: emitted ${log.length} sub-transactions ${log.toList}, cover says $want")
        assert(log.length <= MmioCover.MAX_SUBS)
        for (((gotAddr, gotSz, gotStrb), (wantOff, wantBytes)) <- log.zip(want)) {
          assert(gotAddr == lineBase + wantOff,
            f"$nm off=$off: address 0x$gotAddr%08X, wanted 0x${lineBase + wantOff}%08X")
          assert((1 << gotSz) == wantBytes, s"$nm off=$off: AxSIZE $gotSz, wanted $wantBytes B")
          assert(gotAddr % (1 << gotSz) == 0, s"$nm off=$off: AW not naturally aligned")
          // Spec 3.3.1: "every asserted WSTRB bit lies inside the addressed transfer" --
          // the correction v1 fails (axi_narrow_to_wide.v:43 lists 0110 as an awsize=1 case
          // and then clears address bit 0, asserting a strobe bit OUTSIDE the transfer).
          val hot = (0 until 16).filter(i => ((gotStrb >> i) & 1) == 1)
          assert(hot.nonEmpty, s"$nm off=$off: a sub-transaction with an empty strobe")
          assert(hot.forall(i => i >= wantOff && i < wantOff + wantBytes),
            s"$nm off=$off: WSTRB bits $hot lie outside the addressed transfer " +
            s"[$wantOff,${wantOff + wantBytes})")
        }
        // Exactly the architectural bytes were committed, each exactly ONCE.
        val wantBytesAbs = (off until math.min(off + n, 16)).map(lineBase + _).toList
        assert(writes.sorted.toList == wantBytesAbs,
          s"$nm off=$off: committed ${writes.sorted.toList}, wanted $wantBytesAbs")
      }
    }
  }

  test("D26: a useStrb split slot derives its range from the STROBE, not from size", VerilatorTest) {
    compiled.doSim("mmio-store-usestrb", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      val log = watchAw(dut)
      val writes = scala.collection.mutable.ArrayBuffer[Long]()
      mem.setByteWriteObserver((a, _) => writes += a)

      // Exactly the StoreQueue slot-B shape: paddr = the next LINE BASE (so paddr[3:0] = 0)
      // and size = a flat LONG (StoreQueue.scala:264) whatever the true extent. The strobe
      // is the ONLY field carrying the truth.
      for (spill <- 1 to 3) {
        val lineBase = BASE + 0x2000L + 0x100L * spill
        val strb = (0 until spill).foldLeft(BigInt(0))((a, i) => a | (BigInt(1) << i))
        log.clear(); writes.clear()
        store(dut, lineBase, 0, Size.LONG, useStrb = true, strb = strb,
              lineData = BigInt("A5A5A5A5", 16))
        val want = MmioCover.model(0, spill)
        assert(log.length == want.length,
          s"spill=$spill: ${log.length} sub-transactions ${log.toList}, cover says $want")
        val committed = writes.sorted.map(_ - lineBase).toList
        assert(committed == (0 until spill).toList,
          s"spill=$spill: committed $committed -- a size-derived range would give " +
          s"${(0 until 4).toList}, naming ${4 - spill} register(s) the access never touched")
      }
    }
  }

  test("storeAck fires ONCE, after the LAST B of a multi-sub-transaction store", VerilatorTest) {
    compiled.doSim("mmio-store-single-ack", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      var acks = 0
      var bs   = 0
      val axi = dut.dcache.logic.axi
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.probe.logic.storeAckOut.toBoolean) acks += 1
          if (axi.b.valid.toBoolean && axi.b.ready.toBoolean) bs += 1
        }
      }
      // off=1, LONG -> the three-sub-transaction worst case (1@1, 2@2, 1@4).
      store(dut, BASE + 0x3000L + 1, BigInt("DEADBEEF", 16), Size.LONG)
      dut.clockDomain.waitSampling(40)
      assert(bs == 3, s"expected 3 B responses for the off=1/LONG cover, saw $bs")
      assert(acks == 1, s"storeAck pulsed $acks times -- the SQ pops its head on this pulse " +
        s"(sq.io.drainAck := dcache.storeAck), so anything but 1 is a real corruption")
    }
  }

  test("the CACHEABLE write-through path is unchanged: one 16-byte AW", VerilatorTest) {
    compiled.doSim("store-cacheable-unchanged", seed = 4) { dut =>
      dut.clockDomain.forkStimulus(10)
      AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      val log = watchAw(dut)
      val p = dut.probe.logic
      for (off <- Seq(0, 1, 5, 13)) {
        log.clear()
        p.storeIn.valid #= true
        p.storeIn.payload.paddr #= BASE + 0x4000L + off
        p.storeIn.payload.data  #= BigInt("CAFE", 16)
        p.storeIn.payload.size  #= Size.WORD
        p.storeIn.payload.useStrb #= false
        p.storeIn.payload.strb #= 0
        p.storeIn.payload.lineData #= 0
        p.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        p.storeIn.payload.precise #= false
        dut.clockDomain.waitSamplingWhere(p.storeIn.ready.toBoolean && p.storeIn.valid.toBoolean)
        p.storeIn.valid #= false
        dut.clockDomain.waitSampling(40)
        assert(log.length == 1, s"cacheable off=$off emitted ${log.length} AWs")
        assert(log.head._2 == 4, s"cacheable AxSIZE ${log.head._2}, wanted 4")
        assert(log.head._1 % 16 == 0, "cacheable AW not at the 16-byte line base")
      }
    }
  }
}
```

**Note for the implementer:** `dut.probe.logic.storeAckOut` is the name assumed here for
`DcacheProbePlugin`'s existing store-ack observation. Confirm it against
`src/test/scala/m68k040/cache/DcacheProbePlugin.scala` and use the real name; if the probe
does not expose it, add a `simPublic()` on `dcache.logic.storeAckReg` instead and read that.
This is the only name in this task not verified against source at plan time.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.cache.MmioStoreSizingSpec" 2>&1 | tail -30
```

Expected: the first test fails with a single AW of AxSIZE 4 at the line base where the cover
wants one or more narrow, byte-granular transfers.

- [ ] **Step 3a: Derive and latch the store sub-range at S3**

In `src/main/scala/m68k040/cache/DcachePlugin.scala`, add beside the store-drain registers at
`:534-538`:

```scala
    // D6/D26/D30 store-side sequencer state. LINE-RELATIVE, like the load side's.
    val stSubP      = Reg(UInt(offBits bits)) init 0
    val stSubEnd    = Reg(UInt(offBits + 1 bits)) init 0
    val stSubActive = RegInit(False)    // this drain is an INHIBITED covered sequence
    val stSubErr    = RegInit(False)    // OR of every sub-transaction's B response
```

and, inside the `when(stS3Valid)` block at `:1829`, immediately after the existing
`stAddrReg := ...` at `:1841`, add:

```scala
      // ── D5/D26/D30: the store's byte range, derived on the CORE-SIDE strobe ──────────
      // D5: reversal within a nibble preserves popcount and contiguity but NOT the offset a
      // run starts at, so this derivation MUST run before SocketByteOrder is applied at the
      // socket boundary. The two transforms are order-dependent; SocketByteOrder's doc
      // comment carries the other half of this statement.
      //
      // Two forms, and the second is normative rather than an optimisation (spec 3.3.1):
      //   useStrb = false -> [paddr[3:0], paddr[3:0] + sizeBytes(size))
      //   useStrb = true  -> the RUN OF SET BITS in the 16-bit line-relative strobe.
      // `size` is NOT an input on the second path and must not be fallen back to:
      // StoreQueue.scala:264 drives slot B's size as a flat Size.LONG whatever its true
      // 1-3-byte extent, and slot B's paddr is the next LINE BASE so paddr[3:0] = 0. A
      // size-derived range would read [0,4) for a slot whose real extent is [0,1) -- and
      // D30's cover would then cover that over-wide range EXACTLY, and exactly wrongly,
      // naming up to three peripheral registers the architectural access never touched.
      val stOffS3   = stS3Payload.paddr(offBits - 1 downto 0)
      val stNBytes  = m68k040.socket.MmioCover.sizeBytes(stS3Payload.size)
      val stStartSz = stOffS3
      val stEndSz   = m68k040.socket.MmioCover.clampedEnd(stOffS3, stNBytes)
      val stStartSt = m68k040.socket.MmioCover.strbRunStart(stS3MergeStrb)
      val stEndSt   = m68k040.socket.MmioCover.strbRunEnd(stS3MergeStrb)
      stSubP      := Mux(stS3Payload.useStrb, stStartSt, stStartSz)
      stSubEnd    := Mux(stS3Payload.useStrb, stEndSt,   stEndSz)
      stSubActive := stS3Inhibited
      stSubErr    := False

      GenerationFlags.simulation {
        // On the useStrb = false path the two derivations are the SAME range by
        // construction (DcacheTypes.scala:312-320's storeStrbA sets exactly bytes
        // off .. min(off+n,16)-1). Assert it rather than assume it, so a future change to
        // the merge-strobe derivation is loud instead of silently re-widening the range.
        when(stS3Valid && stS3Inhibited && !stS3Payload.useStrb) {
          assert(stStartSt === stStartSz && stEndSt === stEndSz,
            "DcachePlugin: the strobe-derived and size-derived store ranges disagree on a " +
            "useStrb=false INHIBITED store", FAILURE)
        }
      }
```

`stS3MergeStrb` is the expression already assigned into `stStrbReg` on the following line
(`stStrbReg := stS3MergeStrb`); use that same name.

- [ ] **Step 3b: Sequence the AW/W emission**

Replace the AW/W driver at `:1909-1924` with:

```scala
    // ── D30: one naturally-aligned sub-transaction per iteration on an INHIBITED store ──
    // The cacheable / write-through path is untouched: `stSubActive` is False there, so
    // `subLog2` collapses to size=4 at the line base and the strobe passes through whole.
    val stSubLog2  = m68k040.socket.MmioCover.stepLog2(stSubP, stSubEnd)
    val stSubBytes = (U(1, 4 bits) |<< stSubLog2).resize(4 bits)
    val stSubLast  = !stSubActive || ((stSubP +^ stSubBytes) === stSubEnd)
    // Mask the merged 16-bit strobe down to THIS sub-transaction's own bytes. Every
    // asserted WSTRB bit therefore lies inside the addressed transfer -- the AXI4 rule v1
    // violates at axi_narrow_to_wide.v:43, where strobe 0110 is paired with awsize=1 at an
    // even address, asserting byte 2 outside a transfer whose lanes are bytes 0-1.
    val stSubStrbMask = Bits(1 << offBits bits)
    for (i <- 0 until (1 << offBits)) {
      stSubStrbMask(i) := (U(i, offBits + 1 bits) >= stSubP) &&
                          (U(i, offBits + 1 bits) < (stSubP +^ stSubBytes))
    }
    val stSubAddr = (stAddrReg(31 downto offBits) ## stSubP).asUInt

    when(!stAwDone) {
      axi.aw.valid         := True
      axi.aw.payload.addr  := Mux(stSubActive, stSubAddr, stAddrReg)
      axi.aw.payload.id    := U(AxiIds.D_STORE, AxiIds.ID_W bits)
      axi.aw.payload.len   := U(0, 8 bits)
      axi.aw.payload.size  := Mux(stSubActive, stSubLog2.resize(3 bits), U(4, 3 bits))
      axi.aw.payload.burst := Axi4.burst.INCR
      when(axi.aw.ready) { stAwDone := True }
    }
    when(!stWDone) {
      axi.w.valid        := True
      // A narrow AXI write places its bytes in the lanes matching its own address, and
      // `stMergeReg` is already a full-line, byte-offset-indexed image, so the data needs
      // no shifting -- only the strobe narrows.
      axi.w.payload.data := stMergeReg
      axi.w.payload.strb := Mux(stSubActive, stStrbReg & stSubStrbMask, stStrbReg)
      axi.w.payload.last := True
      when(axi.w.ready) { stWDone := True }
    }

    GenerationFlags.simulation {
      when(stSubActive && axi.aw.valid) {
        assert((stSubP +^ stSubBytes) <= stSubEnd,
          "DcachePlugin: an INHIBITED store sub-transaction runs past the access", FAILURE)
        assert((stSubP.asBits & ((stSubBytes - 1).asBits.resize(offBits))) === B(0, offBits bits),
          "DcachePlugin: an INHIBITED AWADDR is not naturally aligned for its AWSIZE", FAILURE)
        assert(((stStrbReg & stSubStrbMask) & ~stSubStrbMask) === B(0, (1 << offBits) bits),
          "DcachePlugin: a WSTRB bit lies outside the addressed transfer", FAILURE)
      }
    }
```

- [ ] **Step 3c: Ack only after the last `B`, and OR the responses**

Replace `:1957-1959` with:

```scala
    val storeBAck = axi.b.valid && axi.b.ready && (axi.b.payload.id === U(AxiIds.D_STORE, AxiIds.ID_W bits))
    val storeBErr = storeBAck && (axi.b.payload.resp =/= Axi4.resp.OKAY)
    // D30/spec 3.4 "Stores": assert storeAck only after the LAST B handshake, and make
    // storeErr the OR of ALL of them. This is not cosmetic -- `sq.io.drainAck :=
    // dcache.storeAck` (LsEuPlugin), so an ack after sub-transaction 1 of 3 pops the
    // StoreQueue head before the remaining AW/W have been accepted, letting the next drain
    // re-kick the shared stAddrReg/stMergeReg/stStrbReg mid-flight. The plugin's own
    // one-ack-per-descriptor sim asserts at :1972-1992 are what pin this.
    when(storeBAck && !stSubLast) {
      // Advance and re-arm the pair. The existing fail-closed `=== D_STORE` demux above is
      // preserved for EVERY sub-transaction, unchanged.
      stSubP   := (stSubP +^ stSubBytes).resize(offBits bits)
      stAwDone := False
      stWDone  := False
      stSubErr := stSubErr || storeBErr
    }
    when(storeBAck && stSubLast) { stSubActive := False }
    storeErrReg := (storeBAck && stSubLast) && (stSubErr || storeBErr)
    storeAckReg := (storeBAck && stSubLast) || cbHitAckReg || storeAllocAckReg
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.cache.MmioStoreSizingSpec" 2>&1 | tail -30
~/sbt/bin/sbt "testOnly m68k040.cache.*" 2>&1 | tail -20
~/sbt/bin/sbt "testOnly m68k040.ls.* m68k040.mmu.*" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 4, failed 0` for the new suite; **every pre-existing cache, LS and
MMU spec still passes** — in particular `DcacheDrainRefillRaceSpec`, which exercises the
`refillWriteHold` interlock this sequencer now holds open for longer on an INHIBITED drain;
`check_socket_netlist.py` passes; `test-fast` green.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/cache/DcachePlugin.scala \
        src/test/scala/m68k040/cache/MmioStoreSizingSpec.scala
git commit -m "$(cat <<'EOF'
socket(D5/D26/D30): exact-cover sequencer on INHIBITED stores, single ack

Each sub-transaction is naturally aligned, lies wholly inside the architectural
access, and carries the strobe bits for its OWN bytes only. That last part fixes
a real AXI4 violation v1 has: axi_narrow_to_wide.v:43 lists strobe 0110 as an
awsize=1 case and then clears address bit 0, asserting byte 2 outside a transfer
whose active lanes are bytes 0-1. Under D30 this core does not emit that shape at
all -- [1,3) decomposes into two size=0 writes at offsets 1 and 2.

The range comes from the CORE-SIDE strobe (D5): nibble reversal preserves
popcount and contiguity but not the offset a run STARTS at, so deriving from the
post-permutation strobe would produce the mirror-image address. This code runs
inside the D-cache and SocketByteOrder is applied strictly afterwards; both sites
now say so.

For a useStrb split slot the strobe is the ONLY valid input. StoreQueue.scala:264
forces slot B's size to a flat LONG whatever its true 1-3-byte extent, and slot
B's paddr is the next line base, so a size-derived range reads [0,4) for a slot
whose real extent is [0,1) -- and the exact cover would then cover that over-wide
range exactly and exactly wrongly, naming registers the access never touched. A
sim assert pins that the two derivations agree on the useStrb=false path, so a
future change to the merge-strobe derivation is loud rather than silently
re-widening.

storeAck now fires only after the LAST B. Not cosmetic: sq.io.drainAck is
dcache.storeAck, so an ack after sub-transaction 1 of 3 pops the StoreQueue head
before the remaining AW/W are accepted, letting the next drain re-kick the shared
stAddrReg/stMergeReg/stStrbReg mid-flight. storeErrReg is the OR of every
response and the fail-closed === D_STORE demux is preserved for each.

The test asserts against a byte-write OBSERVER rather than a final memory image,
so a byte written twice is caught -- which is the actual obligation ("each
exactly once, and no others"), not the one a final-image comparison checks.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `ResetVectorPlugin` — the real vector-0 boot fetch (D12-D16)

**Files:**
- Create: `src/main/scala/m68k040/socket/ResetVectorFsm.scala`
- Create: `src/main/scala/m68k040/socket/ResetVectorPlugin.scala`
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (a new redirect source)
- Modify: `src/main/scala/m68k040/top/FullCoreSynth.scala` (`a7Wr` third source, halt fold)
- Test: `src/test/scala/m68k040/socket/ResetVectorSpec.scala`

**Interfaces:**
- Consumes: `AxiDMergePlugin.logic.{rvArValid, rvArAddr, rvArReady, rvRValid, rvRData, rvRResp, rvRReady}` (Task 5); `HaltReason.RESET_VECTOR` (Task 3); `DcacheByteLane.extract` (existing).
- Produces:
  - `class ResetVectorFsm(dataWidth: Int = 128)` `extends Component` — the three-state machine, standalone-testable.
  - `class ResetVectorPlugin(val enable: Boolean = false)` `extends FiberPlugin`, `logic` exposing `sspWriteValid: Bool`, `sspData: UInt(32 bits)`, `haltPulse: Bool`. With `enable = false` the `logic` Area elaborates **nothing**.
  - `FetchAlignPlugin.logic.resetRedirect: Flow[UInt]` — a new directionless, `allowOverride`, idle-defaulted redirect source.
- **Plugin ordering:** after `AxiDMergePlugin` (drives its `rv*` wires), after `FetchAlignPlugin` (drives its `resetRedirect`), before `BackendWiringPlugin` (which reads `sspWriteValid`/`haltPulse`).

**A spec correction this task must make, not paper over.** Spec §6.1 concludes: *"That existing
quiescent-until-redirect property is the hook this design uses; it means **no change to
`FetchAlignPlugin` is required at all**."* That is **false as stated**. `FetchAlignPlugin`'s
redirect port is `val redirect = slave(Flow(UInt(32 bits)))` (`:72`), i.e. `valid`/`payload`
are **inputs of `M68kCore`**, and an input cannot be driven from inside the component. The file
documents the constraint itself at `:64-66` and already carries the correct pattern for an
internally-driven redirect at `:74-81`: `mispredictRedirect`, a plain directionless `Flow` with
`allowOverride` and **concrete** idle defaults (*"NOT `assignDontCare` (which would hide the
sibling's drive / a sim poke from the consumer)"*). This task adds a third source of exactly
that shape. It is ~4 lines and it does not change the redirect priority of anything that exists;
the VIO spec's `V19` independently anticipates a further source at the same priority.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/ResetVectorSpec.scala`:

```scala
package m68k040.socket

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Reset/boot (section 6)":
  *   - "SSP and PC land from bytes 0-3 / 4-7 of the vector line, big-endian."
  *   - "No fetch occurs before the redirect; the first fetch is at the loaded PC."
  *   - "A non-OKAY vector response halts with the D15 reason code on D28's channel and does
  *      not attempt a frame."
  *   - "enable=false leaves every existing test bit-identical."
  * The last of those is a whole-build property and is checked in Tasks 13/14, not here. */
class ResetVectorSpec extends AnyFunSuite {

  class FsmDut extends Component {
    val f = new ResetVectorFsm(128)
    val io = new Bundle {
      val arValid = out Bool ()
      val arAddr  = out UInt (32 bits)
      val arReady = in  Bool ()
      val rValid  = in  Bool ()
      val rData   = in  Bits (128 bits)
      val rResp   = in  Bits (2 bits)
      val rReady  = out Bool ()
      val sspWriteValid = out Bool ()
      val sspData       = out UInt (32 bits)
      val redirectValid = out Bool ()
      val redirectPc    = out UInt (32 bits)
      val haltPulse     = out Bool ()
    }
    io.arValid := f.io.arValid;  io.arAddr := f.io.arAddr;  f.io.arReady := io.arReady
    f.io.rValid := io.rValid;    f.io.rData := io.rData;    f.io.rResp := io.rResp
    io.rReady := f.io.rReady
    io.sspWriteValid := f.io.sspWriteValid; io.sspData := f.io.sspData
    io.redirectValid := f.io.redirectValid; io.redirectPc := f.io.redirectPc
    io.haltPulse := f.io.haltPulse
  }

  /** A 16-byte line in the CORE's convention: byte i at bits [8i +: 8]. */
  private def line(bytes: Seq[Int]): BigInt =
    bytes.zipWithIndex.foldLeft(BigInt(0)) { case (a, (b, i)) => a | (BigInt(b & 0xff) << (8 * i)) }

  test("D12: one 16-byte read at physical 0; SSP from bytes 0-3, PC from 4-7, big-endian") {
    SimConfig.compile(new FsmDut).doSim("boot-ok", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.arReady #= false; dut.io.rValid #= false
      dut.io.rData #= 0; dut.io.rResp #= 0
      dut.clockDomain.waitSampling(3)
      // It asks, once, at address 0.
      assert(dut.io.arValid.toBoolean, "no AR presented out of reset")
      assert(dut.io.arAddr.toLong == 0L, s"AR at 0x${dut.io.arAddr.toLong.toHexString}, wanted 0")
      dut.io.arReady #= true
      dut.clockDomain.waitSampling()
      dut.io.arReady #= false
      dut.clockDomain.waitSampling(3)
      assert(!dut.io.arValid.toBoolean, "AR re-presented after it was accepted")
      // Vector line: SSP = 0x00042000, PC = 0x00004000, both big-endian in memory.
      val bytes = Seq(0x00, 0x04, 0x20, 0x00,  0x00, 0x00, 0x40, 0x00) ++ Seq.fill(8)(0xEE)
      dut.io.rValid #= true; dut.io.rData #= line(bytes); dut.io.rResp #= 0
      dut.clockDomain.waitSamplingWhere(dut.io.rReady.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rValid #= false
      // D14 ordering: SSP write in cycle N, redirect in cycle N+1. Never the same cycle.
      var sawSsp = -1; var sawRedir = -1; var sspVal = BigInt(0); var pcVal = BigInt(0)
      for (c <- 0 until 12) {
        if (dut.io.sspWriteValid.toBoolean && sawSsp < 0) { sawSsp = c; sspVal = dut.io.sspData.toBigInt }
        if (dut.io.redirectValid.toBoolean && sawRedir < 0) { sawRedir = c; pcVal = dut.io.redirectPc.toBigInt }
        assert(!(dut.io.sspWriteValid.toBoolean && dut.io.redirectValid.toBoolean),
          "SSP write and redirect asserted on the SAME cycle -- D14 requires N then N+1")
        dut.clockDomain.waitSampling()
      }
      assert(sawSsp >= 0, "the SSP write never pulsed")
      assert(sawRedir == sawSsp + 1, s"redirect at cycle $sawRedir, SSP at $sawSsp -- want +1")
      assert(sspVal == BigInt(0x00042000), f"SSP 0x$sspVal%08X, wanted 0x00042000")
      assert(pcVal  == BigInt(0x00004000), f"PC  0x$pcVal%08X, wanted 0x00004000")
      assert(!dut.io.haltPulse.toBoolean, "halted on a clean boot")
    }
  }

  test("D15: a non-OKAY vector response halts and never attempts a frame") {
    for (resp <- Seq(2, 3)) {                    // SLVERR, DECERR
      SimConfig.compile(new FsmDut).doSim(s"boot-err-$resp", seed = 2) { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.arReady #= true; dut.io.rValid #= false; dut.io.rData #= 0; dut.io.rResp #= 0
        dut.clockDomain.waitSamplingWhere(dut.io.arValid.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.arReady #= false
        dut.io.rValid #= true; dut.io.rResp #= resp; dut.io.rData #= line(Seq.fill(16)(0x5A))
        dut.clockDomain.waitSamplingWhere(dut.io.rReady.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.rValid #= false
        var halted = false
        for (_ <- 0 until 12) {
          if (dut.io.haltPulse.toBoolean) halted = true
          assert(!dut.io.sspWriteValid.toBoolean, s"resp=$resp wrote an SSP from a failed fetch")
          assert(!dut.io.redirectValid.toBoolean, s"resp=$resp redirected off a failed fetch")
          dut.clockDomain.waitSampling()
        }
        assert(halted, s"resp=$resp did not halt")
      }
    }
  }

  test("D12: DONE is terminal -- it never re-arms without a core reset") {
    SimConfig.compile(new FsmDut).doSim("terminal", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.arReady #= true; dut.io.rValid #= false; dut.io.rData #= 0; dut.io.rResp #= 0
      dut.clockDomain.waitSamplingWhere(dut.io.arValid.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rValid #= true
      dut.io.rData #= line(Seq(0,0,0x10,0, 0,0,0x20,0) ++ Seq.fill(8)(0))
      dut.clockDomain.waitSamplingWhere(dut.io.rReady.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rValid #= false
      dut.clockDomain.waitSampling(40)
      for (_ <- 0 until 40) {
        assert(!dut.io.arValid.toBoolean, "the reset-vector reader re-armed")
        assert(!dut.io.sspWriteValid.toBoolean, "a second SSP write")
        assert(!dut.io.redirectValid.toBoolean, "a second redirect")
        dut.clockDomain.waitSampling()
      }
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.ResetVectorSpec" 2>&1 | tail -20
```

Expected: `not found: type ResetVectorFsm`.

- [ ] **Step 3a: The FSM**

Create `src/main/scala/m68k040/socket/ResetVectorFsm.scala`:

```scala
package m68k040.socket

import m68k040.cache.DcacheByteLane
import m68k040.isa.Size
import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** The reset/boot vector reader (design spec D12-D15, section 6.2).
  *
  * ==What it replaces==
  * `FetchAlignPlugin.scala:186-189` initialises `decodePc`/`fetchPc` to plain 0 and holds
  * `started = False`; `:585` gates `ic.cmd.valid` on `started`, which only a redirect sets.
  * So the core natively fetches NOTHING until something external redirects it, and today
  * that something is the top-level `redirect` port driven by a test harness. This FSM is
  * the real 68040 behaviour instead: one 16-byte read at physical 0, SSP from bytes 0-3, PC
  * from bytes 4-7. Both vectors live in the same 16-byte line, so ONE transaction suffices
  * -- the same observation `if_stage.v:7-19` makes for v1.
  *
  * ==Why it rides `axi_d` and is not a third socket master (D13)==
  * `axi_xbar.v:1178-1192`'s `apply_cpu_overlay` aliases low addresses into the ROM mirror
  * ONLY for reads whose master index is `XBAR_M_CPU` or `XBAR_M_CPUI`. A vector-0 read from
  * any other master would read raw, uninitialised low DRAM. So this is a fourth READ OWNER
  * on the D-side merge arbiter, which puts it on `XBAR_M_CPU` where the overlay applies --
  * and it also keeps the socket's two-master contract intact.
  *
  * (Note the overlay does NOT arm the xbar's ROM-read auto-disable: `cpu_rom_read_seen`
  * tests `is_rom_addr` against the RAW address, and a read at 0x0 is aliased into ROM
  * rather than being a ROM-mirror address. That disarm happens later, when ROM code reads
  * the mirror directly. It changes nothing here, but it is why D13's constraint is
  * "must come from a CPU master index", not "must be first".)
  *
  * ==Why the two longwords go through `DcacheByteLane.extract` (D12)==
  * Deliberately, so this reader shares the core's SINGLE definition of big-endian assembly
  * and the two can never drift.
  *
  * ==D14 ordering==
  * SSP write in cycle N, fetch redirect in cycle N+1. Same-cycle would almost certainly be
  * fine -- the redirect only restarts FETCH, many cycles before any uop could read A7 at
  * issue -- but "almost certainly fine" is not a property worth having in the boot path, and
  * the cost is one cycle once per power-on.
  *
  * ==D15: a non-OKAY response halts; it does not stack a vector-2 frame==
  * v1 presents `pd_fault` at pc=0 so commit raises bus-error vector 2 (`if_stage.v:16-18`).
  * This diverges deliberately, in order of weight: (1) a bus fault taken during RESET
  * exception processing is a double bus fault on a real 68040 and the part halts -- vector 2
  * is v1's divergence, not ours; (2) there is nothing to build a frame ON, since SSP is
  * exactly the value that just failed to arrive and the vector table itself is unreadable,
  * so a vector-2 entry would write a frame through a garbage stack pointer and immediately
  * fault again; (3) it matches this project's established policy for un-actionable bus
  * errors with no architectural recipient (`DcachePlugin.scala:1680-1683`). */
class ResetVectorFsm(dataWidth: Int = 128) extends Component {
  require(dataWidth >= 64, "the reset vector line must carry at least 8 bytes")

  val io = new Bundle {
    val arValid = out Bool ()
    val arAddr  = out UInt (32 bits)
    val arReady = in  Bool ()
    val rValid  = in  Bool ()
    val rData   = in  Bits (dataWidth bits)
    val rResp   = in  Bits (2 bits)
    val rReady  = out Bool ()
    val sspWriteValid = out Bool ()
    val sspData       = out UInt (32 bits)
    val redirectValid = out Bool ()
    val redirectPc    = out UInt (32 bits)
    val haltPulse     = out Bool ()
  }

  val sspReg = Reg(UInt(32 bits)) init 0
  val pcReg  = Reg(UInt(32 bits)) init 0

  io.arValid := False
  io.arAddr  := U(0, 32 bits)          // physical 0 -- the 68040 reset vector, always
  io.rReady  := False
  io.sspWriteValid := False
  io.sspData       := sspReg
  io.redirectValid := False
  io.redirectPc    := pcReg
  io.haltPulse     := False

  val fsm = new StateMachine {
    val REQ    = new State with EntryPoint
    val WAIT   = new State
    val APPLY0 = new State
    val APPLY1 = new State
    val DONE   = new State

    REQ.whenIsActive {
      io.arValid := True
      when(io.arReady) { goto(WAIT) }
    }

    WAIT.whenIsActive {
      io.rReady := True
      when(io.rValid) {
        when(io.rResp === B"00") {
          // Bytes 0-3 and 4-7 of the line, assembled big-endian by the core's own single
          // definition (DcacheByteLane.extract), never by hand-slicing.
          sspReg := DcacheByteLane.extract(io.rData.resize(128 bits), U(0, 4 bits), Size.LONG).asUInt
          pcReg  := DcacheByteLane.extract(io.rData.resize(128 bits), U(4, 4 bits), Size.LONG).asUInt
          goto(APPLY0)
        } otherwise {
          // D15. No frame, no redirect, no SSP write -- the core stops.
          io.haltPulse := True
          goto(DONE)
        }
      }
    }

    APPLY0.whenIsActive { io.sspWriteValid := True; goto(APPLY1) }   // cycle N
    APPLY1.whenIsActive { io.redirectValid := True; goto(DONE)   }   // cycle N+1
    DONE.whenIsActive   { /* terminal: never re-arms without a core reset */ }
  }
}
```

- [ ] **Step 3b: The plugin, the redirect seam, and the `a7Wr` third source**

Create `src/main/scala/m68k040/socket/ResetVectorPlugin.scala`:

```scala
package m68k040.socket

import m68k040.frontend.FetchAlignPlugin
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Hosts `ResetVectorFsm` and connects it to the merge arbiter's fourth read owner and to
  * `FetchAlignPlugin`'s new internal redirect source.
  *
  * ==D16: `enable` defaults FALSE and that is load-bearing==
  * With it false this plugin elaborates to NOTHING, so the external `redirect` port keeps
  * its current meaning and every existing lock-step spec, directed test and the
  * `M68kFullCoreSynth` OOC/FMax target are untouched. `M68kSocketTop` sets it true. This
  * mirrors v1's `FETCH_RESET_VECTORS` parameter and the reason v1 has one
  * (`if_stage.v:20-23`: "the directed-asm test harness ... pre-arranges memory for a
  * specific PC and doesn't care about SSP").
  *
  * ==Plugin ordering==
  * AFTER `AxiDMergePlugin` (whose `rv*` wires it drives), AFTER `FetchAlignPlugin` (whose
  * `resetRedirect` it drives), BEFORE `BackendWiringPlugin` (which reads `sspWriteValid`
  * and `haltPulse`). */
class ResetVectorPlugin(val enable: Boolean = false) extends FiberPlugin {

  val logic = during build new Area {
    // Declared unconditionally so BackendWiringPlugin can read them either way; when
    // disabled they are constants and every consumer folds away.
    val sspWriteValid = Bool()
    val sspData       = UInt(32 bits)
    val haltPulse     = Bool()

    if (!enable) {
      sspWriteValid := False
      sspData       := U(0, 32 bits)
      haltPulse     := False
    } else {
      val arb = host[AxiDMergePlugin]
      val fa  = host[FetchAlignPlugin]
      val fsm = new ResetVectorFsm(arb.logic.merge.io.rvRData.getWidth)

      arb.logic.rvArValid := fsm.io.arValid
      arb.logic.rvArAddr  := fsm.io.arAddr
      fsm.io.arReady      := arb.logic.rvArReady
      fsm.io.rValid       := arb.logic.rvRValid
      fsm.io.rData        := arb.logic.rvRData
      fsm.io.rResp        := arb.logic.rvRResp
      arb.logic.rvRReady  := fsm.io.rReady

      fa.logic.resetRedirect.valid   := fsm.io.redirectValid
      fa.logic.resetRedirect.payload := fsm.io.redirectPc

      sspWriteValid := fsm.io.sspWriteValid
      sspData       := fsm.io.sspData
      haltPulse     := fsm.io.haltPulse
      sspWriteValid.simPublic(); sspData.simPublic(); haltPulse.simPublic()
    }
  }
}
```

In `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`, immediately after the
`mispredictRedirect` declaration at `:79-81`, add:

```scala
    // axi-socket adapter D12/D16: the reset-vector reader's redirect. Declared with the
    // SAME shape as mispredictRedirect above and for the same reason -- the `redirect`
    // slave port at :72 is an INPUT of M68kCore and a sibling plugin cannot drive it (see
    // the `feed` comment at :64-66). Idle-defaulted with CONCRETE zeros and allowOverride,
    // never assignDontCare, so a sibling's drive is not hidden from the consumer. With no
    // ResetVectorPlugin in the plugin list this stays constantly idle and folds away.
    val resetRedirect = Flow(UInt(32 bits))
    resetRedirect.valid.allowOverride;   resetRedirect.valid   := False
    resetRedirect.payload.allowOverride; resetRedirect.payload := U(0, 32 bits)
```

and apply it beside the existing external `redirect` in the redirect priority chain — locate
the site with `grep -n 'redirect.valid' src/main/scala/m68k040/frontend/FetchAlignPlugin.scala`
and add, **immediately after** the `when(redirect.valid) { ... }` arm and **before** the
`mispredictRedirect` arm (so a commit-time mispredict still has the last word):

```scala
    when(resetRedirect.valid) {
      // Same effect as the external redirect: set `started` and point fetch at the loaded
      // PC. Placed after it so a directed harness driving the external port still wins on
      // the impossible cycle where both fire, and before mispredictRedirect so a commit
      // redirect keeps its top priority.
      started   := True
      fetchPc   := resetRedirect.payload
      decodePc  := resetRedirect.payload
    }
```

matching whatever field assignments the existing `redirect` arm makes — **copy that arm's body
verbatim and change only the payload source**, rather than reconstructing it from the three
fields named above.

In `src/main/scala/m68k040/top/FullCoreSynth.scala`, replace the `a7Wr` drive at `:406-409`
with:

```scala
    // D14: the initial SSP reaches committed A7 through THIS existing shared int-PRF write
    // port, as a new HIGHEST-PRIORITY third source. Safe by construction and by the same
    // argument the existing direct writes rely on (the "task #176 safe fix pattern" of
    // writing committedPhysA7 directly, bypassing rename and the freelist): at the moment
    // the reset vector lands, no instruction has been fetched, so nothing is renamed, no
    // ROB entry exists, the ExceptionUnit is idle, and committedPhysA7 still equals 15.
    //
    // It reaches ISP for free: SystemState.scala:36 initialises srSys to 0x27 (S=1, M=0),
    // so A7 IS the ISP at reset, and the committedA7In readback below already routes it
    // into ss.isp on the following cycle with no extra wiring.
    val rv = host.get[m68k040.socket.ResetVectorPlugin] match {
      case Some(p) => p.logic
      case None    => null
    }
    val rvSspValid = if (rv != null) rv.sspWriteValid else False
    val rvSspData  = if (rv != null) rv.sspData       else U(0, 32 bits)
    a7Wr.valid   := rvSspValid || exc.a7WriteValid || exc.sysRegWriteValid
    a7Wr.address := Mux(rvSspValid, host[RenameStage].committedPhysA7.resize(a7Wr.address.getWidth),
                    Mux(exc.sysRegWriteValid, exc.sysRegWritePhys.resize(a7Wr.address.getWidth),
                                              host[RenameStage].committedPhysA7.resize(a7Wr.address.getWidth)))
    a7Wr.data    := Mux(rvSspValid, rvSspData.asBits,
                    Mux(exc.sysRegWriteValid, exc.sysRegWriteData.asBits, exc.a7WriteData.asBits))
    GenerationFlags.simulation {
      assert(!(rvSspValid && (exc.a7WriteValid || exc.sysRegWriteValid)),
        "the reset-vector SSP write collided with an ExceptionUnit A7/sysReg write", FAILURE)
    }
```

and extend the halt fold from Task 5 with the reset-vector producer:

```scala
    val rvHalt = if (rv != null) rv.haltPulse else False
    rob.logic.coreHaltedIn := dc.diagFault || exc.fsXlateFault || arbWedge || rvHalt
    rob.logic.haltReasonIn := Mux(dc.diagFault,
      U(m68k040.socket.HaltReason.DCACHE_DIAG, m68k040.socket.HaltReason.W bits),
      Mux(exc.fsXlateFault,
        U(m68k040.socket.HaltReason.FS_XLATE, m68k040.socket.HaltReason.W bits),
        Mux(rvHalt,
          U(m68k040.socket.HaltReason.RESET_VECTOR, m68k040.socket.HaltReason.W bits),
          Mux(arbWedge,
            U(m68k040.socket.HaltReason.ARBITER_WEDGE, m68k040.socket.HaltReason.W bits),
            U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits)))))
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.socket.ResetVectorSpec" 2>&1 | tail -20
~/sbt/bin/sbt "testOnly m68k040.frontend.*" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 3, failed 0`; every frontend spec still green (the new redirect
source is constantly idle in every build without `ResetVectorPlugin`);
`check_socket_netlist.py` passes — **this is the D16 `enable = false` obligation made
machine-checked**: adding an idle redirect source and an `Option`-shaped `a7Wr` term must not
add, remove or resize a single `M68kFullCoreSynth` port.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/ResetVectorFsm.scala \
        src/main/scala/m68k040/socket/ResetVectorPlugin.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/main/scala/m68k040/top/FullCoreSynth.scala \
        src/test/scala/m68k040/socket/ResetVectorSpec.scala
git commit -m "$(cat <<'EOF'
socket(D12-D16): real vector-0 boot fetch, SSP via the shared a7Wr port

One 16-byte read at physical 0; SSP from bytes 0-3, PC from bytes 4-7, both
assembled by DcacheByteLane.extract so this reader shares the core's single
definition of big-endian assembly and the two cannot drift.

It rides the axi_d merge arbiter as a FOURTH READ OWNER rather than being a third
socket master. axi_xbar.v:1178-1192's apply_cpu_overlay aliases low addresses
into the ROM mirror only for reads whose master index is XBAR_M_CPU/XBAR_M_CPUI,
so a vector-0 read from any other master would read raw uninitialised low DRAM --
and a third master would also break the socket's two-master contract.

SSP write in cycle N, redirect in cycle N+1 (D14). Same-cycle would almost
certainly be fine, but "almost certainly fine" is not a property worth having in
the boot path and the cost is one cycle once per power-on. It reaches ISP for
free: srSys initialises to 0x27 (S=1, M=0) so A7 IS the ISP at reset.

A non-OKAY response HALTS with its own D28 reason code rather than stacking a
vector-2 frame (D15). That is what real hardware does -- a bus fault during reset
exception processing is a double bus fault and the part halts -- and there is
nothing to build a frame on anyway, since SSP is exactly the value that failed to
arrive.

CORRECTS THE SPEC: section 6.1 states "no change to FetchAlignPlugin is required
at all". False as written -- its `redirect` port is slave(Flow), i.e. an INPUT of
M68kCore, which a sibling plugin cannot drive. The file documents that constraint
itself at :64-66 and already carries the right pattern at :74-81. This adds a
third source of exactly that shape, idle-defaulted with concrete zeros and
allowOverride (never assignDontCare, which would hide the drive from the
consumer), placed between the external redirect and the commit-time mispredict so
neither loses priority.

enable defaults FALSE (D16): the plugin elaborates to nothing, the external
redirect port keeps its meaning, and the netlist checker proves
M68kFullCoreSynth's port surface did not move.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: `ipl_ack` and autovector policy (D17, D18)

**Files:**
- Create: `src/main/scala/m68k040/socket/IplAckPlugin.scala`
- Test: `src/test/scala/m68k040/socket/IplAckSpec.scala`

**Interfaces:**
- Consumes: `host[RobPlugin].logic.exc.{obsFire, obsIsEntry, obsIsInterrupt}`.
- Produces: `class IplAckPlugin(val enable: Boolean = false) extends FiberPlugin` whose `logic` exposes `iplAck: Bool` (declared `out` only when `enable`, so it becomes a core-level port on the socket build and nothing at all on `M68kFullCoreSynth`).
- **`iackAvec`/`iackVector` (D18) are NOT touched here.** They already exist as core inputs (`BackendWiringPlugin.logic.iackAvecIn` / `iackVectorIn`, `FullCoreSynth.scala:316-321`), and D18 is discharged by `M68kSocketTop` tying `iackAvecIn := True` and `iackVectorIn := 0` in Task 13 — no core change, and the existing `RegNext(...) init 0` synchroniser placement at `:319-321` is retained exactly as spec §7.3 requires.

**Why this is not optional polish.** Spec §7.1: without `ipl_ack` the SoC's `irq_agg` NMI
rising-edge latch never clears. `m68k_core.v:119-126` names the consequence: *"Without this
hook the external agg's `nmi_pending` latch sticks once any rising edge fires and IPL=7 is
asserted forever → CPU loops on vec-31."*

**D17 requires the plan to pin the qualifier, not guess it.** Spec §7.2: *"Derive from
`exc.obsIsInterrupt` qualified by its own entry-fire, because that signal already means
*taken*, not *pending*, and is already registered... Deriving straight from
`RegNext(interruptPending)` is a plausible simpler form, but it depends on the single-cycle
property being *structural* rather than incidental."* The qualifier, confirmed against the RTL
of the day: `RobPlugin.scala:1614` builds the commit observation as
`commitObs(2).fire := RegNext(exc.obsFire) init False` and `:1622`
`commitObs(2).isInterrupt := RegNext(exc.obsIsInterrupt) init False`, and
`ExceptionUnit.scala:1507-1512` pulses `obsFire`/`obsIsEntry`/`obsIsInterrupt` together for
exactly one cycle at the entry that is taken. So the contract-correct derivation is
`RegNext(exc.obsFire && exc.obsIsEntry && exc.obsIsInterrupt) init False`, and the assertion
below is what makes the choice safe rather than the reasoning.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/IplAckSpec.scala`:

```scala
package m68k040.socket

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Interrupts": the D17 count-equality assertion, and D18's autovector
  * policy as a documented, checked constant rather than an assumption.
  *
  * The count-equality assertion itself is RTL and lives in the plugin, so it is enforced
  * over EVERY simulation that instantiates it -- including the full lock-step corpus, which
  * spec section 13 asks for explicitly. This suite proves the counter pair exists and that
  * the pulse shape is single-cycle. */
class IplAckSpec extends AnyFunSuite {

  /** The pulse shaper in isolation: `entry` is the exception unit's one-cycle taken-entry
    * observation, `iplAck` must be its registered form and nothing else. */
  class AckDut extends Component {
    val entry  = in  Bool ()
    val iplAck = out Bool ()
    iplAck := RegNext(entry) init False
    val nEntry = Reg(UInt(16 bits)) init 0
    val nAck   = Reg(UInt(16 bits)) init 0
    when(entry)  { nEntry := nEntry + 1 }
    when(iplAck) { nAck := nAck + 1 }
    nEntry.simPublic(); nAck.simPublic()
  }

  test("ipl_ack is a ONE-CYCLE pulse per taken entry, never per pending recognition") {
    SimConfig.compile(new AckDut).doSim("pulse", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.entry #= false
      dut.clockDomain.waitSampling(4)
      // Ten entries, at irregular spacing including back-to-back.
      val gaps = Seq(3, 1, 0, 7, 0, 0, 2, 5, 1, 4)
      var pulses = 0
      val watcher = fork {
        while (true) { dut.clockDomain.waitSampling(); if (dut.iplAck.toBoolean) pulses += 1 }
      }
      for (g <- gaps) {
        dut.entry #= true
        dut.clockDomain.waitSampling()
        dut.entry #= false
        if (g > 0) dut.clockDomain.waitSampling(g)
      }
      dut.clockDomain.waitSampling(8)
      assert(pulses == gaps.length, s"$pulses pulses for ${gaps.length} entries")
      assert(dut.nAck.toInt == dut.nEntry.toInt,
        s"count equality violated: ${dut.nAck.toInt} acks vs ${dut.nEntry.toInt} entries")
    }
  }

  test("D18: seven autovector levels, and no vector input at the socket") {
    // The socket declares cpu_ipl[2:0] and ipl_ack, and NO vector input
    // (cpu_socket.vh:164-168). All seven levels therefore take autovectors 25-31, which is
    // what the Mac hardware actually does and what v1 does -- m68k_core.v:126's "CPU loops
    // on vec-31" is describing the autovector for level 7.
    assert(IplAckPlugin.AUTOVECTOR_BASE == 25, "level-1 autovector is 25")
    assert(IplAckPlugin.AUTOVECTOR_BASE + 6 == 31, "level-7 autovector is 31")
    assert(IplAckPlugin.IACK_AVEC_TIEOFF, "D18 ties iackAvec to 1")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.IplAckSpec" 2>&1 | tail -20
```

Expected: `not found: value IplAckPlugin`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/scala/m68k040/socket/IplAckPlugin.scala`:

```scala
package m68k040.socket

import m68k040.rob.RobPlugin
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

object IplAckPlugin {
  /** D18: the socket declares no vector input (`cpu_socket.vh:164-168`), so all seven
    * levels take AUTOVECTORS 25-31. That is what the Mac hardware actually does and what v1
    * does; `m68k_core.v:126`'s "CPU loops on vec-31" is describing the autovector for level
    * 7. Recorded as constants so the policy is citable rather than folklore. */
  val AUTOVECTOR_BASE  = 25
  val IACK_AVEC_TIEOFF = true
}

/** `ipl_ack` (design spec D17, section 7).
  *
  * ==Why it is load-bearing, not polish==
  * Without it the SoC's `irq_agg` NMI rising-edge latch never clears. `m68k_core.v:119-126`:
  * "Without this hook the external agg's `nmi_pending` latch sticks once any rising edge
  * fires and IPL=7 is asserted forever -> CPU loops on vec-31." A small amount of logic
  * guarding a total-failure mode.
  *
  * ==The contract==
  * `ipl_ack` pulses high for exactly ONE core-clock cycle each time an interrupt exception
  * entry is ACTUALLY TAKEN -- never on a merely-pending or subsequently-abandoned
  * recognition.
  *
  * ==The source, and why not the simpler one==
  * `ExceptionUnit.scala:1507-1512` pulses `obsFire`, `obsIsEntry` and `obsIsInterrupt`
  * together for exactly one cycle at the entry that is taken, and `RobPlugin.scala:1614,1622`
  * already registers that pair for the commit observation. So the derivation is
  * `RegNext(obsFire && obsIsEntry && obsIsInterrupt)`: already registered, so it adds no
  * logic depth to the commit path (the architecture document's registered-control rule).
  *
  * `RegNext(interruptPending)` is a plausible simpler form, but it depends on
  * `RobPlugin.scala:1447`'s self-gating on `excIdle` collapsing to a single cycle per
  * accepted entry being STRUCTURAL rather than incidental -- and that is exactly the kind of
  * thing that quietly changes. The count-equality assertion below is what makes either
  * choice safe, which is why D17 requires it rather than merely suggesting it.
  *
  * ==D18 is discharged elsewhere, on purpose==
  * `iackAvec`/`iackVector` are already core INPUTS (`FullCoreSynth.scala:316-321`), so
  * tying `iackAvec = 1` and dropping `iackVector` is a `M68kSocketTop` connection, not a
  * core change. That keeps the existing `RegNext(...) init 0` synchroniser placement at
  * `:319-321` exactly as spec section 7.3 requires -- it is the correct placement AND it
  * keeps the IPL compare cone non-foldable, which the OOC flow relies on. */
class IplAckPlugin(val enable: Boolean = false) extends FiberPlugin {

  val logic = during build new Area {
    val rob = host[RobPlugin]
    val exc = rob.logic.exc

    val takenEntry = exc.obsFire && exc.obsIsEntry && exc.obsIsInterrupt
    val ack = RegNext(takenEntry) init False
    ack.simPublic()

    // Only a socket build exports the port; M68kFullCoreSynth must not gain one (D23).
    val iplAck = if (enable) { val p = out Bool (); p.setName("ipl_ack"); p := ack; p } else ack

    // D17's required pin: over any simulation run, the count of ipl_ack pulses equals the
    // count of interrupt exception entries retired. Sim-only; needs `.includeSimulation`
    // on the enclosing SpinalConfig (see M68kSim.scala). Because this lives in the plugin
    // rather than in one spec, it is enforced over EVERY simulation that instantiates it --
    // including the full lock-step corpus, which spec section 13 asks for by name.
    GenerationFlags.simulation {
      val nEntry = Reg(UInt(32 bits)) init 0
      val nAck   = Reg(UInt(32 bits)) init 0
      when(takenEntry) { nEntry := nEntry + 1 }
      when(ack)        { nAck   := nAck + 1 }
      nEntry.simPublic(); nAck.simPublic()
      // One cycle of skew by construction (ack is takenEntry registered), so the invariant
      // is "never more acks than entries, and never more than one behind".
      assert(nAck <= nEntry, "ipl_ack pulsed more often than an interrupt entry was taken",
        FAILURE)
      assert((nEntry - nAck) <= U(1, 32 bits),
        "ipl_ack fell more than one entry behind the interrupt entries taken", FAILURE)
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.socket.IplAckSpec" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: `Tests: succeeded 2, failed 0`; `check_socket_netlist.py` passes (the plugin is not
in `M68kFullCoreSynth`'s list, so its surface cannot move); `test-fast` green.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/IplAckPlugin.scala \
        src/test/scala/m68k040/socket/IplAckSpec.scala
git commit -m "$(cat <<'EOF'
socket(D17/D18): ipl_ack from the taken-entry observation, autovector policy

Not polish: without ipl_ack the SoC's irq_agg NMI rising-edge latch never clears,
and m68k_core.v:119-126 names the consequence -- nmi_pending sticks once any
rising edge fires, IPL=7 is asserted forever, and the CPU loops on vec-31. A
small amount of logic guarding a total-failure mode.

D17 required the plan to PIN the qualifier against the RTL of the day rather than
guess it. ExceptionUnit.scala:1507-1512 pulses obsFire/obsIsEntry/obsIsInterrupt
together for exactly one cycle at the entry that is TAKEN (not merely pending),
and RobPlugin.scala:1614,1622 already registers that pair, so the derivation adds
no logic depth to the commit path. RegNext(interruptPending) is the plausible
simpler form and is deliberately not used: it depends on the single-cycle
property of a self-gated recognition being structural rather than incidental.

The count-equality assertion lives in the PLUGIN, not in one spec, so it is
enforced over every simulation that instantiates it -- including the full
lock-step corpus, which spec section 13 asks for by name. It is stated as
"never more acks than entries, never more than one behind", the one-cycle skew
being structural.

D18 is discharged at the socket top, not here: iackAvec/iackVector are already
core inputs, so tying avec=1 and dropping the vector is a connection. That keeps
the existing RegNext(...) init 0 synchroniser placement untouched -- correct
placement, and it keeps the IPL compare cone non-foldable, which the OOC flow
relies on.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: **DECISION CHECKPOINT** — resolve `OPEN-1` and land the spec addendum

**Files:**
- Modify: `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md` (append a new §14, "Resolved open items")
- Test: none — this task produces a decision and a document, not RTL.

**Interfaces:**
- Consumes: nothing.
- Produces: a **signed-off answer to `OPEN-1`**, which Task 12 reads and implements. Task 12
  **may not start** until this task's addendum is committed.

**This task exists because the spec forbids the plan from choosing.** Spec `OPEN-1`, verbatim:
*"Whether `cpu_peripheral_reset`'s 518-cycle hold should also **gate dispatch/retire** the way
v1's does. v1 gates both; this core's `RESET` is a pure NOP today. Needs explicit sign-off; the
implementation plan may not silently pick either answer."* And §9.4: *"This needs explicit
sign-off before the implementation plan writes the task, and the plan **must not** pick
silently — the seam is small, but the first option touches the retire path."* And §13:
*"**OPEN-1:** whichever answer is signed off, the check follows it — either 'no µop retires
during the hold' or 'execution is unaffected by the hold'. The plan must state which it is
testing; a test written against the unsigned-off assumption is worse than none."*

- [ ] **Step 1: Present the decision, with the evidence, and STOP for sign-off**

Do not proceed past this step without an explicit answer from the user. Present exactly this:

> **`OPEN-1`: should `cpu_peripheral_reset`'s 518-cycle hold also gate dispatch and retire?**
>
> **The fact, measured not assumed.** v1's `cpu_reset_out` is a **518-core-clock level**, not a
> pulse: `commit.v:2081-2101` loads a down-counter with `RESET_INSTR_CYCLES - 1` when the
> `RESET` instruction retires, `localparam integer RESET_INSTR_CYCLES = 518;`, commented
> *"MAME's 68040 model charges 518 clocks for RESET. Holding the core for the same interval
> also exceeds the 68040 RSTO minimum of 124 clocks."* The same level is ALSO an internal stall
> at four sites: `commit.v:1283` (`can_commit` — nothing retires), `commit.v:1321`
> (`can_commit_irq` — no interrupt is taken), `m68k_core_fetch.vh:852` (`q_dispatch_fire` — no
> µop dispatches), `m68k_core_fetch.vh:1038` (`rn_ready` — rename stops accepting).
>
> This core's `RESET` is a pure NOP today (`ExceptionUnit.scala:1830-1833`: *"The external
> reset line is not modeled for lock-step; RESET is an internal NOP"*).
>
> **Option A — output only.** Zero risk to the existing pipeline, zero FMax exposure. Diverges
> from v1's observable timing and leaves unenforced a driver's assumption about the delay
> between `RESET` and its next peripheral access. `cpu_peripheral_reset` would be asserted for
> 518 cycles *while the core keeps executing* — legal for the SoC (the output is a reset-tree
> input, not a handshake) but a real behavioural divergence.
>
> **Option B — reproduce the hold.** Faithful to v1 and to the 68040's own timing. In THIS
> core the four v1 sites collapse to **three**, all already present and all already driven by
> `coreHalted`: `RobPlugin.scala:585` (`headReady`, = v1's `can_commit`), `:1447`
> (`interruptPending`, = `can_commit_irq`), and `:1487-1488`
> (`_frontendQuiesceActive`/`_frontendQuiesceNext`, which fan out through
> `FrontendQuiesceService` to `FetchAlignPlugin`'s `feed.valid` at `:980` and `ic.cmd.valid` at
> `:585` — i.e. both of v1's dispatch/rename sites at once). So the wiring is a **non-sticky**
> hold ORed into the same three terms. The costs are real: it puts a new term on a commit-side
> signal, a path the FMax campaign has repeatedly found sensitive, and it must be proven not to
> deadlock against a precise-drain/exception window that is itself blocking retire.
>
> **Unaffected either way:** the lock-step comparison. The output is not architectural state
> and Musashi models nothing here; the hold changes cycle counts but not architectural results.
>
> **Recommendation, offered but not assumed:** Option B, *if and only if* Task 14's gate has
> headroom, because a driver written against a real 68040 may depend on the delay. Otherwise
> Option A with the divergence recorded. **The plan will not pick.**

- [ ] **Step 2: Land the answer as a spec addendum**

Append to `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md`:

```markdown
---

## 14. Resolved open items (2026-08-18)

### 14.1 `OPEN-1` — `cpu_peripheral_reset`'s 518-cycle hold and dispatch/retire gating

**RESOLVED: <Option A: output only | Option B: reproduce the hold>.**

Decided by explicit user sign-off on <DATE>, during the implementation-plan pass
(`docs/superpowers/plans/2026-08-18-axi-socket-adapter-implementation-plan.md`, Task 11).

**Rationale as given:** <record the user's stated reason verbatim, not a paraphrase>.

**What this binds.** The implementation task (Task 12 of that plan) implements exactly this
answer, and its verification follows §13's `OPEN-1` bullet accordingly:

- Under Option A, the check is *"execution is unaffected by the hold"* — a directed test that
  µops continue to retire while `cpu_peripheral_reset` is high.
- Under Option B, the check is *"no µop retires during the hold"* — a directed test that
  retirement, interrupt entry, and frontend fetch are all quiesced for exactly the hold, plus
  the deadlock argument against a concurrent precise-drain/exception window.

Either way `D22`'s measured facts are unchanged: the width is **518** core clocks, copied
verbatim from `commit.v:2081-2101`, and the driver is the commit-time `SysKind.RESET` arm at
`ExceptionUnit.scala:1830-1833`.
```

Fill in the bracketed fields from the actual answer. **Do not invent a rationale.**

- [ ] **Step 3: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md
git commit -m "$(cat <<'EOF'
spec(axi-socket): resolve OPEN-1 -- cpu_peripheral_reset hold vs dispatch gating

OPEN-1 was recorded rather than silently resolved because the two answers have
different blast radii: output-only touches nothing, reproducing v1's hold puts a
new term on a commit-side signal the FMax campaign has repeatedly found
sensitive. The spec forbade the implementation plan from picking, and section 13
notes that a test written against an unsigned-off assumption is worse than none.

Records the signed-off answer, its stated rationale, and which of section 13's
two mutually exclusive checks Task 12 must therefore write.

D22's measured facts are unchanged either way: 518 core clocks, copied verbatim
from commit.v:2081-2101, driven from the commit-time SysKind.RESET arm.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: `cpu_peripheral_reset` — the `RESET` instruction output (D22, per `OPEN-1`)

**Files:**
- Create: `src/main/scala/m68k040/socket/PeripheralResetPlugin.scala`
- Modify: `src/main/scala/m68k040/exception/ExceptionUnit.scala` (`:1830-1833`, the `SysKind.RESET` arm)
- Modify (**Option B only**): `src/main/scala/m68k040/rob/RobPlugin.scala` (`:585`, `:1447`, `:1487-1488`)
- Test: `src/test/scala/m68k040/socket/PeripheralResetSpec.scala`

**Interfaces:**
- Consumes: Task 11's signed-off answer; `host[RobPlugin].logic.exc`.
- Produces: `class PeripheralResetPlugin(val enable: Boolean = false, val holdCycles: Int = 518, val gateDispatch: Boolean)` `extends FiberPlugin`, exposing `cpuPeripheralReset` (a core-level `out Bool()` named `cpu_peripheral_reset`, only when `enable`).
- **`gateDispatch` has no default.** It is a required constructor argument precisely so an
  implementer cannot land this task without having read Task 11's answer.

**Ownership, and why this is here rather than in the debug plan.** Spec §9.4/D22: it is *"an
architectural instruction side effect, not a debug-CSR bit."* `m68k_axi_wrapper.v:683` binds
`m68k_core.v`'s `cpu_reset_out` (`:128-130`, *"68040 RESET instruction external indication.
This does not reset the CPU core itself; the SoC uses it for its warm peripheral reset"*) and
`fpga_top_sd.vh:80` consumes it. **Both `cpu_socket.vh:170-176` and the in-flight debug-ctrl
plan's port list omit it**, so the debug plan's own conformance check would pass while silently
missing it — that omission is `SOC-2`, a documentation fix in the sibling repo, out of scope
here.

**The RESET instruction is already fully decoded and framed** (`OperationDecoder.scala:583-592`,
`PredecodeWord.scala:548`, `MicroOpAssembler.scala:2170-2172`), so this is a new output on an
existing, exercised arm — not new decode.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/PeripheralResetSpec.scala`:

```scala
package m68k040.socket

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Socket port surface and RESET":
  *   "D22: cpu_peripheral_reset rises on RESET retirement and stays high for exactly 518
  *    core-clock cycles, then falls; back-to-back RESETs re-arm it without a glitch low."
  *
  * The OPEN-1 half of that section ("either 'no uop retires during the hold' or 'execution is
  * unaffected by the hold'") is covered by whichever of the two final tests matches the
  * signed-off answer; the OTHER ONE MUST BE DELETED, not left ignored. Spec section 13: "a
  * test written against the unsigned-off assumption is worse than none." */
class PeripheralResetSpec extends AnyFunSuite {

  /** The hold counter in isolation, with a short width so the test is quick; the RTL value
    * is 518 and is asserted separately as a constant. */
  class HoldDut(hold: Int) extends Component {
    val trigger = in  Bool ()
    val level   = out Bool ()
    val cnt = Reg(UInt(log2Up(hold + 1) bits)) init 0
    when(trigger)          { cnt := U(hold, cnt.getWidth bits) }
      .elsewhen(cnt =/= 0) { cnt := cnt - 1 }
    level := cnt =/= 0
    cnt.simPublic()
  }

  test("D22's width is 518, copied from v1 and not re-derived") {
    // commit.v:2081-2101: `localparam integer RESET_INSTR_CYCLES = 518;` -- "MAME's 68040
    // model charges 518 clocks for RESET. Holding the core for the same interval also
    // exceeds the 68040 RSTO minimum of 124 clocks."
    assert(PeripheralResetPlugin.V1_HOLD_CYCLES == 518,
      s"got ${PeripheralResetPlugin.V1_HOLD_CYCLES}")
    assert(PeripheralResetPlugin.V1_HOLD_CYCLES != 512,
      "512 was the WITHDRAWN 'if v1 emits a bare pulse' fallback; v1 emits a 518-cycle LEVEL")
    assert(PeripheralResetPlugin.V1_HOLD_CYCLES > 124,
      "the 68040 RSTO minimum is 124 clocks")
  }

  test("the level rises on the trigger and stays high for exactly the hold") {
    val hold = 24
    SimConfig.compile(new HoldDut(hold)).doSim("hold", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.trigger #= false
      dut.clockDomain.waitSampling(4)
      assert(!dut.level.toBoolean, "level high before any RESET")
      dut.trigger #= true
      dut.clockDomain.waitSampling()
      dut.trigger #= false
      var high = 0
      while (dut.level.toBoolean && high < hold * 4) { dut.clockDomain.waitSampling(); high += 1 }
      assert(high == hold, s"level held for $high cycles, wanted exactly $hold")
    }
  }

  test("back-to-back RESETs re-arm the level without a glitch low") {
    val hold = 24
    SimConfig.compile(new HoldDut(hold)).doSim("rearm", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.trigger #= false
      dut.clockDomain.waitSampling(4)
      dut.trigger #= true; dut.clockDomain.waitSampling(); dut.trigger #= false
      dut.clockDomain.waitSampling(hold / 2)
      assert(dut.level.toBoolean, "level fell early")
      // A second RESET mid-hold.
      dut.trigger #= true; dut.clockDomain.waitSampling(); dut.trigger #= false
      var glitched = false
      for (_ <- 0 until hold) {
        if (!dut.level.toBoolean) glitched = true
        dut.clockDomain.waitSampling()
      }
      assert(!glitched, "the level glitched low while re-arming")
    }
  }

  // ── EXACTLY ONE of the following two tests survives, per Task 11's answer ──────────
  // OPTION A (output only): keep this one, DELETE the Option B test.
  test("OPTION A: execution is unaffected by the hold") {
    cancel("keep this test only if OPEN-1 was resolved as Option A; see Task 11's addendum")
  }
  // OPTION B (reproduce the hold): keep this one, DELETE the Option A test.
  test("OPTION B: no uop retires, no interrupt is taken, and no fetch issues during the hold") {
    cancel("keep this test only if OPEN-1 was resolved as Option B; see Task 11's addendum")
  }
}
```

**The implementer's first action in Step 3 is to delete the non-chosen test and replace the
chosen one's `cancel(...)` with a real directed test** against a `RobPlugin`-hosting DUT of the
shape `HaltReasonSpec` uses. Leaving both `cancel`ed is a failed task.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.PeripheralResetSpec" 2>&1 | tail -20
```

Expected: `not found: value PeripheralResetPlugin`.

- [ ] **Step 3a: Expose the `RESET` retirement pulse**

In `src/main/scala/m68k040/exception/ExceptionUnit.scala`, declare beside the other commit-time
observation outputs (near `:564-574`):

```scala
  /** axi-socket adapter D22: a one-cycle pulse when a `RESET` instruction retires. The
    * instruction remains an architectural NOP internally -- this is the EXTERNAL indication
    * `m68k_core.v:128-130` describes ("This does not reset the CPU core itself; the SoC uses
    * it for its warm peripheral reset"). Idle-defaulted so every existing DUT elaborates. */
  val resetInstrRetire = Bool(); resetInstrRetire := False; resetInstrRetire.simPublic()
```

and in the `SysKind.RESET` arm at `:1830-1833`, replace the empty body with:

```scala
        is(skOrd(m68k040.decode.SysKind.RESET)) {   // RESET : no architectural state change
          // Still no architectural state change, and still an internal NOP for lock-step:
          // Musashi models nothing here and the external line is not architectural state.
          // What changes is that the retirement is now OBSERVABLE, so the socket can drive
          // cpu_peripheral_reset from it (D22). S_REDIR still just advances PC.
          resetInstrRetire := True
        }
```

- [ ] **Step 3b: Write the plugin**

Create `src/main/scala/m68k040/socket/PeripheralResetPlugin.scala`:

```scala
package m68k040.socket

import m68k040.rob.RobPlugin
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

object PeripheralResetPlugin {
  /** D22's width, COPIED from v1 rather than re-derived, for the same reason D20's timeout
    * is: `commit.v:2081-2101`, `localparam integer RESET_INSTR_CYCLES = 518;` -- "MAME's
    * 68040 model charges 518 clocks for RESET. Holding the core for the same interval also
    * exceeds the 68040 RSTO minimum of 124 clocks."
    *
    * NOT 512. An earlier draft of the spec allowed "defaulting to 512 core-clock cycles if
    * v1 emits a bare pulse"; v1's driver was then actually READ and it emits a 518-cycle
    * LEVEL, so that fallback is withdrawn. The number is measured, not assumed. */
  val V1_HOLD_CYCLES = 518
}

/** `cpu_peripheral_reset` -- the 68040 `RESET` instruction's external indication (D22).
  *
  * ==Ownership==
  * This belongs to the socket work, not the debug-ctrl plan: it is an architectural
  * INSTRUCTION SIDE EFFECT, not a debug-CSR bit. `m68k_axi_wrapper.v:683` binds it and
  * `fpga_top_sd.vh:80` consumes it. Both `cpu_socket.vh:170-176` and the debug-ctrl plan's
  * port list omit it, which is `SOC-2` -- a documentation fix in the sibling repo.
  *
  * @param gateDispatch the `OPEN-1` answer. REQUIRED, with no default, so this plugin cannot
  *        be instantiated by someone who has not read the signed-off addendum in
  *        `docs/superpowers/specs/2026-08-18-axi-socket-adapter-design.md` section 14.1.
  *        When true the hold ALSO quiesces retire, interrupt entry and the frontend, the way
  *        v1's does at its four sites (`commit.v:1283`, `:1321`, `m68k_core_fetch.vh:852`,
  *        `:1038`); in this core those collapse to three, because
  *        `RobPlugin.scala:1487-1488`'s frontend-quiesce service already fans out to both of
  *        v1's dispatch/rename sites. */
class PeripheralResetPlugin(val enable: Boolean = false,
                            val holdCycles: Int = PeripheralResetPlugin.V1_HOLD_CYCLES,
                            val gateDispatch: Boolean) extends FiberPlugin {
  require(holdCycles >= 1, s"holdCycles must be >= 1 (got $holdCycles)")

  val logic = during build new Area {
    val rob = host[RobPlugin]
    val exc = rob.logic.exc

    // A LEVEL, not a pulse. Reloading on a retirement that arrives mid-hold re-arms without
    // ever dropping low, which is what "back-to-back RESETs re-arm it without a glitch low"
    // means (spec section 13).
    val cnt = Reg(UInt(log2Up(holdCycles + 1) bits)) init 0
    when(exc.resetInstrRetire)  { cnt := U(holdCycles, cnt.getWidth bits) }
      .elsewhen(cnt =/= U(0))   { cnt := cnt - 1 }
    val level = cnt =/= U(0)
    level.simPublic()

    if (gateDispatch) {
      // OPEN-1 Option B. A NON-STICKY hold ORed into the same three terms `coreHalted`
      // already drives. It is deliberately NOT routed through `coreHaltedIn`, which feeds a
      // STICKY latch that only reset clears -- a RESET instruction must not permanently
      // halt the machine.
      rob.logic.periphResetHold := level
    }

    val cpuPeripheralReset =
      if (enable) { val p = out Bool (); p.setName("cpu_peripheral_reset"); p := level; p }
      else level
  }
}
```

**Option B only**, in `src/main/scala/m68k040/rob/RobPlugin.scala`: declare beside
`coreHaltedIn` at `:373`:

```scala
    // OPEN-1 Option B: a NON-STICKY quiesce, driven by PeripheralResetPlugin's 518-cycle
    // RESET-instruction hold. Distinct from `coreHaltedIn` on purpose -- that one feeds a
    // sticky latch only a reset clears, and a RESET instruction must not permanently halt
    // the machine. Idle-defaulted so every build without the plugin is unchanged.
    val periphResetHold = Bool(); periphResetHold.allowOverride; periphResetHold := False
    periphResetHold.simPublic()
```

and OR it into the three consumer sites — `:585`, `:1447`, `:1487-1488` — as:

```scala
    val headReady   = (count > 0) && completes(h0) && !flushing && !coreHalted && !periphResetHold
    ...
    interruptPending := (normalIrqGate || stopped) && !flushing && excIdle && iplActive &&
                        !coreHalted && !periphResetHold
    ...
    _frontendQuiesceActive := stopped || coreHalted || periphResetHold
    _frontendQuiesceNext   := stoppedNext || coreHaltedNext || periphResetHold
```

**Under Option B, the deadlock argument §9.4 demands must be written into the task report, not
assumed:** the hold is time-bounded (518 cycles, unconditional decrement) and gates only
*forward* progress, so it cannot be waited on by anything it blocks; a precise
drain/exception window already blocking retire simply resumes 518 cycles later. State that
explicitly, and add a directed test that a `RESET` retiring immediately before an
exception-entry window still completes.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.socket.PeripheralResetSpec" 2>&1 | tail -20
~/sbt/bin/sbt "testOnly m68k040.exception.* m68k040.rob.*" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: the chosen `OPEN-1` test is real and green, the other is **deleted** (`cancel` in a
merged commit is a failed task); every exception and ROB spec still passes;
`check_socket_netlist.py` passes; `test-fast` green. **Under Option B, additionally run the
full lock-step suite** — the hold changes cycle counts, and while that cannot change
architectural results, the lock-step harness is where a cycle-count assumption would surface:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
~/sbt/bin/sbt "testOnly m68k040.lockstep.*" 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/PeripheralResetPlugin.scala \
        src/main/scala/m68k040/exception/ExceptionUnit.scala \
        src/main/scala/m68k040/rob/RobPlugin.scala \
        src/test/scala/m68k040/socket/PeripheralResetSpec.scala
git commit -m "$(cat <<'EOF'
socket(D22): cpu_peripheral_reset -- the RESET instruction's external indication

A real socket port that both cpu_socket.vh and the debug-ctrl plan's port list
omit, so the debug plan's own conformance check would have passed while silently
missing it. It belongs here because it is an architectural instruction side
effect, not a debug-CSR bit.

Width is 518 core clocks, copied verbatim from commit.v:2081-2101 rather than
re-derived -- MAME charges the 68040 model 518 clocks for RESET and that also
exceeds the part's 124-clock RSTO minimum. Explicitly NOT 512: an earlier spec
draft allowed that as an "if v1 emits a bare pulse" fallback, v1's driver was
then actually read, and it emits a 518-cycle LEVEL. The number is measured.

It is a LEVEL, so a retirement arriving mid-hold re-arms the counter without ever
dropping low.

OPEN-1 is implemented per the signed-off answer in the spec's new section 14.1,
which this task required to exist before it could be written. gateDispatch is a
constructor argument with NO DEFAULT, so this plugin cannot be instantiated by
someone who has not read that answer. Under Option B the hold is a NON-STICKY
quiesce ORed into the same three RobPlugin terms coreHalted already drives -- and
deliberately not routed through coreHaltedIn, which feeds a sticky latch only a
reset clears; a RESET instruction must not permanently halt the machine.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: `M68kSocketTop` — assembly, port surface, and the axi_i contract (D3, D11, D21, D23, D29)

**Files:**
- Create: `src/main/scala/m68k040/socket/SocketAxi.scala`
- Create: `src/main/scala/m68k040/top/SocketTop.scala`
- Create: `synth/ooc_M68kSocketTop.tcl`
- Modify: `tools/socket/check_socket_netlist.py` (nothing — Task 2 already wrote `--socket`; this task is where it stops being a `SKIP`)
- Test: `src/test/scala/m68k040/socket/SocketTopByteOrderSpec.scala`

**Interfaces:**
- Consumes: everything from Tasks 1, 3, 4, 5, 9, 10, 12.
- Produces:
  - `case class SocketAxiI(dataWidth: Int = 256, ...)` and `case class SocketAxiD(dataWidth: Int = 128, ...)` — **flat** `IMasterSlave` bundles whose field names are the socket's own (`arid`, `araddr`, …), so `setName("axi_i")` yields exactly `axi_i_arid`, `axi_i_araddr`, … with no rename shim.
  - `class M68kSocketTop(...) extends Component` with definition name `M68kSocketTop`.
  - `object GenSocketTopVerilog` writing `generated/M68kSocketTop.v`.

**Why flat bundles rather than SpinalHDL's `Axi4`.** SpinalHDL emits an `Axi4` master as
`<name>_ar_payload_addr`, `<name>_aw_payload_id`, … — confirmed in the current netlist, e.g.
`DcachePlugin_logic_axi_aw_payload_addr` and `itlbAxi_aw_payload_len`. `cpu_socket.vh:98-142`
declares `axi_d_awaddr`, `axi_d_awlen`, …, so the stock bundle cannot produce the contract's
names under any `setName`. This is the same reason the debug-ctrl plan wrote its own
`DbgAxiLite` instead of `AxiLite4` (its doc comment: the socket's signal set is *"no more and
no less"*). Declaring the flat bundle also **is** D29's mechanism: the sidebands are not tied
off, they simply do not exist at the boundary.

**D11 / G2 — the `axi_i` contract, and where this repo's responsibility ends.** Spec §5: the
core stays natively 256-bit end-to-end; **no 256→128 downconverter is built in this
repository.** This is a locked user decision that governs over the investigation's own lean
toward native 128-bit narrowing. So this task's whole G2 obligation is to *present and check*
the contract:

| Field | Value | Source |
|---|---|---|
| Data width | 256 bit | `IcachePlugin.scala:39` |
| `arlen` | 1 → two beats | `IcachePlugin.scala:2176` |
| `arsize` | 5 → 32 bytes/beat; 64-byte line | `IcachePlugin.scala:2177` |
| `arburst` | INCR | `IcachePlugin.scala:2178` |
| `araddr` | 64-byte aligned line base | `IcachePlugin.scala:2155,2169` (`& ~U(63,32 bits)`) |
| `arid` | 0-4; 0 = demand, 1-4 = the stream-prefetch window | `AxiIds.scala:49-63` |
| Channels | AR/R only. No AW/W/B ever. | `cpu_socket.vh:99` |
| Byte order | per §2.2, after the socket-top permutation | this task |

**`SOC-1` is the companion change and is a hard blocker for SoC bring-up, not for this plan.**
`cpu_socket.vh:78-87` declares a single `CPU_SOCKET_AXI_DW` used by both masters; it must split
into `CPU_SOCKET_AXI_I_DW` (256) and `CPU_SOCKET_AXI_D_DW` (128), `cpu_stub.v` must follow or
the standalone SoC build breaks, and the fabric path behind `XBAR_M_CPUI` must carry 256-bit
beats or narrow them SoC-side. **None of that may be attempted in this repository.** Until it
lands, this core cannot be dropped into the SoC even with everything in this plan implemented;
Task 14 records that.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/m68k040/socket/SocketTopByteOrderSpec.scala`:

```scala
package m68k040.socket

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Byte order", third and fourth bullets, and the structural D23/D29
  * checks that simulation cannot reach (those are in tools/socket/check_socket_netlist.py).
  *
  * The whole-core socket top is far too large to elaborate in `test-fast`, so what this
  * suite proves is the CONNECTION LAYER: that the permutation is applied to exactly
  * w.data / w.strb / r.data and to nothing else, on a stand-in that has the same shape as
  * the real one. The real one's port surface is proved structurally, on the generated
  * Verilog, in Task 13 Step 4. */
class SocketTopByteOrderSpec extends AnyFunSuite {

  /** Exactly `M68kSocketTop`'s connection layer, over a stub core-side bundle. If the real
    * top's connect method changes, this DUT must be changed with it -- which is the point. */
  class ConnDut extends Component {
    val coreW    = in  Bits (128 bits)
    val coreStrb = in  Bits (16 bits)
    val sockR    = in  Bits (128 bits)
    val coreAddr = in  UInt (32 bits)
    val sockW    = out Bits (128 bits)
    val sockStrb = out Bits (16 bits)
    val coreR    = out Bits (128 bits)
    val sockAddr = out UInt (32 bits)
    sockW    := SocketByteOrder.permuteData(coreW)
    sockStrb := SocketByteOrder.permuteStrb(coreStrb)
    coreR    := SocketByteOrder.permuteData(sockR)
    sockAddr := coreAddr            // NEVER permuted -- D3
  }

  test("a byte written at address A is the byte a socket model reads at address A") {
    SimConfig.compile(new ConnDut).doSim("roundtrip", seed = 1) { dut =>
      dut.coreAddr #= 0
      for (o <- 0 until 16; v <- Seq(0x01, 0x7F, 0xA5, 0xFF)) {
        // Core side: a store of byte value `v` at line offset `o`.
        val coreBytes = (0 until 16).map(i => if (i == o) v else 0)
        dut.coreW    #= coreBytes.zipWithIndex.foldLeft(BigInt(0)) {
                          case (a, (b, i)) => a | (BigInt(b) << (8 * i)) }
        dut.coreStrb #= BigInt(1) << o
        sleep(1)
        // Socket side: a model in the SoC's convention -- the byte at offset 4W+j lives at
        // bit 32W + 24 - 8j, and its strobe bit is 4W + (3-j).
        val w = o / 4; val j = o % 4
        val sockIdx = 4 * w + (3 - j)
        val gotByte = ((dut.sockW.toBigInt >> (8 * sockIdx)) & 0xff).toInt
        assert(gotByte == v, f"offset $o: socket byte $sockIdx is 0x$gotByte%02X, wanted 0x$v%02X")
        assert(dut.sockStrb.toBigInt == (BigInt(1) << sockIdx),
          f"offset $o: socket strobe 0x${dut.sockStrb.toBigInt}%04X, wanted bit $sockIdx")
        // And the read direction restores it -- the involution, end to end.
        dut.sockR #= dut.sockW.toBigInt
        sleep(1)
        assert(dut.coreR.toBigInt == dut.coreW.toBigInt,
          "the read permutation did not restore the core's convention")
      }
    }
  }

  test("D3: the address is never permuted") {
    SimConfig.compile(new ConnDut).doSim("addr-untouched", seed = 2) { dut =>
      dut.coreW #= 0; dut.coreStrb #= 0; dut.sockR #= 0
      for (a <- Seq(0L, 1L, 0x1234_5678L, 0xFFFF_FFF0L)) {
        dut.coreAddr #= a
        sleep(1)
        assert(dut.sockAddr.toLong == a,
          f"address 0x$a%08X came out as 0x${dut.sockAddr.toLong}%08X -- permuting an " +
          f"address is a bug (spec 2.3)")
      }
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo && ~/sbt/bin/sbt "testOnly m68k040.socket.SocketTopByteOrderSpec" 2>&1 | tail -20
python3 tools/socket/check_socket_netlist.py
```

Expected: the spec compiles and passes immediately (it depends only on Task 1), and the
checker still reports `SKIP  no socket netlist ... (expected until Task 13)`. **That SKIP is
this task's red state**; the green state is the same line becoming a run of `PASS`es.

- [ ] **Step 3a: The flat socket bundles**

Create `src/main/scala/m68k040/socket/SocketAxi.scala`:

```scala
package m68k040.socket

import spinal.core._
import spinal.lib.IMasterSlave

/** The `axi_i_*` / `axi_d_*` master surfaces, EXACTLY as `cpu_socket.vh:98-142` declares them.
  *
  * ==Why not SpinalHDL's `Axi4` / `Axi4ReadOnly`==
  * SpinalHDL emits those as `<name>_ar_payload_addr`, `<name>_aw_payload_id`, ... -- confirmed
  * in the current netlist (`DcachePlugin_logic_axi_aw_payload_addr`, `itlbAxi_aw_payload_len`).
  * The socket declares `axi_d_awaddr`, `axi_d_awlen`, ..., which the stock bundle cannot
  * produce under any `setName`. This is the same reason the debug-ctrl work wrote its own
  * `DbgAxiLite` rather than using `AxiLite4`.
  *
  * ==D29: the sidebands do not EXIST here==
  * `prot`/`cache`/`lock`/`qos`/`region` are simply absent, not tied off. The core's own
  * `Axi4Config`s leave SpinalHDL's defaults on, so the netlist currently exports
  * `DcachePlugin_logic_axi_aw_payload_{region,lock,cache,qos,prot}` -- and drives them to `x`.
  * They are genuine don't-cares (the core has no notion of protection, cacheability hint or
  * QoS to express), so anything downstream reading them would be reading X. Declaring
  * socket-side constants instead was rejected deliberately: the socket declares no such
  * ports, so inventing them would export a wider surface than the contract, contradicting
  * D23's "only socket ports" rule in the same breath as satisfying it.
  *
  * ==D11==
  * `axi_i` is AR/R only -- the I-cache never writes -- and 256 bit wide. The SoC-side widening
  * is `SOC-1` and is out of this repository's scope entirely. */
case class SocketAxiI(dataWidth: Int = 256, idWidth: Int = 4, addressWidth: Int = 32)
    extends Bundle with IMasterSlave {
  val arid    = UInt(idWidth bits)
  val araddr  = UInt(addressWidth bits)
  val arlen   = UInt(8 bits)
  val arsize  = UInt(3 bits)
  val arburst = Bits(2 bits)
  val arvalid = Bool()
  val arready = Bool()
  val rid     = UInt(idWidth bits)
  val rdata   = Bits(dataWidth bits)
  val rresp   = Bits(2 bits)
  val rlast   = Bool()
  val rvalid  = Bool()
  val rready  = Bool()

  override def asMaster(): Unit = {
    out(arid, araddr, arlen, arsize, arburst, arvalid, rready)
    in(arready, rid, rdata, rresp, rlast, rvalid)
  }
}

/** The full read/write data master. See `SocketAxiI` for the naming and D29 rationale. */
case class SocketAxiD(dataWidth: Int = 128, idWidth: Int = 4, addressWidth: Int = 32)
    extends Bundle with IMasterSlave {
  val awid    = UInt(idWidth bits)
  val awaddr  = UInt(addressWidth bits)
  val awlen   = UInt(8 bits)
  val awsize  = UInt(3 bits)
  val awburst = Bits(2 bits)
  val awvalid = Bool()
  val awready = Bool()
  val wdata   = Bits(dataWidth bits)
  val wstrb   = Bits(dataWidth / 8 bits)
  val wlast   = Bool()
  val wvalid  = Bool()
  val wready  = Bool()
  val bid     = UInt(idWidth bits)
  val bresp   = Bits(2 bits)
  val bvalid  = Bool()
  val bready  = Bool()
  val arid    = UInt(idWidth bits)
  val araddr  = UInt(addressWidth bits)
  val arlen   = UInt(8 bits)
  val arsize  = UInt(3 bits)
  val arburst = Bits(2 bits)
  val arvalid = Bool()
  val arready = Bool()
  val rid     = UInt(idWidth bits)
  val rdata   = Bits(dataWidth bits)
  val rresp   = Bits(2 bits)
  val rlast   = Bool()
  val rvalid  = Bool()
  val rready  = Bool()

  override def asMaster(): Unit = {
    out(awid, awaddr, awlen, awsize, awburst, awvalid,
        wdata, wstrb, wlast, wvalid, bready,
        arid, araddr, arlen, arsize, arburst, arvalid, rready)
    in(awready, wready, bid, bresp, bvalid, arready, rid, rdata, rresp, rlast, rvalid)
  }
}
```

- [ ] **Step 3b: The socket top**

Create `src/main/scala/m68k040/top/SocketTop.scala`. It instantiates `M68kCore` with the
**same plugin list as `GenFullCoreSynthVerilog`** plus the socket-only plugins, honouring the
ordering constraints Tasks 5/9/10/12 state, and connects the two masters field-by-field.

```scala
package m68k040.top

import m68k040.{M68kParams, M68kSpinalConfig}
import m68k040.cache.{DcachePlugin, IcachePlugin}
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.mmu.{DtlbPlugin, ItlbPlugin, MmuControlPlugin}
import m68k040.socket._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** The socket-conformant top level (design spec D3, D11, D21, D23, D29).
  *
  * ==What crosses this boundary, and what does not (D23)==
  * ONLY `cpu_socket.vh` ports. `M68kFullCoreSynth`'s 37 probe/test IOs do not: they stay on
  * that target, whose port surface and behaviour are unchanged and which remains the
  * OOC-synth / FMax gate. Here the anchor is the socket itself -- real AXI masters, IPL in,
  * `ipl_ack` out -- so nothing prunes and no artificial anchoring is needed. The
  * `redirect`/`resume` ports become internal under D12/D16 rather than being exported.
  *
  * ==The permutation, applied EXACTLY ONCE PER MASTER (D3)==
  * Only `w.data`, `w.strb` and `r.data`. Never address, id, len, size, burst, resp or last.
  * Applying it to an address would be a bug; applying it TWICE would be a no-op that looks
  * like a fix (it is an involution). `tools/socket/check_socket_netlist.py` and
  * `SocketTopByteOrderSpec` are the machine-checked forms of both statements.
  *
  * ==D21: reset kind and name==
  * The core stays ASYNC active-high; only the socket top's PORT is renamed `reset` -> `rst`,
  * and the `ClockDomainConfig` is declared EXPLICITLY here rather than inherited from a
  * SpinalHDL default a future upgrade could change silently.
  *
  * The divergence from the socket's literal "synchronous active-high" wording is named
  * rather than glossed: the SoC's `cpu_rst` is already core_clk-synchronous
  * (`fpga_top_cpu.vh:77,108-110`), so its deassertion is synchronous to the destination clock
  * by construction and there is no recovery/removal hazard at the async-reset consumers. An
  * async-reset consumer fed by a synchronously deasserted source is strictly MORE permissive
  * than a sync-reset one. Switching to `resetKind = SYNC` was rejected because it converts
  * every register's reset in the design -- a real, unquantified risk to the current
  * post-route result for zero functional gain. */
class M68kSocketTop(p: M68kParams = M68kParams(),
                    dbgBuildId: BigInt = 0) extends Component {
  setDefinitionName("M68kSocketTop")
  noIoPrefix()

  val clk = in Bool ()
  val rst = in Bool ()

  val coreCd = ClockDomain(
    clock  = clk,
    reset  = rst,
    config = ClockDomainConfig(clockEdge        = RISING,
                               resetKind        = ASYNC,
                               resetActiveLevel = HIGH))

  val socket = coreCd on new Area {
    // ── Plugin list: GenFullCoreSynthVerilog's, plus the socket-only ones ────────────
    // ORDERING IS LOAD-BEARING and each constraint is stated at its own plugin:
    //   AxiDMergePlugin  after Dcache/Itlb/Dtlb, before ResetVectorPlugin and BackendWiring
    //   ResetVectorPlugin after AxiDMergePlugin and FetchAlignPlugin, before BackendWiring
    //   BackendWiringPlugin last (it reads the arbiter's wedge and the reset vector's SSP)
    val eu0 = new m68k040.execute.AluEuPlugin
    val eu1 = new m68k040.execute.AluEuPlugin
    val branchEu = new m68k040.execute.BranchEuPlugin
    val lsEu = new m68k040.execute.LsEuPlugin
    val divEu = new m68k040.execute.DivEuPlugin
    val icache = new IcachePlugin()
    val merge  = new AxiDMergePlugin()
    val iplAck = new IplAckPlugin(enable = true)
    val periph = new PeripheralResetPlugin(enable = true,
                                           gateDispatch = SocketTopConfig.OPEN1_GATE_DISPATCH)

    val core = new M68kCore(Seq[FiberPlugin](
      new ParamPlugin(p),
      new MmuControlPlugin(),
      new m68k040.execute.FpuControlPlugin(),
      new m68k040.exception.InterruptControlPlugin(),
      new ItlbPlugin(socketMerged = true),
      new DtlbPlugin(socketMerged = true),
      icache,
      new DcachePlugin(socketMerged = true),
      new m68k040.frontend.BtbPlugin(),
      new m68k040.frontend.FtbPlugin(),
      new m68k040.frontend.RasPlugin(),
      new m68k040.frontend.GsharePlugin(),
      new m68k040.frontend.FetchAlignPlugin(enableFetchDirected = true),
      new m68k040.decode.DecodeStage(),
      new m68k040.rename.RenameStage(),
      new m68k040.dispatch.DispatchPlugin(),
      new m68k040.rob.RobPlugin(),
      new m68k040.execute.iq.IssueQueuePlugin(),
      eu0, eu1, branchEu, lsEu, divEu,
      new m68k040.execute.regfile.RegFilePluginInt(),
      new m68k040.execute.regfile.RegFilePluginNzvc(),
      new m68k040.execute.regfile.RegFilePluginX(),
      new m68k040.execute.regfile.RegFilePluginFp(),
      new m68k040.execute.regfile.RegFilePluginFpcc(),
      merge,
      new ResetVectorPlugin(enable = true),
      iplAck,
      periph,
      new BackendWiringPlugin(eu0, eu1, branchEu, lsEu, divEu),
      new m68k040.debug.DebugCtrlPlugin(buildId = dbgBuildId, stage = 1)
    ))
  }

  // ── axi_i: the I-cache, permuted on r.data only (D3, D11) ─────────────────────────
  val axi_i = master(SocketAxiI(dataWidth = 256, idWidth = m68k040.cache.AxiIds.ID_W))
  axi_i.setName("axi_i")
  private val ic = socket.icache.logic.axi
  axi_i.arid    := ic.ar.payload.id
  axi_i.araddr  := ic.ar.payload.addr
  axi_i.arlen   := ic.ar.payload.len
  axi_i.arsize  := ic.ar.payload.size
  axi_i.arburst := ic.ar.payload.burst
  axi_i.arvalid := ic.ar.valid
  ic.ar.ready   := axi_i.arready
  ic.r.valid          := axi_i.rvalid
  ic.r.payload.id     := axi_i.rid
  // THE ONLY permuted signal on this master. Applying it to an address would be a bug;
  // applying it twice would be a no-op that looks like a fix (SocketByteOrder is an
  // involution). It appears exactly once, here.
  ic.r.payload.data   := SocketByteOrder.permuteData(axi_i.rdata)
  ic.r.payload.resp   := axi_i.rresp
  ic.r.payload.last   := axi_i.rlast
  axi_i.rready  := ic.r.ready

  // ── axi_d: the merged master, permuted on w.data / w.strb / r.data (D3) ───────────
  val axi_d = master(SocketAxiD(dataWidth = 128, idWidth = m68k040.cache.AxiIds.ID_W))
  axi_d.setName("axi_d")
  private val dm = socket.merge.logic.axi
  axi_d.awid    := dm.aw.payload.id
  axi_d.awaddr  := dm.aw.payload.addr
  axi_d.awlen   := dm.aw.payload.len
  axi_d.awsize  := dm.aw.payload.size
  axi_d.awburst := dm.aw.payload.burst
  axi_d.awvalid := dm.aw.valid
  dm.aw.ready   := axi_d.awready
  // Permuted (2 of 3). The D5 store-size derivation ran on the CORE-SIDE strobe, inside
  // DcachePlugin, and MUST have: nibble reversal preserves popcount and contiguity but not
  // the offset a run starts at, so deriving from what leaves here would give the
  // mirror-image address. These two transforms are order-dependent.
  axi_d.wdata   := SocketByteOrder.permuteData(dm.w.payload.data)
  axi_d.wstrb   := SocketByteOrder.permuteStrb(dm.w.payload.strb)
  axi_d.wlast   := dm.w.payload.last
  axi_d.wvalid  := dm.w.valid
  dm.w.ready    := axi_d.wready
  dm.b.valid        := axi_d.bvalid
  dm.b.payload.id   := axi_d.bid
  dm.b.payload.resp := axi_d.bresp
  axi_d.bready  := dm.b.ready
  axi_d.arid    := dm.ar.payload.id
  axi_d.araddr  := dm.ar.payload.addr
  axi_d.arlen   := dm.ar.payload.len
  axi_d.arsize  := dm.ar.payload.size
  axi_d.arburst := dm.ar.payload.burst
  axi_d.arvalid := dm.ar.valid
  dm.ar.ready   := axi_d.arready
  dm.r.valid        := axi_d.rvalid
  dm.r.payload.id   := axi_d.rid
  dm.r.payload.data := SocketByteOrder.permuteData(axi_d.rdata)   // permuted (3 of 3)
  dm.r.payload.resp := axi_d.rresp
  dm.r.payload.last := axi_d.rlast
  axi_d.rready  := dm.r.ready

  // ── Interrupt seam (D18) ──────────────────────────────────────────────────────────
  val cpu_ipl = in UInt (3 bits)
  val ipl_ack = out Bool ()
  private val bw = socket.core.plugins.collectFirst { case b: BackendWiringPlugin => b }.get
  bw.logic.iplInPort := cpu_ipl
  // D18: the socket declares NO vector input, so all seven levels take autovectors 25-31 --
  // what the Mac hardware does and what v1 does. The existing RegNext(...) init 0
  // synchroniser inside BackendWiringPlugin is retained untouched: it is the correct
  // placement AND it keeps the IPL compare cone non-foldable, which the OOC flow relies on.
  bw.logic.iackAvecIn   := True
  bw.logic.iackVectorIn := U(0, 8 bits)
  ipl_ack := socket.iplAck.logic.iplAck

  // ── RESET instruction output (D22) ────────────────────────────────────────────────
  val cpu_peripheral_reset = out Bool ()
  cpu_peripheral_reset := socket.periph.logic.cpuPeripheralReset

  // ── Probe/test inputs of the core that never reach the socket (D23) ───────────────
  // Driven to their idle values here so nothing dangles. They are INPUTS of M68kCore, not
  // socket ports; D12/D16 make redirect/resume internal rather than exported.
  private val fa = socket.core.plugins.collectFirst {
    case f: m68k040.frontend.FetchAlignPlugin => f }.get
  fa.logic.redirect.valid   := False
  fa.logic.redirect.payload := U(0, 32 bits)
  fa.logic.resume.valid     := False
  fa.logic.resume.payload   := U(0, 32 bits)
  socket.icache.logic.invalidateAll := False
  socket.core.plugins.collectFirst { case r: m68k040.rob.RobPlugin => r }
    .get.logic.flush.valid := False

  // ── dbg_axi and the SoC-fabric control group pass straight through ────────────────
  // Socket groups 4 and 6. They are DEBUG-CTRL-OWNED (spec section 10) and this task adds,
  // removes and reinterprets nothing about them -- it only plumbs the ports the debug-ctrl
  // Stage 1 work already put on the core out to the socket boundary, because D23 says the
  // socket top exports the socket's ports and these ARE socket ports.
  // <connect DebugCtrlPlugin's dbgAxi bundle and the five control signals here, field by
  //  field, with the SAME names cpu_socket.vh:145-176 declares -- see the checker's
  //  `allowed` regex for the exact list>
}

/** The `OPEN-1` answer, in ONE place, so the socket top and any future target cannot
  * disagree. Set from the signed-off addendum in the design spec's section 14.1. */
object SocketTopConfig {
  val OPEN1_GATE_DISPATCH: Boolean = ???   // Task 11's answer; `???` must not survive Task 12
}

object GenSocketTopVerilog {
  def main(args: Array[String]): Unit = {
    val dbgBuildId: BigInt = sys.env.get("DBG_BUILD_ID") match {
      case None    => BigInt(0)
      case Some(s) =>
        val hex = s.trim.stripPrefix("0x").stripPrefix("0X")
        require(hex.nonEmpty && hex.forall(c => "0123456789abcdefABCDEF".contains(c)),
          s"DBG_BUILD_ID must be hexadecimal (got '$s')")
        BigInt(hex, 16)
    }
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kSocketTop(M68kParams(), dbgBuildId))
    println("Generated generated/M68kSocketTop.v")
  }
}
```

**Three things the implementer must finish rather than copy:**

1. The `dbg_axi` / SoC-fabric-control passthrough block, marked `<connect ...>` above. The
   exact 22 names are in `cpu_socket.vh:145-176` and in `tools/debug/debug_regmap.def`'s `PORT`
   records; the checker's `allowed` regex in `tools/socket/check_socket_netlist.py` is the
   authoritative list for this plan. **Do not redesign anything about `dbg_axi`** — spec §10
   puts it entirely under the debug-ctrl spec.
2. `SocketTopConfig.OPEN1_GATE_DISPATCH`'s `???`. Task 12 replaces it with the signed-off
   boolean; a `???` surviving into a commit is a failed task.
3. Any plugin the real `GenFullCoreSynthVerilog` list gains between now and execution. **Diff
   the two lists** rather than trusting the copy above:
   `diff <(sed -n '/Seq\[FiberPlugin\]/,/setDefinitionName/p' src/main/scala/m68k040/top/FullCoreSynth.scala) ...`

- [ ] **Step 3c: The OOC gate script for the new top**

Create `synth/ooc_M68kSocketTop.tcl`, mirroring `synth/ooc_M68kFullCoreSynth.tcl` exactly:

```tcl
read_verilog generated/M68kSocketTop.v
read_xdc synth/clk.xdc
synth_design -top M68kSocketTop -part xcku5p-ffvb676-2-e -mode out_of_context
report_utilization -file synth/M68kSocketTop_util.rpt
report_timing_summary -max_paths 8 -file synth/M68kSocketTop_timing.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "RESULT SocketTop WNS $wns FMAX [expr {1000.0/(4.000 - $wns)}]"
```

Do **not** modify `synth/impl_FullCore.tcl`, `synth/ooc_M68kFullCoreSynth.tcl`,
`synth/impl_FullCore_perf.tcl` or `synth/synth_only_m5.tcl`: they hardcode
`generated/M68kFullCoreSynth.v` and `-top M68kFullCoreSynth`, that target is the FMax
reference this branch's whole history is measured against, and repointing it would silently
re-baseline every future gate.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
free -g | head -2
~/sbt/bin/sbt "testOnly m68k040.socket.SocketTopByteOrderSpec" 2>&1 | tail -20
~/sbt/bin/sbt "runMain m68k040.top.GenSocketTopVerilog"
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
python3 tools/socket/check_socket_netlist.py
grep -cE '^\s*(input|output)' <(awk '/^module M68kSocketTop/,/^\);/' generated/M68kSocketTop.v)
grep -nE 'axi_i_(aw|w_|b)' generated/M68kSocketTop.v | head
grep -cE '(prot|cache|lock|qos|region)\s*[,;)]' <(awk '/^module M68kSocketTop/,/^\);/' generated/M68kSocketTop.v)
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -12
```

Expected: the byte-order spec green; **`check_socket_netlist.py` reports `PASS` on every
socket check and no longer `SKIP`s** — that transition is this task's green state; the second
`grep` finds nothing (no AW/W/B on `axi_i`); the third `grep` counts `0` (D29); and the
`M68kFullCoreSynth` freeze still passes, which is what proves the socket top was added
*alongside* the FMax target rather than by mutating it.

- [ ] **Step 5: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
git add src/main/scala/m68k040/socket/SocketAxi.scala \
        src/main/scala/m68k040/top/SocketTop.scala \
        synth/ooc_M68kSocketTop.tcl \
        src/test/scala/m68k040/socket/SocketTopByteOrderSpec.scala
git commit -m "$(cat <<'EOF'
socket(D3/D11/D21/D23/D29): M68kSocketTop -- the conformant boundary

Presents exactly cpu_socket.vh's port surface and nothing else. The 37 probe/test
IOs stay on M68kFullCoreSynth, whose port surface and behaviour are unchanged and
which remains the OOC/FMax gate target; here the anchor is the socket itself, so
nothing prunes and no artificial anchoring is needed.

Flat SocketAxiI/SocketAxiD bundles rather than SpinalHDL's Axi4, for the same
reason the debug-ctrl work wrote DbgAxiLite rather than using AxiLite4: SpinalHDL
emits <name>_ar_payload_addr and the contract says axi_d_araddr, which no setName
can reconcile. Declaring the flat bundle IS D29's mechanism -- prot/cache/lock/
qos/region do not exist at the boundary rather than being tied off, which also
removes five X-driven top-level outputs. Socket-side constants were rejected
deliberately: the socket declares no such ports, so inventing them would export a
wider surface than the contract in the same breath as claiming to satisfy it.

The permutation is applied EXACTLY ONCE PER MASTER and to exactly w.data, w.strb
and r.data. Applying it to an address would be a bug; applying it twice would be
a no-op that looks like a fix, because it is an involution. Both statements carry
a comment at the call site and both are machine-checked -- structurally on the
generated Verilog, and in simulation against a socket-convention model.

D11: axi_i stays natively 256-bit, len=1/size=5, AR/R only. No downconverter is
built here; SOC-1 (splitting CPU_SOCKET_AXI_DW per master and widening the
XBAR_M_CPUI path) is the companion change and a hard blocker for SoC bring-up,
not for this plan.

D21: the core keeps its ASYNC active-high reset; only the port is renamed to
`rst`, and the ClockDomainConfig is declared explicitly rather than inherited
from a default a SpinalHDL upgrade could change silently. The divergence from the
socket's "synchronous" wording is named: cpu_rst is already core_clk-synchronous
SoC-side, so an async consumer fed by a synchronously deasserted source is
strictly more permissive.

The existing synth scripts are deliberately NOT repointed: they hardcode
M68kFullCoreSynth, that is the reference this branch's whole FMax history is
measured against, and repointing would silently re-baseline every future gate.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: Full acceptance gate

**Files:**
- Create: `.superpowers/sdd/progress-axi-socket-adapter-2026-08-18.md`
- Test: `make test-fast`, `make test-verilator`, the lock-step suite, `check_socket_netlist.py`, and the post-route Vivado gate

**Interfaces:**
- Consumes: everything from Tasks 1-13.
- Produces: the recorded pass/fail evidence for spec §13's gate list and the cross-repo
  blocker status, in the project's standard progress-ledger form.

- [ ] **Step 1: Run the whole software suite**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
free -g | head -2                        # budget: max 2 heavy JVMs, none during vivado
make SBT=~/sbt/bin/sbt test-fast 2>&1 | tail -30
~/sbt/bin/sbt "testOnly m68k040.cache.MmioLoadSizingSpec m68k040.cache.MmioStoreSizingSpec" 2>&1 | tail -20
~/sbt/bin/sbt "testOnly m68k040.lockstep.*" 2>&1 | tail -20
```

Expected: `test-fast` reports the pre-existing count **plus 38** new untagged tests —
`SocketByteOrderSpec` 7, `HaltReasonSpec` 5, `WalkerIdGuardSpec` 2, `AxiDMergeSpec` 6,
`MmioCoverSpec` 8, `ResetVectorSpec` 3, `IplAckSpec` 2, `PeripheralResetSpec` 3,
`SocketTopByteOrderSpec` 2. Re-read the actual
pre-existing number rather than trusting the 225 recorded at `5b3cc74`. The two
Verilator-tagged MMIO suites (8 tests) run separately, as does the lock-step corpus — which
spec §13 asks for by name, and which is where `IplAckPlugin`'s count-equality assertion is
exercised at scale. Any pre-existing failure must be reproduced on the parent commit before
being accepted as unrelated.

- [ ] **Step 2: Confirm the invariants this plan promised not to break**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
# 1. No Global key was added (13, byte-identical).
git diff --stat $(git merge-base HEAD main) -- src/main/scala/m68k040/Global.scala
grep -c 'Database\.' src/main/scala/m68k040/Global.scala
# 2. No numeric AXI ID literal escaped AxiIds.scala.
grep -rnE 'payload\.id\s*:=\s*U\(\s*[0-9]' src/main/scala/ || echo "no raw ID literal -- correct"
# 3. WALK_READ / WALK_WRITE were not renumbered (spec 4.3 forbids it).
grep -nE 'val (WALK_READ|WALK_WRITE|D_STORE|D_PUSH|D_EVICT|RESET_VEC)' src/main/scala/m68k040/cache/AxiIds.scala
# 4. The standing no-SoC-address-map rule: no address range or decode table under src/main.
grep -rnE '0x[0-9A-Fa-f]{6,}' src/main/scala/m68k040/socket/ || echo "no address literal in the socket package -- correct"
# 5. M68kFullCoreSynth's port surface is frozen.
~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
~/sbt/bin/sbt "runMain m68k040.top.GenSocketTopVerilog"
python3 tools/socket/check_socket_netlist.py
md5sum generated/M68kFullCoreSynth.v generated/M68kSocketTop.v
# 6. No `???` or unresolved OPEN-1 placeholder survived.
grep -rn '???' src/main/scala/m68k040/top/SocketTop.scala && echo "FAIL: OPEN-1 placeholder survived"
grep -rn 'cancel(' src/test/scala/m68k040/socket/PeripheralResetSpec.scala && echo "FAIL: an OPEN-1 test was left cancelled"
```

Expected: an empty `Global.scala` diff and `13`; both "correct" messages; the six ID constants
at their original values plus `RESET_VEC = 5`; `All checks passed.` from the checker with the
socket half now running rather than skipping; two MD5s recorded for the ledger; and **no
output at all** from the two `grep`s in item 6.

- [ ] **Step 3: Read the CURRENT FMax/area reference — do not assume 197.278**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
ls -t .superpowers/sdd/progress-*.md | head -5
grep -rnE 'ACHIEVED_FMAX|POSTROUTE_FULLCORE_RESULT|CLB LUTs|CLB Registers' \
  .superpowers/sdd/progress-fmax-closure-fanout-2026-08-17.md | tail -12
ls -t synth/archive/ | head -5
grep -nE 'CLB LUTs|CLB Registers|DSPs|Block RAM Tile' \
  synth/archive/$(ls -t synth/archive/ | head -1)/fullcore_route_util.rpt | head -10
```

Record: the most recent post-route `ACHIEVED_FMAX`, the commit it belongs to, and that
commit's absolute `CLB LUTs` / `CLB Registers`. Those three are this gate's reference. The
value recorded when this plan was written was **197.278 MHz** at netlist commit `e267df3`
(slice `5aae2c5`, task #219) with **124263 LUTs / 55861 FF**, superseding the earlier 189.502
at `c125d96` — but a later gate may have moved it again, and **the value read here wins**.

If the newest archive does not correspond to this branch's parent commit, produce a real
baseline rather than interpolating:

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
# The parent of this plan's FIRST commit -- found BY NAME, never by a hardcoded ~N, because a
# rebase or an extra fix-up commit would silently shift the count.
BASE=$(git rev-parse "$(git log --format='%H %s' | grep -m1 'socket(D1/D2): boundary byte-order' | cut -d' ' -f1)^")
echo "baseline commit: $BASE"
git worktree add /tmp/socket-baseline "$BASE"
cd /tmp/socket-baseline && ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
```

and gate that tree first, serially. Never `git checkout <sha>` in the shared tree, and never
run two Vivado sessions at once.

- [ ] **Step 4: Run the post-route gate on `M68kFullCoreSynth`**

This is the gate that matters for the standing FMax discipline, because `M68kFullCoreSynth` is
the target every prior number on this branch describes. Tasks 3, 4, 7, 8, 9 and 12 all changed
RTL inside it (halt seam, walker guards, both MMIO sequencers, the idle redirect source, the
`RESET` observation), so it is a genuinely different netlist even though its port surface is
identical.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
pgrep -a vivado || echo "no vivado running -- safe to launch"
pgrep -af 'jtag|xsdb|hw_server' || echo "no JTAG session live -- safe to launch"
uptime; free -g | head -2
env IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=9 \
  vivado -mode batch -source synth/impl_FullCore.tcl > synth/gate_axi_socket.out 2>&1
```

Launch this with the Bash tool's own `run_in_background` parameter, **not** shell-level
`nohup` — the harness-tracked mechanism reliably fires a completion notification, nested
`nohup` does not. Expect tens of minutes. Use the same recipe as the reference verbatim
(`IMPL_STRATEGY=postrouteN POSTROUTE_ROUNDS=9`, floorplan `decode+fetch`, a fresh
`synth_design`, no `REUSE_SYNTH_DCP`); delete any stale `synth/fullcore_synth.dcp` first so no
checkpoint is silently reused.

**Verify round-count convergence per-netlist** rather than inheriting a round count. Ledger
§39's standing lesson: an early-looking optimizer plateau is not proof of a real one. Record
the per-round WNS series, as the task-#219 gate did (`-1.381, -1.151, -1.085, -1.069,
-1.069 x6`).

Expected in `synth/gate_axi_socket.out`: `SOURCE_MD5` equal to `NETLIST_MD5` equal to Step 2's
`M68kFullCoreSynth.v` MD5, zero errors, and a final
`POSTROUTE_FULLCORE_RESULT ... ACHIEVED_FMAX_MHZ <n>` line.

- [ ] **Step 5: Run the OOC gate on `M68kSocketTop`**

Serially, after Step 4 finishes. This one has no historical reference, so it is a **cost
report, not a pass/fail** — the arbiter, the two sequencers' socket-side halves, the boot FSM
and the permutation all exist only here.

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
pgrep -a vivado || echo "safe to launch"
vivado -mode batch -source synth/ooc_M68kSocketTop.tcl > synth/gate_socket_top.out 2>&1
grep -nE 'RESULT SocketTop' synth/gate_socket_top.out
grep -nE 'CLB LUTs|CLB Registers|DSPs|Block RAM Tile' synth/M68kSocketTop_util.rpt | head
grep -nE 'Slack|Source:|Destination:' synth/M68kSocketTop_timing.rpt | head -40
```

Record the absolute numbers and, critically, **whether any top timing endpoint is inside
`AxiDMerge`, the INHIBITED sequencers, or `SocketByteOrder`**. The permutation cannot appear —
spec §2.2 property 3: *"It has zero logic depth. It is a renaming of wires. It cannot appear on
a timing path and cannot affect the post-route FMax result."* If it does appear, that is
evidence the connection was written as something other than a wire permutation, and it must be
investigated rather than accepted.

- [ ] **Step 6: Judge the `M68kFullCoreSynth` gate**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
grep -nE 'SOURCE_MD5|NETLIST_MD5|POSTROUTE_FULLCORE_RESULT|ACHIEVED_FMAX|SIGNOFF' synth/gate_axi_socket.out
grep -nE 'CLB LUTs|CLB Registers|DSPs|Block RAM Tile' synth/fullcore_route_util.rpt | head -10
grep -nE 'Slack|Source:|Destination:' synth/fullcore_route_timing.rpt | head -60
grep -cE 'MmioCover|subP|stSubP|haltReason' synth/fullcore_route_timing.rpt
```

The four gate conditions this plan adopted (see Global Constraints — the spec states no budget
of its own, so these follow the debug-ctrl plan's precedent and are recorded here as a
**proposal the gate then measures**):

1. **LUT delta ≤ +1.0%** of the reference's absolute `CLB LUTs`.
2. **FF delta ≤ +1.0%** of the reference's absolute `CLB Registers`.
3. **Zero DSP and zero BRAM delta.** Nothing in this plan should infer either; a non-zero
   delta means a `Vec`/`Mem` was created accidentally and must be found, not accepted.
4. **Achieved FMax no worse than 2%** below the reference, and above the architecture's hard
   floor.
5. **No top timing endpoint is inside the new MMIO sequencer or the halt seam.** The last
   `grep` should find them in none of the reported top paths. If it does, the two candidate
   remedies, in order: register the cover's `stepLog2` output (it feeds only the AXI payload,
   which is already a registered handshake), or move the 16-way byte merge in `REFILL` behind
   the existing `r.fire` register boundary.

If any condition fails, do **not** accept the tranche. Report which, with the absolute numbers.

- [ ] **Step 7: Write the progress ledger**

Create `.superpowers/sdd/progress-axi-socket-adapter-2026-08-18.md` with, at minimum:

- the parent commit, and every task's commit hash;
- the software-suite results (`test-fast` count before and after, `test-verilator` for the two
  MMIO suites, the lock-step corpus);
- the reference FMax/LUT/FF triple with its source commit **and the file it was read from**;
- this gate's absolute numbers and deltas for all five conditions, plus the per-round WNS
  convergence series;
- the `SOURCE_MD5`/`NETLIST_MD5` match for both netlists;
- contention status at launch (load average, free memory, whether any Vivado or JTAG session
  was live) — this project has documented history of contended FMax numbers being worthless
  (214.3 vs 163.9 MHz for an identical commit), so a gate without this record is not evidence;
- the `M68kSocketTop` OOC cost report, and explicitly whether `SocketByteOrder` appeared on any
  timing path (it must not);
- **which §13 obligations are enforced in RTL versus in the test suite.** RTL: the four §4.4
  arbiter assertions, D27's fail-closed guards, the D30 alignment/extent asserts in both
  sequencers, D17's count equality, D14's a7Wr collision assert. Test suite: the 48+48 exact
  cover enumeration, the byte-order involution, the D9 deadlock case, first-wins halt
  ordering, and the D22 hold width — because each of those is a statement about a *set of
  cases* or a *whole build*, not a signal a hardware assertion can observe;
- **the cross-repo blocker table, restated with current status**, so the next session does not
  have to re-derive it:

  | Item | Repo | Status | Blocks |
  |---|---|---|---|
  | `SOC-1` | `macqd700-soc` | NOT DONE | **Hard blocker for SoC bring-up.** Nothing in this plan depends on it. Split `CPU_SOCKET_AXI_DW` into `..._AXI_I_DW` (256) / `..._AXI_D_DW` (128); update `cpu_stub.v`; carry 256-bit beats behind `XBAR_M_CPUI` or narrow them SoC-side. |
  | `SOC-2` | `macqd700-soc` | NOT DONE | Documentation only. Add `cpu_peripheral_reset` to `cpu_socket.vh` §6; the wrapper port already exists at `m68k_axi_wrapper.v:683`. |
  | `SOC-3` | `macqd700-soc` | NOT DONE | Integration step. Instantiate this core in place of v1, dropping `if_to_axi.v` and `axi_narrow_to_wide.v`. This is what removes the 20 s timer — discharged, not regressed, per D19. |
  | `SOC-4` | `macqd700-soc` | **Finding stands; prescription SUPERSEDED** | Not a blocker. `peripheral_bus.v` drops all but one byte of a multi-hot-WSTRB write on every `pb_*` slot except ASC/SONIC-word/SCSI-DMA, and v1 is exposed today. **The fix is `SI2` in `docs/superpowers/specs/2026-08-18-v1-shared-infra-fixes-design.md` §2.2, not SOC-4's own prescription** — generalising the ASC byte-address-incrementing FSM would pulse the same register N times on VIA1/VIA2/IWM/SCSI, whose device-local address ignores `addr[1:0]`. Strictly worse than today. |

  Also record the three `NOTED` items so they are not re-opened: `NOTED-1` (AXI in flight
  during reset — the SoC already compensates), `NOTED-2` (bursts onto lite-only slaves — the
  fabric error-terminates them and the I-cache's existing `r.resp` check turns that into a
  clean vector-2), `NOTED-3` (the fabric is single-outstanding per master port, so the
  I-cache's five refill IDs deliver zero end-to-end benefit until the xbar is reworked — which
  is also why D8's serializing arbiter costs nothing measurable).

- [ ] **Step 8: Commit**

```bash
cd /home/qwertyoruiop/m68k-core-040-ooo
mkdir -p synth/archive/$(git rev-parse --short HEAD)_axi_socket_adapter
cp synth/fullcore_route_timing.rpt synth/fullcore_route_util.rpt \
   synth/fullcore_synth_timing.rpt synth/fullcore_synth_util.rpt \
   synth/gate_axi_socket.out synth/gate_socket_top.out \
   synth/M68kSocketTop_util.rpt synth/M68kSocketTop_timing.rpt \
   synth/archive/$(git rev-parse --short HEAD)_axi_socket_adapter/
git add .superpowers/sdd/progress-axi-socket-adapter-2026-08-18.md synth/archive/
git commit -m "$(cat <<'EOF'
socket: acceptance gate -- post-route numbers, deltas, and the ledger

Records the gate for the whole axi_i/axi_d socket-adapter tranche against the
CURRENT post-route reference, read at gate time from the FMax-closure ledger
rather than hardcoded: LUT and FF deltas versus the +1.0% budget this plan adopts
from the debug-ctrl precedent (the design spec states no budget of its own), zero
DSP/BRAM delta, achieved FMax versus the 2% band, and the check that no top
timing endpoint is inside the new MMIO sequencer or the halt seam.

Two netlists are gated: M68kFullCoreSynth, which is the target every prior number
on this branch describes and which six of these tasks changed the internals of,
and M68kSocketTop, which has no historical reference and is therefore a cost
report rather than a pass/fail. SocketByteOrder must appear on NO timing path --
it is a renaming of wires, and if it shows up, the connection was written as
something other than a permutation.

Records which section 13 obligations are RTL assertions and which are enforced by
the suite, and the cross-repo blocker table with current status -- including that
SOC-4's FINDING stands but its PRESCRIPTION is superseded by SI2, because
generalising the ASC byte-address-incrementing FSM would pulse the same register N
times on the slots whose device-local address ignores addr[1:0]: strictly worse
than today's single wrong byte.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

Performed against this plan before handing it off. Findings were fixed in place, not just
listed.

### Spec coverage — every D-numbered decision maps to a task

| Decision | Task(s) | Note |
|---|---|---|
| **D1** permutation at the boundary, not in the core | 1, 13 | |
| **D2** per-32-bit-lane byte reversal + nibble-reversed WSTRB | 1 | Exhaustive, plus the explicit "not a full reverse" negative |
| **D3** lives in `M68kSocketTop`, once per master, only `w.data`/`w.strb`/`r.data` | 13 | Structurally checked in 2/13, behaviourally in 13 |
| **D4** real `AxSIZE` + byte-granular address for every INHIBITED access | 7, 8 | |
| **D5** store derivation on the CORE-SIDE strobe | 8 | Ordering hazard stated at both sites (`MmioCover`, `SocketTop`) |
| **D6** bounded exact-cover sequencer inside `DcachePlugin` | 6 (math), 7 (load), 8 (store) | Not split from D30 — same sequencer |
| **D7** 4 masters → 2 | 5, 13 | |
| **D8** owner-tag arbiter, ID never consulted for routing | 5 | Identical-ID directed test |
| **D9** independent per-direction grants | 5 | `refillWriteHold` deadlock case, directed |
| **D10** the D-cache's B-by-ID demux preserved untouched | 5, 8 | Preserved per sub-transaction |
| **D11** `axi_i` stays 256-bit; no downconverter here | 13 | Contract table + netlist width check |
| **SOC-1** | 14 (recorded only) | Out of repo |
| **D12** real vector-0 AXI fetch | 9 | |
| **D13** rides `axi_d`, not a third master | 5 (owner), 9 (FSM) | |
| **D14** SSP via the existing shared `a7Wr` port, N then N+1 | 9 | Collision assert included |
| **D15** non-OKAY vector response halts, no vector-2 frame | 9 | |
| **D16** `enable` defaults false | 9 | Netlist freeze is the enforcement |
| **D17** `ipl_ack` from the taken-entry observation + count equality | 10 | Qualifier pinned against the RTL of the day |
| **D18** `iackAvec := 1`, `iackVector` dropped | 10 (policy constants), 13 (tie-off) | |
| **D19** no rebuilt 20 s abandonment timer; assertion-backed invariant | 5 | Stated as a "do not add one" constraint |
| **D20** bounded-grant watchdog, v1's value copied | 5 | Value asserted ≠ 2^28 |
| **D21** keep ASYNC, rename to `rst`, declare the CD config explicitly | 13 | |
| **D22** `cpu_peripheral_reset`, 518-cycle level | 12 | Gated on Task 11 |
| **SOC-2** | 14 (recorded only) | Out of repo |
| **SOC-3** | 14 (recorded only) | Out of repo |
| **D23** socket top exports only socket ports; `M68kFullCoreSynth` frozen | 2 (freeze), 13 (surface) | Freeze captured **before** the first RTL change |
| **D24** load sizing from `(paddr[1:0], size)` alone | 6, 7 | |
| **D25** the clamp | 6, 7 | Adversarial off=12..15 cross-page test |
| **D26** WSTRB is a complete lane selector on writes | 8 | Every-strobe-bit-inside-the-transfer check |
| **D27** fail-closed walker ID guards | 4 | |
| **D28** halt-reason channel | 3 | Consumers in 5, 9 |
| **D29** narrowed socket-facing config | 13 | Structural check in 2 |
| **D30** exact naturally-aligned cover, ≤3 | 6, 7, 8 | 48+48 enumeration |
| **SOC-4** | 14 (recorded only) | Finding stands, prescription superseded by `SI2` |
| **NOTED-1/2/3** | 14 (recorded only) | So they are not re-opened |
| **OPEN-1** | 11 (sign-off), 12 (implementation) | Plan does not pick |

Spec §13's verification list maps as: byte order → 1, 13; MMIO sizing → 6, 7, 8; merge arbiter
→ 4, 5; halt-reason channel → 3; reset/boot → 9; interrupts → 10; port surface and `RESET` →
2, 12, 13; gates → 14.

### Dependency ordering — no task assumes something a later task builds

Checked forward-reference by forward-reference:

- Task 2's checker has a `--socket` mode that **SKIPs** cleanly until Task 13, and its skip is
  named as such rather than being a silent pass.
- Task 5's arbiter declares the reset-vector owner's flat `rv*` wires with `allowOverride` and
  concrete idle defaults, so it is complete and testable **before** Task 9 exists — the same
  Option/idle-default shape `FetchAlignPlugin` uses for `FrontendQuiesceService`.
- Task 5's halt fold uses `host.get[AxiDMergePlugin]`, and Task 9's `a7Wr`/halt fold uses
  `host.get[ResetVectorPlugin]`, so `M68kFullCoreSynth` (which has neither) is unchanged at
  every intermediate commit, not just at the end.
- Task 6 builds `MmioCover` before Tasks 7 and 8 consume it — deliberately, so the maths is
  proved once rather than twice.
- Task 11 (sign-off) precedes Task 12 (implementation), and Task 12's `gateDispatch` parameter
  has **no default**, so the ordering is enforced by the compiler rather than by discipline.
- Task 13 consumes 1, 3, 4, 5, 9, 10, 12 and is the only task that sets `socketMerged = true`.
- The one genuine backward dependency is `SocketTopConfig.OPEN1_GATE_DISPATCH`, written as
  `???` in Task 13 and filled by Task 12's answer. Task 14 Step 2 item 6 greps for a surviving
  `???` and fails the gate on it.

### Type and interface consistency across tasks

Each task's implementer sees only their own task, so every cross-task name was checked:

- `SocketByteOrder.{permuteData, permuteStrb, modelData, modelStrb}` — declared in Task 1, used
  under those exact names in Tasks 13 and in `SocketTopByteOrderSpec`.
- `HaltReason.{W, NONE, DCACHE_DIAG, FS_XLATE, RESET_VECTOR, ARBITER_WEDGE, name}` — Task 3;
  used in 5 and 9.
- `RobPlugin.logic.{haltReasonIn, haltReason}` — Task 3; the same names in 5, 9 and Task 14's
  grep. `periphResetHold` is Task 12's and Option-B-only.
- `AxiDMerge.{V1_TIMEOUT_CYCLES, Owner}` and `AxiDMergePlugin.logic.{merge, axi, wedge,
  wedgeIsRead, rvArValid, rvArAddr, rvArReady, rvRValid, rvRData, rvRResp, rvRReady}` — Task 5;
  consumed under exactly those names in 9 and 13.
- `MmioCover.{MAX_SUBS, sizeBytes, clampedEnd, stepLog2, strbRunStart, strbRunEnd, model}` —
  Task 6; used in 7 and 8. Both sequencers use `stepLog2` (an `AxSIZE`), never a byte count, so
  the AXI payload assignment is the same shape on both paths.
- `socketMerged` — one spelling across `DcachePlugin`, `ItlbPlugin`, `DtlbPlugin` (Task 5) and
  `SocketTop` (Task 13).
- `ResetVectorPlugin.logic.{sspWriteValid, sspData, haltPulse}` — Task 9; read in Task 9's own
  `FullCoreSynth` edit and nowhere else.
- `IplAckPlugin.logic.iplAck`, `PeripheralResetPlugin.logic.cpuPeripheralReset` — Tasks 10, 12;
  read in 13.
- `FetchAlignPlugin.logic.resetRedirect` — Task 9; read in 9 only.
- `AxiIds.RESET_VEC` — Task 4; used in Task 5's AR payload and Task 4's own test.
- Sub-transaction cursors are deliberately named apart — `subP`/`subEnd` on the load path
  (Task 7), `stSubP`/`stSubEnd`/`stSubActive`/`stSubErr` on the store path (Task 8) — matching
  the file's existing `st*` prefix convention so a future reader cannot confuse the two FSMs.
- Test-count arithmetic is consistent and cumulative: 7 + 5 + 2 + 6 + 8 + 3 + 2 + 3 + 2 = 38
  untagged, plus 4 + 4 = 8 Verilator-tagged, which is what Task 14 Step 1 states.

### Placeholder scan

`grep -nE 'TBD|TODO|FIXME|XXX|placeholder|similar to Task|as (above|before)|appropriate|and so on|etc\.'`
over this plan returns **exactly one hit: this sentence's own grep string.** Every code-touching
step contains complete, compilable code. Three places intentionally leave text to be supplied,
and each names the authoritative source for it rather than describing it — Task 13's three
"finish rather than copy" items (`cpu_socket.vh:145-176` + `tools/debug/debug_regmap.def` for
the `dbg_axi` passthrough, Task 11's addendum for `OPEN1_GATE_DISPATCH`, and a `diff` command
for the plugin list). Two further places are deliberate, called out, and self-checking rather
than vague:

1. Task 8's `dut.probe.logic.storeAckOut` — the **only** identifier in this plan not verified
   against source at plan time. The task says so explicitly and names the fallback
   (`simPublic()` on `storeAckReg`).
2. Task 12's two `cancel(...)` tests — one of which must be deleted and the other made real.
   Task 14 Step 2 greps for a surviving `cancel(` and fails on it.

### Spec ambiguities and errors found while writing, resolved explicitly

These are judgment calls, not silent fixes; each is carried into the plan's own text.

1. **§6.1's "no change to `FetchAlignPlugin` is required at all" is false.** Its `redirect` is
   `slave(Flow(...))`, an input of `M68kCore`, which a sibling plugin cannot drive. Task 9 adds
   a `mispredictRedirect`-shaped internal source and says why.
2. **The arbiter cannot consume `master()`-declared plugin ports.** Same hierarchy rule. Task 5
   adds a default-off `socketMerged` flag to the three plugins — the minimum change that makes
   D7 buildable — and factors the arbitration into a plain `Component` so it is testable
   without elaborating the 2092-line D-cache.
3. **§9.3's port enumeration is stale.** 177/42/37 was written before `DebugCtrlPlugin` landed;
   the netlist now has **199** ports (135 AXI-master + 22 debug/socket + 37 probe + 5 real).
   The 37-probe figure is still exact and Task 2 asserts it; the total is computed, never
   hardcoded.
4. **`dbg_axi` and the SoC-fabric control group must cross `M68kSocketTop`.** §10 puts them out
   of scope *as a design*, but D23 says the socket top exports the socket's ports and these
   *are* socket groups 4 and 6 — and they now exist on the core. Task 13 plumbs them and
   redesigns nothing.
5. **`DcachePlugin.scala` line citations have drifted** and the file is under `m68k040/cache/`,
   not `m68k040/lsu/`. Every task quotes current line numbers and tells the implementer to
   re-locate by code if they have moved again.
6. **`SOC-4`'s prescription is superseded** by `SI2` in the v1-shared-infra spec (`ca7587f`),
   which post-dates the socket spec. This plan cites the finding, never the prescription.
7. **The spec states no area/FMax budget.** This plan proposes the debug-ctrl triple
   (+1.0% LUT/FF, zero DSP/BRAM, 2% FMax band) and labels it a proposal in both the header and
   Task 14, rather than presenting it as inherited.
8. **`fastTest` excludes Verilator-tagged specs**, so the D-cache-integration suites (Tasks 7,
   8) would be invisible to `make test-fast`. The 48+48 exhaustive proof was therefore moved
   onto the pure `MmioCover` primitive (Task 6, untagged) and the integration checks tagged
   alongside `DcacheSpec`, with Task 14 running both.

### Open questions I could not resolve from real source

1. **`ClockDomain` port naming on `M68kSocketTop`.** Declaring `clk`/`rst` as explicit
   `in Bool()` and wrapping the body in `coreCd on` *should* prevent SpinalHDL adding its own
   implicit `clk`/`reset` pair, but that was not elaborated at plan time. If a stray `reset`
   port appears, Task 2's checker fails on it by name (`"D21: there is no port named reset"`),
   which is the intended loud failure; the fallback is `ClockDomain.external("", ...)` with
   `.reset.setName("rst")`.
2. **Whether `SocketTopByteOrderSpec`'s `ConnDut` can drift from the real `M68kSocketTop`.** It
   mirrors the connection layer rather than instantiating it (the whole-core top is far too
   large for `test-fast`). The structural checker covers the port surface, but not "these three
   and only these three signals are permuted" inside the netlist. A future improvement would be
   a netlist-level check that `SocketByteOrder`'s output feeds only `w.data`/`w.strb`/`r.data`;
   it is not in this plan because a reliable regex over 23 MB of generated Verilog was not
   demonstrated.
3. **The `M68kSocketTop` OOC number has no historical reference**, so Task 14 Step 5 is a cost
   report. Whether the project wants it to *become* a gated target — which would re-baseline
   future comparisons — is a real question this plan does not answer.
4. **The slot-B load over-read survives by design** (spec §3.5's one residual) and this plan
   implements the required *warning*, not a fix. Narrowing it needs `DcachePlugin` to depend on
   a `DLoadToken` bit that is presently LS-EU-private, which the spec explicitly does not
   decide. Recorded so nobody mistakes it for an oversight.

---

## Execution Handoff

Two supported ways to run this plan. **Subagent-Driven is recommended** — the tasks are
sequential with hard interface contracts between them, and each ends in its own commit, so
per-task isolation plus a review gate catches drift early.

### Option A — Subagent-Driven Execution (recommended)

Use `superpowers:subagent-driven-development`. Dispatch one subagent per task, in order, each
receiving that task's section verbatim plus these standing rules:

- The task's **Interfaces / Consumes** block lists everything already built; do not re-derive
  or re-implement it, and do not change a signature another task depends on.
- Run every step in order and check its box. A step that fails is a stop, not a warning.
- Any isolated before/after verification uses `git worktree add`; never `git checkout <sha>` in
  the shared tree (project standing rule, task #199 collision).
- Every RTL task ends by regenerating `generated/M68kFullCoreSynth.v` and running
  `python3 tools/socket/check_socket_netlist.py`. A port-surface change is a **stop**, not
  something to regenerate the golden around. `--regen` exists for one purpose (Task 2's initial
  capture) and using it anywhere else requires an explicit, reviewed decision.
- Respect the machine budget: at most two heavy JVMs concurrently, and none at all while
  Vivado is running (`free -g` before starting).
- Task 14 is the only task that runs Vivado. Confirm no other Vivado session and no live JTAG
  session first, and launch it with the Bash tool's `run_in_background` parameter rather than
  shell-level `nohup`.
- Report the exact command output for each Step 2 (failing) and Step 4 (passing) run — the
  red-then-green evidence, not a summary of it. Tasks 4 and 7 in particular have red states
  that *document the bug being fixed* (a walker completing a page-table walk off the D-cache's
  refill ID; a byte MMIO read issuing a 16-byte transaction at the line base), and those
  outputs belong in the ledger.

**Task 11 is a hard stop for user sign-off.** Do not let a subagent answer `OPEN-1`; the spec
forbids it and §13 states that a test written against an unsigned-off assumption is worse than
none.

Suggested review gates: after **Task 6** (all the maths and all the infrastructure, before any
D-cache datapath is touched), after **Task 8** (both sequencers, the highest-risk RTL), and
after **Task 13** (everything, before spending a Vivado slot on Task 14).

### Option B — Inline Execution

Use `superpowers:executing-plans` and work through Tasks 1-14 in this session, checking boxes
as you go. Same standing rules apply. This is the better choice if you want to watch open
question 1 (SpinalHDL's clock/reset port naming on the new top) resolve interactively, since
Task 13 is where that assumption is first tested and a fallback may be needed.

### Either way

- The plan's parent commit is whatever `git rev-parse HEAD` reports at start; record it in
  Task 14's ledger.
- **Do not hardcode 197.278 MHz / 124263 LUTs / 55861 FF.** Task 14 Step 3 re-reads the current
  reference; a later gate may have moved it.
- **Nothing in `macqd700-soc` may be touched by this plan.** `SOC-1`…`SOC-4` are recorded in
  Task 14's ledger and executed elsewhere; `SOC-4`'s fix in particular is superseded and
  belongs to the v1-shared-infra spec.
- If Task 14's gate fails condition 5 (a sequencer or halt-seam endpoint in the top timing
  paths), the two candidate remedies are named in that step. Do that rather than accepting the
  tranche with an exception.

