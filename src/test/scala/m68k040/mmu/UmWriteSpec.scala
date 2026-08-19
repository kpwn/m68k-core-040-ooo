package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{DTranslationToken, TranslationReq, TranslationRsp}
import m68k040.services.DTranslationService
import m68k040.ls.BehavioralMemAgent
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
    val accessRobId = in UInt (6 bits)
    val commitValid = in Bool ()
    val commitId    = in UInt (6 bits)
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
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe)) }
    def walkerAxi = dtlb.walkerAxi
  }

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
  // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
  def pokeWordLE(mem: BehavioralMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  def vpnOf(va: Long): Long  = (va >> 12) & 0xfffff

  def buildTable(mem: BehavioralMemAgent, va: Long, ppn: Long): Long = {
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

  def init(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val mem = new BehavioralMemAgent(dut.walkerAxi, cd)
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
      while (!(dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) && guard < 100) {
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

  test("a full four-entry U/M queue stalls the fifth walker without overwrite", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val (cd, mem) = init(dut)
      val vas = (0 until 5).map(i => 0x01402000L + i * 0x1000L)
      val pageAddrs = vas.zipWithIndex.map { case (va, i) =>
        buildTable(mem, va, ppn = 0x30000L + i)
      }

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1
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
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1
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
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1
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
        if (dut.walkerAxi.ar.valid.toBoolean && dut.walkerAxi.ar.ready.toBoolean) arCount += 1
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
}
