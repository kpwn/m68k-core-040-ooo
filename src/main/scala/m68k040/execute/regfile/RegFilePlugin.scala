package m68k040.execute.regfile

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import scala.collection.mutable.ArrayBuffer

class RegFilePlugin(val spec: RegfileSpec) extends FiberPlugin with RegfileService {
  private case class WriteReq(port: RegFileWritePort, latency: Int, key: Any, priority: Int)
  private val reads    = ArrayBuffer[(RegFileReadPort, Boolean)]()
  private val writeReq = ArrayBuffer[WriteReq]()
  private val bypasses = ArrayBuffer[RegFileBypassPort]()

  override def newRead(forceNoBypass: Boolean = false): RegFileReadPort = {
    val p = RegFileReadPort(spec.addressWidth, spec.dataWidth)
    reads += ((p, forceNoBypass)); p
  }
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0): RegFileWritePort = {
    val p = RegFileWritePort(spec.addressWidth, spec.dataWidth)
    writeReq += WriteReq(p, latency, if (sharingKey == null) new Object else sharingKey, priority); p
  }
  override def newBypass(): RegFileBypassPort = {
    val p = RegFileBypassPort(spec.addressWidth, spec.dataWidth)
    bypasses += p; p
  }

  val logic = during build new Area {
    assert(writeReq.nonEmpty, s"RegFile ${spec.name}: at least one write port required (for init)")
    // PRECONDITION (silent-corruption class): the multi-write Mem lowers to XOR/LVT
    // banks (m68k040.hw.MultiPortWritesSymplifier), which require that NO TWO PHYSICAL
    // write ports write the SAME address in the SAME cycle. Same-`sharingKey` requests
    // merge into ONE physical port here (safe). DIFFERENT-key requests become DIFFERENT
    // physical ports — callers MUST guarantee they never target the same physical
    // register in one cycle (held by rename's unique-pdst allocation: each in-flight
    // writer owns a distinct phys reg). Violating this corrupts silently.
    //
    // Merge write requests sharing a key into one physical write port; within a
    // group the highest-priority valid request wins.
    //
    // ELABORATION DETERMINISM (do NOT reintroduce `groupBy` here): `groupBy` returns an
    // immutable HashMap, whose `.values` iteration order is a function of the KEYS'
    // hashCodes. The default sharing key is `new Object` (see `newWrite` above) whose
    // hashCode is the JVM IDENTITY hash — not stable across JVM runs. That made the
    // physical write-port slot order (`logic_phys_0..N`, and hence the whole XOR/LVT
    // multi-write bank structure and every generated `_zz_RegFilePlugin*_logic_phys_*`
    // name) a per-run lottery: two elaborations of byte-identical Scala source produced
    // two distinct netlists, differing ONLY in this permutation (286 lines of a ~700k
    // line file, 100% of them `_zz_RegFilePlugin{Int,Nzvc,X}_logic_phys_*`), and those
    // two netlists implemented to post-route FMax 12.7-17.8 MHz apart — larger than any
    // single RTL lever in the FMax campaign, and enough to invalidate any A/B gate.
    // See scratchpad reports `fmax-final-combined-gate-report.md` and
    // `fmax-leverb-task-report.md`.
    //
    // Group in FIRST-APPEARANCE order instead: `writeReq` is an ArrayBuffer appended in
    // plugin-setup order, so the resulting slot order is a stable function of the source.
    // LinkedHashMap still hashes for lookup but iterates in insertion order.
    //
    // The resulting Int-RF slot order is
    //   0=AluEu0  1=AluEu1  2=BranchEu  3=LsEu  4=DivEu  5=RobPlugin exception ("excA7")
    // which is a DIFFERENT permutation from either of the two lottery draws previously
    // observed, so it has not itself been post-route-measured. The permutation IS a real
    // (if second-order) FMax knob; it is now a deliberate, reproducible one. To sweep it,
    // permute `byKey.values.toSeq` on the line below — nothing else in the design depends
    // on the slot order (phys(0) is only special in that it also carries the reset-time
    // init-zero sweep, which is complete long before any real write can occur).
    val phys = {
      // kept inside a block (not an Area member) so the private WriteReq type does not
      // escape the plugin's scope through the anonymous Area's inferred type
      val byKey = scala.collection.mutable.LinkedHashMap[Any, ArrayBuffer[WriteReq]]()
      for (r <- writeReq) byKey.getOrElseUpdate(r.key, ArrayBuffer[WriteReq]()) += r
      byKey.values.toSeq
    }.map { grp =>
      // sort descending by priority so element 0 is the highest priority
      val sorted = grp.sortBy(-_.priority)
      val bus    = RegFileWritePort(spec.addressWidth, spec.dataWidth)
      val valids = sorted.map(_.port.valid)
      val anyValid = valids.reduce(_ || _)
      // foldRight over the descending-priority list: the accumulator starts at the
      // lowest-priority end and each higher-priority valid request overrides it, so the
      // highest-priority valid request wins the address/data selection.
      bus.valid   := anyValid
      bus.address := sorted.foldRight(U(0, spec.addressWidth bits)) { case (r, acc) => Mux(r.port.valid, r.port.address, acc) }
      bus.data    := sorted.foldRight(B(0, spec.dataWidth bits))    { case (r, acc) => Mux(r.port.valid, r.port.data, acc) }
      bus
    }

    val ram = Mem(Bits(spec.dataWidth bits), spec.depth)

    // sim-only debug: expose the merged physical write buses (bf3c bring-up)
    val dbgW = Vec(phys.map { w =>
      val b = RegFileWritePort(spec.addressWidth, spec.dataWidth)
      b.valid := w.valid; b.address := w.address; b.data := w.data; b
    })
    spinal.core.sim.SimPublic(dbgW)

    // init-zero boot sweep: write 0 to every address through physical write 0
    // before normal operation (no fetch happens until the first redirect).
    // Counter counts 0..depth (inclusive); init writes addresses 0..depth-1
    // only (gated by initDone) so no out-of-range write occurs.
    val initCounter = Reg(UInt(log2Up(spec.depth + 1) bits)) init 0
    val initDone    = initCounter === U(spec.depth)
    when(!initDone) { initCounter := initCounter + 1 }

    for ((w, i) <- phys.zipWithIndex) {
      if (i == 0) {
        ram.write(
          address = Mux(initDone, w.address, initCounter.resized),
          data    = Mux(initDone, w.data, B(0, spec.dataWidth bits)),
          enable  = !initDone || w.valid)
      } else {
        ram.write(w.address, w.data, enable = w.valid && initDone)
      }
    }

    // ── SIM-ONLY whitebox shadow of `ram` ────────────────────────────────────
    // `ram` is NOT readable from a simulation: every full-core build routes through
    // M68kSpinalConfig, which installs m68k040.hw.MultiPortWritesSymplifier, and that
    // rewrites a multi-write Mem out of the netlist entirely into XOR/LVT banks -- so
    // there is no `ram` handle left for `getBigInt` to reach. RobPlugin.scala hit the
    // identical wall for its `payload` Mem and solved it the same way.
    //
    // This mirrors the ram.write loop above STATEMENT FOR STATEMENT (same enables, same
    // address/data muxes, same last-writer-wins ordering), so the shadow and the real
    // Mem cannot drift: any future change to the write structure that is not mirrored
    // here shows up as a lock-step FP divergence, not as a silently stale shadow.
    //
    // Elaborated ONLY when the `simulation` generation flag is set (M68kSim sets it);
    // `shadow` is null in every synth / GenVerilog build => zero synthesis cost. Any
    // unguarded reference from RTL would therefore be an immediate NPE at elaboration,
    // not a silent area cost -- which is exactly what the GenFullCoreSynthVerilog gate
    // checks for.
    val shadow = GenerationFlags.simulation {
      val v = Vec.fill(spec.depth)(Reg(Bits(spec.dataWidth bits)) init 0)
      for ((w, i) <- phys.zipWithIndex) {
        if (i == 0) {
          when(!initDone || w.valid) {
            v(Mux(initDone, w.address, initCounter.resize(spec.addressWidth))) :=
              Mux(initDone, w.data, B(0, spec.dataWidth bits))
          }
        } else {
          when(w.valid && initDone) { v(w.address) := w.data }
        }
      }
      spinal.core.sim.SimPublic(v)
      v
    }

    for ((r, noByp) <- reads) {
      val rfData = ram.readAsync(r.addr)
      if (noByp || bypasses.isEmpty) {
        r.data := rfData
      } else {
        // a bypass hit on the read address overrides RF data
        r.data := bypasses.foldLeft(rfData) { case (acc, b) =>
          Mux(b.valid && b.address === r.addr, b.data, acc)
        }
      }
    }
  }
}

class RegFilePluginInt  extends RegFilePlugin(RegfileSpec.Int)  with IntRegFileService
class RegFilePluginNzvc extends RegFilePlugin(RegfileSpec.Nzvc) with NzvcRegFileService
class RegFilePluginX    extends RegFilePlugin(RegfileSpec.X)    with XRegFileService
