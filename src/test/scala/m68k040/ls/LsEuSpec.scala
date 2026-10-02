package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class LsEuSpec extends AnyFunSuite {
  class Dut(p3Fast: Boolean = false, p1Early: Boolean = false,
            p1Dn: Boolean = false) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc   // LS EU now writes NZVC for MOVE-to-memory
    val rfX    = new RegFilePluginX     // LS EU now writes X for RTR CCR-restore
    val cacheCtrl = new CacheControlStubPlugin
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin(exportBusQuiesced =
      p3Fast || m68k040.top.ShippingCoreConfig.inhibitedFullBarrier)
    val eu     = new LsEuPlugin(p3FastLoad = p3Fast, p1EarlyLoad = p1Early || p1Dn,
      inhibitedFullBarrier = p3Fast || m68k040.top.ShippingCoreConfig.inhibitedFullBarrier,
      alignedLoadFallThrough = p3Fast)
    val src    = new LsEuSourcePlugin(dstArch = if (p1Early && !p1Dn) 8 else 0,
                                     writeNzvc = p1Dn)
    val phead  = new TbPreciseDrainWirePlugin(eu)
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, cacheCtrl,
                                           xlate, dcache, eu, src, phead)) }
  }

  def simConfig = M68kSim().withVerilator
  def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  def expectedLong(base: Long): BigInt =
    (0 until 4).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg; dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut, injectBusErrors: Boolean = false): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    // 2026-09-09: construct the AXI responder BEFORE the first clock edge. Built after
    // `forkStimulus` it left the R/B valid + payload inputs undriven across reset release,
    // and a randomly-asserted B response then reaches the cache as a store acknowledgement
    // with no accepted descriptor (its tripwire kills the sim at time=170). Same ordering
    // ExceptionEntrySpec/RteSpec already document for the identical hazard.
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd,
      injectBusErrors = injectBusErrors)
    cd.forkStimulus(10)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    // `iSqCommitRob` is the PAYLOAD of that commit port and was the one input this
    // fixture never drove. It is gated by `iSqCommitValid` (held false here), so it is
    // harmless today -- but an undriven input is randomised per seed, and this spec
    // already has history with exactly that: see `iLeaAddr` below, where a randomised
    // default sent EVERY load down the LEA address-generate path. Leaving one behind is
    // leaving a trap for whoever next chases the intermittent "load hit (second load
    // same line)" failure in this file.
    s.iSqCommitRob #= 0
    s.iStkPush #= false
    s.iLeaAddr #= false   // MUST default: undriven -> randomized per seed -> every load
                          // takes the LEA address-generate path (dst = EA, not the data).
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    dut.phead.logic.iRobHeadIn #= 0
    dut.phead.logic.iRobHeadValidIn #= false
    // Make the fixture's advertised "hit" tests real: without a CacheControlService
    // the LS EU correctly forces every access INHIBITED, so no first load can allocate.
    cd.waitSampling(80) // PRF init sweep
    // Apply after reset/PRF initialization; poking a RegInit while reset is still
    // asserted would be overwritten back to the architectural DE=0 reset value.
    dut.cacheCtrl.logic.dcacheEnabled #= true
    cd.waitSampling()
    (cd, mem)
  }

  def issueLoad(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long, size: SpinalEnumElement[Size.type], pdst: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true; s.iPsrcBValid #= false
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdst #= pdst; s.iPdstValid #= true; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  def issueStore(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long, dataPreg: Int, size: SpinalEnumElement[Size.type], robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= dataPreg; s.iPsrcBValid #= true
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  /** ⚠ 120, NOT 60. The old budget was marginal against slice `D3-BURST`
    * (`DcachePlugin.sectored`), where a 64-byte line miss legitimately costs up to ~14
    * cycles more than a 16-byte one: three extra R beats for the burst, up to three more
    * for the demanded sector's position within it, and up to eight for the eviction walk
    * over the victim line's four sectors. At 60 the sectored arm reported "load completion
    * must fire ... was false" on three tests; at 400 all seven pass, so it is a BUDGET
    * ASSUMPTION and not a hang (that diagnostic is the reason this comment can say so).
    * 120 keeps roughly 4x headroom over the observed sectored latency while staying tight
    * enough to still catch a genuine hang -- which is the only thing this budget is for.
    * Do not raise it further without re-running the 400-cycle diagnostic: a budget that
    * cannot fail stops being a test. */
  def waitCompletion(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 120): Boolean = {
    var saw = false
    var n = 0
    while (!saw && n < maxCycles) {
      if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId) saw = true
      cd.waitSampling(); n += 1
    }
    saw
  }

  /** Wait for the store to become a real resident SQ entry. Standalone identity-
    * translated stores are precise, so allocation deliberately does not complete
    * the ROB entry; completion follows commit and the acknowledged drain. */
  def waitStoreAlloc(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 60): Boolean = {
    var resident = false
    var n = 0
    while (!resident && n < maxCycles) {
      resident = dut.eu.logic.sq.valids.zip(dut.eu.logic.sq.robIds)
        .exists { case (valid, id) => valid.toBoolean && id.toInt == robId }
      if (!resident) cd.waitSampling()
      n += 1
    }
    resident
  }

  test("P3 fast load admits only empty-SQ ordinary loads; forward and split fall back", VerilatorTest) {
    simConfig.compile(new Dut(p3Fast = true)).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x7000L
      for (i <- 0 until 32) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0x12345678L)
      var tracking = true
      var fastFires = 0
      var p3Offers = 0
      var p3Accepted = 0
      var p4Accepted = 0
      var p3Refused = 0
      var queuedP3Accepted = 0
      val expectedAddrByRob = Map(1 -> base, 2 -> (base + 8), 5 -> (base + 8),
        8 -> 0x8000L, 9 -> base)
      fork {
        while (tracking) {
          sleep(1)
          val ls = dut.eu.logic
          if (ls.p3FastEnq.toBoolean) fastFires += 1
          val cmd = dut.dcache.logic.loadCmdPort
          // The cache sees this payload before the edge, including when it refuses
          // the offer. The selected source must match the actual push owner; after
          // refusal the same descriptor is read from the queued ring.
          if (ls.alignedFallThrough.toBoolean && cmd.valid.toBoolean) {
            val fromP3 = ls.p3FastEnq.toBoolean
            val rob = cmd.payload.token.toInt & 0x3f
            val expectedAddr = expectedAddrByRob.getOrElse(rob,
                fail(s"fall-through command has unissued ROB id $rob"))
            assert((cmd.payload.vaddr.toLong & 0xffffffffL) == expectedAddr,
              s"fall-through VA belongs to another load (rob=$rob)")
            assert((cmd.payload.paddr.toLong & 0xffffffffL) == expectedAddr,
              s"fall-through PA belongs to another load (rob=$rob)")
            assert(cmd.payload.size.toEnum == Size.LONG,
              s"fall-through size belongs to another load (rob=$rob)")
            assert(if (fromP3) Set(1, 2, 8, 9).contains(rob) else rob == 5,
              s"fall-through stage owner and ROB id disagree (P3=$fromP3 rob=$rob)")
            if (fromP3) p3Offers += 1
            if (cmd.ready.toBoolean) {
              if (fromP3) p3Accepted += 1 else p4Accepted += 1
            } else if (fromP3) p3Refused += 1
          } else if (cmd.valid.toBoolean && cmd.ready.toBoolean) {
            val rob = cmd.payload.token.toInt & 0x3f
            if (Set(1, 2, 8, 9).contains(rob)) {
              val expectedAddr = expectedAddrByRob(rob)
              assert((cmd.payload.vaddr.toLong & 0xffffffffL) == expectedAddr &&
                (cmd.payload.paddr.toLong & 0xffffffffL) == expectedAddr,
                s"queued P3 descriptor changed identity after refusal (rob=$rob)")
              queuedP3Accepted += 1
            }
          }
          cd.waitSampling()
        }
      }
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 1)
      assert(waitCompletion(dut, cd, robId = 1), "ordinary load completion")
      assert(fastFires == 1, s"ordinary empty-SQ load used P3 $fastFires times")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base))
      issueLoad(dut, cd, basePreg = 10, disp = 8, Size.LONG, pdst = 27, robId = 2)
      assert(waitCompletion(dut, cd, robId = 2), "warm P3 load completion")
      dut.src.logic.obsIntAddr #= 27; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base + 8))

      issueStore(dut, cd, basePreg = 10, disp = 4, dataPreg = 11, Size.LONG, robId = 3)
      assert(waitStoreAlloc(dut, cd, robId = 3), "older store resident")
      val beforeForward = fastFires
      issueLoad(dut, cd, basePreg = 10, disp = 4, Size.LONG, pdst = 21, robId = 4)
      assert(waitCompletion(dut, cd, robId = 4), "SQ-forwarded load completion")
      assert(fastFires == beforeForward, "resident older store bypassed SQ forwarding")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt(0x12345678L))

      // The older resident store blocks the P3 shortcut even for an unrelated
      // address. Its P4 command must use P4's identity and complete with its own
      // data after the precise store is committed and drained.
      issueLoad(dut, cd, basePreg = 10, disp = 8, Size.LONG, pdst = 26, robId = 5)
      var p4Resident = false
      var p4Wait = 0
      while (!p4Resident && p4Wait < 30) {
        p4Resident = dut.eu.logic.p4Valid.toBoolean &&
          dut.eu.logic.p4Ctx.xlate.front.robId.toInt == 5
        if (!p4Resident) cd.waitSampling()
        p4Wait += 1
      }
      assert(p4Resident, "unrelated load must reach P4 behind the resident store")
      dut.phead.logic.iRobHeadIn #= 3
      dut.phead.logic.iRobHeadValidIn #= true
      var sqCompleted = false
      var sqWait = 0
      while (!sqCompleted && sqWait < 120) {
        sqCompleted = dut.phead.logic.oSqCompValid.toBoolean &&
          dut.phead.logic.oSqCompPayload.toInt == 3
        if (!sqCompleted) cd.waitSampling()
        sqWait += 1
      }
      assert(sqCompleted, "precise store must drain at ROB head before the P4 load")
      dut.src.logic.iSqCommitRob #= 3
      dut.src.logic.iSqCommitValid #= true
      cd.waitSampling()
      dut.src.logic.iSqCommitValid #= false
      dut.phead.logic.iRobHeadIn #= 5
      assert(waitCompletion(dut, cd, robId = 5),
        s"P4 load completion (p4Valid=${dut.eu.logic.p4Valid.toBoolean} " +
          s"p4Rob=${dut.eu.logic.p4Ctx.xlate.front.robId.toInt} " +
          s"sqEmpty=${dut.eu.logic.sq.io.empty.toBoolean} " +
          s"fwdStall=${dut.eu.logic.p4Ctx.fwdStall.toBoolean} " +
          s"fwdSerial=${dut.eu.logic.p4Ctx.fwdSerial.toBoolean} " +
          s"alignedCount=${dut.eu.logic.alignedCount.toInt} " +
          s"p4Accepted=$p4Accepted)")
      dut.src.logic.obsIntAddr #= 26; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base + 8),
        "P4 load used another stage's payload")

      dut.src.logic.iSqFlush #= true
      cd.waitSampling()
      dut.src.logic.iSqFlush #= false
      cd.waitSampling(3)
      val beforeSplit = fastFires
      issueLoad(dut, cd, basePreg = 10, disp = 14, Size.LONG, pdst = 22, robId = 6)
      assert(waitCompletion(dut, cd, robId = 6), "split load completion")
      assert(fastFires == beforeSplit, "split load entered P3 fast path")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base + 14))

      // Squash after admission, while the cold refill still owns its token.
      // The ring may receive the bus response but must not complete/write back
      // the killed ROB id; a following load must still make progress.
      val cold = 0x8000L
      for (i <- 0 until 16) mem.pokeByte(cold + i, memByte(cold + i))
      seed(dut, cd, preg = 12, value = cold)
      val beforeFlush = fastFires
      issueLoad(dut, cd, basePreg = 12, disp = 0, Size.LONG, pdst = 23, robId = 8)
      var waitFast = 0
      while (fastFires == beforeFlush && waitFast < 20) { cd.waitSampling(); waitFast += 1 }
      assert(fastFires == beforeFlush + 1, "cold load did not enter fast ring")
      dut.src.logic.iSqFlush #= true
      cd.waitSampling()
      dut.src.logic.iSqFlush #= false
      for (_ <- 0 until 80) {
        assert(!(dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 8),
          "squashed fast load completed")
        cd.waitSampling()
      }
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 24, robId = 9)
      assert(waitCompletion(dut, cd, robId = 9), "load after fast-path squash")
      assert(p3Offers > 0 && p3Refused > 0 && queuedP3Accepted > 0 && p4Accepted > 0,
        s"P3 offer/refusal/queued acceptance and P4 direct acceptance must occur " +
          s"(P3 offers=$p3Offers refused=$p3Refused queued=$queuedP3Accepted " +
          s"direct=$p3Accepted P4 direct=$p4Accepted)")
      tracking = false
    }
  }

  test("P3 fast cacheable load preserves physical bus fault and no register result", VerilatorTest) {
    simConfig.compile(new Dut(p3Fast = true)).doSim { dut =>
      val (cd, _) = initDut(dut, injectBusErrors = true)
      val unmapped = 0x10000000L
      seed(dut, cd, preg = 10, value = unmapped)
      seed(dut, cd, preg = 25, value = 0x55aa33ccL)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 25, robId = 10)
      var sawFault = false
      var sawCompletion = false
      var fastFires = 0
      var n = 0
      while ((!sawFault || !sawCompletion) && n < 160) {
        if (dut.eu.logic.p3FastEnq.toBoolean) fastFires += 1
        if (dut.src.logic.fValid.toBoolean) {
          assert(dut.src.logic.fRob.toInt == 10)
          assert((dut.src.logic.fAddr.toLong & 0xffffffffL) == unmapped)
          sawFault = true
        }
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 10)
          sawCompletion = true
        if (!sawFault || !sawCompletion) cd.waitSampling()
        n += 1
      }
      assert(sawFault && sawCompletion, "fast-path bus fault lost fault or completion")
      assert(fastFires == 1, s"faulting ordinary load used P3 fast path $fastFires times")
      dut.src.logic.obsIntAddr #= 25; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt(0x55aa33ccL),
        "faulting fast load wrote a destination register")
    }
  }

  test("load miss refills then writes PRF + fires completion", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x1000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)   // base preg = vaddr base
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "load completion must fire")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base), s"load result ${dut.src.logic.obsIntData.toBigInt.toString(16)} exp ${expectedLong(base).toString(16)}")
    }
  }

  test("load hit (second load same line) returns correct data", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x2000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 1)
      assert(waitCompletion(dut, cd, robId = 1), "first load")
      cd.waitSampling(4)
      // second load in same line (disp +8), should hit
      var tracking = true
      var parallelLaunchSeen = false
      var earlyConsumeSeen = false
      val viptTrace = scala.collection.mutable.ArrayBuffer.empty[String]
      fork {
        while (tracking) {
          // `useEarlyProbe && loadCmd.fire` is a pre-edge handshake condition.
          // Sample in the body of the cycle; sampling only after the edge can miss
          // the exact consume-and-replace cycle even though the transfer occurred.
          sleep(1)
          if (dut.eu.logic.parallelViptLaunch.toBoolean) parallelLaunchSeen = true
          if (dut.dcache.logic.useEarlyProbe.toBoolean &&
              dut.dcache.logic.loadCmdPort.valid.toBoolean &&
              dut.dcache.logic.loadCmdPort.ready.toBoolean) earlyConsumeSeen = true
          if (dut.eu.logic.parallelViptLaunch.toBoolean ||
              dut.dcache.logic.loadCmdPort.valid.toBoolean ||
              dut.dcache.logic.earlyProbeValid.toBoolean) {
            viptTrace += s"launch=${dut.eu.logic.parallelViptLaunch.toBoolean}" +
              s",de=${dut.cacheCtrl.logic.dcacheEnabled.toBoolean}" +
              s",probeResolved=${dut.dcache.logic.loadProbePort.payload.resolved.toBoolean}" +
              s",probeMode=${dut.dcache.logic.loadProbePort.payload.cacheMode.toEnum}" +
              s",cmdV=${dut.dcache.logic.loadCmdPort.valid.toBoolean}" +
              s",cmdR=${dut.dcache.logic.loadCmdPort.ready.toBoolean}" +
              s",owns=${dut.dcache.logic.earlyProbeOwnsCmd.toBoolean}" +
              s",use=${dut.dcache.logic.useEarlyProbe.toBoolean}" +
              s",rdUse=${dut.dcache.logic.probeReadUsable.toBoolean}" +
              s",lineHit=${dut.dcache.logic.probeLineHit.toBoolean}" +
              s",q=" + dut.dcache.logic.earlyProbeValids.indices.map { i =>
                s"${dut.dcache.logic.earlyProbeValids(i).toBoolean}/" +
                  s"${dut.dcache.logic.earlyProbeReadies(i).toBoolean}/" +
                  s"${dut.dcache.logic.earlyProbeHits(i).toBoolean}"
              }.mkString("[", ";", "]")
          }
          cd.waitSampling()
        }
      }
      issueLoad(dut, cd, basePreg = 10, disp = 8, Size.LONG, pdst = 21, robId = 2)
      assert(waitCompletion(dut, cd, robId = 2), "second (hit) load")
      tracking = false
      assert(parallelLaunchSeen,
        "the registered LS token must launch DTLB and virtual-set RAM lookup together")
      assert(earlyConsumeSeen,
        "the later physical-tag command must consume that DTLB-parallel RAM result; " +
          viptTrace.mkString(" | "))
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == expectedLong(base + 8), s"hit result ${dut.src.logic.obsIntData.toBigInt.toString(16)}")
    }
  }

  test("store then commit drains to memory", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x3000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xDEADBEEFL)  // store data preg
      issueStore(dut, cd, basePreg = 10, disp = 4, dataPreg = 11, Size.LONG, robId = 7)
      assert(waitStoreAlloc(dut, cd, robId = 7), "store must allocate into the SQ")
      assert(!waitCompletion(dut, cd, robId = 7, maxCycles = 4),
        "a precise store must not complete before its acknowledged drain")
      // not committed yet -> memory unchanged
      assert(mem.peekByte(base + 4) != 0xDE, "store must not drain before commit")
      // A precise store launches when it reaches the ROB head; it cannot be marked
      // committed before its acknowledged drain.  This standalone DUT has no ROB,
      // so drive the real at-head contract directly instead of the legacy commit
      // shortcut (which would make this test vacuous against precise serialization).
      dut.phead.logic.iRobHeadIn #= 7; dut.phead.logic.iRobHeadValidIn #= true
      assert(waitCompletion(dut, cd, robId = 7),
        "committed precise store must complete after the memory ack")
      dut.phead.logic.iRobHeadValidIn #= false
      cd.waitSampling(2)
      assert(mem.peekByte(base + 4) == 0xDE, "committed store drains to memory")
      assert(mem.peekByte(base + 7) == 0xEF, "store byte +7")
    }
  }

  test("store-to-load forward (uncommitted store)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xABCD1234L)
      // store (robId 3) then younger load (robId 5) same addr -> forward
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 3)
      assert(waitStoreAlloc(dut, cd, robId = 3), "store must be resident for forwarding")
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 22, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "forwarded load")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt(0xABCD1234L), s"forwarded ${dut.src.logic.obsIntData.toBigInt.toString(16)}")
    }
  }

  test("optional P1 probe still cancels on exact older-SQ forwarding", VerilatorTest) {
    for (dn <- Seq(false, true)) simConfig.compile(new Dut(p1Early = true, p1Dn = dn)).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4800L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xABCDEF12L)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 3)
      assert(waitStoreAlloc(dut, cd, robId = 3), "older store becomes SQ resident")
      cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
      var monitor = true
      var p1Fires = 0
      var cancels = 0
      var flagWrites = 0
      var writtenFlags = -1
      fork {
        while (monitor) {
          cd.waitSampling()
          if (dut.eu.logic.p1ReqFire.toBoolean) p1Fires += 1
          if (dut.eu.logic.probeCancel.toBoolean) cancels += 1
          if (dut.eu.nzvcW.valid.toBoolean) {
            flagWrites += 1
            writtenFlags = dut.eu.logic.compNzvc.toBigInt.toInt
          }
        }
      }
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 22, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "younger forwarded P1 load completes")
      cd.waitSampling(3)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt("ABCDEF12", 16))
      assert(p1Fires == 1 && cancels >= 1 && !dut.dcache.logic.earlyProbeValid.toBoolean,
        s"P1/SQ collision must cancel its single probe: P1=$p1Fires cancels=$cancels")
      assert(flagWrites == (if (dn) 1 else 0) && (!dn || writtenFlags == 8),
        s"Dn SQ-forwarded MOVE flags writes=$flagWrites value=$writtenFlags")
      monitor = false
    }
  }

  test("optional P1 partial SQ overlap waits for older store drain", VerilatorTest) {
    simConfig.compile(new Dut(p1Early = true)).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4a00L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xABCDEF12L)
      issueStore(dut, cd, basePreg = 10, disp = 2, dataPreg = 11, Size.WORD, robId = 3)
      assert(waitStoreAlloc(dut, cd, robId = 3), "older partial store becomes resident")
      cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
      var monitor = true
      var p1Fires = 0
      fork {
        while (monitor) {
          cd.waitSampling()
          if (dut.eu.logic.p1ReqFire.toBoolean) p1Fires += 1
        }
      }
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 22, robId = 5)
      var earlyLoad = false
      for (_ <- 0 until 20) {
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 5)
          earlyLoad = true
        cd.waitSampling()
      }
      assert(!earlyLoad && p1Fires == 1,
        s"partial overlap cannot expose stale data; early=$earlyLoad P1=$p1Fires")
      dut.phead.logic.iRobHeadIn #= 3
      dut.phead.logic.iRobHeadValidIn #= true
      assert(waitCompletion(dut, cd, robId = 3), "older store drains at ROB head")
      dut.phead.logic.iRobHeadValidIn #= false
      assert(waitCompletion(dut, cd, robId = 5), "younger load resumes after drain")
      cd.waitSampling(3)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      val expected = (BigInt(memByte(base)) << 24) |
                     (BigInt(memByte(base + 1)) << 16) | BigInt(0xEF12)
      assert(dut.src.logic.obsIntData.toBigInt == expected,
        s"post-drain partial overlap returned ${dut.src.logic.obsIntData.toBigInt.toString(16)} expected ${expected.toString(16)}")
      monitor = false
    }
  }

  test("younger load that misses L1D forwards a committed store through the drain window", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      // base is a line NEVER loaded into L1D -> a load here MISSES. The store also
      // misses (write-through, no-allocate) so it does NOT update the L1D line.
      val base = 0x6000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))  // STALE memory
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0x0BADF00DL)
      // store (robId 3) then COMMIT it -> it begins draining to memory.
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 3)
      assert(waitStoreAlloc(dut, cd, robId = 3), "store must allocate before commit")
      dut.phead.logic.iRobHeadIn #= 3; dut.phead.logic.iRobHeadValidIn #= true
      // Immediately issue a YOUNGER load (robId 5) to the same addr. Even though the
      // line misses L1D and the store may already be mid-drain, the SQ entry stays
      // resident until its memory write is ACKed -> the load must FORWARD the store
      // data, not refill stale memory.
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 23, robId = 5)
      assert(waitCompletion(dut, cd, robId = 5), "younger load completes")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 23; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt(0x0BADF00DL),
        s"load must forward store data, got ${dut.src.logic.obsIntData.toBigInt.toString(16)} (stale = ${expectedLong(base).toString(16)})")
      dut.phead.logic.iRobHeadValidIn #= false
    }
  }

  // DIRECTED REPRO of the cross-line store-after-load drain bug. A cross-line LONG
  // store (slot A low line / slot B high line), AFTER a cross-line LOAD to the same
  // address, must write BOTH slot A and slot B through to backing memory.
  def presentPreciseAtHead(dut: Dut, robId: Int): Unit = {
    dut.phead.logic.iRobHeadIn #= robId
    dut.phead.logic.iRobHeadValidIn #= true
  }
  test("cross-line store after cross-line load drains BOTH slots", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addr = 0x3FFEL              // long spans 0x3FFE..0x4001 (lines 0x3FF0 / 0x4000)
      // seed both lines so the load refills clean lines
      for (i <- 0 until 16) { mem.pokeByte(0x3FF0L + i, memByte(0x3FF0L + i)); mem.pokeByte(0x4000L + i, memByte(0x4000L + i)) }
      seed(dut, cd, preg = 10, value = addr)
      // initial cross-line store (the seed) of 0x12345678
      seed(dut, cd, preg = 11, value = 0x12345678L)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 1)
      assert(waitStoreAlloc(dut, cd, robId = 1), "seed store alloc")
      presentPreciseAtHead(dut, robId = 1)
      assert(waitCompletion(dut, cd, robId = 1), "seed store acknowledged drain")
      dut.phead.logic.iRobHeadValidIn #= false
      cd.waitSampling(2)
      assert(mem.peekByte(0x3FFEL) == 0x12, s"seed slotA drained: ${mem.peekByte(0x3FFEL).toHexString}")
      assert(mem.peekByte(0x4000L) == 0x56, s"seed slotB drained: ${mem.peekByte(0x4000L).toHexString}")
      // cross-line LOAD to the same address
      issueLoad(dut, cd, basePreg = 10, disp = 0, Size.LONG, pdst = 20, robId = 2)
      assert(waitCompletion(dut, cd, robId = 2), "cross-line load")
      cd.waitSampling(4)
      // cross-line STORE of 0xCAFEBABE to the same address
      seed(dut, cd, preg = 12, value = 0xCAFEBABEL)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 12, Size.LONG, robId = 3)
      assert(waitStoreAlloc(dut, cd, robId = 3), "cross-line store alloc")
      presentPreciseAtHead(dut, robId = 3)
      assert(waitCompletion(dut, cd, robId = 3), "cross-line store acknowledged drain")
      dut.phead.logic.iRobHeadValidIn #= false
      cd.waitSampling(2)
      assert(mem.peekByte(0x3FFEL) == 0xCA, s"slotA write-through dropped: dut=0x${mem.peekByte(0x3FFEL).toHexString} exp=0xca")
      assert(mem.peekByte(0x3FFFL) == 0xFE, s"slotA byte1: dut=0x${mem.peekByte(0x3FFFL).toHexString} exp=0xfe")
      assert(mem.peekByte(0x4000L) == 0xBA, s"slotB byte0: dut=0x${mem.peekByte(0x4000L).toHexString} exp=0xba")
      assert(mem.peekByte(0x4001L) == 0xBE, s"slotB byte1: dut=0x${mem.peekByte(0x4001L).toHexString} exp=0xbe")
    }
  }

  test("flush squashes an uncommitted store (never drains)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x5000L
      for (i <- 0 until 16) mem.pokeByte(base + i, memByte(base + i))
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0x55667788L)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 9)
      assert(waitStoreAlloc(dut, cd, robId = 9), "store alloc")
      // flush before commit -> squashed, never drains
      dut.src.logic.iSqFlush #= true
      cd.waitSampling()
      dut.src.logic.iSqFlush #= false
      cd.waitSampling(12)
      assert(mem.peekByte(base + 0) == memByte(base + 0), "squashed store must not drain")
    }
  }
}
