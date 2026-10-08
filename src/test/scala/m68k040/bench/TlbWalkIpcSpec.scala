package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import m68k040.sim.AxiMemModel
import spinal.core.sim._

/** MMU-ON, TLB-MISS-HEAVY kernels for the TLB-miss cost chain (Fable review §2.3/§4.3).
  *
  * Every kernel in `IpcBenchSpec` runs with the MMU off or behind a match-all TTR, so the
  * table walker never runs there and the three knobs below CANNOT move those numbers.
  * These kernels exist to give the knobs something to act on:
  *
  *   * `tlbwp-*`   WRONG-PATH WALKS. A Galois-LFSR branch (~50% mispredicted) guards a
  *                 load to one of 16 pages that thrash ONE 4-way DTLB set, so the wrong
  *                 path routinely starts a 3-level walk that the flush kills; the
  *                 correct path's own load is to a resident page in the other bank.
  *                 `CPU_DTLB_HIT_UNDER_WALK` / `CPU_WALK_ABORT_ON_FLUSH` target this.
  *   * `tlbstream-*` DTLB walks interleaved with a stream of independent L1-hit loads
  *                 (LS-OoO lets the hits run ahead of the missing load and compete with
  *                 the walker for the D-cache load port) -- `CPU_WALK_AGE_LIMIT`.
  *   * `itlbstream-*` I-SIDE walks: 16 code blocks 32 KiB apart (one 4-way ITLB set)
  *                 each issuing a burst of independent loads, so the ITLB walker's
  *                 descriptor reads compete with the backend's load stream while fetch
  *                 is stopped -- the starvation case `walkerAgeLimit` bounds.
  *
  * Run with the shipping env (`IPC_SHIPPING=1` + `gate/combo.env`); every knob comes in
  * through the constructor defaults, i.e. the environment. Prints cycles per kernel and
  * the walker counters; `TLB_ONLY=a,b` filters kernels. */
class TlbWalkIpcSpec extends CoreBenchHarness {

  val DataBase = 0x00400000L
  val HotPage  = 0x00401000L        // vpn[0] = 1: the other DTLB bank from every cold page
  val MmuRoot  = 0x00080000L
  val PtrBase  = 0x00081000L
  val LeafBase = 0x00090000L
  val ColdStride = 0x8010L          // 32 KiB (constant vpn[2:0]) + 16 B (rotate L1D set)
  val ColdMask   = 0x78FF0L         // 16 pages, in-page offset bits [11:4] only

  private def pokeDescBE(mem: AxiMemModel, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  /** Identity-map every page in `vas` (4 KiB pages, long-format 3-level tables). One
    * pointer table per root slot and one leaf table per (root, ptr) slot, allocated
    * 0x100 apart (their true size) so table bases do not alias in the L1D. */
  def buildTables(mem: AxiMemModel, vas: Seq[Long]): Unit = {
    val ptrFor  = scala.collection.mutable.LinkedHashMap.empty[Int, Long]
    val leafFor = scala.collection.mutable.LinkedHashMap.empty[(Int, Int), Long]
    for (va <- vas.distinct) {
      val r = ((va >> 25) & 0x7f).toInt
      val p = ((va >> 18) & 0x7f).toInt
      val g = ((va >> 12) & 0x3f).toInt
      val pt = ptrFor.getOrElseUpdate(r, PtrBase + ptrFor.size * 0x200L)
      val lf = leafFor.getOrElseUpdate((r, p), LeafBase + leafFor.size * 0x100L)
      pokeDescBE(mem, MmuRoot + r * 4, (pt & 0xfffffff0L) | 0x3L)
      pokeDescBE(mem, pt + p * 4, (lf & 0xfffffff0L) | 0x3L)
      pokeDescBE(mem, lf + g * 4, (va & 0xfffff000L) | 0x1L)
    }
  }

  def coldPages: Seq[Long] = (0 until 16).map(i => DataBase + i * 0x8000L)

  /** D-side walks, I-side match-all TTR (the code never walks). */
  def mmuWalkD = MmuSetup(enable = true, urp = MmuRoot, srp = MmuRoot, itt0 = 0x00FFC000L, dtt0 = 0L)
  /** I-side walks, D-side match-all TTR (data never walks). */
  def mmuWalkI = MmuSetup(enable = true, urp = MmuRoot, srp = MmuRoot, itt0 = 0L, dtt0 = 0x00FFC000L)

  def stopAt(total: Int): Int = total - 8

  /** Wrong-path walk kernel. `iters` LFSR steps. */
  def kWrongPath(iters: Int, seed: Long = 0x1234567L): Kernel = {
    val taps = 0x80200003L
    val setup = Seq(f"lea 0x$DataBase%08x,%%a1", f"lea 0x$HotPage%08x,%%a0",
      f"move.l #0x$seed%x,%%d3", f"move.l #0x$taps%x,%%d4", f"move.l #0x$ColdStride%x,%%d5",
      f"move.l #0x$ColdMask%x,%%d0", "moveq #0,%d6", s"move.w #${iters - 1},%d7")
    val body = Seq(
      ".Lloop: move.l (%a0),%d2",        // resident hot load: delays the branch's retirement
      "lsr.l #1,%d3",
      "bcc.s .Lskip",                    // ~50% mispredicted
      "eor.l %d4,%d3",
      "add.l %d5,%d6",
      "and.l %d0,%d6",
      "move.l 0(%a1,%d6.l),%d1",         // cold page: a DTLB miss -> 3-level walk
      ".Lskip: dbra %d7,.Lloop",
      ".Lend: bra.s .Lend")
    var s = seed; var total = setup.size
    for (_ <- 0 until iters) {
      val carry = (s & 1L) != 0; s = s >>> 1
      total += 4                         // hot load, lsr, bcc, dbra
      if (carry) { s ^= taps; total += 4 }
    }
    Kernel(s"tlbwp-$iters", (setup ++ body).mkString(" ; "), stopAt(total),
      mmu = Some(mmuWalkD), zeroFillData = true,
      prepMem = h => buildTables(h.dWalkMem, coldPages :+ HotPage))
  }

  /** DTLB walk + independent L1-hit load stream. */
  def kWalkStream(iters: Int, hits: Int = 8): Kernel = {
    val setup = Seq(f"lea 0x$DataBase%08x,%%a1", f"lea 0x$HotPage%08x,%%a0",
      f"move.l #0x$ColdStride%x,%%d5", f"move.l #0x$ColdMask%x,%%d0", "moveq #0,%d6",
      s"move.w #${iters - 1},%d7")
    val hitLoads = (0 until hits).map(i => s"move.l ${(i % 4) * 4}(%a0),%d${1 + (i % 2)}")
    val body = Seq(".Lloop: add.l %d5,%d6", "and.l %d0,%d6", "move.l 0(%a1,%d6.l),%d3") ++
      hitLoads ++ Seq("dbra %d7,.Lloop", ".Lend: bra.s .Lend")
    val total = setup.size + iters * (3 + hits + 1)
    Kernel(s"tlbstream-$iters", (setup ++ body).mkString(" ; "), stopAt(total),
      mmu = Some(mmuWalkD), zeroFillData = true,
      prepMem = h => buildTables(h.dWalkMem, coldPages :+ HotPage))
  }

  /** I-side walks: `blocks` code blocks 32 KiB apart, visited round-robin `rounds`
    * times; each block issues `hits` independent loads then jumps on. */
  def kItlbStream(rounds: Int, blocks: Int = 16, hits: Int = 8): Kernel = {
    val code = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val setup = Seq(f"lea 0x$HotPage%08x,%%a0", s"move.w #${rounds - 1},%d7")
    val hitLoads = (0 until hits).map(i => s"move.l ${(i % 4) * 4}(%a0),%d${1 + (i % 2)}")
    val sb = Seq.newBuilder[String]
    sb ++= setup
    sb += "bra.l .Lb0"
    for (b <- 0 until blocks) {
      sb += s".balign 0x8000"
      sb += s".Lb$b: nop"
      sb ++= hitLoads
      if (b < blocks - 1) sb += s"bra.l .Lb${b + 1}"
      else { sb += "dbra %d7,.Lb0"; sb += ".Lend: bra.s .Lend" }
    }
    val perRound = blocks * (1 + hits + 1)
    val total = setup.size + 1 + rounds * perRound
    // Code pages (8 KiB is enough per block) + the hot data page (behind the D TTR,
    // so it needs no table entry). The first 32 KiB holds setup + block 0.
    val codePages = (0 to blocks).map(b => code + b * 0x8000L)
    Kernel(s"itlbstream-$rounds", sb.result().mkString(" ; "), stopAt(total),
      mmu = Some(mmuWalkI), zeroFillData = true,
      prepMem = h => buildTables(h.iWalkMem, codePages))
  }

  test("TLB-miss cost chain: MMU-on walk kernels", VerilatorTest) {
    val all = Seq(kWrongPath(400), kWrongPath(800), kWalkStream(200), kWalkStream(400),
      kItlbStream(12), kItlbStream(24))
    val kernels = sys.env.get("TLB_ONLY") match {
      case Some(sel) => val n = sel.split(',').map(_.trim).toSet; all.filter(k => n.exists(k.name.startsWith))
      case None => all
    }
    val shipping = sys.env.get("IPC_SHIPPING").contains("1")
    val compiled = M68kSim().withVerilator.compile(
      if (shipping) new FullCoreDut(shippingProfile = true) else new FullCoreDut)
    println(s"  TLBWALK config: walkAgeLimit=${m68k040.top.ShippingCoreConfig.walkAgeLimit} " +
      s"dtlbHitUnderWalk=${m68k040.top.ShippingCoreConfig.dtlbHitUnderWalk} " +
      s"walkAbortOnFlush=${m68k040.top.ShippingCoreConfig.walkAbortOnFlush} shipping=$shipping")
    for (k <- kernels) {
      var dWalks = 0; var iWalks = 0; var dWalkBusy = 0; var iWalkBusy = 0; var dRead = 0
      var orderHold = 0; var orderHoldEv = 0   // CPU_WALK_SQ_CHECK: withheld descriptor reads
      dutProbe = { dut =>
        val cd = dut.clockDomain
        var prevD = false; var prevI = false; var prevH = false
        val holdTap = Option(dut.dtlb.logic.dbgWalkOrderHold)
        cd.onSamplings {
          holdTap.foreach { t => val h = t.toBoolean
            if (h) orderHold += 1
            if (h && !prevH) orderHoldEv += 1
            prevH = h }
          val db = dut.dtlb.logic.walker.io.busy.toBoolean
          val ib = dut.itlb.logic.walker.io.busy.toBoolean
          if (db) dWalkBusy += 1
          if (ib) iWalkBusy += 1
          if (db && !prevD) dWalks += 1
          if (ib && !prevI) iWalks += 1
          prevD = db; prevI = ib
        }
      }
      val r = runKernel(compiled, k)
      dutProbe = null
      println(f"  TLBWALK ${k.name}%-16s cycles=${r.windowCycles}%7d retired=${r.retiredInstrs}%6d " +
        f"ipc=${r.retiredInstrs.toDouble / r.windowCycles}%.4f dWalks=$dWalks%5d dWalkBusy=$dWalkBusy%7d " +
        f"iWalks=$iWalks%5d iWalkBusy=$iWalkBusy%7d walkOrderHold=$orderHoldEv%d/${orderHold}cyc")
    }
  }
}
