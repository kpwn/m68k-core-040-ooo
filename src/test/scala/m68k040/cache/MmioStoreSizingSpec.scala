package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import m68k040.socket.MmioCover
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "MMIO sizing", the store half, plus the "touches exactly the registers
  * the access names, each exactly once, and no others" obligation -- checked against a
  * byte-write observer rather than a final memory image, so a byte written TWICE is caught.
  *
  * VerilatorTest-tagged to match DcacheSpec (whose DUT this mirrors). The 48-case cover
  * proof itself is in the untagged m68k040.socket.MmioCoverSpec. */
class MmioStoreSizingSpec extends AnyFunSuite {

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val probe  = new DcacheProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe)) }
  }

  lazy val compiled = M68kSim().withVerilator.compile(new Dut)
  private val BASE = 0x0400_0000L

  /** SpinalHDL sim-poke gotcha, same root cause and same fix as
    * `MmioLoadSizingSpec.quiesceUnusedPorts` (also documented in
    * `DcacheSpec.initDut`/`initDutErrInject`): an unpoked testbench-driven input field is
    * NOT guaranteed 0 across seeds/runs. This suite's own `store()` helper only pokes
    * `storeIn` and never touches `loadCmdIn`/`loadProbeIn`/`loadProbeCancelIn`/
    * `maintCmdIn`, so without this all four can power up with a stray `valid` and garbage
    * payload during the initial settle window, before this suite's own first explicit
    * command -- confirmed concretely on this exact prescribed test code (Step 2's
    * verify-fails run): a garbage-valid `maintCmdIn` at seed=1 launched a real maintenance
    * writeback with a garbage address BEFORE the suite's own first store, producing a
    * spurious extra AW/W the D30 cover-length assertion caught as a wrong sub-transaction
    * count. Not part of the feature under test; pinning every one of these ports idle is
    * required for every test in this file to be deterministic across seeds, mirroring the
    * sibling `MmioLoadSizingSpec`'s already-reviewed fix for this identical
    * DUT/plugin pairing. */
  private def quiesceUnusedPorts(dut: Dut): Unit = {
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    dut.probe.logic.storeIn.valid #= false
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
  }

  /** Every AW/W pair the D-cache presented, as (address, AxSIZE, WSTRB), in issue order.
    *
    * AXI4's AW and W channels are only weakly ordered: a compliant subordinate may accept
    * either one first (concretely reproduced against `AxiMemModel` here -- W regularly
    * completes several cycles before AW's own `ready` lands, since the two are arbitrated
    * independently). Correlating "the W that completed right after an already-accepted AW"
    * therefore silently drops the pair whenever W wins the race, which happens routinely,
    * not as a corner case. The store side is single-outstanding by construction (the next
    * sub-transaction's AW/W is not issued until this one's B lands, per the D30 sequencer),
    * so there is never more than one unmatched AW or W in flight at a time -- tracking each
    * channel's own latch independently and emitting the moment BOTH have latched (regardless
    * of which completed first) is therefore both correct and sufficient. */
  private def watchAw(dut: Dut) = {
    val log = scala.collection.mutable.ArrayBuffer[(Long, Int, BigInt)]()
    val axi = dut.dcache.logic.axi
    var haveAw = false; var awAddr = -1L; var awSize = -1
    var haveW  = false; var wStrb  = BigInt(0)
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (axi.aw.valid.toBoolean && axi.aw.ready.toBoolean) {
          awAddr = axi.aw.payload.addr.toLong; awSize = axi.aw.payload.size.toInt; haveAw = true
        }
        if (axi.w.valid.toBoolean && axi.w.ready.toBoolean) {
          wStrb = axi.w.payload.strb.toBigInt; haveW = true
        }
        if (haveAw && haveW) {
          log += ((awAddr, awSize, wStrb))
          haveAw = false; haveW = false
        }
      }
    }
    log
  }

  private def store(dut: Dut, paddr: Long, data: BigInt, size: SpinalEnumElement[Size.type],
                    useStrb: Boolean = false, strb: BigInt = 0, lineData: BigInt = 0): Unit = {
    val p = dut.probe.logic
    p.storeIn.valid #= true
    p.storeIn.payload.paddr #= paddr
    p.storeIn.payload.data  #= data
    p.storeIn.payload.size  #= size
    p.storeIn.payload.useStrb #= useStrb
    p.storeIn.payload.strb    #= strb
    p.storeIn.payload.lineData #= lineData
    p.storeIn.payload.cacheMode #= CacheMode.INHIBITED
    p.storeIn.payload.precise #= false
    dut.clockDomain.waitSamplingWhere(p.storeIn.ready.toBoolean && p.storeIn.valid.toBoolean)
    p.storeIn.valid #= false
    dut.clockDomain.waitSampling(40)
  }

  test("D30: every INHIBITED store emits an exact naturally-aligned cover", VerilatorTest) {
    compiled.doSim("mmio-store-cover", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      val log = watchAw(dut)
      // Every byte the model actually commits, so a byte written TWICE is caught.
      val writes = scala.collection.mutable.ArrayBuffer[Long]()
      mem.setByteWriteObserver((a, _) => writes += a)

      for (off <- 0 until 16; (sz, nm, n) <- Seq((Size.BYTE, "BYTE", 1),
                                                 (Size.WORD, "WORD", 2),
                                                 (Size.LONG, "LONG", 4))) {
        val lineBase = BASE + 0x100L * (off * 3 + n)
        log.clear(); writes.clear()
        store(dut, lineBase + off, BigInt("11223344", 16), sz)

        val want = MmioCover.model(off, math.min(off + n, 16))
        assert(log.length == want.length,
          s"$nm off=$off: emitted ${log.length} sub-transactions ${log.toList}, cover says $want")
        assert(log.length <= MmioCover.MAX_SUBS)
        for (((gotAddr, gotSz, gotStrb), (wantOff, wantBytes)) <- log.zip(want)) {
          assert(gotAddr == lineBase + wantOff,
            f"$nm off=$off: address 0x$gotAddr%08X, wanted 0x${lineBase + wantOff}%08X")
          assert((1 << gotSz) == wantBytes, s"$nm off=$off: AxSIZE $gotSz, wanted $wantBytes B")
          assert(gotAddr % (1 << gotSz) == 0, s"$nm off=$off: AW not naturally aligned")
          // Spec 3.3.1: "every asserted WSTRB bit lies inside the addressed transfer" --
          // the correction v1 fails (axi_narrow_to_wide.v:43 lists 0110 as an awsize=1 case
          // and then clears address bit 0, asserting a strobe bit OUTSIDE the transfer).
          val hot = (0 until 16).filter(i => ((gotStrb >> i) & 1) == 1)
          assert(hot.nonEmpty, s"$nm off=$off: a sub-transaction with an empty strobe")
          assert(hot.forall(i => i >= wantOff && i < wantOff + wantBytes),
            s"$nm off=$off: WSTRB bits $hot lie outside the addressed transfer " +
            s"[$wantOff,${wantOff + wantBytes})")
        }
        // Exactly the architectural bytes were committed, each exactly ONCE.
        val wantBytesAbs = (off until math.min(off + n, 16)).map(lineBase + _).toList
        assert(writes.sorted.toList == wantBytesAbs,
          s"$nm off=$off: committed ${writes.sorted.toList}, wanted $wantBytesAbs")
      }
    }
  }

  test("D26: a useStrb split slot derives its range from the STROBE, not from size", VerilatorTest) {
    compiled.doSim("mmio-store-usestrb", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      val log = watchAw(dut)
      val writes = scala.collection.mutable.ArrayBuffer[Long]()
      mem.setByteWriteObserver((a, _) => writes += a)

      // Exactly the StoreQueue slot-B shape: paddr = the next LINE BASE (so paddr[3:0] = 0)
      // and size = a flat LONG (StoreQueue.scala:264) whatever the true extent. The strobe
      // is the ONLY field carrying the truth.
      for (spill <- 1 to 3) {
        val lineBase = BASE + 0x2000L + 0x100L * spill
        val strb = (0 until spill).foldLeft(BigInt(0))((a, i) => a | (BigInt(1) << i))
        log.clear(); writes.clear()
        store(dut, lineBase, 0, Size.LONG, useStrb = true, strb = strb,
              lineData = BigInt("A5A5A5A5", 16))
        val want = MmioCover.model(0, spill)
        assert(log.length == want.length,
          s"spill=$spill: ${log.length} sub-transactions ${log.toList}, cover says $want")
        val committed = writes.sorted.map(_ - lineBase).toList
        assert(committed == (0 until spill).toList,
          s"spill=$spill: committed $committed -- a size-derived range would give " +
          s"${(0 until 4).toList}, naming ${4 - spill} register(s) the access never touched")
      }
    }
  }

  test("storeAck fires ONCE, after the LAST B of a multi-sub-transaction store", VerilatorTest) {
    compiled.doSim("mmio-store-single-ack", seed = 3) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      var acks = 0
      var bs   = 0
      val axi = dut.dcache.logic.axi
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.dcache.logic.storeAckReg.toBoolean) acks += 1
          if (axi.b.valid.toBoolean && axi.b.ready.toBoolean) bs += 1
        }
      }
      // off=1, LONG -> the three-sub-transaction worst case (1@1, 2@2, 1@4).
      store(dut, BASE + 0x3000L + 1, BigInt("DEADBEEF", 16), Size.LONG)
      dut.clockDomain.waitSampling(40)
      assert(bs == 3, s"expected 3 B responses for the off=1/LONG cover, saw $bs")
      assert(acks == 1, s"storeAck pulsed $acks times -- the SQ pops its head on this pulse " +
        s"(sq.io.drainAck := dcache.storeAck), so anything but 1 is a real corruption")
    }
  }

  test("the CACHEABLE write-through path is unchanged: one 16-byte AW", VerilatorTest) {
    compiled.doSim("store-cacheable-unchanged", seed = 4) { dut =>
      dut.clockDomain.forkStimulus(10)
      quiesceUnusedPorts(dut)
      AxiMemModel.attachFull(dut.dcache.logic.axi, dut.clockDomain,
        AxiMemModelConfig(crossbarSingleOutstanding = true, checkIdUnique = false))
      dut.clockDomain.waitSampling(8)
      val log = watchAw(dut)
      val p = dut.probe.logic
      for (off <- Seq(0, 1, 5, 13)) {
        log.clear()
        p.storeIn.valid #= true
        p.storeIn.payload.paddr #= BASE + 0x4000L + off
        p.storeIn.payload.data  #= BigInt("CAFE", 16)
        p.storeIn.payload.size  #= Size.WORD
        p.storeIn.payload.useStrb #= false
        p.storeIn.payload.strb #= 0
        p.storeIn.payload.lineData #= 0
        p.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        p.storeIn.payload.precise #= false
        dut.clockDomain.waitSamplingWhere(p.storeIn.ready.toBoolean && p.storeIn.valid.toBoolean)
        p.storeIn.valid #= false
        dut.clockDomain.waitSampling(40)
        assert(log.length == 1, s"cacheable off=$off emitted ${log.length} AWs")
        assert(log.head._2 == 4, s"cacheable AxSIZE ${log.head._2}, wanted 4")
        assert(log.head._1 % 16 == 0, "cacheable AW not at the 16-byte line base")
      }
    }
  }
}
