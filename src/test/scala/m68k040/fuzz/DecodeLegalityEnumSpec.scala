package m68k040.fuzz

import m68k040.{SlowTest, VerilatorTest}
import m68k040.oracle.Musashi
import org.scalatest.funsuite.AnyFunSuite

/** Instruction / effective-address LEGALITY enumerator, DUT vs Musashi (68040 mode).
  *
  * For each opcode family (an opword with a generic 6-bit EA field) the generator emits
  * one case per EA variant -- modes 0..7, registers 0..4, plus the full-format index forms
  * (no memory indirection, memory-indirect pre-indexed, memory-indirect post-indexed) for
  * mode 6 and mode 7/3. Each case resets the register file, arms a resume slot, executes
  * the instruction once, and records into a result table either 0x7E (no exception) or
  * the vector number the (single, generic) exception handler read from the stacked frame.
  *
  * The same program runs on Musashi and on the core; the two result tables are compared.
  * Legality is the property under test: a case "mismatches" when exactly one side took
  * vector 4 (illegal instruction) or vector 10/11 (line A/F). Other vector differences
  * (DIV0/CHK/TRAPcc depend on operand values that differ between the two memory models'
  * uninitialised fill) are reported but not counted.
  *
  * The run is ~30 minutes, so it is opt-in: `LEGALITY_ENUM=1`. Every remaining, understood
  * mismatch is listed in `src/test/resources/decode-legality-known.txt` (`desc|dut|musashi`);
  * the test fails on any mismatch NOT in that list (a regression or a new finding) and prints
  * the listed entries that no longer mismatch (a fix -- delete them from the list).
  *
  * Every EA extension word that is not structurally required is the one-word no-op opcode
  * 0x4A40 (TST.W D0), so a length disagreement on a LEGAL instruction degrades into
  * harmless no-ops rather than a runaway. Control transfers (JMP/JSR) run in a final
  * section whose every possible target holds a line-A word (0xA000), so a legal JMP/JSR
  * ends in vector 10 and an illegal one in vector 4.
  */
object DecodeLegalityEnum {
  final case class Case(desc: String, words: Seq[String], setup: Seq[String])

  // memory layout
  val RES     = 0x00300000L            // result table, one byte per case
  val SLOT    = 0x000F0000L            // RESPTR (+0), RESUME (+4)
  val STK     = 0x000E0000L
  val MIT     = 0x00290000L            // memory-indirect pointer target
  def anInit(n: Int): Long = 0x00208000L + n * 0x10000L
  val initRegs: Seq[Long] = Seq(3L, 5L, 7L, 1L, 0x1000L, 0x10L, 0x20L, 0L) ++
    (0 until 5).map(anInit) ++ Seq(0x00260000L, 0x00270000L)

  private def h(x: Long): String = f"0x${x & 0xffffffffL}%08X"
  private val PAD = ".short 0x4A40"

  /** One EA variant: (mode, reg, tag, ext-word directives (`@L` = this case's EA label),
    * setup lines, possible JMP targets). */
  final case class EaV(mode: Int, reg: Int, tag: String, ext: Seq[String], setup: Seq[String],
                       jmpTargets: Seq[Long])

  def eaName(m: Int, r: Int, tag: String): String = {
    val base = (m, r) match {
      case (0, _) => s"D$r"; case (1, _) => s"A$r"; case (2, _) => s"(A$r)"
      case (3, _) => s"(A$r)+"; case (4, _) => s"-(A$r)"; case (5, _) => s"(d16,A$r)"
      case (6, _) => s"(..,A$r,Xn)"; case (7, 0) => "abs.W"; case (7, 1) => "abs.L"
      case (7, 2) => "(d16,PC)"; case (7, 3) => "(..,PC,Xn)"; case (7, 4) => "#imm"
      case (7, x) => s"m7r$x"
    }
    if (tag.isEmpty) base else s"$base[$tag]"
  }

  /** immWords: 1 = byte/word immediate (one word), 2 = long immediate. */
  def eaVariants(immWords: Int, regs: Seq[Int] = 0 to 4): Seq[EaV] = {
    val v = Seq.newBuilder[EaV]
    for (m <- 0 to 4; r <- regs) {
      val t = m match {
        case 2 | 3 => Seq(anInit(r)); case 4 => Seq(anInit(r) - 4, anInit(r) - 2); case _ => Nil
      }
      v += EaV(m, r, "", Nil, Nil, t)
    }
    for (r <- regs) v += EaV(5, r, "", Seq(PAD), Nil, Seq(anInit(r) + 0x4A40))
    for (r <- regs) v += EaV(6, r, "brief", Seq(PAD), Nil, Seq(anInit(r) + 0x2040))
    // full format: D0.W index suppressed (IS=1), word bd = 0x2040, no memory indirection
    v += EaV(6, 0, "full", Seq(".short 0x0160", ".short 0x2040"), Nil, Seq(anInit(0) + 0x2040))
    // full format: memory indirect pre-indexed, IS=1, bd.W 0x100, null od -> [A0+0x100]
    // (the pointer slot is off A0 itself so it never aliases a JMP (A0) target)
    val miSet = Seq(s"move.l #${h(MIT)},${h(anInit(0) + 0x100)}")
    v += EaV(6, 0, "MI-pre", Seq(".short 0x0161", ".short 0x0100"), miSet, Seq(MIT))
    // memory indirect post-indexed: D4.L, bd.W 0x100, word od 0x0010 -> [A0+0x100] + D4 + 0x10
    v += EaV(6, 0, "MI-post", Seq(".short 0x4926", ".short 0x0100", ".short 0x0010"), miSet, Seq(MIT + 0x1010))
    v += EaV(7, 0, "", Seq(PAD), Nil, Seq(0x4A40L))
    v += EaV(7, 1, "", Seq(PAD, PAD), Nil, Seq(0x4A404A40L))
    v += EaV(7, 2, "", Seq(PAD), Nil, Nil)                    // lands in the line-A fill
    v += EaV(7, 3, "brief", Seq(PAD), Nil, Nil)
    v += EaV(7, 3, "full", Seq(".short 0x0160", ".short 0x2040"), Nil, Nil)
    // PC memory indirect: IS=1, long bd = &_ptrslot - &ext, null od -> [_ptrslot]
    v += EaV(7, 3, "MI-pre", Seq(".short 0x0171", ".long _ptrslot-@L"), Nil, Seq(MIT))
    v += EaV(7, 4, "", if (immWords == 2) Seq(".short 0x0000", ".short 0x0007") else Seq(".short 0x0007"), Nil, Nil)
    for (r <- 5 to 7) v += EaV(7, r, "", Nil, Nil, Nil)
    v.result()
  }

  final case class Fam(name: String, op: Int, exts: Seq[Int], immWords: Int,
                       skip: (Int, Int) => Boolean = (_, _) => false)

  def families: Seq[Fam] = {
    val f = Seq.newBuilder[Fam]
    val sz = Seq(("b", 0x00, 1), ("w", 0x40, 1), ("l", 0x80, 2))
    // line 0 immediate ops
    for ((n, b) <- Seq("ORI" -> 0x0000, "ANDI" -> 0x0200, "SUBI" -> 0x0400, "ADDI" -> 0x0600,
                       "EORI" -> 0x0A00, "CMPI" -> 0x0C00); (s, so, iw) <- sz) {
      val immExt = if (iw == 2) Seq(0x0000, 0x0001) else Seq(0x0001)
      // the .W #imm,SR forms change SR (S/T): excluded
      f += Fam(s"$n.$s", b | so, immExt, iw, skip = (m, r) => s == "w" && m == 7 && r == 4 && n != "SUBI" && n != "ADDI" && n != "CMPI")
    }
    for ((n, b) <- Seq("BTST#" -> 0x0800, "BCHG#" -> 0x0840, "BCLR#" -> 0x0880, "BSET#" -> 0x08C0))
      f += Fam(n, b, Seq(0x0003), 1)
    for ((n, b) <- Seq("BTST" -> 0x0300, "BCHG" -> 0x0340, "BCLR" -> 0x0380, "BSET" -> 0x03C0))
      f += Fam(s"$n D1", b, Nil, 1)
    for ((s, b) <- Seq("b" -> 0x00C0, "w" -> 0x02C0, "l" -> 0x04C0)) {
      f += Fam(s"CMP2.$s", b, Seq(0x0000), if (s == "l") 2 else 1)
      f += Fam(s"CHK2.$s", b, Seq(0x0800), if (s == "l") 2 else 1)
    }
    for ((s, b) <- Seq("b" -> 0x0AC0, "w" -> 0x0CC0, "l" -> 0x0EC0))
      f += Fam(s"CAS.$s", b, Seq(0x0000), 1, skip = (m, r) => s != "b" && m == 7 && r == 4)
    f += Fam("CALLM", 0x06C0, Seq(0x0000), 1)
    // line 4
    for ((n, b) <- Seq("NEGX" -> 0x4000, "CLR" -> 0x4200, "NEG" -> 0x4400, "NOT" -> 0x4600,
                       "TST" -> 0x4A00); (s, so, iw) <- sz)
      f += Fam(s"$n.$s", b | so, Nil, iw)
    f += Fam("MOVE fromSR", 0x40C0, Nil, 1)
    f += Fam("MOVE fromCCR", 0x42C0, Nil, 1)
    f += Fam("MOVE toCCR", 0x44C0, Nil, 1)
    f += Fam("CHK.L D1", 0x4300, Nil, 2)
    f += Fam("CHK.W D1", 0x4380, Nil, 1)
    f += Fam("LEA A5", 0x4BC0, Nil, 2)
    f += Fam("NBCD", 0x4800, Nil, 1)
    f += Fam("PEA", 0x4840, Nil, 2, skip = (m, _) => m == 1)   // mode 1 = BKPT
    f += Fam("TAS", 0x4AC0, Nil, 1)
    f += Fam("MOVEM.w r>m", 0x4880, Seq(0x0101), 1)
    f += Fam("MOVEM.l r>m", 0x48C0, Seq(0x0101), 2)
    f += Fam("MOVEM.w m>r", 0x4C80, Seq(0x0101), 1)
    f += Fam("MOVEM.l m>r", 0x4CC0, Seq(0x0101), 2)
    f += Fam("MULU.L", 0x4C00, Seq(0x0000), 2)
    f += Fam("MULS.L 64", 0x4C00, Seq(0x0C01), 2)
    f += Fam("DIVU.L", 0x4C40, Seq(0x0000), 2)
    f += Fam("DIVS.L 64", 0x4C40, Seq(0x1C02), 2)
    // line 5 (Scc mode 1 = DBcc, skipped: DBF branches by the pad word)
    for ((s, so, iw) <- sz) {
      f += Fam(s"ADDQ.$s", 0x5200 | so, Nil, iw)
      f += Fam(s"SUBQ.$s", 0x5300 | so, Nil, iw)
    }
    f += Fam("ST", 0x50C0, Nil, 1, skip = (m, _) => m == 1)
    f += Fam("SF", 0x51C0, Nil, 1, skip = (m, _) => m == 1)
    // lines 8, 9, B, C, D
    for ((n, b) <- Seq("OR" -> 0x8000, "SUB" -> 0x9000, "CMP" -> 0xB000, "AND" -> 0xC000, "ADD" -> 0xD000);
         (s, so, iw) <- sz) {
      f += Fam(s"$n.$s <ea>,D1", b | 0x0200 | so, Nil, iw)
      val dn = if (n == "CMP") "EOR" else n
      f += Fam(s"$dn.$s D1,<ea>", b | 0x0300 | so, Nil, iw)
    }
    f += Fam("DIVU.W", 0x82C0, Nil, 1); f += Fam("DIVS.W", 0x83C0, Nil, 1)
    f += Fam("MULU.W", 0xC2C0, Nil, 1); f += Fam("MULS.W", 0xC3C0, Nil, 1)
    f += Fam("SUBA.W", 0x92C0, Nil, 1); f += Fam("SUBA.L", 0x93C0, Nil, 2)
    f += Fam("CMPA.W", 0xB2C0, Nil, 1); f += Fam("CMPA.L", 0xB3C0, Nil, 2)
    f += Fam("ADDA.W", 0xD2C0, Nil, 1); f += Fam("ADDA.L", 0xD3C0, Nil, 2)
    // line E: memory shifts, bit fields (static and dynamic offset)
    for ((n, b) <- Seq("ASR" -> 0xE0C0, "ASL" -> 0xE1C0, "LSR" -> 0xE2C0, "LSL" -> 0xE3C0,
                       "ROXR" -> 0xE4C0, "ROXL" -> 0xE5C0, "ROR" -> 0xE6C0, "ROL" -> 0xE7C0))
      f += Fam(s"$n mem", b, Nil, 1)
    for ((n, b) <- Seq("BFTST" -> 0xE8C0, "BFEXTU" -> 0xE9C0, "BFCHG" -> 0xEAC0, "BFEXTS" -> 0xEBC0,
                       "BFCLR" -> 0xECC0, "BFFFO" -> 0xEDC0, "BFSET" -> 0xEEC0, "BFINS" -> 0xEFC0)) {
      f += Fam(s"$n st", b, Seq(0x10C5), 2)
      f += Fam(s"$n dyn", b, Seq(0x1805), 2)
    }
    f.result()
  }

  def moveCases: Seq[Case] = {
    val c = Seq.newBuilder[Case]
    for ((s, b, iw) <- Seq(("b", 0x1000, 1), ("w", 0x3000, 1), ("l", 0x2000, 2))) {
      for (e <- eaVariants(iw))        // source sweep, dst D1
        c += Case(s"MOVE.$s ${eaName(e.mode, e.reg, e.tag)},D1",
          Seq(f".short 0x${b | 0x0200 | (e.mode << 3) | e.reg}%04X") ++ e.ext.map(lbl), e.setup)
      for (e <- eaVariants(iw))        // destination sweep, src D0 (dst mode 1 = MOVEA)
        c += Case(s"MOVE.$s D0,${eaName(e.mode, e.reg, e.tag)}",
          Seq(f".short 0x${b | (e.reg << 9) | (e.mode << 6)}%04X") ++ e.ext.map(lbl), e.setup)
    }
    // both operands memory-indirect (src [A0] / [PC], dst [A1] pre and post indexed)
    val miSrc = Seq(
      ("[A0]", 0x30, Seq(".short 0x0161", ".short 0x0100"), Seq(s"move.l #${h(MIT)},${h(anInit(0) + 0x100)}")),
      ("[A0]+X+od", 0x30, Seq(".short 0x4926", ".short 0x0100", ".short 0x0010"), Seq(s"move.l #${h(MIT)},${h(anInit(0) + 0x100)}")),
      ("[PC]", 0x3B, Seq(".short 0x0171", ".long _ptrslot-@L"), Nil))
    val miDst = Seq(
      ("[A1]", (1 << 9) | (6 << 6), Seq(".short 0x0161", ".short 0x0100")),
      ("[A1]+X+od", (1 << 9) | (6 << 6), Seq(".short 0x4926", ".short 0x0100", ".short 0x0010")))
    for ((sn, sb, sw, ss) <- miSrc; (dn, db, dw) <- miDst)
      c += Case(s"MOVE.l $sn,$dn",
        Seq(f".short 0x${0x2000 | db | sb}%04X") ++ sw.map(lbl) ++ dw,
        ss :+ s"move.l #${h(MIT + 0x100)},${h(anInit(1) + 0x100)}")
    c.result()
  }

  private def lbl(s: String): String = s   // `@L` substituted when the case is emitted

  def familyCases: Seq[Case] =
    for {
      fam <- families
      e <- eaVariants(fam.immWords)
      if !fam.skip(e.mode, e.reg)
    } yield Case(s"${fam.name} ${eaName(e.mode, e.reg, e.tag)}",
      Seq(f".short 0x${fam.op | (e.mode << 3) | e.reg}%04X") ++ fam.exts.map(x => f".short 0x$x%04X") ++ e.ext,
      e.setup)

  def jmpCases: Seq[(Case, Seq[Long])] =
    for {
      (n, op) <- Seq("JMP" -> 0x4EC0, "JSR" -> 0x4E80)
      e <- eaVariants(2)
    } yield (Case(s"$n ${eaName(e.mode, e.reg, e.tag)}",
      Seq(f".short 0x${op | (e.mode << 3) | e.reg}%04X") ++ e.ext,
      // re-plant this case's own targets (a memory-indirect setup may overwrite one)
      e.setup ++ e.jmpTargets.map(t => s"move.w  #0xA000,${h(t)}")), e.jmpTargets)

  /** All cases, in program order; the JMP/JSR cases (and their targets) come last. */
  def allCases(extra: Seq[Case] = Nil): (Vector[Case], Int, Seq[Long]) = {
    val body = (familyCases ++ moveCases ++ extra).toVector
    val jmps = jmpCases.toVector
    (body ++ jmps.map(_._1), body.size, jmps.flatMap(_._2).distinct)
  }

  /** Assemble the program for cases `start until all.size` (result slots keep their global
    * index). Returns (source, case list). */
  def program(start: Int = 0, extra: Seq[Case] = Nil): (String, Vector[Case]) = {
    val (all, jmpStart, targets) = allCases(extra)
    val sb = new StringBuilder
    sb ++= s"""    .text
              |_start:
              |    lea     ${h(STK)},%sp
              |    lea     8,%a0
              |    move.w  #253,%d0
              |1:  move.l  #_h,(%a0)+
              |    dbf     %d0,1b
              |    move.l  #${h(MIT)},_ptrslot
              |""".stripMargin
    def emit(c: Case, i: Int): Unit = {
      sb ++= s"| ${c.desc}\n_c$i:\n"
      sb ++= s"    movem.l _init,%d0-%d7/%a0-%a6\n"
      sb ++= s"    lea     ${h(STK)},%sp\n"
      sb ++= s"    move.l  #${h(RES + i)},${h(SLOT)}\n"
      sb ++= s"    move.l  #_r$i,${h(SLOT + 4)}\n"
      c.setup.foreach(l => sb ++= s"    $l\n")
      sb ++= s"    ${c.words.head}\n"
      if (c.words.size > 1) {
        // `_eN` marks the full-format ext word of a PC memory-indirect EA (its long bd is
        // `_ptrslot - _eN`); it follows the opcode's own extension words.
        val rest = c.words.tail
        val firstEa = rest.indexWhere(_.contains("@L")) match {
          case -1 => 0
          case k  => k - 1
        }
        rest.zipWithIndex.foreach { case (w, k) =>
          if (k == firstEa) sb ++= s"_e$i:\n"
          sb ++= s"    ${w.replace("@L", s"_e$i")}\n"
        }
      }
      sb ++= s"    $PAD\n    $PAD\n    $PAD\n    $PAD\n"
      sb ++= s"    move.b  #0x7E,${h(RES + i)}\n"
      sb ++= s"_r$i:\n"
    }
    for (i <- start until all.size) {
      // JMP/JSR section: plant line-A words at every computed target first.
      if (i == math.max(start, jmpStart)) {
        targets.foreach(t => sb ++= s"    move.w  #0xA000,${h(t)}\n")
        sb ++= s"    move.w  #0xA000,${h(MIT)}\n"
      }
      emit(all(i), i)
    }
    sb ++= s"""    move.l  #0xC0FFEE00,0xFFFF0000
              |_done:
              |    bra     _done
              |_h:
              |    move.w  6(%sp),%d0
              |    and.w   #0x0fff,%d0
              |    lsr.w   #2,%d0
              |    movea.l ${h(SLOT)},%a0
              |    move.b  %d0,(%a0)
              |    lea     ${h(STK)},%sp
              |    move.w  #0x2700,%sr
              |    movea.l ${h(SLOT + 4)},%a0
              |    jmp     (%a0)
              |    .align 4
              |_init:
              |${initRegs.map(r => s"    .long ${h(r)}").mkString("\n")}
              |_ptrslot:
              |    .long ${h(MIT)}
              |    .fill 0x10000,2,0xA000
              |""".stripMargin
    (sb.result(), all)
  }

  def vecName(v: Int): String = v match {
    case 0x7E => "ok"; case 0xFF => "unwritten"; case 4 => "ILL"; case 10 => "LINEA"; case 11 => "LINEF"
    case 5 => "DIV0"; case 6 => "CHK"; case 7 => "TRAPcc"; case 2 => "BERR"; case 3 => "ADDR"
    case 8 => "PRIV"; case -1 => "none"; case -2 => "HANG"; case x => s"v$x"
  }
  def legalityClass(v: Int): Boolean = v == 4 || v == 10 || v == 11 || v == -2
}

class DecodeLegalityEnumSpec extends AnyFunSuite {
  import DecodeLegalityEnum._

  test("decode legality: every opcode family x EA mode, DUT vs Musashi (vector 4 / line A/F)", VerilatorTest, SlowTest) {
    assume(sys.env.get("LEGALITY_ENUM").contains("1"), "opt-in (~30 min): set LEGALITY_ENUM=1")
    val (src, cases) = program()
    sys.env.get("LEGALITY_DUMP_SRC").foreach { p =>
      java.nio.file.Files.write(java.nio.file.Paths.get(p), src.getBytes("UTF-8"))
    }
    val mus = Musashi.assembleAndRun(src, maxCycles = 200000000) match {
      case Right(s) => s
      case Left(e)  => fail(s"musashi: ${e.reason}")
    }
    assert(mus.pass, s"Musashi did not reach the sentinel (pc=0x${mus.pc.toHexString})")
    val musRes = cases.indices.map(i => mus.memoryWrites.getOrElse(RES + i, -1))

    // The DUT runs in segments: a case that wedges the core is recorded as HANG and the
    // next segment restarts the program at the following case.
    val dutRes = Array.fill(cases.size)(-1)
    var start = 0; var runs = 0
    while (start < cases.size) {
      val (segSrc, _) = program(start)
      runs += 1
      val outcome = PortedTestRunner.run(s"decode_legality_$start", segSrc,
        sys.env.get("LEGALITY_CYCLES").map(_.toLong).getOrElse(6000000L), allowBkptCompletion = false,
        onFinish = rd => for (i <- start until cases.size) dutRes(i) = rd(RES + i),
        stallWatch = (SLOT, 20000L))
      (start until cases.size).find(i => dutRes(i) == 0xFF) match {
        case None    => start = cases.size
        case Some(k) =>
          println(s"[legality] segment from #$start: $outcome, wedged at #$k ${cases(k).desc}")
          dutRes(k) = -2; start = k + 1
      }
    }
    println(s"[legality] cases=${cases.size} dut runs=$runs")

    val legal = Seq.newBuilder[String]; val other = Seq.newBuilder[String]
    val keys = Seq.newBuilder[String]
    for (i <- cases.indices) {
      val d = if (dutRes.isEmpty) -1 else dutRes(i); val m = musRes(i)
      val line = f"#$i%4d ${cases(i).desc}%-40s dut=${vecName(d)}%-6s musashi=${vecName(m)}"
      if (legalityClass(d) != legalityClass(m) || (legalityClass(d) && d != m)) {
        legal += line; keys += s"${cases(i).desc}|${vecName(d)}|${vecName(m)}"
      } else if (d != m) other += line
    }
    val l = legal.result(); val o = other.result()
    println(s"[legality] LEGALITY MISMATCHES: ${l.size}")
    l.foreach(x => println(s"[legality] $x"))
    println(s"[legality] other vector differences (value-dependent, informational): ${o.size}")
    o.foreach(x => println(s"[legality-info] $x"))
    val known: Set[String] = {
      val f = java.nio.file.Paths.get("src/test/resources/decode-legality-known.txt")
      if (!java.nio.file.Files.exists(f)) Set.empty
      else scala.io.Source.fromFile(f.toFile).getLines().map(_.trim)
        .filter(x => x.nonEmpty && !x.startsWith("#")).toSet
    }
    val now = keys.result().toSet
    val fresh = (now -- known).toSeq.sorted
    val gone  = (known -- now).toSeq.sorted
    gone.foreach(x => println(s"[legality-fixed] no longer mismatches (remove from the known list): $x"))
    fresh.foreach(x => println(s"[legality-NEW] $x"))
    assert(fresh.isEmpty, s"${fresh.size} legality mismatches not in decode-legality-known.txt")
  }
}
