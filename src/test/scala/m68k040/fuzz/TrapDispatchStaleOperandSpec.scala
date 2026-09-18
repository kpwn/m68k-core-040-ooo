package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** TRAP-DISPATCH STALE-OPERAND HUNT (2026-09-18).
  *
  * The board symptom is PC landing INSIDE a low-memory table of 32-bit addresses --
  * 0xC30 (ToolBox trap table entry 12) and 0x2E (the exception vector table). Executing
  * the ADDRESS OF an entry instead of its CONTENTS is a MISSING INDIRECTION: Mac trap
  * dispatch is, in shape,
  *
  *     move.l  (table,Dn*4),A0
  *     jmp     (A0)
  *
  * and if that load never architecturally lands -- dropped from the stream, squashed and
  * never replayed, or its writeback lost -- A0 retains the computed ENTRY ADDRESS and the
  * jump executes the table itself.
  *
  * There is prior form for exactly that class in this core: `7437b29c` ("FMOVEM.X
  * silently DROPPED an instruction -- two guards every other macro had"), whose commit
  * message describes the board capture as "PC marching 4 bytes at a time through a
  * low-memory pointer table". The mechanism was a slot-1 stash FSM in `DecodeStage` that
  * consumed a fetch group WITHOUT emitting it, and it was ALIGNMENT-DEPENDENT: which
  * decode slot the victim landed in decided whether it died.
  *
  * WHY THIS IS SELF-CHECKING AND NOT LOCK-STEP. The first version of this spec ran the
  * same programs through `FuzzRunner` (full Musashi lock-step). That reported 64
  * divergences out of 176 -- ALL of them `kind: STEP`, and ALL of them confined to the
  * MOVEM and FMOVEM.X variants. Every one was a RETIRE-BOUNDARY ATTRIBUTION artifact,
  * not a defect:
  *   * `movem.l <ea>,%d0-%d1` retires TWO integer writes at ONE architectural PC, and the
  *     harness emits one boundary per integer write, so it books two boundaries for one
  *     oracle step -- the harness prints this itself as
  *     "DUPLICATE BOUNDARY: ... (harness bug, not an RTL bug)".
  *   * `fmovem.x %fp0-%fp1,(%a3)` writes only memory and FP registers, so it produces NO
  *     integer-visible boundary and the harness books ZERO boundaries for one oracle step.
  * Both shift the index by one, after which the index-aligned structural compare reports
  * A0 as "stale" or "ahead" purely from the lag. In every sampled trace the DUT's own
  * retire stream shows A0 receiving the CORRECT target and control reaching the correct
  * label. That makes lock-step unusable as the verdict for exactly the macro families
  * implicated on the board -- so the verdict here is the PROGRAM'S OWN, via the ported
  * corpus sentinel, which cannot be confounded by boundary bookkeeping.
  *
  * Construction: A0 is PRE-POISONED with a wrong target immediately before the
  * indirection, so a dropped/lost load sends control to a FAIL block. Every macro-op
  * family that owns a slot-1 stash FSM in `DecodeStage` is used as the stressor between
  * the poison and the indirection, and the whole sequence is swept across 0..7 words of
  * leading padding and 0..1 words of separation, because the defect class is decided by
  * which decode slot the victim lands in.
  */
class TrapDispatchStaleOperandSpec extends AnyFunSuite {

  /** Macro-op families that own a slot-1 stash FSM in DecodeStage (`7437b29c` proves a
    * missing guard in ANY of them drops the instruction that followed), plus a control. */
  private val stressors: Seq[(String, Seq[String])] = Seq(
    "none"       -> Seq(),
    "movem_st"   -> Seq("    movem.l %d0-%d1,0x9100"),
    "movem_ld"   -> Seq("    movem.l 0x9100,%d0-%d1"),
    "movem_pd"   -> Seq("    lea 0x9160,%a3", "    movem.l %d0-%d3,-(%a3)"),
    "movem_pi"   -> Seq("    lea 0x9100,%a3", "    movem.l (%a3)+,%d0-%d3"),
    "movep"      -> Seq("    lea 0x9120,%a3", "    movep.l %d0,1(%a3)"),
    "fmovemx_st" -> Seq("    fmove.l %d0,%fp0", "    fmove.l %d1,%fp1",
                        "    lea 0x9120,%a3", "    fmovem.x %fp0-%fp1,(%a3)"),
    "fmovemx_ld" -> Seq("    fmove.l %d0,%fp0", "    fmove.l %d1,%fp1",
                        "    lea 0x9120,%a3", "    fmovem.x (%a3),%fp0-%fp1"),
    "ucode_bcd"  -> Seq("    lea 0x9120,%a3", "    lea 0x9128,%a4",
                        "    abcd -(%a3),-(%a4)"),
    "ucode_bf"   -> Seq("    moveq #3,%d5", "    bfextu 0x9120{%d5:4},%d6"),
    "ucode_memi" -> Seq("    lea 0x9120,%a3", "    move.l %a3,0x9134",
                        "    move.l ([0x9134]),%d6"),
    "ucode_cas"  -> Seq("    lea 0x9120,%a3", "    cas.l %d0,%d1,(%a3)")
  )

  private val SentinelAddr = PortedTestRunner.SentinelAddr   // 0xFFFF0000
  private val PassWord     = PortedTestRunner.PassWord       // 0xC0FFEE00
  private val TABLE        = 0x9200L                         // the "trap dispatch table"

  /** One self-checking program per (stressor, pad, sep). */
  private def program(stressor: Seq[String], pad: Int, sep: Int): String = {
    val sb = new StringBuilder
    def e(s: String): Unit = { sb.append(s); sb.append('\n') }
    e("    .text")
    e("    .org 0")
    e("    .long 0x00011000")          // vector 0: initial SSP
    e("    .long _start")              // vector 1: initial PC
    // Every remaining vector points at the FAIL block, so a spurious trap taken anywhere
    // in this program is reported as a FAIL sentinel rather than wandering off.
    for (_ <- 2 until 256) e("    .long vec_fail")
    e("_start:")
    e("    lea 0x00011000,%a7")
    for (i <- 0 until 8) e(f"    move.l #0x${0x11110000L + i * 0x1111}%x,%%d$i")
    for (i <- 0 until 7) e(f"    lea 0x9120,%%a$i")
    // Seed the scratch area the stressors read/write.
    for (i <- 0 until 24) e(f"    move.l %%d${i % 8},0x${0x9100L + i * 4}%x")
    // The dispatch table entry holds the address of the CORRECT continuation.
    e("    lea Lgood,%a2")
    e(f"    move.l %%a2,0x$TABLE%x")
    // ── PRE-POISON A0 with the WRONG target. On the board the stale value is the table
    //    ENTRY ADDRESS itself; a real label keeps the failure bounded and self-reporting.
    e("    lea Lbad,%a0")
    for (_ <- 0 until pad) e("    nop")
    stressor.foreach(e)
    for (_ <- 0 until sep) e("    nop")
    // ── THE INDIRECTION UNDER TEST ───────────────────────────────────────────────
    e(f"    move.l 0x$TABLE%x,%%a0")
    e("    jmp (%a0)")
    // Reaching Lbad means the JMP used a STALE A0: the indirection did not land.
    e("Lbad:")
    e(f"    move.l #0xDEADD15B,0x$SentinelAddr%x")
    e("    bra .")
    e("vec_fail:")
    e(f"    move.l #0xDEAD0BAD,0x$SentinelAddr%x")
    e("    bra .")
    e("Lgood:")
    // Prove the landing was real AND that A0 really holds the table CONTENTS, not the
    // entry address -- a jump that arrived here for any other reason still fails.
    e("    lea Lgood,%a1")
    e("    cmp.l %a1,%a0")
    e("    bne Lbad")
    e(f"    move.l #0x${PassWord}%x,0x$SentinelAddr%x")
    e("    bra .")
    sb.toString
  }

  /** Variant C: the MEMORY-INDIRECT dispatch, which is the most literal form of the board
    * symptom. `jmp ([table,Dn*4])` performs the indirection INSIDE the addressing mode --
    * the microcode entry `MI_JMP_ENTRY` ("ptr-load -> T0, ibranch to T0+od(+post-idx)") --
    * so if the pointer load is lost or the intermediate EA is used as the target, PC
    * becomes the table ENTRY ADDRESS itself. That is exactly PC=0xC30 (ToolBox trap table
    * entry 12) and PC=0x2E (the exception vector table).
    *
    * Detection does not rely on a poisoned register here: if the intermediate address were
    * branched to, control would land ON the table, which is data, so the vector table's
    * catch-all `vec_fail` reports it. Arriving at `Lgood` proves the indirection resolved.
    */
  private def memIndProgram(form: String, pad: Int): String = {
    val sb = new StringBuilder
    def e(s: String): Unit = { sb.append(s); sb.append('\n') }
    e("    .text")
    e("    .org 0")
    e("    .long 0x00011000")
    e("    .long _start")
    for (_ <- 2 until 256) e("    .long vec_fail")
    e("_start:")
    e("    lea 0x00011000,%a7")
    for (i <- 0 until 8) e(f"    move.l #0x${0x11110000L + i * 0x1111}%x,%%d$i")
    for (i <- 0 until 7) e(f"    lea 0x9120,%%a$i")
    for (i <- 0 until 24) e(f"    move.l %%d${i % 8},0x${0x9100L + i * 4}%x")
    e("    lea Lgood,%a2")
    // Fill four table slots so an index of 0..3 is always valid; entry 2 is the one used.
    for (i <- 0 until 4) e(f"    move.l %%a2,0x${TABLE + i * 4}%x")
    e("    moveq #2,%d1")                       // the "trap number"
    e("    moveq #63,%d4")
    e("Lloop:")
    for (_ <- 0 until pad) e("    nop")
    form match {
      case "abs"    => e(f"    jmp ([0x$TABLE%x])")
      case "idx_w"  => e(f"    jmp ([0x$TABLE%x,%%d1.w*4])")
      case "idx_l"  => e(f"    jmp ([0x$TABLE%x,%%d1.l*4])")
      case "jsr"    => e(f"    jsr ([0x$TABLE%x,%%d1.w*4])")
      case "load"   => e(f"    move.l ([0x$TABLE%x,%%d1.w*4]),%%a0"); e("    jmp (%a0)")
    }
    e("Lbad:")
    e(f"    move.l #0xDEADD15B,0x$SentinelAddr%x")
    e("    bra .")
    e("vec_fail:")
    e(f"    move.l #0xDEAD0BAD,0x$SentinelAddr%x")
    e("    bra .")
    e("Lgood:")
    if (form == "jsr") e("    addq.l #4,%a7")    // discard the return address
    e("    dbf %d4,Lloop")
    e(f"    move.l #0x${PassWord}%x,0x$SentinelAddr%x")
    e("    bra .")
    sb.toString
  }

  test("a MEMORY-INDIRECT dispatch branches to the table CONTENTS, never to the entry address", VerilatorTest) {
    val fails    = scala.collection.mutable.ArrayBuffer[String]()
    val genFails = scala.collection.mutable.ArrayBuffer[String]()
    var ran = 0
    val postures = Seq(
      "cache-off" -> CachePosture.AsWritten,
      "copyback"  -> CachePosture.ForceCacheableCopyback)
    for ((pname, posture) <- postures; form <- Seq("abs", "idx_w", "idx_l", "jsr", "load");
         pad <- 0 until 4) {
      val tag = s"$pname/$form pad=$pad"
      val src = memIndProgram(form, pad)
      PortedTestRunner.run(s"trapmi_${pname}_${form}_$pad", src, 400000L,
                           cachePosture = posture) match {
        case PortedPass       => ran += 1
        case PortedFail(w)    =>
          ran += 1
          val why = w match {
            case 0xDEADD15BL => "fell through the dispatch without branching"
            case 0xDEAD0BADL => "TRAP taken -- control most likely landed ON the table " +
                                "(the entry ADDRESS was used as the target)"
            case other       => f"unexpected sentinel 0x$other%08x"
          }
          fails += f"$tag: $why"
        case PortedHang(c)    => ran += 1; fails += s"$tag: HANG after $c cycles"
        case PortedGenFail(r) => genFails += s"$tag: $r"
      }
    }
    println(s"[trapmi] ran=$ran fails=${fails.size} genfails=${genFails.size}")
    genFails.foreach(g => println(s"[trapmi] GENFAIL $g"))
    assert(ran > 0, "vacuous sweep")
    assert(genFails.isEmpty, s"${genFails.size} variants never ran:\n" + genFails.mkString("\n"))
    assert(fails.isEmpty, s"${fails.size} memory-indirect dispatch failures:\n" + fails.mkString("\n"))
  }

  /** Variant B: the same indirection, but with a FLUSH landing in the window between the
    * load and the jump, and repeated 64 times so many flushes land there.
    *
    * The coordinator's mechanism requires something to squash the window: a mispredict,
    * an interrupt, or a cache-maintenance restart. This puts an ALTERNATING conditional
    * branch immediately before the stressor+indirection, so the BTB/gshare pair is wrong
    * roughly every other iteration and a real redirect flushes the pipe right where the
    * dispatch is being renamed/issued. `cpush` variants additionally fire the
    * `icMaintFlush` frontend restart arm in the same window.
    */
  private def loopProgram(stressor: Seq[String], pad: Int, flush: String): String = {
    val sb = new StringBuilder
    def e(s: String): Unit = { sb.append(s); sb.append('\n') }
    e("    .text")
    e("    .org 0")
    e("    .long 0x00011000")
    e("    .long _start")
    for (_ <- 2 until 256) e("    .long vec_fail")
    e("_start:")
    e("    lea 0x00011000,%a7")
    for (i <- 0 until 8) e(f"    move.l #0x${0x11110000L + i * 0x1111}%x,%%d$i")
    for (i <- 0 until 7) e(f"    lea 0x9120,%%a$i")
    for (i <- 0 until 24) e(f"    move.l %%d${i % 8},0x${0x9100L + i * 4}%x")
    e("    lea Lgood,%a2")
    e(f"    move.l %%a2,0x$TABLE%x")
    e("    moveq #63,%d4")                       // loop counter
    e("    moveq #0,%d3")                        // branch-direction toggle
    e("Lloop:")
    e("    lea Lbad,%a0")                        // PRE-POISON, every iteration
    flush match {
      case "mispred" =>
        e("    eor.b #1,%d3")
        e("    and.b #1,%d3")
        e("    bne Lalt")                        // alternates -> mispredicts + flushes
        e("    nop")
        e("Lalt:")
      case "cpush" =>
        e("    cpusha %bc")                      // icMaintFlush: frontend restart arm
      case "none" => ()
    }
    for (_ <- 0 until pad) e("    nop")
    stressor.foreach(e)
    e(f"    move.l 0x$TABLE%x,%%a0")
    e("    jmp (%a0)")
    e("Lbad:")
    e(f"    move.l #0xDEADD15B,0x$SentinelAddr%x")
    e("    bra .")
    e("vec_fail:")
    e(f"    move.l #0xDEAD0BAD,0x$SentinelAddr%x")
    e("    bra .")
    e("Lgood:")
    e("    lea Lgood,%a1")
    e("    cmp.l %a1,%a0")
    e("    bne Lbad")
    e("    dbf %d4,Lloop")
    e(f"    move.l #0x${PassWord}%x,0x$SentinelAddr%x")
    e("    bra .")
    sb.toString
  }

  test("a flush landing between the table load and the JMP never leaves a stale A0", VerilatorTest) {
    val fails    = scala.collection.mutable.ArrayBuffer[String]()
    val genFails = scala.collection.mutable.ArrayBuffer[String]()
    var ran = 0
    val postures = Seq(
      "cache-off" -> CachePosture.AsWritten,
      "copyback"  -> CachePosture.ForceCacheableCopyback)
    for ((pname, posture) <- postures; flush <- Seq("mispred", "cpush");
         (name, body) <- stressors; pad <- 0 until 4) {
      val tag = s"$pname/$flush/$name pad=$pad"
      val src = loopProgram(body, pad, flush)
      PortedTestRunner.run(s"trapflush_${pname}_${flush}_${name}_$pad", src, 400000L,
                           cachePosture = posture) match {
        case PortedPass       => ran += 1
        case PortedFail(w)    =>
          ran += 1
          val why = w match {
            case 0xDEADD15BL => "STALE A0: the JMP used the pre-poisoned address, or A0 " +
                                "!= table contents on arrival"
            case 0xDEAD0BADL => "unexpected TRAP taken"
            case other       => f"unexpected sentinel 0x$other%08x"
          }
          fails += f"$tag: $why"
        case PortedHang(c)    => ran += 1; fails += s"$tag: HANG after $c cycles"
        case PortedGenFail(r) => genFails += s"$tag: $r"
      }
    }
    println(s"[trapflush] ran=$ran fails=${fails.size} genfails=${genFails.size}")
    genFails.foreach(g => println(s"[trapflush] GENFAIL $g"))
    assert(ran > 0, "vacuous sweep")
    assert(genFails.isEmpty, s"${genFails.size} variants never ran:\n" + genFails.mkString("\n"))
    assert(fails.isEmpty, s"${fails.size} stale-operand dispatch failures:\n" + fails.mkString("\n"))
  }

  test("a table-indirect JMP never executes with a stale address register", VerilatorTest) {
    val fails    = scala.collection.mutable.ArrayBuffer[String]()
    val genFails = scala.collection.mutable.ArrayBuffer[String]()
    var ran = 0
    val postures = Seq(
      "cache-off" -> CachePosture.AsWritten,
      "copyback"  -> CachePosture.ForceCacheableCopyback)
    for ((pname, posture) <- postures; (name, body) <- stressors; pad <- 0 until 8; sep <- 0 until 2) {
      val tag = s"$pname/$name pad=$pad sep=$sep"
      val src = program(body, pad, sep)
      PortedTestRunner.run(s"trapdisp_${pname}_${name}_${pad}_$sep", src, 200000L,
                           cachePosture = posture) match {
        case PortedPass       => ran += 1
        case PortedFail(w)    =>
          ran += 1
          val why = w match {
            case 0xDEADD15BL => "STALE A0: the JMP used the pre-poisoned address -- the " +
                                "indirection never landed (or A0 != table contents at Lgood)"
            case 0xDEAD0BADL => "unexpected TRAP taken (vector table points at vec_fail)"
            case other       => f"unexpected sentinel 0x$other%08x"
          }
          fails += f"$tag: $why"
        case PortedHang(c)    => ran += 1; fails += s"$tag: HANG after $c cycles"
        case PortedGenFail(r) => genFails += s"$tag: $r"
      }
    }
    println(s"[trapdispatch] ran=$ran fails=${fails.size} genfails=${genFails.size}")
    genFails.foreach(g => println(s"[trapdispatch] GENFAIL $g"))
    assert(ran > 0, "vacuous sweep: every variant was refused by the assembler")
    assert(genFails.isEmpty, s"${genFails.size} variants never ran:\n" + genFails.mkString("\n"))
    assert(fails.isEmpty, s"${fails.size} stale-operand dispatch failures:\n" + fails.mkString("\n"))
  }
}
