package m68k040.top

import m68k040.execute.iq.{IssueQueueService, IqContext}
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, XRegFileService,
  RegFileReadPort, RegFileWritePort, RegFileBypassPort}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesis-only: registers the IssueQueue's IO so an OOC run measures the
  * internal reg→reg paths (compaction, wakeup trigger-clear, age-select). */
class IqSynthProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val iq = host[IssueQueueService]

    val pushValidIn = in Bool ()
    val push0In     = in(IqContext())
    val push1In     = in(IqContext())
    val slot1In     = in Bool ()
    val flushIn     = in Bool ()
    val ready0In    = in Bool ()
    val ready1In    = in Bool ()
    val ready2In    = in Bool ()
    val ready3In    = in Bool ()
    val ready4In    = in Bool ()
    val fastAccept0In = in Bool ()
    val fastAccept1In = in Bool ()

    iq.push.valid      := RegNext(pushValidIn) init False
    iq.push.payload(0) := RegNext(push0In)
    iq.push.payload(1) := RegNext(push1In)
    iq.pushSlot1Valid  := RegNext(slot1In) init False
    iq.flushPort       := RegNext(flushIn) init False
    iq.issue(0).ready  := RegNext(ready0In) init False
    iq.issue(1).ready  := RegNext(ready1In) init False
    iq.issue(2).ready  := RegNext(ready2In) init False
    iq.issue(3).ready  := RegNext(ready3In) init False
    iq.issue(4).ready  := RegNext(ready4In) init False
    // Preserve the forecast-gated fast candidate cones in the OOC netlist. The
    // standalone probe has no ALU pipeline, so these are explicit registered IO.
    iq.aluFastAcceptNext(0) := RegNext(fastAccept0In) init False
    iq.aluFastAcceptNext(1) := RegNext(fastAccept1In) init False

    val pushReadyOut = out(RegNext(iq.push.ready) init False)
    val issue0Valid  = out(RegNext(iq.issue(0).valid) init False)
    val issue0Rob    = out(RegNext(iq.issue(0).payload.robId))
    val issue1Valid  = out(RegNext(iq.issue(1).valid) init False)
    val issue1Rob    = out(RegNext(iq.issue(1).payload.robId))
    val issue2Valid  = out(RegNext(iq.issue(2).valid) init False)
    val issue2Rob    = out(RegNext(iq.issue(2).payload.robId))
    val issue3Valid  = out(RegNext(iq.issue(3).valid) init False)
    val issue3Rob    = out(RegNext(iq.issue(3).payload.robId))
    val issue4Valid  = out(RegNext(iq.issue(4).valid) init False)
    val issue4Rob    = out(RegNext(iq.issue(4).payload.robId))
  }
}

/** Synthesis-only: allocates a representative PRF port set (int + NZVC + X) in
  * setup and registers its IO, so an OOC run measures the RF read/bypass/LVT-mux
  * + write-merge paths reg→reg. */
class PrfSynthProbePlugin extends FiberPlugin {
  // PORT COUNTS ARE SWEEPABLE so the cost of a physical write port can be MEASURED
  // rather than argued.  Under the LVT lowering (dataWidth >= 10, so the int RF) every
  // physical write port is a FULL COPY of the register file, which is why the port
  // count -- not the depth -- dominates PRF area.
  //
  //   PRF_PROBE_INT_WRITES=6 PRF_PROBE_INT_READS=8   (the REAL core's int RF shape:
  //                                                   6 write ports, 8 read ports)
  //
  // Defaults stay 2/2, the shape this gate has always measured, so historical
  // M68kPrfSynth numbers remain comparable.  Sweep, do not redefine the baseline.
  private def envInt(name: String, dflt: Int): Int =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).map { v =>
      val n = try v.toInt catch { case _: NumberFormatException =>
        SpinalError(s"$name must be an integer, got '$v'") }
      if (n < 1) SpinalError(s"$name must be >= 1, got $n")
      n
    }.getOrElse(dflt)
  val nIntWrites = envInt("PRF_PROBE_INT_WRITES", 2)
  val nIntReads  = envInt("PRF_PROBE_INT_READS", 2)

  var iReads: Seq[RegFileReadPort] = null
  var iWrites: Seq[RegFileWritePort] = null
  var iB0: RegFileBypassPort = null
  var nR: RegFileReadPort = null
  var nW: RegFileWritePort = null
  var xW: RegFileWritePort = null

  during setup {
    val irf = host[IntRegFileService]
    iReads = Seq.fill(nIntReads)(irf.newRead())
    // Each write gets a DISTINCT sharing key, so each becomes its own PHYSICAL port --
    // that is the thing being measured.  (iW0 keeps the priority=1 shared-key form the
    // original harness used, so the 2-port default is bit-for-bit the old gate.)
    val k = new Object
    iWrites = (0 until nIntWrites).map { i =>
      if (i == 0) irf.newWrite(latency = 1, sharingKey = k, priority = 1)
      else        irf.newWrite(latency = 1)
    }
    iB0 = irf.newBypass()
    val nz = host[NzvcRegFileService]
    nR = nz.newRead(); nW = nz.newWrite(latency = 1)
    val xrf = host[XRegFileService]
    xW = xrf.newWrite(latency = 1)
  }

  val logic = during build new Area {
    // int reads -- registered both sides so the measured path is RF read + bypass mux
    val iRData = iReads.zipWithIndex.map { case (r, i) =>
      r.addr := RegNext(in UInt (r.addr.getWidth bits))
      out(RegNext(r.data)).setName(s"iR${i}Data")
    }
    // int writes -- one registered input set per PHYSICAL port
    for (w <- iWrites) {
      w.valid   := RegNext(in Bool ()) init False
      w.address := RegNext(in UInt (w.address.getWidth bits))
      w.data    := RegNext(in Bits (w.data.getWidth bits))
    }
    iB0.valid := RegNext(in Bool ()) init False; iB0.address := RegNext(in UInt (iB0.address.getWidth bits)); iB0.data := RegNext(in Bits (iB0.data.getWidth bits))
    // nzvc
    nR.addr := RegNext(in UInt (nR.addr.getWidth bits))
    val nRData = out(RegNext(nR.data))
    nW.valid := RegNext(in Bool ()) init False; nW.address := RegNext(in UInt (nW.address.getWidth bits)); nW.data := RegNext(in Bits (nW.data.getWidth bits))
    // x (tie idle so its init sweep runs)
    xW.valid := False; xW.address := 0; xW.data := 0
  }
}
