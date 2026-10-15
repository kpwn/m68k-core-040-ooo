package m68k040.top

import m68k040.{M68kParams, M68kSpinalConfig}
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.decode.{DecodeStage, DecodedUop}
import m68k040.frontend.DecodePacket
import m68k040.services._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** OOC probe of the WHOLE shipping DecodeStage (assemble cones, stash, FSMs, microcode
  * sequencer, push mux, MicroOpQueue, side banks) between registered IO: a registered
  * DecodeFeedService source, a registered RAS live snapshot, and every DecodeStage output
  * (uops, preds, RAS snaps, complexResume, FP imm table/trap ports) registered to a port.
  * Consumer-driven inputs (uops.ready, flushes, FP table read/free, debug configure) come
  * from registered top inputs. Shipping knobs (ipcThroughput = true). */
class DecodeFeedProbePlugin extends FiberPlugin with DecodeFeedService with RasSnapshotService {
  private var feedS: Stream[Vec[DecodePacket]] = null
  private var s1v: Bool = null
  private var snap: RasSnapRec = null
  override def feed: Stream[Vec[DecodePacket]] = feedS
  override def slot1Valid: Bool = s1v
  override def snapshotEnabled: Boolean = ShippingCoreConfig.rasBranchSnapshot
  override def liveSnapshot: RasSnapRec = snap
  during setup {
    feedS = Stream(Vec(DecodePacket(), 2)); s1v = Bool(); snap = RasSnapRec()
  }
  val logic = during build new Area {
    val pkts  = in(Vec(DecodePacket(), 2))
    val valid = in(Bool())
    val s1    = in(Bool())
    val sn    = in(RasSnapRec())
    val ready = out(Bool())
    feedS.valid   := RegNext(valid) init False
    feedS.payload := RegNext(pkts)
    s1v           := RegNext(s1)
    snap          := RegNext(sn)
    ready         := RegNext(feedS.ready)
  }
}

class DecodeSinkProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val ds = host[DecodeStage]
    val uopsOut  = out(Vec(DecodedUop(), 2))
    val uopsV    = out(Bool())
    val u1v      = out(Bool())
    val predOut  = out(Vec(BranchPredRec(), 2))
    val snapOut  = out(Vec(RasSnapRec(), 2))
    val resume   = out(Flow(UInt(32 bits)))
    val fpRd     = out(Bits(80 bits))
    val trapRd   = out(Bits(80 bits))
    val trapV    = out(Bool())
    val skipC    = out(Bits(4 bits))
    uopsOut := RegNext(ds.uops.payload); uopsV := RegNext(ds.uops.valid)
    u1v := RegNext(ds.uop1Valid)
    predOut := RegNext(ds.uopPred); snapOut := RegNext(ds.uopRasSnap)
    resume := RegNext(ds.complexResume)
    fpRd := RegNext(ds.fpImmRdData); trapRd := RegNext(ds.trapImmData); trapV := RegNext(ds.trapImmValid)
    skipC := RegNext(ds.skipConsumed)

    val rdy     = in(Bool())
    val flush   = in(Bool())
    val bflush  = in(Bool())
    val fpAddr  = in(UInt(m68k040.Global.FP_IMM_TAG_W bits))
    val trAddr  = in(UInt(m68k040.Global.FP_IMM_TAG_W bits))
    val free    = in(Flow(UInt(m68k040.Global.FP_IMM_TAG_W bits)))
    val dbgPcs  = in(Vec(UInt(32 bits), 4))
    val dbgEn   = in(Bits(4 bits))
    val dbgSkip = in(Bits(4 bits))
    ds.uops.ready := RegNext(rdy)
    ds.pipeFlush := RegNext(flush)
    ds.backendFlush := RegNext(bflush)
    ds.fpImmRdAddr := RegNext(fpAddr)
    ds.trapImmAddr := RegNext(trAddr)
    ds.fpImmFree.valid := RegNext(free.valid); ds.fpImmFree.payload := RegNext(free.payload)
    ds.configure(RegNext(dbgPcs), RegNext(dbgEn), RegNext(dbgSkip))
  }
}

object GenDecodeSynthVerilog {
  def main(args: Array[String]): Unit = {
    val name = "DecodeSynth" + args.headOption.getOrElse("").capitalize
    val p = M68kParams()
    M68kSpinalConfig(targetDirectory = "generated").generateVerilog(new M68kCore(Seq(
      new ParamPlugin(p), new DecodeFeedProbePlugin, ShippingPlugins.decode(ipcThroughput = true),
      new DecodeSinkProbePlugin)).setDefinitionName(name))
    println(s"Generated generated/$name.v immCone=${ShippingCoreConfig.immCone}")
  }
}
