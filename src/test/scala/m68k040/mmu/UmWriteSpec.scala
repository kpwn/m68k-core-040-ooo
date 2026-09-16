package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import m68k040.sim.{DcacheClientMemAgent, WalkerDcacheSimIo}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Test-only plugin: drives the DTranslationService request + the DtlbPlugin's U/M
  * queue hooks (access robId, commit, flush), exposes the response. */
class UmProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate = host[DTranslationService]
    val dtlb  = host[DtlbPlugin]
    val reqIn = in(TranslationReq())
    val rspOut = out(TranslationRsp())
    val accessRobId = in UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
    val commitValid = in Bool ()
    val commitId    = in UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
    val flush       = in Bool ()
    val pflusha     = in Bool ()
    val walkDone    = out Bool ()
    val requestIssued = RegInit(False)
    xlate.req.valid      := reqIn.valid && !requestIssued
    xlate.req.payload.vpn        := reqIn.vpn
    xlate.req.payload.supervisor := reqIn.supervisor
    xlate.req.payload.write      := reqIn.write
    xlate.req.payload.token      := U(0, DTranslationToken.Width bits)
    xlate.rsp.ready   := !reqIn.valid
    rspOut.ready      := xlate.rsp.valid
    rspOut.ppn        := xlate.rsp.payload.ppn
    rspOut.cacheMode  := xlate.rsp.payload.cacheMode
    rspOut.fault      := xlate.rsp.payload.fault
    when(xlate.req.fire) { requestIssued := True }
    when(!reqIn.valid)   { requestIssued := False }
    dtlb.umAccessRobId := accessRobId
    dtlb.umCommitValid := commitValid
    dtlb.umCommitId    := commitId
    dtlb.umFlush       := flush
    dtlb.flushAll      := pflusha
    walkDone           := dtlb.logic.walker.io.done
  }
}

/** Directed tests for the deferred U/M descriptor-write queue:
  *  - a walk for a WRITE access queues a U+M descriptor write; on COMMIT it drains
  *    (RMW the descriptor byte in the page-table memory) -> the byte is updated
  *  - a queued write that is FLUSHED (mispredict) is discarded -> memory unchanged */
class UmWriteSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val probe = new UmProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
    // The table walker is a DcacheService CLIENT now, not an AXI master. This DUT hosts
    // no DcachePlugin, so it exposes the walker's client port pair as its own IO and lets
    // `DcacheClientMemAgent` answer it out of a SparseMemory -- the direct replacement for
    // attaching a DcacheClientMemAgent to the retired `walkerAxi`.
  }

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
  // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
  def pokeWordLE(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff

  def buildTable(mem: DcacheClientMemAgent, va: Long, ppn: Long): Long = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    val pageAddr = PAGT + pageIdx(va) * 4
    pokeWordLE(mem, pageAddr, ((ppn << 12) & 0xfffff000L) | 0x1L)  // PDT resident, U/M=0
    pageAddr
  }

  def walk(dut: Dut, cd: ClockDomain, va: Long, write: Boolean, robId: Int): Unit = {
    dut.probe.logic.accessRobId #= robId
    dut.probe.logic.reqIn.valid #= true
    dut.probe.logic.reqIn.vpn   #= vpnOf(va)
    dut.probe.logic.reqIn.write #= write
    dut.probe.logic.reqIn.supervisor #= false
    cd.waitSampling()
    var guard = 0
    while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
    assert(dut.probe.logic.rspOut.ready.toBoolean, "walk resolved")
    dut.probe.logic.reqIn.valid #= false
    cd.waitSampling(2)
  }

  def init(dut: Dut): (ClockDomain, DcacheClientMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val mem = new DcacheClientMemAgent(dut.walkPort, cd)
    dut.probe.logic.reqIn.valid #= false
    dut.probe.logic.reqIn.vpn #= 0; dut.probe.logic.reqIn.write #= false; dut.probe.logic.reqIn.supervisor #= false
    dut.probe.logic.accessRobId #= 0
    dut.probe.logic.commitValid #= false; dut.probe.logic.commitId #= 0
    dut.probe.logic.flush #= false
    dut.probe.logic.pflusha #= false
    cd.waitSampling(4)
    dut.ctrl.logic.mmuEnable #= true
    dut.ctrl.logic.urp   #= ROOT
    dut.ctrl.logic.srp   #= ROOT
    cd.waitSampling(2)
    (cd, mem)
  }

  test("write-access walk queues U+M; commit drains -> descriptor byte updated", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x00802000L
      val pageAddr = buildTable(mem, va, ppn = 0xABCDEL)
      assert(mem.peekByte(pageAddr + 3) == 0x01, "initial descriptor low byte = PDT resident")

      // a WRITE access (robId 7) -> walk queues U+M descriptor write (low byte 0x01|U|M = 0x19)
      walk(dut, cd, va, write = true, robId = 7)
      // not yet committed -> memory must be UNCHANGED (speculative, not drained)
      cd.waitSampling(5)
      assert(mem.peekByte(pageAddr + 3) == 0x01, "U/M write must NOT drain before commit")

      // commit robId 7 -> drain RMW
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId    #= 7
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      // let the drain write-through land
      cd.waitSampling(20)
      assert(mem.peekByte(pageAddr + 3) == 0x19, f"descriptor byte after commit-drain = 0x${mem.peekByte(pageAddr + 3)}%x expected 0x19")
    }
  }

  test("flushed U/M write is discarded -> memory unchanged", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x00C04000L
      val pageAddr = buildTable(mem, va, ppn = 0x12300L)
      assert(mem.peekByte(pageAddr + 3) == 0x01, "initial descriptor")

      // a WRITE access (robId 3) queues a U+M write...
      walk(dut, cd, va, write = true, robId = 3)
      // ...then a flush (mispredict) discards it (speculative, uncommitted)
      dut.probe.logic.flush #= true
      cd.waitSampling()
      dut.probe.logic.flush #= false
      cd.waitSampling(20)
      assert(mem.peekByte(pageAddr + 3) == 0x01, "flushed U/M write must NOT drain (memory unchanged)")

      // a subsequent commit of robId 3 must do nothing (entry was discarded)
      dut.probe.logic.commitValid #= true; dut.probe.logic.commitId #= 3
      cd.waitSampling(); dut.probe.logic.commitValid #= false
      cd.waitSampling(20)
      assert(mem.peekByte(pageAddr + 3) == 0x01, "no drain after a discarded entry")
    }
  }

  test("flush during an active walk poisons its late U/M allocation", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x01005000L
      val pageAddr = buildTable(mem, va, ppn = 0x23450L)

      dut.probe.logic.accessRobId #= 11
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn #= vpnOf(va)
      dut.probe.logic.reqIn.write #= true
      dut.probe.logic.reqIn.supervisor #= false

      // Wait until the walk is genuinely active, then squash before completion.
      var guard = 0
      while (!(dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) && guard < 100) {
        cd.waitSampling(); guard += 1
      }
      assert(guard < 100, "write walk never launched")
      dut.probe.logic.flush #= true
      cd.waitSampling()
      dut.probe.logic.flush #= false

      guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) {
        cd.waitSampling(); guard += 1
      }
      assert(dut.probe.logic.rspOut.ready.toBoolean, "poisoned walk still returns its tagged result")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      // Reusing/committing the same ROB id after the old walk finishes must not
      // resurrect its discarded descriptor update.
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 11
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      cd.waitSampling(30)
      assert(mem.peekByte(pageAddr + 3) == 0x01,
        "a walk completing after flush must not allocate or drain U/M")
    }
  }

  // C1 regression (fmax-closure-fanout MMU-walker review): a backend flush landing
  // on an in-flight DTLB walk must not just suppress the walk's deferred U/M
  // descriptor WRITE (already covered by the test above) but must also stop that
  // walk's result from being cached into the ATC. `walkUmPoison`'s ONLY job used
  // to be gating `umq.io.alloc.valid`; `tlb.io.fillValid` was ungated, so the
  // poisoned walk's `fe.modified` (True for a write-triggered walk, unconditionally
  // -- see DtlbPlugin's `fe.modified := walker.io.rsp.modified`) still landed in a
  // fresh ATC entry. The very next write to the SAME page then read that cached
  // modified=true and took the fast `tlbHit && !needsMRefresh` path, never
  // re-walking -- M was lost for the life of the entry. Same shape silently loses
  // U on a poisoned READ (cold-miss) walk: TlbEntry has no separate U field, so
  // once ANY fill happens for a page, nothing ever re-triggers a walk for U's own
  // sake.
  //
  // This test proves BOTH shapes are now closed: after a poisoned walk, the VERY
  // NEXT access to that same page must perform a genuine, fresh 3-level re-walk
  // (not a cached hit) and, once that access's OWN instruction commits, correctly
  // land the real U/M bits in memory.
  test("C1: flush during an in-flight walk must not cache a stale ATC entry (M-loss on write, U-loss on read)",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)

      def flushMidWalk(va: Long, write: Boolean, robId: Int, arCountNow: () => Int): Unit = {
        dut.probe.logic.accessRobId #= robId
        dut.probe.logic.reqIn.valid #= true
        dut.probe.logic.reqIn.vpn #= vpnOf(va)
        dut.probe.logic.reqIn.write #= write
        dut.probe.logic.reqIn.supervisor #= false
        var guard = 0
        while (!(dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) && guard < 100) {
          cd.waitSampling(); guard += 1
        }
        assert(guard < 100, s"walk (robId=$robId) never launched before the flush")
        dut.probe.logic.flush #= true
        cd.waitSampling()
        dut.probe.logic.flush #= false
        guard = 0
        while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
        assert(dut.probe.logic.rspOut.ready.toBoolean, s"poisoned walk (robId=$robId) still returns its tagged result")
        dut.probe.logic.reqIn.valid #= false
        cd.waitSampling(2)
        // Committing the poisoned walk's own robId must still drain nothing
        // (already covered by the sibling test above; re-checked here as a sanity
        // anchor before probing the ATC-fill side-effect).
        dut.probe.logic.commitValid #= true
        dut.probe.logic.commitId #= robId
        cd.waitSampling()
        dut.probe.logic.commitValid #= false
        cd.waitSampling(10)
      }

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1
      } }

      // ---- M-loss shape: a poisoned WRITE walk ----
      val vaW = 0x02001000L
      val pageAddrW = buildTable(mem, vaW, ppn = 0x11111L)
      flushMidWalk(vaW, write = true, robId = 30, () => arCount)
      assert(mem.peekByte(pageAddrW + 3) == 0x01, "poisoned write walk must not have drained U/M")

      val beforeSecondW = arCount
      dut.probe.logic.accessRobId #= 31
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn #= vpnOf(vaW)
      dut.probe.logic.reqIn.write #= true
      dut.probe.logic.reqIn.supervisor #= false
      var guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(dut.probe.logic.rspOut.ready.toBoolean, "second write to the same page must eventually resolve")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)
      assert(arCount > beforeSecondW,
        s"a write immediately after a poisoned walk to the SAME page must re-walk (M must not be cached as " +
        s"already-set): ARs $beforeSecondW -> $arCount")

      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 31
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      guard = 0
      while (mem.peekByte(pageAddrW + 3) != 0x19 && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(mem.peekByte(pageAddrW + 3) == 0x19,
        f"post-re-walk commit must set U+M (0x19); got 0x${mem.peekByte(pageAddrW + 3)}%x")

      // ---- U-loss shape: a poisoned READ (cold-miss) walk ----
      val vaR = 0x02002000L
      val pageAddrR = buildTable(mem, vaR, ppn = 0x22222L)
      flushMidWalk(vaR, write = false, robId = 32, () => arCount)
      assert(mem.peekByte(pageAddrR + 3) == 0x01, "poisoned read walk must not have drained U")

      val beforeSecondR = arCount
      dut.probe.logic.accessRobId #= 33
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn #= vpnOf(vaR)
      dut.probe.logic.reqIn.write #= false
      dut.probe.logic.reqIn.supervisor #= false
      guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(dut.probe.logic.rspOut.ready.toBoolean, "second read of the same page must eventually resolve")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)
      assert(arCount > beforeSecondR,
        s"a read immediately after a poisoned walk to the SAME page must re-walk (the page must not be cached " +
        s"as already-resident with U silently lost): ARs $beforeSecondR -> $arCount")

      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 33
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      guard = 0
      while (mem.peekByte(pageAddrR + 3) != 0x09 && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(mem.peekByte(pageAddrR + 3) == 0x09,
        f"post-re-walk commit must set U only (0x09); got 0x${mem.peekByte(pageAddrR + 3)}%x")
    }
  }

  test("a full four-entry U/M queue stalls the fifth walker without overwrite", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val vas = (0 until 5).map(i => 0x01402000L + i * 0x1000L)
      val pageAddrs = vas.zipWithIndex.map { case (va, i) =>
        buildTable(mem, va, ppn = 0x30000L + i)
      }

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1
      } }

      // Four uncommitted write walks consume every deferred-update slot.
      for (i <- 0 until 4) walk(dut, cd, vas(i), write = true, robId = i + 1)
      assert(arCount >= 12, s"four cold walks should issue at least 12 ARs, got $arCount")
      for (i <- 0 until 4)
        assert(mem.peekByte(pageAddrs(i) + 3) == 0x01,
          s"descriptor $i drained before commit; the full-queue setup is not live")
      val beforeFifth = arCount

      // The fifth DTLB command may be accepted into the cold miss holder, but its
      // walker must not launch until an older slot is safely drained.
      dut.probe.logic.accessRobId #= 5
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn #= vpnOf(vas(4))
      dut.probe.logic.reqIn.write #= true
      dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling(12)
      assert(!dut.probe.logic.rspOut.ready.toBoolean, "fifth walk must wait for U/M credit")
      assert(arCount == beforeFifth,
        s"full queue launched a fifth walk: AR $beforeFifth -> $arCount")

      // Commit/drain the oldest update. If tail had silently overwritten it, this
      // byte would never update and the fifth walk would remain deadlocked.
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 1
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      var guard = 0
      while (mem.peekByte(pageAddrs.head + 3) != 0x19 && guard < 200) {
        cd.waitSampling(); guard += 1
      }
      assert(mem.peekByte(pageAddrs.head + 3) == 0x19,
        "oldest full-queue entry was preserved and drained")

      guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) {
        cd.waitSampling(); guard += 1
      }
      assert(dut.probe.logic.rspOut.ready.toBoolean, "fifth walk resumes after credit returns")
      assert(arCount >= beforeFifth + 3, s"resumed fifth walk did not perform a full walk: $arCount")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)
      assert(mem.peekByte(pageAddrs(4) + 3) == 0x01,
        "fifth update remains speculative before its own commit")

      // Drain every remaining unique ROB association; no descriptor may be lost or
      // cross-associated around the full-ring tail/head turnover.
      for (rob <- 2 to 5) {
        dut.probe.logic.commitValid #= true
        dut.probe.logic.commitId #= rob
        cd.waitSampling()
        dut.probe.logic.commitValid #= false
        cd.waitSampling(30)
      }
      for (i <- pageAddrs.indices)
        assert(mem.peekByte(pageAddrs(i) + 3) == 0x19,
          s"descriptor $i lost/cross-associated around U/M full turnover")
    }
  }

  test("PFLUSHA on walker done suppresses the old fill, response, and U/M update", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x01807000L
      val pageAddr = buildTable(mem, va, ppn = 0x45670L)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1
      } }

      dut.probe.logic.accessRobId #= 12
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn #= vpnOf(va)
      dut.probe.logic.reqIn.write #= true
      dut.probe.logic.reqIn.supervisor #= false

      // Assert PFLUSHA during the exact cycle in which walker.done is presented
      // to DtlbPlugin.  This is the collision that used to enqueue the old U/M
      // descriptor even though the response and TLB fill were discarded.
      var guard = 0
      while (!dut.probe.logic.walkDone.toBoolean && guard < 300) {
        cd.waitSampling(); sleep(1); guard += 1
      }
      assert(guard < 300, "walk never reached its done collision cycle")
      assert(arCount >= 3, s"cold walk setup was vacuous: only $arCount descriptor reads")
      dut.probe.logic.pflusha #= true
      cd.waitSampling()
      dut.probe.logic.pflusha #= false
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(3)
      assert(!dut.probe.logic.rspOut.ready.toBoolean,
        "PFLUSHA/done collision leaked the pre-flush response")

      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 12
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      cd.waitSampling(30)
      assert(mem.peekByte(pageAddr + 3) == 0x01,
        "PFLUSHA/done collision leaked the pre-flush U/M update")

      // The old fill must also be absent: the same VPN must perform all three
      // descriptor reads again before returning a current result.
      val beforeReplay = arCount
      walk(dut, cd, va, write = true, robId = 13)
      assert(arCount >= beforeReplay + 3,
        s"PFLUSHA/done collision left a stale resident fill: $beforeReplay -> $arCount")
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 13
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      guard = 0
      while (mem.peekByte(pageAddr + 3) != 0x19 && guard < 200) {
        cd.waitSampling(); guard += 1
      }
      assert(mem.peekByte(pageAddr + 3) == 0x19,
        "post-PFLUSHA replay did not preserve the new U/M association")
    }
  }

  // Task #210: a write that HITS an already-resident TLB entry whose cached M bit
  // is clear must trigger a REAL table-search re-walk (the same single-outstanding
  // walker machinery a cold miss uses) whose deferred descriptor write, once
  // committed, sets M in memory. This access's OWN response is deliberately held
  // back until that walk completes (exactly like a cold miss) rather than firing
  // immediately with the walk backgrounded -- an earlier version of this fix did
  // the latter and passed every DIRECTED test here while silently NEVER DRAINING
  // on the real full core: `UmWriteQueue.commit` (see its own file) only marks
  // committed an entry that is ALREADY allocated at the exact cycle the one-shot
  // commit pulse arrives. The cold-miss path is safe by construction because its
  // response -- and hence the triggering instruction's own retirement/commit --
  // is held back until `walker.io.done`, the SAME cycle the queue allocation
  // happens, so the commit pulse can never arrive before the entry exists. An
  // immediate hit response breaks that invariant: the instruction can retire (and
  // pulse commit) many cycles before the background walk even finishes, and the
  // late allocation is then permanently stuck uncommitted. Blocking here costs
  // one real 3-level walk -- exactly the "a table search proceeds" latency real
  // hardware pays too -- but is correct by the same construction the miss path
  // already relies on.
  test("task #210: write-hit against an M=0 resident entry re-walks to set M", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x01A0B000L
      val pageAddr = buildTable(mem, va, ppn = 0x67890L)
      assert(mem.peekByte(pageAddr + 3) == 0x01, "initial descriptor: resident, U=0, M=0")

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1
      } }

      // READ (robId 1): cold miss -> walk -> fill (TLB entry modified=false); U-only
      // deferred write queued and committed.
      walk(dut, cd, va, write = false, robId = 1)
      val afterRead = arCount
      assert(afterRead >= 3, s"read miss should have walked; got $afterRead ARs")
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 1
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      var guard = 0
      while (mem.peekByte(pageAddr + 3) != 0x09 && guard < 200) { cd.waitSampling(); guard += 1 }
      assert(mem.peekByte(pageAddr + 3) == 0x09, f"post-read-commit descriptor should be U=1,M=0 (0x09); got 0x${mem.peekByte(pageAddr + 3)}%x")

      // WRITE the SAME va (robId 2): TLB-hits but the cached entry's modified bit
      // is still false, so this must fall through to a REAL 3-level re-walk (its
      // own tagged response only fires once that walk completes).
      dut.probe.logic.accessRobId #= 2
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnOf(va)
      dut.probe.logic.reqIn.write #= true
      dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling()
      guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(dut.probe.logic.rspOut.ready.toBoolean, "write-hit-needing-refresh response must eventually fire")
      dut.probe.logic.reqIn.valid #= false
      assert(arCount > afterRead,
        s"write-hit against M=0 entry must have performed a real re-walk before responding: $afterRead -> $arCount")

      // Commit robId 2 (only NOW, after the response -- and hence the umq
      // allocation -- are already known to have happened) -> the deferred M
      // write drains.
      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 2
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      guard = 0
      while (mem.peekByte(pageAddr + 3) != 0x19 && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(mem.peekByte(pageAddr + 3) == 0x19, f"post-write-hit-commit descriptor should be U=1,M=1 (0x19); got 0x${mem.peekByte(pageAddr + 3)}%x")

      // A SECOND write-hit to the same (now modified=true) entry must NOT re-walk.
      val beforeSecond = arCount
      dut.probe.logic.accessRobId #= 3
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn   #= vpnOf(va)
      dut.probe.logic.reqIn.write #= true
      dut.probe.logic.reqIn.supervisor #= false
      cd.waitSampling()
      guard = 0
      while (!dut.probe.logic.rspOut.ready.toBoolean && guard < 300) { cd.waitSampling(); guard += 1 }
      assert(dut.probe.logic.rspOut.ready.toBoolean, "second write-hit response must fire promptly")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(10)
      assert(arCount == beforeSecond,
        s"a write-hit against an already-modified=true entry must NOT re-walk: $beforeSecond -> $arCount")
    }
  }

  test("PFLUSHA during an active walk poisons its later response, fill, and U/M update", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val va = 0x01C09000L
      val pageAddr = buildTable(mem, va, ppn = 0x56790L)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkPort.logic.cmd.valid.toBoolean && dut.walkPort.logic.cmd.ready.toBoolean) arCount += 1
      } }

      dut.probe.logic.accessRobId #= 20
      dut.probe.logic.reqIn.valid #= true
      dut.probe.logic.reqIn.vpn #= vpnOf(va)
      dut.probe.logic.reqIn.write #= true
      dut.probe.logic.reqIn.supervisor #= false
      var guard = 0
      while (arCount == 0 && guard < 100) { cd.waitSampling(); guard += 1 }
      assert(arCount > 0, "walk never became active before PFLUSHA")

      dut.probe.logic.pflusha #= true
      cd.waitSampling()
      dut.probe.logic.pflusha #= false

      guard = 0
      while (!dut.probe.logic.walkDone.toBoolean && guard < 300) {
        cd.waitSampling(); sleep(1); guard += 1
      }
      assert(guard < 300 && arCount >= 3, "poisoned walk did not finish normally")
      cd.waitSampling(3)
      assert(!dut.probe.logic.rspOut.ready.toBoolean,
        "an active pre-PFLUSHA walk leaked its later response")
      dut.probe.logic.reqIn.valid #= false
      cd.waitSampling(2)

      dut.probe.logic.commitValid #= true
      dut.probe.logic.commitId #= 20
      cd.waitSampling()
      dut.probe.logic.commitValid #= false
      cd.waitSampling(30)
      assert(mem.peekByte(pageAddr + 3) == 0x01,
        "an active pre-PFLUSHA walk leaked its later U/M update")

      val beforeReplay = arCount
      walk(dut, cd, va, write = true, robId = 21)
      assert(arCount >= beforeReplay + 3,
        s"an active pre-PFLUSHA walk left a stale fill: $beforeReplay -> $arCount")
    }
  }

  // ── flush rollback: a COMMITTED entry stranded behind an uncommitted one ────────
  //
  // FAIL-BEFORE / PASS-AFTER for the `UmWriteQueue.flush` ring-invariant bug
  // (2026-09-09). Entries are allocated in walk-COMPLETION order, which is LS ISSUE
  // order (`IssueQueuePlugin` selects the oldest READY op, not the oldest op), while
  // `commit` arrives in program order -- so a committed entry CAN sit behind an
  // uncommitted one. The old flush body cleared every non-kept slot and set
  // `tail := head + CountOne(keep)`, a POPULATION count rather than a prefix count from
  // `head`. In that state `head` was left pointing at a slot whose `valid` had just been
  // cleared, so `headReady` was false forever and the queue NEVER DRAINED AGAIN, and
  // `tail` landed on the surviving committed entry for the next allocation to overwrite.
  // Losing an M means a page that IS dirty is later evicted as clean.
  //
  // Unfixed, the second assertion below reads 0x01 (the committed write never lands).
  test("flush: a COMMITTED entry behind an uncommitted one still drains", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      // Two pages under the SAME root/pointer entries, different page-table slots.
      val vaYoung = 0x00802000L   // walked FIRST  -> queue slot 0; robId 9  = YOUNGER
      val vaOld   = 0x00803000L   // walked SECOND -> queue slot 1; robId 2  = OLDER
      val pgYoung = buildTable(mem, vaYoung, ppn = 0x11100L)
      val pgOld   = buildTable(mem, vaOld,   ppn = 0x22200L)
      assert(mem.peekByte(pgYoung + 3) == 0x01, "initial descriptor (young page)")
      assert(mem.peekByte(pgOld   + 3) == 0x01, "initial descriptor (old page)")

      // The YOUNGER access's walk completes first, so its entry takes the queue HEAD.
      walk(dut, cd, vaYoung, write = true, robId = 9)
      walk(dut, cd, vaOld,   write = true, robId = 2)

      // The OLDER instruction retires first -- its entry (slot 1) becomes committed
      // while slot 0, at the head, is still speculative.
      dut.probe.logic.commitValid #= true; dut.probe.logic.commitId #= 2
      cd.waitSampling(); dut.probe.logic.commitValid #= false
      cd.waitSampling(2)

      // ...and then the younger instruction is squashed.
      dut.probe.logic.flush #= true
      cd.waitSampling()
      dut.probe.logic.flush #= false
      cd.waitSampling(80)

      assert(mem.peekByte(pgYoung + 3) == 0x01,
        f"the SQUASHED entry must never be written (got 0x${mem.peekByte(pgYoung + 3)}%02x)")
      assert(mem.peekByte(pgOld + 3) == 0x19,
        f"the COMMITTED entry stranded behind it must still drain: got " +
        f"0x${mem.peekByte(pgOld + 3)}%02x, expected 0x19 (PDT|U|M). 0x01 means the queue " +
        "was left with `head` on an invalidated slot and never drained again")
    }
  }

  // The queue must also stay USABLE afterwards: a flush that strands a committed entry
  // used to leave `tail` on top of it, so the next allocation silently overwrote an
  // architectural write. Here the post-flush allocation must land on its own slot and
  // drain on its own commit, with the stranded entry's write already in memory.
  test("flush: the queue keeps allocating correctly after a stranded-commit flush", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val vaYoung = 0x00804000L
      val vaOld   = 0x00805000L
      val vaNext  = 0x00806000L
      val pgYoung = buildTable(mem, vaYoung, ppn = 0x33300L)
      val pgOld   = buildTable(mem, vaOld,   ppn = 0x44400L)
      val pgNext  = buildTable(mem, vaNext,  ppn = 0x55500L)

      walk(dut, cd, vaYoung, write = true, robId = 9)
      walk(dut, cd, vaOld,   write = true, robId = 2)
      dut.probe.logic.commitValid #= true; dut.probe.logic.commitId #= 2
      cd.waitSampling(); dut.probe.logic.commitValid #= false
      cd.waitSampling(2)
      dut.probe.logic.flush #= true
      cd.waitSampling()
      dut.probe.logic.flush #= false
      cd.waitSampling(80)
      assert(mem.peekByte(pgOld + 3) == 0x19, "stranded committed entry drained")

      // A fresh access after the flush must queue and drain normally.
      walk(dut, cd, vaNext, write = true, robId = 17)
      dut.probe.logic.commitValid #= true; dut.probe.logic.commitId #= 17
      cd.waitSampling(); dut.probe.logic.commitValid #= false
      cd.waitSampling(80)
      assert(mem.peekByte(pgNext + 3) == 0x19,
        f"a post-flush allocation must still drain (got 0x${mem.peekByte(pgNext + 3)}%02x)")
      assert(mem.peekByte(pgYoung + 3) == 0x01, "the squashed entry is still never written")
    }
  }
}
