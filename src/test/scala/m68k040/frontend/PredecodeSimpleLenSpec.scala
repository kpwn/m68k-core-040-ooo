package m68k040.frontend

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** EXHAUSTIVE proof of `PredecodeWord.classify(...).simple ==> lenWords >= 1`.
  *
  * ── WHY THIS EXISTS (load-bearing, not a nicety) ─────────────────────────────
  *
  * `Aligner.align` populates `slot0.words(0)` / `slot1.words(0)` UNCONDITIONALLY
  * (no `when(0 < L0)` / `when(0 < L1)` mask at index 0) — see the `i == 0` cases
  * of both word-select loops in `Aligner.scala` and the FMax "Frontend Lever A"
  * design/plan under `docs/superpowers/{specs,plans}/2026-08-08-fmax-frontend-levera-*`.
  * That deletion removed `L0`/`L1` from the OPWORD's availability chain (a
  * netlist-measured >= 0.318ns on the design's WNS-holding frontend path), and it
  * is correct EXACTLY BECAUSE both loops only ever run for a slot already known
  * `simple`, and a `simple` instruction is never zero words long.
  *
  * The `i >= 1` masks in those loops are NOT covered by this invariant and are
  * load-bearing for a different reason (the offloaded `dstEa` computation needs
  * the zero-fill beyond `L0`/`L1`) — they are untouched.
  *
  * This test RUNS TO COMPLETION and REPORTS COUNTS; it accumulates violations
  * rather than throwing at the first mismatch. (`PredecodeWordSpec`'s own
  * "exhaustive 65536-opword" test aborts at its first mismatch and has been
  * distrusted for exactly that reason — do not copy it. The precedent to follow
  * is `OperationDecoderSpec`'s "EXHAUSTIVE 65536-opword contract" test.)
  *
  * ── SCOPING DETERMINATION: does the sweep need EXTENSION-WORD CONTENT? ───────
  *
  * YES — and this is not academic. Reading every `r.simple := True` site in
  * `PredecodeWord.scala` and classifying its co-assigned `r.lenWords`:
  *
  *   (a) Most sites assign a Scala CONSTANT >= 1 (`U(1..4, 4 bits)`) — content-
  *       independent, trivially satisfying the invariant.
  *   (b) Most of the rest assign `base + ext` where `base >= 1` is a constant and
  *       `ext` comes from `eaExt`/`memDestExt`/`fullExtLen` (range 0..5), computed
  *       at a width that cannot wrap (`U(1,3 bits) + ext` maxes at 6 < 8;
  *       `bitBase` is 4 bits wide; `U(2,3 bits) + ext` maxes at exactly 7). Also
  *       always >= 1.
  *   (c) MOVE (lines 1/2/3) assigns `totalLen = U(1,3) +^ sExt +^ dExt`, using the
  *       WIDTH-EXTENDING `+^` and additionally gated on `totalLen <= WINDOW` — so
  *       >= 1 by construction.
  *   (d) The line-0 immediate MEM-DESTINATION site was the ONE three-term sum at a
  *       3-bit width: `U(1,3 bits) + immWords + mext`, with `immWords` reaching 2
  *       (.L immediate) and `mext` reaching 5 (a full-format mode-6 destination
  *       with long base displacement AND long outer displacement). True total 8,
  *       truncated by the 3-bit `+` to **ZERO**, with `r.simple := True`.
  *
  * (d) was a REAL, REACHABLE latent bug on this branch, found by writing this
  * test's scoping analysis rather than by the test itself. Concrete pre-fix
  * repro (verified in simulation before the fix):
  *
  *     classify(op = 0x00B0, extW3 = 0x0133, extW3Valid = True)
  *         -> simple = True, lenWords = 0
  *
  * i.e. `ORI.L #imm,(bd,An,Xn)` whose full-format destination extension word sits
  * at op+3. Both real callers can supply that: `IcachePlugin` predecodes with the
  * whole 64-byte line resident (`extW3Valid = i+3 < nWords`) and
  * `FetchAlignPlugin`'s live re-classify passes `extW3Valid = avail >= 4`. The
  * consequence was the documented F2 livelock signature (`lenWords = 0` ->
  * `Aligner.shiftWords = 0` -> `decodePc` never advances). Fixed in
  * `PredecodeWord.scala` by switching that site to `+^`, exactly as the MOVE path
  * (c) was fixed for the same class of defect. See that site's comment.
  *
  * So: ext-word CONTENT genuinely reaches `lenWords`, and this sweep covers it.
  *
  * ── SCOPE REDUCTIONS (each justified, none assumed) ─────────────────────────
  *
  * R1. Only 5 bits of any extension word are ever READ by `classify`. Every
  *     ext-word read in the file is either `eaW(8)` (the brief-vs-full-format
  *     selector, in `eaExt` mode 6 / mode 7-3, in `memDestExt` mode 6, and inline
  *     in the MOVE dst mode-6 branch and the BTST (d8,PC,Xn) branch) or
  *     `fullExtLen(eaW)`, which reads `eaW(5 downto 4)` (bd size) and `eaW(1)`/
  *     `eaW(0)` (od present / od long). Hence bits {8,5,4,1,0} = mask 0x0133 are
  *     live and the other 11 bits are dead. This is a SOURCE claim, so it is not
  *     assumed: `test 2` below PROVES it empirically over ALL 65536 ext-word
  *     values, for an opword set covering every ext-word-consuming code path.
  *     Test 1 therefore sweeps only the 32 live-bit patterns per ext word.
  *
  * R2. `extW2` and `extW3` are tied together (same swept value, same validity
  *     flag). No opword can read BOTH: the only two sites that mention `extW3` are
  *     `Mux(ss === 2, extW3, extW2)` (line-0 immediate mem-dest; `ss` is an OPWORD
  *     field) and `Mux(sExt === 2, extW3, Mux(sExt === 1, extW2, ...))` (MOVE dst;
  *     `sExt` is a function of the opword and `extW`). Both are single-word
  *     selects — for any fixed (opword, extW) exactly one of extW2/extW3 is
  *     functionally read — so tying them loses nothing while halving the sweep's
  *     dimensionality. Their validity flags accompany the identical Mux and are
  *     tied for the same reason.
  *
  * R3. `extW` is swept INDEPENDENTLY of `extW2`/`extW3` (a full 32x32 grid, not a
  *     diagonal), because MOVE genuinely reads two different ext words in one
  *     classification: `extW` decides `sExt`, and `sExt` in turn selects which of
  *     `extW`/`extW2`/`extW3` supplies `dExt`.
  *
  * R4. All 4 combinations of the (extWValid, extW2Valid==extW3Valid) validity
  *     flags are swept — in hardware, so they cost sim steps nothing. (They could
  *     have been argued away: `Known = False` forces every content-dependent EA
  *     length to the constant 1, which is also reachable with `Known = True` and
  *     `eaW(8) = 0`. Sweeping them is cheaper than trusting that argument.)
  *
  * Total configurations checked by test 1:
  *     65536 opwords x 32 extW patterns x 32 extW2/3 patterns x 4 validity combos
  *   = 268,435,456
  */
class PredecodeSimpleLenSpec extends AnyFunSuite {

  /** Bits of an extension word that `PredecodeWord.classify` actually reads:
    * bit 8 (brief/full selector), bits 5:4 (bd size), bit 1 (od present),
    * bit 0 (od long). See R1 above; proven by `test 2`. */
  val LIVE = 0x0133
  val NPAT = 32

  /** The 32 distinct live-bit patterns (dead bits zero). */
  def pat(k: Int): Int = (((k >> 4) & 1) << 8) | (((k >> 2) & 3) << 4) | (k & 3)

  /** (extWValid, extW2Valid == extW3Valid) */
  val VALIDS: Seq[(Boolean, Boolean)] = Seq((false, false), (false, true), (true, false), (true, true))
  val NCFG = NPAT * VALIDS.size    // 128 parallel classifiers

  class ProofDut extends Component {
    val op   = in Bits (16 bits)
    val extW = in Bits (16 bits)
    val viol = out Bits (NCFG bits)   // simple && lenWords === 0
    val simp = out Bits (NCFG bits)
    for (v <- VALIDS.indices; j <- 0 until NPAT) {
      val (vw, v23) = VALIDS(v)
      val e = B(pat(j), 16 bits)
      val r = PredecodeWord.classify(op, extW, e, e, Bool(vw), Bool(v23), Bool(v23))
      val k = v * NPAT + j
      simp(k) := r.simple
      viol(k) := r.simple && (r.lenWords === U(0, 4 bits))
    }
  }

  test("EXHAUSTIVE: classify(...).simple ==> lenWords >= 1, over all 65536 opwords x all extension-word content", VerilatorTest) {
    SimConfig.withVerilator.compile(new ProofDut).doSim { dut =>
      var opsSwept    = 0L
      var checked     = 0L
      var simpleCount = 0L
      var violCount   = 0L
      val examples    = scala.collection.mutable.ArrayBuffer[String]()

      val t0 = System.nanoTime()
      for (op <- 0 until 65536) {
        dut.op #= op
        for (i <- 0 until NPAT) {
          dut.extW #= pat(i)
          sleep(1)
          val s = dut.simp.toBigInt
          val v = dut.viol.toBigInt
          simpleCount += s.bitCount
          checked     += NCFG
          if (v.signum != 0) {
            violCount += v.bitCount
            if (examples.size < 20) {
              // decode the first offending config index for a readable report
              var k = 0
              while (k < NCFG && !v.testBit(k)) k += 1
              val (vw, v23) = VALIDS(k / NPAT)
              examples += f"op=0x$op%04x extW=0x${pat(i)}%04x extW2=extW3=0x${pat(k % NPAT)}%04x " +
                          f"extWValid=$vw extW23Valid=$v23"
            }
          }
        }
        opsSwept += 1
      }
      val dt = (System.nanoTime() - t0) / 1e9

      println(f"[simple-nonzero-len] EXHAUSTIVE sweep COMPLETED in $dt%.1f s: " +
              f"opwords swept $opsSwept/65536; configurations checked $checked " +
              f"(= 65536 x $NPAT extW patterns x $NPAT extW2/3 patterns x ${VALIDS.size} validity combos); " +
              f"simple===True on $simpleCount of them; violations (simple && lenWords===0): $violCount")
      if (examples.nonEmpty) println("[simple-nonzero-len] first violations:\n  " + examples.mkString("\n  "))

      assert(opsSwept == 65536L, s"sweep did not run to completion: only $opsSwept opwords")
      assert(checked == 65536L * NPAT * NCFG, s"unexpected configuration count $checked")
      // Non-vacuity: the invariant must be satisfied by real `simple` cases, not by
      // "simple never happens".
      assert(simpleCount > 0L, "vacuous: no configuration ever produced simple===True")
      assert(violCount == 0L,
        s"$violCount configurations produced simple===True with lenWords===0 -- " +
        s"Aligner's unconditional i==0 word write is NOT justified. Examples:\n  " + examples.mkString("\n  "))
    }
  }

  // ── test 2: proves reduction R1 (only bits 0x0133 of an extension word matter) ──
  //
  // Sweeps ALL 65536 extension-word values against an opword set chosen to hit
  // EVERY ext-word-consuming path in `classify`, asserting that masking the ext
  // words down to LIVE changes neither `simple` nor `lenWords`. If any of the 11
  // "dead" bits were ever read, this fails.
  val PATH_OPS: Seq[(Int, String)] = Seq(
    // line-0 immediate mem-dest -- reads extW2 (.B/.W imm) / extW3 (.L imm)
    0x0030 -> "ORI.B  #imm,(d8,An,Xn)   [extW2]",
    0x0070 -> "ORI.W  #imm,(d8,An,Xn)   [extW2]",
    0x00B0 -> "ORI.L  #imm,(d8,An,Xn)   [extW3]  <- the length-wrap repro",
    0x0CB0 -> "CMPI.L #imm,(d8,An,Xn)   [extW3]",
    // line-0 bit ops -- dynamic reads extW, static reads extW2
    0x0130 -> "BTST   Dn,(d8,An,Xn)     [extW]",
    0x0830 -> "BTST   #n,(d8,An,Xn)     [extW2]",
    0x013B -> "BTST   Dn,(d8,PC,Xn)     [extW]  (PRM 4.16 PC-rel carve-out)",
    0x083B -> "BTST   #n,(d8,PC,Xn)     [extW2] (PRM 4.16 PC-rel carve-out)",
    0x01B0 -> "BSET   Dn,(d8,An,Xn)     [extW]",
    // MOVE -- src ext word, dst ext word, and both at once
    0x2030 -> "MOVE.L (d8,An,Xn),Dn     [extW  as src]",
    0x203B -> "MOVE.L (d8,PC,Xn),Dn     [extW  as src]",
    0x2180 -> "MOVE.L Dn,(d8,An,Xn)     [extW  as dst, sExt=0]",
    0x21A8 -> "MOVE.L (d16,An),(d8,An,Xn) [extW2 as dst, sExt=1]",
    0x21B9 -> "MOVE.L (xxx).L,(d8,An,Xn)  [extW3 as dst, sExt=2]",
    0x21B0 -> "MOVE.L (d8,An,Xn),(d8,An,Xn) [extW src AND dst, 2-D]",
    0x11B0 -> "MOVE.B (d8,An,Xn),(d8,An,Xn) [extW src AND dst, 2-D]",
    // line-4 family
    0x41B0 -> "CHK.W  (d8,An,Xn),Dn     [extW]",
    0x41F0 -> "LEA    (d8,An,Xn),An     [extW]",
    0x41FB -> "LEA    (d8,PC,Xn),An     [extW]",
    0x4870 -> "PEA    (d8,An,Xn)        [extW]",
    0x40F0 -> "MOVE   SR,(d8,An,Xn)     [extW]",
    0x42F0 -> "MOVE   CCR,(d8,An,Xn)    [extW]",
    0x44F0 -> "MOVE   (d8,An,Xn),CCR    [extW]",
    0x46F0 -> "MOVE   (d8,An,Xn),SR     [extW]",
    0x42B0 -> "CLR.L  (d8,An,Xn)        [extW]",
    0x4AF0 -> "TAS    (d8,An,Xn)        [extW]",
    0x4EB0 -> "JSR    (d8,An,Xn)        [extW]",
    0x4EF0 -> "JMP    (d8,An,Xn)        [extW]",
    0x4EFB -> "JMP    (d8,PC,Xn)        [extW]",
    // line-5
    0x5030 -> "ADDQ.B #n,(d8,An,Xn)     [extW]",
    0x50F0 -> "ST     (d8,An,Xn)        [extW]",
    // lines 8/9/B/C/D
    0x8030 -> "OR.B   (d8,An,Xn),Dn     [extW]",
    0x8130 -> "OR.B   Dn,(d8,An,Xn)     [extW]",
    0x80F0 -> "DIVU.W (d8,An,Xn),Dn     [extW]",
    0xC0F0 -> "MULU.W (d8,An,Xn),Dn     [extW]",
    0xB030 -> "CMP.B  (d8,An,Xn),Dn     [extW]",
    0xB130 -> "EOR.B  Dn,(d8,An,Xn)     [extW]",
    0xD5B0 -> "ADD.L  Dn,(d8,An,Xn)     [extW]",
    // line-E
    0xE8F0 -> "BFTST  (d8,An,Xn){..}    [extW2]",
    0xE0F0 -> "ASR.W  (d8,An,Xn)        [extW]"
  )

  class DeadBitDut extends Component {
    val e   = in Bits (16 bits)
    val raw = out Bits (PATH_OPS.size * 5 bits)
    val msk = out Bits (PATH_OPS.size * 5 bits)
    val eM  = e & B(LIVE, 16 bits)
    for ((opv, idx) <- PATH_OPS.map(_._1).zipWithIndex) {
      val o = B(opv, 16 bits)
      val a = PredecodeWord.classify(o, e,  e,  e,  True, True, True)
      val b = PredecodeWord.classify(o, eM, eM, eM, True, True, True)
      raw(idx * 5 + 4 downto idx * 5) := a.simple.asBits ## a.lenWords.asBits
      msk(idx * 5 + 4 downto idx * 5) := b.simple.asBits ## b.lenWords.asBits
    }
  }

  test("EXHAUSTIVE: classify depends on extension-word bits 0x0133 ONLY (all 65536 ext-word values)", VerilatorTest) {
    SimConfig.withVerilator.compile(new DeadBitDut).doSim { dut =>
      var swept      = 0L
      var mismatches = 0L
      val examples   = scala.collection.mutable.ArrayBuffer[String]()
      val seenLens   = scala.collection.mutable.HashSet[Int]()

      for (e <- 0 until 65536) {
        dut.e #= e
        sleep(1)
        val a = dut.raw.toBigInt
        val b = dut.msk.toBigInt
        if (a != b) {
          mismatches += 1
          if (examples.size < 10) {
            val bad = PATH_OPS.indices.filter(i => ((a >> (i * 5)) & 31) != ((b >> (i * 5)) & 31))
            examples += f"ext=0x$e%04x differs for: " + bad.map(i => f"0x${PATH_OPS(i)._1}%04x ${PATH_OPS(i)._2}").mkString(", ")
          }
        }
        // non-vacuity witness: record which lengths this opword set actually produces
        PATH_OPS.indices.foreach { i =>
          val f = ((a >> (i * 5)) & 31).toInt
          if ((f & 16) != 0) seenLens += (f & 15)
        }
        swept += 1
      }

      println(s"[ext-live-bits] EXHAUSTIVE ext-word sweep COMPLETED: swept $swept/65536 ext-word values " +
              s"against ${PATH_OPS.size} path-covering opwords; mismatches: $mismatches; " +
              s"distinct simple lenWords observed: ${seenLens.toSeq.sorted.mkString(",")}")
      if (examples.nonEmpty) println("[ext-live-bits] first mismatches:\n  " + examples.mkString("\n  "))

      assert(swept == 65536L, s"sweep did not run to completion: only $swept values")
      // Non-vacuity: the opword set must genuinely exercise CONTENT-dependent lengths
      // (a full-format EA reaches lenWords 6/7/8 -- if only the brief lengths showed up,
      // the sweep would not be testing what it claims to).
      assert(seenLens.size >= 6, s"vacuous: only ${seenLens.size} distinct lengths observed: $seenLens")
      assert(seenLens.contains(8), s"vacuous: the maximal full-format length 8 never occurred: $seenLens")
      assert(mismatches == 0L,
        s"$mismatches ext-word values changed classify's result outside the LIVE mask 0x0133 -- " +
        s"reduction R1 is INVALID and test 1's sweep is incomplete. Examples:\n  " + examples.mkString("\n  "))
    }
  }
}
