package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import scala.collection.mutable

/** RANDOMIZED LIVENESS + DEVICE-EXACTNESS STRESS for out-of-order LS issue.
  *
  * Complements the architectural lock-step fuzz (`FuzzLockStepSpec` with
  * `FUZZ_DEVICE_WINDOW=1`), which checks every value against Musashi but cannot inject
  * interrupts. This spec checks the two properties the relaxation can break that a value
  * compare cannot see, UNDER an interrupt storm, exceptions and mispredict flushes:
  *
  *   LIVENESS   `LsLivenessMonitor` (retire progress, per-stage age, oldest-LS progress) is
  *              attached to every run; a wedge fails with a state dump, not a timeout.
  *   EXACTNESS  every aligned device register is read on the bus EXACTLY as many times as
  *              the straight-line program reads it, and written exactly as many times as
  *              it writes it. A replayed or double-launched inhibited access is invisible
  *              in the register file (the 53C96 lesson) and shows up here.
  *   RESULT     the device RMW counter equals the number of RMW instructions (sentinel).
  *
  * Each program is a seeded straight-line mix of: loads whose ADDRESS comes from a divide
  * (so younger ready ops must overtake them -- the relaxed select's whole point), aligned
  * inhibited loads / stores / RMWs, device<->memory moves both ways, a line-crossing
  * inhibited long (unparkable), cacheable store bursts (SQ pressure), store->load forwards,
  * a store whose DATA is a divide-delayed load's value, divide-by-zero traps, TRAP #0,
  * data-dependent branches, and MOVEM. Odd seeds add a phase-swept level-1 IRQ storm.
  *
  * Env: LSOOO_STRESS_START (0), LSOOO_STRESS_SEEDS (16), LSOOO_STRESS_OPS (120). */
class LsOooStressSpec extends AnyFunSuite {
  private def envInt(k: String, d: Int) = sys.env.get(k).map(_.trim.toInt).getOrElse(d)
  private val seedStart = envInt("LSOOO_STRESS_START", 0)
  private val seedCount = envInt("LSOOO_STRESS_SEEDS", 16)
  private val nOps      = envInt("LSOOO_STRESS_OPS", 120)
  private val requireP1Fire = sys.env.get("LSOOO_REQUIRE_P1_FIRE").contains("1")
  private val Timeout   = 400000L

  private val Dev = 0xFFFF0100L
  /** Aligned device registers whose bus traffic is counted exactly. 0x108 is the RMW
    * counter; the split long lives at 0x12E so it never touches these addresses. */
  private val exactRegs = Seq(0x100L, 0x104L, 0x108L, 0x10CL, 0x110L).map(Dev - 0x100L + _)

  // Registered only for the experimental four-cycle build. The ordinary random
  // program below rarely contains its narrow fused MOVEA consumer, so passing that
  // stress alone cannot establish that the live L1 operand bypass ever fired.
  if (m68k040.top.ShippingCoreConfig.lsFourCycleL1) {
    test("four-cycle resident MOVEA bypass survives TRAP/RTE and cold replay", VerilatorTest) {
      val warm = Seq.fill(8)("    movea.l (%a5),%a5")
      // Three different lines avoid the self-pointer's data-vacuity: if a bypass
      // captures the previous address rather than the returned pointer, either
      // eight-step checkpoint lands on the wrong node.
      val beforeCheck = Seq("    cmpa.l #0x00088080,%a5", "    bne _bad")
      val afterCheck  = Seq("    cmpa.l #0x00088040,%a5", "    bne _bad")
      val src = (Seq(
        "    .text", "_start:",
        "    move.l #_traph,0x80",      // TRAP #0 vector
        "    move.l #0x00088040,0x00088000",
        "    move.l #0x00088080,0x00088040",
        "    move.l #0x00088000,0x00088080",
        "    lea 0x00088000,%a5",
        "    cpushl %dc,(%a5)",         // preserve all three pointers in RAM
        "    lea 0x00088040,%a4",
        "    cpushl %dc,(%a4)",
        "    lea 0x00088080,%a4",
        "    cpushl %dc,(%a4)",
        "    cinvl %dc,(%a5)",          // first read of each line is cold
        "    lea 0x00088040,%a4",
        "    cinvl %dc,(%a4)",
        "    lea 0x00088080,%a4",
        "    cinvl %dc,(%a4)"
      ) ++ warm ++ beforeCheck ++ Seq("    trap #0") ++ warm ++ afterCheck ++ Seq(
        "    lea 0xFFFF0000,%a6", "    move.l #0xC0FFEE00,%d0",
        "    move.l %d0,(%a6)", "_halt:", "    bra _halt",
        "_bad:", "    lea 0xFFFF0000,%a6", "    move.l #0xBAD40004,%d0",
        "    move.l %d0,(%a6)", "    bra _halt",
        "_traph:", "    rte"
      )).mkString("\n")
      var beforeTrap = 0L; var afterTrap = 0L
      var preselect = 0L; var liveHit = 0L; var coldRsp = 0L
      var exceptionEntries = 0L; var lastException = false; var trapSeen = false
      val outcome = PortedTestRunner.run("fourcycle_trap_warm", src, Timeout,
        cachePosture = CachePosture.ForceCacheableCopyback, liveness = true,
        simSeed = 17, dcfg = m68k040.sim.L2Sweeps.l2DramSlow,
        onDut = d => d.clockDomain.onSamplings {
          val exception = !d.rob.logic.excIdle.toBoolean
          if (exception && !lastException) {
            exceptionEntries += 1
            if (beforeTrap > 0) trapSeen = true
          }
          lastException = exception
          if (d.iq.logic.p3Preselect.toBigInt != 0) preselect += 1
          if (d.lsEu.logic.liveL1HeadGrant.toBoolean) liveHit += 1
          if (d.dcache.logic.loadRspPort.valid.toBoolean &&
              !d.dcache.logic.loadRspPort.payload.residentHit.toBoolean) coldRsp += 1
          if (d.lsEu.logic.liveBaseBypass.toBoolean &&
              d.lsEu.issuePort.valid.toBoolean && d.lsEu.issuePort.ready.toBoolean) {
            if (trapSeen) afterTrap += 1 else beforeTrap += 1
          }
        })
      println(s"[fourcycle-trap] outcome=$outcome before=$beforeTrap after=$afterTrap " +
        s"p3=$preselect liveHit=$liveHit coldRsp=$coldRsp exceptionEntries=$exceptionEntries")
      assert(outcome == PortedPass, s"four-cycle trap fixture failed: $outcome")
      assert(beforeTrap > 0 && afterTrap > 0 && trapSeen && exceptionEntries > 0,
        "eligible warm consumer did not actually bypass on both sides of TRAP/RTE")
      assert(preselect >= beforeTrap + afterTrap && liveHit >= beforeTrap + afterTrap && coldRsp > 0,
        "cold-to-warm response and exact live-hit confirmation were not exercised")
    }
  }

  private final case class Prog(src: String, reads: Map[Long, Int], writes: Map[Long, Int],
                                rmw: Int, irq: Boolean)

  private def gen(seed: Int): Prog = {
    val r = new scala.util.Random(seed)
    val b = new StringBuilder
    val rd = mutable.Map.empty[Long, Int].withDefaultValue(0)
    val wr = mutable.Map.empty[Long, Int].withDefaultValue(0)
    def dev(off: Int): Long = Dev + off
    var rmw = 0; var lbl = 0
    def L(): String = { lbl += 1; s"_s$lbl" }
    val irq = (seed & 1) == 1
    b ++= """    .text
      |_start:
      |    move.l  #_irqh, 0x64
      |    move.l  #_div0h, 0x14
      |    move.l  #_traph, 0x80
      |    lea     0xFFFF0100, %a0
      |    lea     0x00090000, %a3
      |    lea     0x00001000, %a2
      |    move.l  #0x33333333, 0x00089000
      |    move.l  #0, 0x00090100
      |    move.l  #0, 8(%a0)
      |    moveq   #7, %d5
      |    moveq   #1, %d1
      |""".stripMargin
    wr(dev(8)) += 1
    if (irq) b ++= "    move.w  #0x2000, %sr\n"
    // a4 <- 0x00089000 through two divides: every access through it is late.
    def slowA4(): Unit = b ++=
      """    move.l  #0x00009000, %d6
        |    divu.w  #1, %d6
        |    divu.w  #1, %d6
        |    and.l   #0xffff, %d6
        |    add.l   #0x00080000, %d6
        |    movea.l %d6, %a4
        |""".stripMargin
    val alignedRd = Seq(0x0, 0x4, 0xC, 0x10)
    for (_ <- 0 until nOps) r.nextInt(14) match {
      case 0 => slowA4(); b ++= "    move.l  (%a4), %d1\n"
      case 1 => val o = alignedRd(r.nextInt(alignedRd.size)); rd(dev(o)) += 1
                b ++= f"    move.l  $o%d(%%a0), %%d0\n"
      case 2 => wr(dev(4)) += 1; b ++= "    move.l  %d1, 4(%a0)\n"
      case 3 => rmw += 1; rd(dev(8)) += 1; wr(dev(8)) += 1; b ++= "    addq.l  #1, 8(%a0)\n"
      case 4 => wr(dev(0x10)) += 1; b ++= "    move.l  (%a3), 16(%a0)\n"
      case 5 => rd(dev(0xC)) += 1; b ++= "    move.l  12(%a0), (%a3)\n"
      case 6 => val n = 3 + r.nextInt(8)
                for (k <- 0 until n) b ++= f"    move.l  %%d5, ${4 * k}%d(%%a3)\n"
      case 7 => b ++= "    move.l  %d3, 40(%a3)\n    move.l  40(%a3), %d4\n"
      case 8 => slowA4(); b ++= "    move.l  (%a4), %d1\n    move.l  %d1, 44(%a3)\n    move.l  44(%a3), %d2\n"
      case 9 => b ++= "    moveq   #0, %d6\n    divu.w  %d6, %d7\n"
      case 10 => b ++= "    trap    #0\n"
      case 11 => val l = L(); b ++= s"    btst    #0, %d1\n    beq.s   $l\n    addq.l  #1, %d3\n$l:\n"
      case 12 => slowA4(); b ++= "    move.l  (%a4), %d1\n    move.l  0x2E(%a0), %d2\n"   // split, unparkable
      case _ => b ++= "    movem.l (%a3), %d2-%d4\n"
    }
    rd(dev(8)) += 1   // the final check
    b ++= f"""    move.l  #0xBAD0F00D, %%d0
      |    cmp.l   #$rmw, 8(%%a0)
      |    bne     _fail
      |    lea     0xFFFF0000, %%a6
      |    move.l  #0xC0FFEE00, %%d0
      |    move.l  %%d0, (%%a6)
      |_halt:
      |    bra     _halt
      |_fail:
      |    lea     0xFFFF0000, %%a6
      |    move.l  %%d0, (%%a6)
      |    bra     _halt
      |_irqh:
      |    addq.l  #1, 0x00090100
      |    rte
      |_div0h:
      |    rte
      |_traph:
      |    rte
      |""".stripMargin
    Prog(b.toString, rd.toMap, wr.toMap, rmw, irq)
  }

  test(s"LS-OoO randomized stress: seeds [$seedStart, ${seedStart + seedCount}) x $nOps ops", VerilatorTest) {
    val failures = mutable.ArrayBuffer.empty[String]
    var tot = Map.empty[String, Long].withDefaultValue(0L)
    for (seed <- seedStart until seedStart + seedCount) {
      val p = gen(seed)
      val arCnt = mutable.Map.empty[Long, Int].withDefaultValue(0)
      val awCnt = mutable.Map.empty[Long, Int].withDefaultValue(0)
      var excEntries = 0L; var lastExc = false
      var p1Enabled = false; var p1Fires = 0L
      val outcome = PortedTestRunner.run(s"lsooo_stress_s$seed", p.src, Timeout,
        cachePosture = CachePosture.ForceCacheableCopyback, liveness = true,
        simSeed = seed + 1,
        onDut = d => {
          val cd = d.clockDomain
          p1Enabled = d.lsEu.p1EarlyLoad
          d.intCtrl.logic.iackAvec #= true
          cd.onSamplings {
            if (p1Enabled && d.lsEu.logic.p1ReqFire.toBoolean) p1Fires += 1
            val ax = d.dcache.logic.axi
            if (ax.ar.valid.toBoolean && ax.ar.ready.toBoolean) arCnt(ax.ar.payload.addr.toLong & 0xffffffffL) += 1
            if (ax.aw.valid.toBoolean && ax.aw.ready.toBoolean) awCnt(ax.aw.payload.addr.toLong & 0xffffffffL) += 1
            val e = !d.rob.logic.excIdle.toBoolean
            if (e && !lastExc) excEntries += 1
            lastExc = e
          }
          if (p.irq) fork {
            var gap = 7
            while (true) {
              cd.waitSampling(gap)
              d.intCtrl.logic.iplIn #= 1
              cd.waitSampling(6)
              d.intCtrl.logic.iplIn #= 0
              gap = if (gap >= 97) 7 else gap + 3
            }
          }
        })
      val mon = PortedTestRunner.lastLiveness
      val devMismatch = exactRegs.flatMap { a =>
        val (er, ew) = (p.reads.getOrElse(a, 0), p.writes.getOrElse(a, 0))
        val (gr, gw) = (arCnt(a), awCnt(a))
        if (er != gr || ew != gw) Some(f"0x$a%08x reads $gr (want $er) writes $gw (want $ew)") else None
      }
      val line = f"[lsooo-stress] seed=$seed irq=${p.irq} outcome=$outcome rmw=${p.rmw} excEntries=$excEntries " +
        s"p1Enabled=$p1Enabled p1Fires=$p1Fires " +
        f"devReads=${exactRegs.map(arCnt).sum} splitReads=${arCnt.filter(_._1 >= Dev + 0x2C).values.sum} " +
        mon.summary
      println(line)
      // Every device-page transaction, so the unparkable split long's bus pattern (not in the
      // exact set) can be compared ACROSS ARMS by address, seed for seed.
      println(f"[lsooo-stress] seed=$seed devAR=" +
        arCnt.filter(_._1 >= Dev).toSeq.sorted.map { case (a, n) => f"${a & 0xfff}%03x:$n" }.mkString(",") +
        " devAW=" + awCnt.filter(_._1 >= Dev).toSeq.sorted.map { case (a, n) => f"${a & 0xfff}%03x:$n" }.mkString(",") +
        f" splitOps=${p.src.split("0x2E\\(%a0\\)").length - 1}")
      tot = tot.updated("olderBehindStuckP4", tot("olderBehindStuckP4") + mon.olderBehindStuckP4)
        .updated("p1Fires", tot("p1Fires") + p1Fires)
        .updated("replays", tot("replays") + mon.replays)
        .updated("replayRedirects", tot("replayRedirects") + mon.replayRedirects)
        .updated("excEntries", tot("excEntries") + excEntries)
        .updated("commits", tot("commits") + mon.commits)
      if (outcome != PortedPass)
        failures += s"seed=$seed $outcome ${PortedTestRunner.lastLivenessFailure.getOrElse("")}"
      else if (devMismatch.nonEmpty)
        failures += s"seed=$seed DEVICE COUNT: ${devMismatch.mkString("; ")}"
      else if (requireP1Fire && p.irq && (!p1Enabled || p1Fires == 0))
        failures += s"seed=$seed IRQ run lacked an accepted P1 pair (enabled=$p1Enabled fires=$p1Fires)"
    }
    println(s"[lsooo-stress] TOTAL seeds=$seedCount ops/seed=$nOps " +
      tot.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(" ") +
      s" failures=${failures.size}")
    failures.foreach(f => println(s"[lsooo-stress] FAIL $f"))
    assert(failures.isEmpty, failures.mkString("\n"))
  }
}
