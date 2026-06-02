# ALU Datapath (Execute Slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pure combinational 68k ALU datapath — `op,size,src1,src2,xIn → result,NZVC,xOut` — for the ops simple decode emits (MOVE/ADD/SUB/AND/OR/CMP × B/W/L), verified flag-for-flag against Musashi.

**Architecture:** A stateless `AluDatapath` helper (shared add/sub adder via the NaxRiscv carry-trick, bitwise unit, move path, result mux, per-size flag generator). No PRF/scheduler — operands come in directly; this is the isolated, oracle-verified core that Slice 3's EU will instantiate. Tested by wrapping it in a tiny combinational `AluDut`, driving vectors in SpinalSim, and comparing result + written flags to Musashi single-instruction runs.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / ScalaTest. Musashi oracle: `m68k040.oracle.{Musashi, ProgramAssembler}` (needs `make musashi` built; runner auto-located).

**Branch:** `feat/alu-datapath` (created; spec committed).

**68k facts used here:**
- CCR bit layout: bit4=X, bit3=N, bit2=Z, bit1=V, bit0=C (`OracleState.ccr` is `sr & 0x1f`).
- Operand order: `<op>.<sz> %d1,%d0` computes `D0 = D0 <op> D1`, so **src1=D0=dst, src2=D1=src**; SUB/CMP = src1−src2.
- `MOVE from CCR` (`move %ccr,%d2`) does NOT affect condition codes — used to snapshot the op's CCR before the sentinel store clobbers it.
- For our op set, NZVC is always written; **X is written only by ADD/SUB**; **result is written by all but CMP**.

---

### Task 1: AluDatapath + Dut wrapper + first directed test (ADD.B) red→green

**Files:**
- Create: `src/main/scala/m68k040/execute/AluDatapath.scala`
- Create: `src/test/scala/m68k040/execute/AluDatapathSpec.scala`

- [ ] **Step 1: Create the datapath with bundles + a ZEROED body (so the test can fail first)**

Create `src/main/scala/m68k040/execute/AluDatapath.scala`:

```scala
package m68k040.execute

import m68k040.decode.DecOp
import m68k040.isa.Size
import spinal.core._

/** Pre-rename ALU command (operands already selected by the EU in Slice 3). */
case class AluCmd() extends Bundle {
  val op   = DecOp()
  val size = Size()
  val src1 = Bits(32 bits)  // dst operand (SUB/CMP compute src1 - src2)
  val src2 = Bits(32 bits)  // src operand
  val xIn  = Bool()         // current X (unused by this op set; plumbed for ADDX later)
}

/** ALU result + computed condition codes. nzvc = N(bit3) Z(bit2) V(bit1) C(bit0).
  * Whether result/nzvc/xOut are committed is decided downstream by the rename
  * write masks; this datapath always produces a value. */
case class AluRsp() extends Bundle {
  val result = Bits(32 bits)  // low `size` bits valid; upper bits raw (writeback merges)
  val nzvc   = Bits(4 bits)
  val xOut   = Bool()         // = C for ADD/SUB; don't-care otherwise
}

object AluDatapath {
  def apply(cmd: AluCmd): AluRsp = {
    val rsp = AluRsp()
    rsp.result := B(0, 32 bits)
    rsp.nzvc   := B(0, 4 bits)
    rsp.xOut   := False
    rsp
  }
}
```

- [ ] **Step 2: Write the test harness + one directed ADD.B case (will FAIL on the zeroed body)**

Create `src/test/scala/m68k040/execute/AluDatapathSpec.scala`:

```scala
package m68k040.execute

import m68k040.{VerilatorTest, SlowTest}
import m68k040.decode.DecOp
import m68k040.isa.Size
import m68k040.oracle.Musashi
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class AluDatapathSpec extends AnyFunSuite {

  // Combinational DUT exposing the datapath at top-level IO.
  class AluDut extends Component {
    val io = new Bundle {
      val cmd = in(AluCmd())
      val rsp = out(AluRsp())
    }
    io.rsp := AluDatapath(io.cmd)
  }

  // ---- op/size descriptors: asm mnemonic + DecOp enum + write rules ----
  case class OpCase(mnem: String, dec: SpinalEnumElement[DecOp.type], writesX: Boolean, writesDst: Boolean)
  val OPS = Seq(
    OpCase("move", DecOp.MOVE, writesX = false, writesDst = true),
    OpCase("add",  DecOp.ADD,  writesX = true,  writesDst = true),
    OpCase("sub",  DecOp.SUB,  writesX = true,  writesDst = true),
    OpCase("and",  DecOp.AND,  writesX = false, writesDst = true),
    OpCase("or",   DecOp.OR,   writesX = false, writesDst = true),
    OpCase("cmp",  DecOp.CMP,  writesX = false, writesDst = false)
  )
  case class SzCase(sfx: String, enum: SpinalEnumElement[Size.type], w: Int) { def mask: Long = if (w == 32) 0xFFFFFFFFL else (1L << w) - 1 }
  val SIZES = Seq(SzCase("b", Size.BYTE, 8), SzCase("w", Size.WORD, 16), SzCase("l", Size.LONG, 32))

  // ---- Musashi oracle: run one instruction, snapshot result (D0) + CCR (D2) ----
  case class Exp(result: Long, ccr: Int)
  def oracle(mnem: String, sfx: String, src1: Long, src2: Long): Exp = {
    val src =
      f"""    move.l #0x${src1 & 0xffffffffL}%08x,%%d0
         |    move.l #0x${src2 & 0xffffffffL}%08x,%%d1
         |    $mnem.$sfx %%d1,%%d0
         |    move %%ccr,%%d2
         |    move.l %%d0,0xFFFF0000
         |""".stripMargin
    Musashi.assembleAndRun(src) match {
      case Right(st) => Exp(st.d(0), (st.d(2) & 0x1f).toInt)
      case Left(e)   => throw new RuntimeException(s"musashi: ${e.reason}\n$src")
    }
  }

  // ---- check one vector against the DUT (call inside doSim) ----
  def check(dut: AluDut, oc: OpCase, sz: SzCase, src1: Long, src2: Long): Unit = {
    val exp = oracle(oc.mnem, sz.sfx, src1, src2)
    dut.io.cmd.op   #= oc.dec
    dut.io.cmd.size #= sz.enum
    dut.io.cmd.src1 #= BigInt(src1 & 0xffffffffL)
    dut.io.cmd.src2 #= BigInt(src2 & 0xffffffffL)
    dut.io.cmd.xIn  #= false
    sleep(1)
    val gotResult = dut.io.rsp.result.toLong & 0xffffffffL
    val gotNzvc   = dut.io.rsp.nzvc.toInt
    val gotX      = dut.io.rsp.xOut.toBoolean
    val gN = (gotNzvc >> 3) & 1; val gZ = (gotNzvc >> 2) & 1; val gV = (gotNzvc >> 1) & 1; val gC = gotNzvc & 1
    val eX = (exp.ccr >> 4) & 1; val eN = (exp.ccr >> 3) & 1; val eZ = (exp.ccr >> 2) & 1; val eV = (exp.ccr >> 1) & 1; val eC = exp.ccr & 1
    val ctx = f"${oc.mnem}.${sz.sfx} src1=0x$src1%x src2=0x$src2%x: musCCR=0x${exp.ccr}%02x musRes=0x${exp.result}%x got nzvc=$gotNzvc x=$gotX res=0x$gotResult%x"
    if (oc.writesDst) assert((gotResult & sz.mask) == (exp.result & sz.mask), s"result mismatch — $ctx")
    assert(gN == eN, s"N — $ctx"); assert(gZ == eZ, s"Z — $ctx")
    assert(gV == eV, s"V — $ctx"); assert(gC == eC, s"C — $ctx")
    if (oc.writesX) assert((if (gotX) 1 else 0) == eX, s"X — $ctx")
  }

  test("ADD.B directed: carry + overflow boundary", VerilatorTest) {
    SimConfig.withVerilator.compile(new AluDut).doSim { dut =>
      check(dut, OPS.find(_.mnem == "add").get, SIZES.head /* b */, 0x000000FFL, 0x00000001L) // 0xFF+1 -> 0x00, C,Z, X
      check(dut, OPS.find(_.mnem == "add").get, SIZES.head, 0x0000007FL, 0x00000001L)          // 0x7F+1 -> 0x80, N,V
    }
  }
}
```

- [ ] **Step 3: Run the test — verify it FAILS (zeroed datapath)**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.AluDatapathSpec"`
Expected: FAIL on a flag/result assert (the body returns zeros). Confirm the failure is a value mismatch, not a build/Musashi error. (If it errors with "musashi_run not found", run `make musashi` first.)

- [ ] **Step 4: Implement the datapath**

Replace the body of `AluDatapath.apply` in `src/main/scala/m68k040/execute/AluDatapath.scala` with:

```scala
  def apply(cmd: AluCmd): AluRsp = {
    val a = cmd.src1.asUInt
    val b = cmd.src2.asUInt

    val isSub   = cmd.op === DecOp.SUB || cmd.op === DecOp.CMP
    val isArith = cmd.op === DecOp.ADD || isSub
    val cin     = isSub                     // a + ~b + 1 == a - b
    val b2      = Mux(isSub, ~b, b)
    val sum32   = a + b2 + cin.asUInt.resize(32)

    val result = cmd.op.mux(
      DecOp.MOVE -> cmd.src2.asUInt,
      DecOp.AND  -> (a & b),
      DecOp.OR   -> (a | b),
      default    -> sum32                   // ADD / SUB / CMP (others don't-care)
    )

    // per-size flag pieces (N,Z from final result; V,C from the add of a + b2 + cin)
    def piece(w: Int) = new Area {
      val ext  = (False ## a(w - 1 downto 0)).asUInt + (False ## b2(w - 1 downto 0)).asUInt + cin.asUInt
      val cout = ext(w)
      val n    = result(w - 1)
      val z    = result(w - 1 downto 0) === 0
      val v    = (a(w - 1) === b2(w - 1)) && (result(w - 1) =/= a(w - 1))
    }
    val p8 = piece(8); val p16 = piece(16); val p32 = piece(32)
    def bySize[T <: Data](b: T, w: T, l: T): T =
      cmd.size.mux(Size.BYTE -> b, Size.WORD -> w, Size.LONG -> l)

    val n      = bySize(p8.n, p16.n, p32.n)
    val z      = bySize(p8.z, p16.z, p32.z)
    val vArith = bySize(p8.v, p16.v, p32.v)
    val cout   = bySize(p8.cout, p16.cout, p32.cout)

    val cFlag = isArith ? Mux(isSub, ~cout, cout) | False   // SUB/CMP carry = borrow = !cout
    val vFlag = isArith ? vArith | False

    val rsp = AluRsp()
    rsp.result := result.asBits
    rsp.nzvc   := n ## z ## vFlag ## cFlag                  // N Z V C
    rsp.xOut   := cFlag                                     // = C for ADD/SUB; gated by writesX downstream
    rsp
  }
```

Keep the `import`s already present. If a SpinalHDL nit appears (e.g. `?|` ternary precedence, `mux` default form, `Area` inside a `def`), adjust syntax minimally — the logic above is the intended semantics; **Musashi is the arbiter** for any flag-formula question.

- [ ] **Step 5: Run the ADD.B test — verify PASS**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.AluDatapathSpec"`
Expected: PASS. If a flag mismatch appears, the `ctx` string prints Musashi's CCR vs ours — fix the formula (most likely culprits: SUB borrow `~cout`, or V at sub-word width) to match Musashi. Do not relax the assert.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/m68k040/execute/AluDatapath.scala src/test/scala/m68k040/execute/AluDatapathSpec.scala
git commit -m "execute: AluDatapath (combinational op + NZVC/X), ADD.B vs Musashi"
```

---

### Task 2: Directed corner cases across all op×size

**Files:**
- Modify: `src/test/scala/m68k040/execute/AluDatapathSpec.scala`

- [ ] **Step 1: Add a directed corner-case test covering every op×size**

Add this test to `AluDatapathSpec`:

```scala
  test("directed corner cases across all op x size", VerilatorTest) {
    SimConfig.withVerilator.compile(new AluDut).doSim { dut =>
      // (src1, src2) pairs that hit zero, sign boundary, carry/borrow, overflow, equal
      val vectors = Seq(
        (0x00000000L, 0x00000000L), // zero
        (0x7FFFFFFFL, 0x00000001L), // +max + 1 -> overflow at L; sign boundary at sub-word
        (0x80000000L, 0x00000001L), // -min - ...
        (0x000000FFL, 0x00000001L), // byte carry
        (0x0000FFFFL, 0x00000001L), // word carry
        (0xFFFFFFFFL, 0x00000001L), // long carry / -1
        (0x12345678L, 0x12345678L), // equal (CMP -> Z), AND/OR identity
        (0x80808080L, 0x7F7F7F7FL), // mixed sign per lane
        (0xA5A5A5A5L, 0x5A5A5A5AL),
        (0x00000080L, 0x00000080L)  // byte sign add -> overflow
      )
      for (oc <- OPS; sz <- SIZES; (s1, s2) <- vectors) check(dut, oc, sz, s1, s2)
    }
  }
```

- [ ] **Step 2: Run — fix any flag formula the corners expose**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.AluDatapathSpec"`
Expected: PASS for all op×size×vector. If a case fails, the `ctx` message shows the exact op/size/operands and Musashi's CCR — correct the datapath formula (NOT the assert). Likely areas: sub-word V/C, CMP borrow, MOVE setting V=C=0. Iterate until green.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/m68k040/execute/AluDatapathSpec.scala
git commit -m "execute: AluDatapath directed corner cases (all op x size) vs Musashi"
```

---

### Task 3: Randomized sweep vs Musashi

**Files:**
- Modify: `src/test/scala/m68k040/execute/AluDatapathSpec.scala`
- Verify: `src/test/scala/m68k040/TestTags.scala` already defines `SlowTest` (confirm; it does).

- [ ] **Step 1: Add a randomized sweep, tagged SlowTest (Musashi-heavy)**

Add to `AluDatapathSpec` (the `SlowTest` tag keeps it out of the quick fast-suite; it spawns one Musashi run per vector):

```scala
  test("randomized sweep vs Musashi (all op x size)", VerilatorTest, SlowTest) {
    val rng = new scala.util.Random(0x68040)   // fixed seed -> reproducible
    val PER = 24                                // vectors per op x size
    SimConfig.withVerilator.compile(new AluDut).doSim { dut =>
      for (oc <- OPS; sz <- SIZES) {
        for (_ <- 0 until PER) {
          val s1 = rng.nextInt() & 0xffffffffL
          val s2 = rng.nextInt() & 0xffffffffL
          check(dut, oc, sz, s1, s2)
        }
      }
    }
  }
```

- [ ] **Step 2: Run the sweep**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.AluDatapathSpec"`
Expected: ALL pass (ADD.B + corners + sweep = 6 ops × 3 sizes × 24 ≈ 432 Musashi runs; allow up to 590000 ms — it spawns as/ld/objcopy per vector). If runtime is excessive, the sweep is correctly tagged `SlowTest`; note the count. If any vector fails, fix the formula (Musashi is truth).

- [ ] **Step 3: Confirm fast + full suites green**

Run: `make SBT=~/sbt/bin/sbt test-fast`
Expected: all pass (the ALU tests are VerilatorTest, so they are NOT in the fast suite — confirm fast count unchanged from before this slice).

Run: `make SBT=~/sbt/bin/sbt test-verilator`
Expected: all pass, including the new AluDatapathSpec tests. Report totals.

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/m68k040/execute/AluDatapathSpec.scala
git commit -m "execute: AluDatapath randomized sweep vs Musashi (all op x size)"
```

---

## Self-Review

**1. Spec coverage:**
- §3 datapath (adder/bitwise/move/result mux/flag gen) → Task 1 Step 4. ✓
- §3.2 flag semantics per op×size → encoded in the datapath + validated by Tasks 1–3 vs Musashi. ✓
- §2 op×size coverage (MOVE/ADD/SUB/AND/OR/CMP × B/W/L) → OPS×SIZES in Tasks 2–3. ✓
- §5 verification (Musashi oracle, directed corners, randomized sweep, written-flag masking, fast/full suite split) → Tasks 1–3; `writesX`/`writesDst` gating in `check`. ✓
- §6 files (AluDatapath.scala, AluDatapathSpec.scala) → created. ✓

**2. Placeholder scan:** No TBD/TODO; full datapath + test code given. The "adjust syntax minimally" notes are bounded (semantics fixed, Musashi arbiter) — not placeholders. ✓

**3. Type consistency:** `AluCmd`/`AluRsp` fields (`op,size,src1,src2,xIn` / `result,nzvc,xOut`) consistent between datapath and test. `nzvc` bit order N(3)Z(2)V(1)C(0) consistent in both producer (`n ## z ## vFlag ## cFlag`) and consumer (`check` shifts). `OpCase.writesX/writesDst` match §1's write rules (X only ADD/SUB; result all but CMP; NZVC always). `Size.{BYTE,WORD,LONG}` and `DecOp.{MOVE,ADD,SUB,AND,OR,CMP}` match the enums. Musashi CCR mapping (X=4,N=3,Z=2,V=1,C=0) matches the 68k layout. ✓
