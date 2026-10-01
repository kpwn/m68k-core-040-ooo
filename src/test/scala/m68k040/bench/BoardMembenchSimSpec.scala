package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import m68k040.top.ShippingCoreConfig

/** The SIM half of the board memory benchmark (`macqd700-soc-public`
  * `tools/board_membench.sh`, kernels in `tools/membench/membench.S`).
  *
  * Same loop bodies as the board program, so a silicon number and a sim number
  * describe the same instruction stream:
  *   copy  : `move.l (%a0)+,(%a1)+` x4 ; subq.l ; bne.s   (= `kMemcpy`)
  *   m16   : `move16 (%a0)+,(%a1)+`  ; subq.l ; bne.s    (= `kMemcpyMove16`)
  *   read  : `add.l (%a0)+,%d5` x4    ; subq.l ; bne.s
  *   fill  : `move.l %d2,(%a1)+` x4   ; subq.l ; bne.s
  *   chase : `movea.l (%a0),%a0` x4   ; subq.l ; bne.s   over a random single-cycle
  *           ring with one node per 64-byte line (Sattolo), i.e. one L2 line per hop
  * All with copyback translation (the board's DTT0 = 0x003FC020); pass 1 / lap 1 is
  * warm-up and excluded, exactly like the board's untimed warm-up pass.
  *
  * The board program also reads the VIA timer every ~256 KB, which this does not;
  * that is < 0.1% of a chunk and is ignored.
  *
  * USE: this is a CALIBRATION instrument. The silicon chase gives cycles/hop at L1,
  * L2 and DDR; run this at `IPC_MEM=l2:<hit>:<dram>:<sets>` and fit `hit`/`dram`
  * until the chase matches, then compare the streaming kernels (which the fitted
  * model was NOT fitted to) -- that residual is how wrong the model is about
  * bandwidth, e.g. its write side, which has NO dirty-line traffic
  * (`AxiMemModel.scala:424`).
  *
  * Env: MB_SIZES (bytes, comma list, default 2048,65536), MB_KINDS (default all),
  * MB_PASSES (default 3), MB_LAPS (chase laps, default 3), IPC_SEED.
  * P1 flags follow `ShippingCoreConfig` (the shipping default); fill-forward follows
  * `CPU_DCACHE_FILL_FORWARD` through `FullCoreDut`'s default.
  */
class BoardMembenchSimSpec extends CoreBenchHarness {

  private val Base = 0x00500000L
  private val Dst  = 0x00600000L

  private def poke32(h: MemHandles, a: Long, v: Long): Unit = {
    h.dmem.pokeByte(a + 0, ((v >> 24) & 0xff).toInt)
    h.dmem.pokeByte(a + 1, ((v >> 16) & 0xff).toInt)
    h.dmem.pokeByte(a + 2, ((v >> 8) & 0xff).toInt)
    h.dmem.pokeByte(a + 3, (v & 0xff).toInt)
  }

  private def lastOf(obs: Seq[m68k040.lockstep.CommitObservation], reg: Int, what: String): Long = {
    val w = obs.filter(o => o.archRegValid && o.archRegId == reg)
    assert(w.nonEmpty, s"$what: d$reg was never written")
    w.last.archRegWrite & 0xffffffffL
  }

  def kRead(bytes: Int, passes: Int): Kernel = {
    val iters = bytes / 16
    val seed = 0x5A5A0000L
    val prep: MemHandles => Unit = h => { var a = 0; while (a < bytes) { poke32(h, Base + a, (Base + a) ^ seed); a += 4 } }
    val sum = (0 until bytes by 4).map(a => (Base + a) ^ seed).sum
    val want = (sum * (passes - 0)) & 0xffffffffL
    val setup = Seq(f"lea 0x$Base%x,%%a2", s"move.l #$passes,%d6", "moveq #0,%d5")
    val outer = Seq("movea.l %a2,%a0", s"move.l #$iters,%d7")
    val body  = Seq("add.l (%a0)+,%d5", "add.l (%a0)+,%d5", "add.l (%a0)+,%d5", "add.l (%a0)+,%d5",
                    "subq.l #1,%d7", "bne.s .Lrd")
    val tail  = Seq("subq.l #1,%d6", "bne.s .Louter")
    val src = (setup ++ Seq(".Louter: " + outer.mkString(" ; "), ".Lrd: " + body.mkString(" ; "),
               tail.mkString(" ; "), ".Lend: bra.s .Lend")).mkString(" ; ")
    val perPass = outer.size + iters * body.size + tail.size
    Kernel(s"mb-read-${bytes / 1024}k", src, setup.size + passes * perPass,
      copybackDtt = true, zeroFillData = true, prepMem = prep, warmupInstrs = setup.size + perPass,
      verifyRetirement = obs => {
        val got = lastOf(obs, 5, "read")
        assert(got == want, f"read: sum 0x$got%x != 0x$want%x")
      })
  }

  def kFill(bytes: Int, passes: Int): Kernel = {
    val iters = bytes / 16
    val setup = Seq(f"lea 0x$Dst%x,%%a3", s"move.l #$passes,%d6", "moveq #0,%d2")
    val outer = Seq("movea.l %a3,%a1", s"move.l #$iters,%d7", "addq.l #1,%d2")
    val body  = Seq("move.l %d2,(%a1)+", "move.l %d2,(%a1)+", "move.l %d2,(%a1)+", "move.l %d2,(%a1)+",
                    "subq.l #1,%d7", "bne.s .Lfl")
    val tail  = Seq("subq.l #1,%d6", "bne.s .Louter")
    val epi   = Seq(f"move.l 0x${Dst + bytes - 4}%x,%%d0")
    val src = (setup ++ Seq(".Louter: " + outer.mkString(" ; "), ".Lfl: " + body.mkString(" ; "),
               tail.mkString(" ; ")) ++ epi ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    val perPass = outer.size + iters * body.size + tail.size
    Kernel(s"mb-fill-${bytes / 1024}k", src, setup.size + passes * perPass + epi.size,
      copybackDtt = true, zeroFillData = true, warmupInstrs = setup.size + perPass,
      verifyRetirement = obs => {
        val got = lastOf(obs, 0, "fill")
        assert(got == passes.toLong, s"fill: last long $got != $passes")
      })
  }

  /** Random single-cycle ring, one 4-byte pointer at the start of every 64-byte line. */
  def kChaseRing(bytes: Int, laps: Int, viaDn: Boolean = false): Kernel = {
    val nodes = bytes / 64
    require(nodes % 4 == 0 && nodes >= 4)
    val rng = new scala.util.Random(0x1234567 + bytes)
    val p = Array.tabulate(nodes)(i => i)
    var i = nodes - 1
    while (i > 0) { val j = rng.nextInt(i); val t = p(i); p(i) = p(j); p(j) = t; i -= 1 }  // Sattolo
    val prep: MemHandles => Unit = h => for (k <- 0 until nodes) poke32(h, Base + 64L * k, Base + 64L * p(k))
    val iters = laps * nodes / 4
    val setup = Seq(f"lea 0x$Base%x,%%a0", s"move.l #$iters,%d7")
    val loads = if (viaDn) Seq.fill(4)(Seq("move.l (%a0),%d0", "movea.l %d0,%a0")).flatten
                else Seq.fill(4)("movea.l (%a0),%a0")
    val body  = loads ++ Seq("subq.l #1,%d7", "bne.s .Lch")
    val epi   = Seq("move.l %a0,%d0")
    val src = (setup ++ Seq(".Lch: " + body.mkString(" ; ")) ++ epi ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    val kind = if (viaDn) "chase-dn" else "chase"
    Kernel(s"mb-$kind-${bytes / 1024}k", src, setup.size + iters * body.size + epi.size,
      zeroFillData = true, copybackDtt = true, prepMem = prep,
      warmupInstrs = setup.size + (nodes / 4) * body.size,
      verifyRetirement = obs => {
        val got = lastOf(obs, 0, "chase")
        assert(got == Base, f"chase: final pointer 0x$got%x != 0x$Base%x (ring broken)")
      })
  }

  test("board membench kernels, sim side (calibration)", VerilatorTest) {
    // MB_PLAN = "<mem>|<sizes>|<kinds>;..."  e.g. "l2:5:60:4096|2048,65536|copy,chase;l2:5:40:64|131072|chase"
    // (<mem> as IPC_MEM; empty plan = one entry from IPC_MEM / MB_SIZES / MB_KINDS).
    // MB_FF = comma list of fill-forward arms to compile, default "0".
    val passes = sys.env.get("MB_PASSES").map(_.toInt).getOrElse(3)
    val laps   = sys.env.get("MB_LAPS").map(_.toInt).getOrElse(3)
    val plan: Seq[(Option[String], Seq[Int], Seq[String])] = sys.env.get("MB_PLAN").filter(_.nonEmpty) match {
      case Some(p) => p.split(';').toSeq.map { e =>
        val f = e.split('|')
        (Some(f(0)), f(1).split(',').map(_.trim.toInt).toSeq,
         (if (f.length > 2) f(2) else "copy,m16,read,fill,chase").split(',').map(_.trim).toSeq) }
      case None => Seq((None,
        sys.env.getOrElse("MB_SIZES", "2048,65536").split(',').map(_.trim.toInt).toSeq,
        sys.env.getOrElse("MB_KINDS", "copy,m16,read,fill,chase").split(',').map(_.trim).toSeq))
    }
    val arms = sys.env.getOrElse("MB_FF", "0").split(',').map(_.trim == "1").toSeq
    val specWake = sys.env.get("MB_SPEC_WAKE").contains("1")
    val lsOoo = sys.env.get("MB_LS_OOO").contains("1")
    val fuseLongMoveLoads = sys.env.get("MB_FUSE_LONG_MOVE_LOADS").contains("1")
    val directRefill = sys.env.get("MB_DIRECT_REFILL").contains("1")
    for (ff <- arms) {
      val dut = M68kSim().withVerilator.compile(new FullCoreDut(
        alignedLoadFallThrough = true, earlyLsIntWakeup = true,
        specLoadWakeup = specWake,
        fuseLongMoveLoads = fuseLongMoveLoads,
        loadBypassUnreadyLoad = lsOoo,
        earlyStoreAddress = true, trainSlot1Conditional = true,
        deferTakenSlot1Conditional = true, retainRedirectHistory = true,
        dcacheFillForward = ff,
        dcacheDirectRefillResponse = directRefill,
        rasBranchRepair = ShippingCoreConfig.rasBranchRepair,
        computeDirectTargets = ShippingCoreConfig.computeDirectTargets,
        deferSlot1Uncond = ShippingCoreConfig.deferSlot1Uncond,
        deferSlot1Dbcc = ShippingCoreConfig.deferSlot1Dbcc))
      println(s"MB_SIM_CONFIG fillForward=$ff directRefill=$directRefill lsOoo=$lsOoo fuseLongMoveLoads=$fuseLongMoveLoads p3FastLoad=${ShippingCoreConfig.lsP3FastLoad} p1EarlyLoad=${ShippingCoreConfig.lsP1EarlyLoad} earlyProbeLineForward=${ShippingCoreConfig.dcacheEarlyProbeLineForward} nbEarlyResponse=${ShippingCoreConfig.dcacheNbEarlyResponse} nbEagerAr=${ShippingCoreConfig.dcacheNbEagerAr} loadRingDepth=${ShippingCoreConfig.lsLoadRingDepth} specLoadWakeup=$specWake rasBranchRepair=${ShippingCoreConfig.rasBranchRepair} " +
              s"computeDirectTargets=${ShippingCoreConfig.computeDirectTargets}")
      for ((mem, sizes, kinds) <- plan; sz <- sizes; kd <- kinds) {
        memCfgOverride = mem.map(parseMemSpec)
        val k = kd match {
          case "copy"  => kMemcpy(bytes = sz, passes = passes, label = s"mb-copy-${sz / 1024}k")
          case "m16"   => kMemcpyMove16(sz, passes, s"mb-m16-${sz / 1024}k")
          case "read"  => kRead(sz, passes)
          case "fill"  => kFill(sz, passes)
          case "chase" => kChaseRing(sz, laps)
          case "chase-dn" => kChaseRing(sz, laps, viaDn = true)
        }
        val r = runKernel(dut, k, IpcBenchSpec.simSeed)
        val tag = s"ff=${if (ff) 1 else 0} mem=${mem.getOrElse(sys.env.getOrElse("IPC_MEM", "zero"))}"
        if (kd == "chase" || kd == "chase-dn") {
          val hops = (laps - 1).toLong * (sz / 64)
          println(f"MB_SIM $tag kind=$kd size=$sz cycles=${r.windowCycles} hops=$hops " +
                  f"cyc/hop=${r.windowCycles.toDouble / hops}%.3f")
        } else {
          val bytes = sz.toLong * (passes - 1)
          println(f"MB_SIM $tag kind=$kd size=$sz cycles=${r.windowCycles} bytes=$bytes " +
                  f"B/cyc=${bytes.toDouble / r.windowCycles}%.4f cyc/16B=${r.windowCycles * 16.0 / bytes}%.3f " +
                  f"IPC=${r.ipc}%.4f")
        }
      }
      memCfgOverride = None
    }
  }
}
