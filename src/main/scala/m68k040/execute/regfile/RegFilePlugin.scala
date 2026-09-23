package m68k040.execute.regfile

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import scala.collection.mutable.ArrayBuffer

class RegFilePlugin(val spec: RegfileSpec) extends FiberPlugin with RegfileService {
  private case class WriteReq(port: RegFileWritePort, latency: Int, key: Any, priority: Int)
  private val reads    = ArrayBuffer[(RegFileReadPort, Boolean)]()
  private val writeReq = ArrayBuffer[WriteReq]()
  private val bypasses = ArrayBuffer[RegFileBypassPort]()
  private val bypassDeadProbe = ArrayBuffer[Boolean]()

  override def newRead(forceNoBypass: Boolean = false): RegFileReadPort = {
    val p = RegFileReadPort(spec.addressWidth, spec.dataWidth)
    reads += ((p, forceNoBypass)); p
  }
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0): RegFileWritePort = {
    val p = RegFileWritePort(spec.addressWidth, spec.dataWidth)
    writeReq += WriteReq(p, latency, if (sharingKey == null) new Object else sharingKey, priority); p
  }
  override def newBypass(deadProbe: Boolean = false): RegFileBypassPort = {
    val p = RegFileBypassPort(spec.addressWidth, spec.dataWidth)
    bypasses += p; bypassDeadProbe += deadProbe; p
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
    //   0=AluEu0  1=AluEu1  2=BranchEu (+ exception A7 + DebugCtrl, all on IntWbKey)
    //   3=LsEu  4=DivEu
    // which is a DIFFERENT permutation from either of the two lottery draws previously
    // observed, so it has not itself been post-route-measured. The permutation IS a real
    // (if second-order) FMax knob; it is now a deliberate, reproducible one. To sweep it,
    // permute `byKey.values.toSeq` on the line below — nothing else in the design depends
    // on the slot order (phys(0) is only special in that it also carries the reset-time
    // init-zero sweep, which is complete long before any real write can occur).
    //
    // SWEEPING THE PERMUTATION.  Because the order is worth double-digit MHz it is a
    // knob worth measuring, and re-ordering plugin SETUP to get at it would change far
    // more than the slot order.  So it is exposed explicitly instead:
    //
    //   -DprfSlotPerm.Int=2,0,1,3,4,5      (JVM system property; also accepts an env
    //   PRF_SLOT_PERM_INT=2,0,1,3,4,5       var, which survives sbt/Makefile layers)
    //
    // The value is a permutation of 0..N-1 in FIRST-APPEARANCE order, so for the Int RF
    // (0=AluEu0 1=AluEu1 2=BranchEu 3=LsEu 4=DivEu 5=excA7) the identity is 0,1,2,3,4,5
    // and that is the DEFAULT -- absent the knob, nothing changes.
    //
    // It is validated as a true permutation of exactly the right length and fails
    // elaboration otherwise: a typo'd sweep that silently DROPPED a write port would
    // corrupt the register file rather than just measure slower, and every entry here
    // is a distinct physical port whose loss is invisible in a timing report.
    val slotPerm: Option[Seq[Int]] = {
      val key = spec.name.capitalize
      val raw = Option(System.getProperty(s"prfSlotPerm.$key"))
        .orElse(sys.env.get(s"PRF_SLOT_PERM_${spec.name.toUpperCase}"))
        .map(_.trim).filter(_.nonEmpty)
      raw.map { txt =>
        val idx = txt.split(",").map(_.trim).filter(_.nonEmpty).map { t =>
          try t.toInt catch { case _: NumberFormatException =>
            SpinalError(s"RegFile ${spec.name}: slot permutation '$txt' is not a comma-separated integer list") }
        }.toSeq
        idx
      }
    }
    val phys = {
      // kept inside a block (not an Area member) so the private WriteReq type does not
      // escape the plugin's scope through the anonymous Area's inferred type
      val byKey = scala.collection.mutable.LinkedHashMap[Any, ArrayBuffer[WriteReq]]()
      for (r <- writeReq) byKey.getOrElseUpdate(r.key, ArrayBuffer[WriteReq]()) += r
      val ordered = byKey.values.toSeq
      slotPerm match {
        case None => ordered
        case Some(idx) =>
          if (idx.sorted != ordered.indices.toSeq) SpinalError(
            s"RegFile ${spec.name}: slot permutation [${idx.mkString(",")}] is not a permutation of " +
            s"0..${ordered.size - 1} -- it must list EVERY physical write port exactly once, or ports " +
            s"would be dropped or duplicated (silent register-file corruption, not a slow build)")
          println(s"[RegFile ${spec.name}] physical write-port slot permutation: ${idx.mkString(",")}")
          idx.map(ordered)
      }
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
      // SHARING A KEY IS A PROMISE OF SAME-CYCLE EXCLUSIVITY, AND THIS CHECKS IT.
      //
      // The fold above is a PRIORITY MUX: if two requests in one group are valid in the
      // same cycle, the lower-priority one is not stalled, not retried and not reported
      // -- it is silently DROPPED. A dropped write leaves the destination physical
      // register holding a stale value, and since rename guarantees each in-flight
      // writer owns a distinct pdst, nothing downstream can ever notice. It surfaces
      // much later as a wrong operand with no trace back to here.
      //
      // The existing groups claim exclusivity STRUCTURALLY (AluEu's fast-S1 vs slow-S3
      // share `wbKey`, kept apart by the `fastAcceptNextPort` look-ahead). That claim
      // was never checked. Check it, so the claim is validated by every simulation and
      // lock-step run instead of being re-argued from comments each time -- and so that
      // any FUTURE merge (the 6-port -> fewer-port work) is provable rather than a
      // plausibility argument about how often two writers can collide.
      if (grp.size > 1) GenerationFlags.simulation {
        assert(CountOne(Vec(valids)) <= 1,
          s"RegFile ${spec.name}: two writers sharing one physical port were valid in the " +
          s"same cycle -- the lower-priority write was SILENTLY DROPPED. The group's " +
          s"exclusivity guarantee is broken.", FAILURE)
      }
      bus
    }

    val ram = Mem(Bits(spec.dataWidth bits), spec.depth)

    // sim-only debug: expose the merged physical write buses (bf3c bring-up)
    val dbgW = Vec(phys.map { w =>
      val b = RegFileWritePort(spec.addressWidth, spec.dataWidth)
      b.valid := w.valid; b.address := w.address; b.data := w.data; b
    })
    spinal.core.sim.SimPublic(dbgW)
    // sim-only debug: the bypass ports (EU same-cycle result forwarding into the read
    // ports), so a whitebox can tell whether a read was served from `ram` or a bypass.
    val dbgByp = Vec(bypasses.map { b =>
      val c = RegFileBypassPort(spec.addressWidth, spec.dataWidth)
      c.valid := b.valid; c.address := b.address; c.data := b.data; c
    })
    spinal.core.sim.SimPublic(dbgByp)

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

    // ── Read path: hit-vector + BALANCED masked-OR, not a linear priority fold ───────
    //
    // The previous form was `bypasses.foldLeft(rfData)(Mux(hit, b.data, acc))`: a LINEAR
    // chain, so N bypass sources cost N mux levels on the REGISTER-READ path, which is
    // hot. The int file has 6 sources, so 6 levels of pure serial mux.
    //
    // The priority that fold implements is not needed. This file's own precondition (see
    // the merge note above) is that every in-flight writer owns a DISTINCT physical
    // register -- rename's unique-pdst allocation guarantees it, and the multi-write
    // lowering already depends on it. So AT MOST ONE source can match a given read
    // address, which makes a masked OR-reduce exactly equivalent -- and balanced, so the
    // depth is one compare + log2(N) + a final mux instead of N.
    //
    // The sim assert turns that precondition into a CHECKED invariant rather than a
    // comment: if two sources ever matched one address the OR would silently merge two
    // values into garbage -- the same silent-corruption class the multi-write lowering
    // warns about, which is exactly why it is worth asserting rather than assuming.
    // Sim-only liveness probe, one Bool per bypass source, ORed across every read port.
    // Each bypass source costs an address comparator plus a mux input in EVERY operand
    // read, and that mux is on the core's tightest datapath (operand read -> AluEu
    // s1Src2, 10 logic levels, 0.080 ns slack on the routed 200 MHz build). So knowing
    // WHICH sources actually carry traffic is a direct lever on forwarding depth.
    // Zero synth impact: declared inside GenerationFlags.simulation.
    val bypLive = GenerationFlags.simulation {
      val v = Vec(Bool(), bypasses.size)
      v.foreach(_ := False)
      v.foreach(_.allowOverride)
      v.foreach(_.simPublic())
      v
    }
    val bypHitAcc = Array.fill(bypasses.size)(ArrayBuffer[Bool]())
    for ((r, noByp) <- reads) {
      val rfData = ram.readAsync(r.addr)
      if (noByp || bypasses.isEmpty) {
        r.data := rfData
      } else {
        val hits    = bypasses.map(b => b.valid && b.address === r.addr)
        val anyHit  = hits.reduceBalancedTree(_ || _)
        val bypData = bypasses.zip(hits).map { case (b, h) => b.data.andMask(h) }.reduceBalancedTree(_ | _)
        r.data := Mux(anyHit, bypData, rfData)
        GenerationFlags.simulation {
          for ((h, i) <- hits.zipWithIndex) bypHitAcc(i) += h
          for ((h, i) <- hits.zipWithIndex if bypassDeadProbe(i)) {
            assert(!h,
              s"RegFile ${spec.name}: bypass source #$i is marked deadProbe but HIT a " +
              "read address -- it is load-bearing after all; do NOT delete it", FAILURE)
          }
          assert(CountOne(hits) <= 1,
            s"RegFile ${spec.name}: two bypass sources matched ONE read address -- the " +
            "distinct-physical-register precondition is broken and the OR-reduce would " +
            "merge two values into garbage", FAILURE)
        }
      }
    }
    GenerationFlags.simulation {
      for (i <- bypasses.indices if bypHitAcc(i).nonEmpty) {
        bypLive(i) := bypHitAcc(i).reduceBalancedTree(_ || _)
      }
    }
  }
}

class RegFilePluginInt  extends RegFilePlugin(RegfileSpec.Int)  with IntRegFileService
class RegFilePluginNzvc extends RegFilePlugin(RegfileSpec.Nzvc) with NzvcRegFileService
class RegFilePluginX    extends RegFilePlugin(RegfileSpec.X)    with XRegFileService
