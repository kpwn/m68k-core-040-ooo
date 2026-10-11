package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._
import m68k040.oracle.ProgramAssembler
import m68k040.sim.AxiMemModel

/** EXCEPTION ENTRY / RTE COST -- cycles per trap on a tight trap loop.
  *
  * Mac OS is an A-line-trap operating system, so the per-trap cost of the exception
  * sequencer (frame push, vector fetch, refetch, RTE pop, refetch) is a first-order
  * term on Finder-like code. This spec measures it directly:
  *
  *   `exc-aline`  `.word 0xA000 ; subq ; bne`, handler = `addq.l #2,2(sp) ; addq.l #1,d0 ; rte`
  *                (the 68040 stacks the A-line opcode's OWN address, so the handler
  *                patches the stacked PC exactly like the Mac OS trap dispatcher --
  *                which also exercises "an ordinary store to the frame, then RTE reads
  *                it back")
  *   `exc-trap`   `trap #0 ; subq ; bne`, handler = `addq.l #1,d0 ; rte`
  *
  * each in the COPYBACK-data posture (`-cb`, match-all DTT, the Mac OS RAM posture) and
  * the MMU-off write-through posture (`-wt`).
  *
  * Self-checking: `d0 == iters` iff every trap reached its handler and every RTE returned
  * to the right place (a wrong stacked PC either skips the `subq` or re-executes the trap).
  *
  * The exact macro count per iteration is NOT assumed (how an excepting instruction and
  * an RTE surface in the commit stream is the harness's business): it is CALIBRATED from
  * two short runs (the index of the final `subq` that writes d7 = 0), and the measured
  * runs then stop exactly at the last `bne`.
  *
  * Breakdown: a per-cycle sampler on `ExceptionUnit.dbgStallExcPack` attributes every
  * sequencer-active cycle to ENTRY (E_*), RTE (R_*) or translate (X_*), so the report
  * says where the cycles of one trap go, not only how many there are. The same sampler
  * cross-checks the step-1 perf-event bits (`ExceptionUnit.perfEvents`): ENTRY and RTE
  * pulses must each equal the trap count, A-line pulses must equal it on the A-line
  * kernel and be zero on the TRAP kernel.
  */
class ExcTrapIpcSpec extends CoreBenchHarness {

  private val VecAline = 0x28L   // vector 10
  private val VecTrap0 = 0x80L   // vector 32

  private def src(kind: String, iters: Int, sp: Long): (String, Int) = {
    val setup = Seq(f"lea 0x$sp%08x,%%sp", "lea .Lah,%a0", f"move.l %%a0,0x$VecAline%x",
      "lea .Lth,%a0", f"move.l %%a0,0x$VecTrap0%x", "moveq #0,%d0", s"move.l #$iters,%d7")
    val trapInsn = if (kind == "aline") ".word 0xA000" else "trap #0"
    val body = Seq(".Lx: " + trapInsn, "subq.l #1,%d7", "bne.s .Lx", ".Lend: bra.s .Lend",
      ".Lah: addq.l #2,2(%sp)", "addq.l #1,%d0", "rte",
      ".Lth: addq.l #1,%d0", "rte")
    ((setup ++ body).mkString(" ; "), setup.size)
  }

  private def d0Of(obs: Seq[m68k040.lockstep.CommitObservation]): Long =
    obs.filter(o => o.archRegValid && o.archRegId == 0).lastOption
      .map(_.archRegWrite & 0xffffffffL).getOrElse(-1L)

  /** Index (0-based) in the commit stream of the `subq` that writes d7 = 0. */
  private def lastSubqIdx(obs: Seq[m68k040.lockstep.CommitObservation]): Int =
    obs.indexWhere(o => o.archRegValid && o.archRegId == 7 && (o.archRegWrite & 0xffffffffL) == 0L)

  final case class Breakdown(entryEvt: Long, alineEvt: Long, intEvt: Long, rteEvt: Long,
                             busy: Long, entryCyc: Long, rteCyc: Long, xlateCyc: Long,
                             perState: Map[String, Long])

  private val stateBits = Seq(7 -> "E_DRAIN", 8 -> "E_STORE", 9 -> "E_STWAIT", 10 -> "E_VECREQ",
    11 -> "E_VECWAIT", 12 -> "E_REDIR", 13 -> "R_DRAIN", 14 -> "R_SRREQ", 15 -> "R_SRWAIT",
    16 -> "R_PCREQ", 17 -> "R_PCWAIT", 18 -> "R_PCREQ2", 19 -> "R_PCWAIT2", 20 -> "R_FMTREQ",
    21 -> "R_FMTWAIT", 22 -> "R_REDIR")

  test("exception entry/RTE: cycles per trap (A-line and TRAP #0, CB and WT)", VerilatorTest) {
    def envOn(n: String) = sys.env.get(n).exists(v => v == "1" || v.equalsIgnoreCase("true"))
    val on = s"long=${envOn("CPU_EXC_FAST_FRAMES")},cb=${envOn("CPU_EXC_FRAMES_COPYBACK")}"
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut(shippingProfile = true, excA7Mirror = true))
    val seed = sys.env.get("IPC_SEED").map(_.toInt).getOrElse(1)

    var bd: Breakdown = null
    def withProbe[T](body: => T): T = {
      var counts = Array.fill(5)(0L); var st = Map.empty[String, Long].withDefaultValue(0L)
      var entryCyc = 0L; var rteCyc = 0L; var xCyc = 0L; var lastKind = 0
      dutProbe = d => fork {
        val cd = d.clockDomain
        val exc = d.rob.logic.exc
        while (true) {
          cd.waitSampling()
          val pe = exc.perfEvents.toInt
          for (b <- 0 until 5) if (((pe >> b) & 1) == 1) counts(b) += 1
          val pack = exc.dbgStallExcPack.toLong
          if ((pack & 1L) == 1L) {
            val hit = stateBits.find { case (b, _) => ((pack >> b) & 1L) == 1L }
            hit match {
              case Some((b, nm)) =>
                st += nm -> (st(nm) + 1)
                if (b <= 12) { entryCyc += 1; lastKind = 1 } else { rteCyc += 1; lastKind = 2 }
              case None => xCyc += 1   // X_REQ/X_WAIT (shared translate stage) or other
            }
          }
        }
      }
      val r = body
      dutProbe = null
      bd = Breakdown(counts(0), counts(1), counts(2), counts(3), counts(4), entryCyc, rteCyc, xCyc, st)
      r
    }

    // sp2 = a frame base that is only 2-aligned (mixed WORD/LONG push, WORD pop).
    val cases = Seq(("aline", true, 0x300000L), ("aline", false, 0x300000L),
      ("trap", true, 0x300000L), ("trap", false, 0x300000L),
      ("aline", true, 0x300002L), ("aline", false, 0x300002L))
    val rows = for ((kind, cb, sp) <- cases) yield {
      val name = s"exc-$kind-${if (cb) "cb" else "wt"}${if ((sp & 3) != 0) "-sp2" else ""}"
      // ── calibration: two over-long runs (the trailing `bra.s .Lend` absorbs the
      // excess), find the final subq in each, derive macros/iteration ──
      def calib(iters: Int): Int = {
        val (s, setupN) = src(kind, iters, sp)
        var idx = -1; var d0 = -1L
        if (sys.env.contains("EXC_TRAP_TRACE") && iters == 8) dutProbe = d => fork {
          val cd = d.clockDomain; val exc = d.rob.logic.exc; val dc = d.dcache.logic
          var cyc = 0; var rtes = 0
          while (rtes < 3) {
            cd.waitSampling(); cyc += 1
            val pack = exc.dbgStallExcPack.toLong
            if ((pack & 1L) == 1L || dc.storePort.valid.toBoolean) {
              val st = stateBits.filter { case (b, _) => ((pack >> b) & 1L) == 1L }.map(_._2).mkString("|")
              val sb = new StringBuilder(f"[exc-trace] c=$cyc%5d st=$st%-10s")
              if (dc.storePort.valid.toBoolean && dc.storePort.ready.toBoolean)
                sb ++= f" DCST pa=0x${dc.storePort.payload.paddr.toLong}%08x d=0x${dc.storePort.payload.data.toLong}%08x sz=${dc.storePort.payload.size.toEnum} cm=${dc.storePort.payload.cacheMode.toEnum} pr=${dc.storePort.payload.precise.toBoolean}"
              if (exc.dcLoadCmd.valid.toBoolean && exc.dcLoadCmd.ready.toBoolean)
                sb ++= f" EXLD pa=0x${exc.dcLoadCmd.payload.paddr.toLong}%08x sz=${exc.dcLoadCmd.payload.size.toEnum}"
              if (exc.dcLoadRsp.valid.toBoolean && ((pack & 1L) == 1L))
                sb ++= f" RSP d=0x${exc.dcLoadRsp.payload.data.toLong}%08x"
              println(sb.toString)
              if (((pack >> 22) & 1L) == 1L) rtes += 1
            }
          }
        }
        val k = Kernel(s"$name-cal$iters", s, setupN + iters * 12 + 8, copybackDtt = cb,
          warmupInstrs = setupN, verifyRetirement = obs => { idx = lastSubqIdx(obs); d0 = d0Of(obs)
            if (sys.env.contains("EXC_TRAP_DUMP")) obs.take(40).zipWithIndex.foreach { case (o, i) =>
              println(f"[exc-dump] $i%3d pc=0x${o.pc}%08x reg=${if (o.archRegValid) o.archRegId.toString else "-"} " +
                f"val=0x${o.archRegWrite & 0xffffffffL}%08x sr=0x${o.sr}%04x a7=0x${o.a7 & 0xffffffffL}%08x") } })
        runKernel(compiled, k, seed); dutProbe = null
        assert(d0 == iters.toLong, s"$name calibration: d0=$d0, expected $iters -- trap/RTE took a wrong path")
        assert(idx > 0, s"$name calibration: never saw d7 reach 0")
        idx
      }
      val i8 = calib(8); val i16 = calib(16)
      assert((i16 - i8) % 8 == 0, s"$name: non-integral macros/iteration ($i8 -> $i16)")
      val perIter = (i16 - i8) / 8
      val iters = 64
      val (s, setupN) = src(kind, iters, sp)
      val idx = i8 + (iters - 8) * perIter
      var d0 = -1L
      // Warm-up = setup + the first 4 iterations (cold I$/D$/BTB), window = the rest.
      val warm = setupN + 4 * perIter
      var dmem: AxiMemModel = null
      val k = Kernel(name, s, idx + 2, copybackDtt = cb, warmupInstrs = warm,
        prepMem = h => { dmem = h.dmem },
        verifyRetirement = obs => { d0 = d0Of(obs) })
      val r = withProbe(runKernel(compiled, k, seed))
      assert(d0 == iters.toLong, s"$name: d0=$d0, expected $iters")
      // Write-through posture: the LAST frame is in memory byte-for-byte. Check it.
      if (!cb) {
        def rb(a: Long) = dmem.mem.read(a) & 0xff
        def rw(a: Long) = (rb(a) << 8) | rb(a + 1)
        val fb = sp - 8
        val sr = rw(fb); val pc = (rw(fb + 2).toLong << 16) | rw(fb + 4); val fmt = rw(fb + 6)
        val load = ProgramAssembler.DefaultLoadAddress
        println(f"[exc-trap]   $name last frame @0x$fb%x: SR=0x$sr%04x PC=0x$pc%08x fmt=0x$fmt%04x")
        assert((sr >> 8) == 0x27, f"$name: stacked SR 0x$sr%04x")
        assert(fmt == (if (kind == "aline") 0x0028 else 0x0080), f"$name: stacked format word 0x$fmt%04x")
        assert(pc >= load && pc < load + 0x100 && (pc & 1) == 0, f"$name: stacked PC 0x$pc%08x")
      }
      val winIters = iters - 4
      val cpt = r.windowCycles.toDouble / winIters
      // Probe counts span the WHOLE run (all 64 traps), so per-trap breakdowns divide by iters.
      assert(bd.entryEvt == iters, s"$name: perfEvents ENTRY=${bd.entryEvt}, expected $iters")
      assert(bd.rteEvt == iters, s"$name: perfEvents RTE=${bd.rteEvt}, expected $iters")
      assert(bd.intEvt == 0, s"$name: perfEvents INT=${bd.intEvt}, expected 0")
      assert(bd.alineEvt == (if (kind == "aline") iters else 0),
        s"$name: perfEvents ALINE=${bd.alineEvt}")
      println(f"[exc-trap] $on $name%-13s seed=$seed macros/iter=$perIter " +
        f"cycles/trap=$cpt%7.2f (window ${r.windowCycles} cyc / $winIters traps) " +
        f"seqBusy/trap=${bd.busy.toDouble / iters}%6.2f entry=${bd.entryCyc.toDouble / iters}%6.2f " +
        f"rte=${bd.rteCyc.toDouble / iters}%6.2f xlate=${bd.xlateCyc.toDouble / iters}%5.2f")
      println(s"[exc-trap]   $name states/trap: " + stateBits.map(_._2).filter(bd.perState.contains)
        .map(n => f"$n=${bd.perState(n).toDouble / iters}%.2f").mkString(" "))
      name -> cpt
    }
    println()
    println(s"[exc-trap] SUMMARY $on seed=$seed " + rows.map { case (n, c) => f"$n=$c%.2f" }.mkString(" "))
  }
}
