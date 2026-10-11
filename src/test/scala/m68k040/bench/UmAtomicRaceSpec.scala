package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import m68k040.sim.AxiMemModel
import spinal.core.sim._

/** `CPU_UM_ATOMIC` directed reproducer: the walker's deferred U/M descriptor update must
  * never overwrite (or OR into) a descriptor that a program-YOUNGER store has rewritten.
  *
  * On a 68040 the U/M update is a locked read-modify-write inside the access's own table
  * search, so it is ordered BEFORE every younger store. Here it is queued, drained after
  * the owner commits, and performed as a re-read + merged byte store on the walker port,
  * concurrently with the store queue's own drain. Two failure shapes follow:
  *   (a) STALE WRITE-BACK: the younger store drains between the drain's re-read and its
  *       merged store -> the old VALID descriptor | U is written back (page re-mapped).
  *   (b) LATE OR: the younger store drains before the drain's re-read -> U (and M) are
  *       ORed into the software's (invalid, user-defined) descriptor.
  * Both make the descriptor differ from what software stored.
  *
  * Kernel: N victim pages, each touched once (a DTLB miss -> 3-level walk -> queued U or
  * U|M), then `gap` independent ALU ops, then an ordinary store of an INVALID descriptor
  * pattern to that victim's page descriptor. After a delay loop for every drain to land,
  * the program reads every descriptor back and ORs (value ^ pattern) into d0: d0 must be
  * 0. The page-table page itself is mapped write-through or copyback (two variants).
  * Runs with the shipping env (`IPC_SHIPPING=1` + gate/combo.env). */
class UmAtomicRaceSpec extends CoreBenchHarness {
  val MmuRoot  = 0x00080000L
  val PtrBase  = 0x00081000L
  val Leaf0    = 0x00090000L    // leaf table for (root 0, ptr 2): maps the table page
  val Leaf16   = 0x00090100L    // leaf table for (root 0, ptr 16): the victim pages
  val VictimBase = 0x00400000L
  val Pattern  = 0xA5A5A5A4L    // DT = 00 (invalid); U = M = 0; user bits elsewhere
  val NVictims = 48

  private def pokeDescBE(mem: AxiMemModel, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  private def peekDescBE(mem: AxiMemModel, addr: Long): Long =
    (0 until 4).foldLeft(0L)((acc, i) => (acc << 8) | mem.peekByte(addr + i).toLong)

  def buildTables(mem: AxiMemModel, tableCm: Int): Unit = {
    pokeDescBE(mem, MmuRoot, (PtrBase & 0xfffffff0L) | 0x3L)
    pokeDescBE(mem, PtrBase + 2 * 4, (Leaf0 & 0xfffffff0L) | 0x3L)   // VA[24:18] of 0x90000 = 2
    pokeDescBE(mem, PtrBase + 16 * 4, (Leaf16 & 0xfffffff0L) | 0x3L)
    // identity-map the table page 0x90000 (g = 0x10 under ptr 2) with the variant's CM
    pokeDescBE(mem, Leaf0 + 0x10 * 4, 0x00090000L | (tableCm.toLong << 5) | 0x1L)
    for (i <- 0 until NVictims)
      pokeDescBE(mem, Leaf16 + 4 * i, (VictimBase + i * 0x1000L) | 0x1L)
  }

  def gapOf(i: Int): Int = Seq(0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40, 60)(i % 16)

  def kRace(write: Boolean, tableCm: Int): (Kernel, () => AxiMemModel) = {
    var memRef: AxiMemModel = null
    val setup = Seq(f"move.l #0x$Pattern%08x,%%d4", "moveq #0,%d0", "moveq #0,%d5",
      f"tst.l 0x$Leaf16%08x")             // pull the table page's translation in first
    val body = (0 until NVictims).flatMap { i =>
      val va = VictimBase + i * 0x1000L
      val access = if (write) f"move.l %%d5,0x$va%08x" else f"move.l 0x$va%08x,%%d1"
      Seq(access) ++ Seq.fill(gapOf(i))("addq.l #1,%d6") ++
        Seq(f"move.l %%d4,0x${Leaf16 + 4 * i}%08x")
    }
    val delayIters = 400
    val delay = Seq(s"move.w #${delayIters - 1},%d7", ".Ldly: dbra %d7,.Ldly")
    val check = (0 until NVictims).flatMap { i =>
      Seq(f"move.l 0x${Leaf16 + 4 * i}%08x,%%d1", "eor.l %d4,%d1", "or.l %d1,%d0")
    }
    val src = (setup ++ body ++ delay ++ check ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    val total = setup.size + body.size + 1 + delayIters + check.size
    val name = s"umrace-${if (write) "st" else "ld"}-${if (tableCm == 1) "cb" else "wt"}"
    val k = Kernel(name, src, total,
      mmu = Some(MmuSetup(enable = true, urp = MmuRoot, srp = MmuRoot, itt0 = 0x00FFC000L, dtt0 = 0L)),
      zeroFillData = true, minCycleCap = 400000,
      prepMem = h => { memRef = h.dWalkMem; buildTables(h.dWalkMem, tableCm) },
      verifyRetirement = obs => {
        val w = obs.filter(o => o.archRegValid && o.archRegId == 0)
        lastD0 = if (w.isEmpty) -1L else w.last.archRegWrite & 0xffffffffL
      })
    (k, () => memRef)
  }
  @volatile var lastD0: Long = -1L

  /** ITLB-vs-DTLB on ONE descriptor: `nBlocks` code blocks 32 KiB apart (each a fresh
    * I-side page -> ITLB walk -> queued U), each storing to a word inside its OWN page
    * (-> DTLB walk -> queued U|M on the same descriptor). The two drains' RMWs race;
    * an interleaved pair loses one update. Check: every block page's descriptor must
    * end with U AND M set; d0 = OR of (~desc & 0x18). */
  def kCross(nBlocks: Int = 24): (Kernel, () => AxiMemModel) = {
    var memRef: AxiMemModel = null
    val code = m68k040.oracle.ProgramAssembler.DefaultLoadAddress
    val pages = (0 to nBlocks).map(b => code + b * 0x8000L)
    val leafFor = scala.collection.mutable.LinkedHashMap.empty[(Int, Int), Long]
    val ptrFor  = scala.collection.mutable.LinkedHashMap.empty[Int, Long]
    def descAddr(va: Long): Long = {
      val r = ((va >> 25) & 0x7f).toInt; val p = ((va >> 18) & 0x7f).toInt
      val g = ((va >> 12) & 0x3f).toInt
      ptrFor.getOrElseUpdate(r, PtrBase + ptrFor.size * 0x200L)
      leafFor.getOrElseUpdate((r, p), Leaf0 + leafFor.size * 0x100L) + g * 4
    }
    val tableVa = 0x00090000L
    val allVas = tableVa +: pages
    val dAddr = allVas.map(va => va -> descAddr(va)).toMap
    require(leafFor.size <= 16)
    val gaps = Seq(0, 1, 2, 3, 4, 6, 8, 12)
    val sb = Seq.newBuilder[String]
    var total = 0
    def emit(i: String*): Unit = { sb ++= i; total += i.size }
    emit("moveq #0,%d0", "moveq #0,%d5", "bra.l .Lb0")
    val delayIters = 400
    for (b <- 0 until nBlocks) {
      sb += ".balign 0x8000"; sb += s".Lb$b:"
      emit(Seq.fill(gaps(b % gaps.size))("nop"): _*)
      emit(s"move.l %d5,.Ld$b")
      if (b < nBlocks - 1) emit(s"bra.l .Lb${b + 1}")
      else {
        emit(s"move.w #${delayIters - 1},%d7")
        sb += ".Ldly: dbra %d7,.Ldly"; total += delayIters
        for (bb <- 0 until nBlocks) {
          val da = dAddr(code + (bb + 1) * 0x8000L)
          emit(f"move.l 0x$da%08x,%%d1", "not.l %d1", "andi.l #0x18,%d1", "or.l %d1,%d0")
        }
        sb += ".Lend: bra.s .Lend"
      }
      sb += s".Ld$b: .long 0"
    }
    val k = Kernel(s"umrace-cross-$nBlocks", sb.result().mkString(" ; "), total,
      mmu = Some(MmuSetup(enable = true, urp = MmuRoot, srp = MmuRoot, itt0 = 0L, dtt0 = 0L)),
      zeroFillData = true, minCycleCap = 400000,
      prepMem = h => {
        memRef = h.dWalkMem
        for ((r, pt) <- ptrFor) pokeDescBE(h.dWalkMem, MmuRoot + r * 4, (pt & 0xfffffff0L) | 0x3L)
        for (((r, p), lf) <- leafFor) pokeDescBE(h.dWalkMem, ptrFor(r) + p * 4, (lf & 0xfffffff0L) | 0x3L)
        for (va <- allVas) pokeDescBE(h.dWalkMem, dAddr(va), (va & 0xfffff000L) | 0x1L)
      },
      verifyRetirement = obs => {
        val w = obs.filter(o => o.archRegValid && o.archRegId == 0)
        lastD0 = if (w.isEmpty) -1L else w.last.archRegWrite & 0xffffffffL
      })
    (k, () => memRef)
  }

  test("CPU_UM_ATOMIC: a deferred U/M update never clobbers a younger descriptor store", VerilatorTest) {
    val shipping = sys.env.get("IPC_SHIPPING").contains("1")
    val compiled = M68kSim().withVerilator.compile(
      if (shipping) new FullCoreDut(shippingProfile = true) else new FullCoreDut)
    println(s"  UMRACE config: umAtomic=${m68k040.top.ShippingCoreConfig.umAtomic} " +
      s"walkSqCheck=${m68k040.top.ShippingCoreConfig.walkSqCheck} shipping=$shipping")
    val seeds = sys.env.get("UMRACE_SEEDS").map(_.split(',').map(_.trim.toInt).toSeq).getOrElse(Seq(1, 2, 3))
    val fails = scala.collection.mutable.ArrayBuffer.empty[String]
    val only = sys.env.getOrElse("UMRACE_ONLY", "race,cross")
    if (only.contains("race")) for (write <- Seq(false, true); cm <- Seq(0, 1); seed <- seeds) {
      val (k, mem) = kRace(write, cm)
      var drains = 0; var drops = 0
      dutProbe = { dut =>
        val cd = dut.clockDomain
        val ack = dut.dtlb.logic.umq.io.drainAck
        cd.onSamplings { if (ack.toBoolean) drains += 1 }
        Option(dut.dtlb.logic.dbgUmDrop).foreach(t => cd.onSamplings { if (t.toBoolean) drops += 1 })
        if (sys.env.contains("UMRACE_TRACE")) {
          val w = dut.dtlb.logic.walker.io
          var cyc = 0
          cd.onSamplings {
            cyc += 1
            if (cyc < 600) {
              if (w.loadCmd.valid.toBoolean && w.loadCmd.ready.toBoolean)
                println(f"  TR $cyc dtlb rd 0x${w.loadCmd.payload.paddr.toLong}%08x")
              if (w.loadRsp.valid.toBoolean)
                println(f"  TR $cyc dtlb rsp 0x${w.loadRsp.payload.data.toLong}%08x fault=${w.loadRsp.payload.fault.toBoolean}")
              if (w.done.toBoolean)
                println(f"  TR $cyc dtlb done fault=${w.rsp.fault.toBoolean} reason=${w.rsp.faultReason.toEnum}")
            }
          }
        }
      }
      lastD0 = -1L
      val r = runKernel(compiled, k, seed)
      dutProbe = null
      // Classify from backing memory (exact for the write-through table page; the
      // copyback variant's truth may still be in the L1D, so d0 is the verdict there).
      val m = mem()
      val bad = (0 until NVictims).flatMap { i =>
        val v = peekDescBE(m, Leaf16 + 4 * i)
        if (v == Pattern) None
        else if ((v & 0x3L) != 0) Some(f"#$i%d(gap ${gapOf(i)}%d)=0x$v%08x STALE-VALID")
        else Some(f"#$i%d(gap ${gapOf(i)}%d)=0x$v%08x OR-INTO-INVALID")
      }
      val staleValid = bad.count(_.contains("STALE"))
      println(f"  UMRACE ${k.name}%-14s seed=$seed cycles=${r.windowCycles}%6d d0=0x$lastD0%08x " +
        f"umDrains=$drains%3d umDrops=$drops%3d memBad=${bad.size}%2d (staleValid=$staleValid%d)" +
        (if (bad.nonEmpty) " " + bad.take(6).mkString(" ") else ""))
      if (lastD0 != 0L) fails += f"${k.name} seed=$seed d0=0x$lastD0%08x"
    }
    if (only.contains("cross")) for (seed <- seeds) {
      val (k, _) = kCross()
      lastD0 = -1L
      val r = runKernel(compiled, k, seed)
      println(f"  UMRACE ${k.name}%-14s seed=$seed cycles=${r.windowCycles}%6d d0=0x$lastD0%08x " +
        "(d0 bit3 = some page lost U, bit4 = some page lost M)")
      if (lastD0 != 0L) fails += f"${k.name} seed=$seed d0=0x$lastD0%08x"
    }
    assert(fails.isEmpty, s"descriptor clobbered by a deferred U/M update: ${fails.mkString("; ")}")
  }
}
