package m68k040.ls

import spinal.core.ClockDomain
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4ReadOnly}
import spinal.lib.sim.SparseMemory
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}

/** COMPATIBILITY SHIM (V1.6, D-SIDE ONLY -- see task-mshr-V1.6-report.md for the
  * full split rationale). The real implementation now lives in `m68k040.sim.AxiMemModel`
  * -- one read engine, one write engine, one protocol checker, one latency model. These
  * names are kept ONLY so the ~60 existing instantiation sites (and `BehavioralMemSpec`)
  * do not have to change in the same commit. New code should use
  * `AxiMemModel.attachFull` / `.attachReadOnly` directly and pass an explicit
  * `AxiMemModelConfig`.
  *
  * NOTE: this is a DELIBERATELY PARTIAL landing of the V1.6 consolidation. The other
  * half -- collapsing the four `attachProgram` clones (`ExecuteLockStepSpec`,
  * `FuzzDut`, `IpcBenchSpec`) and the three inline `Axi4ReadOnlySlaveAgent`s in
  * `ExecuteLockStepSpec` onto `AxiMemModel` -- is NOT included here. That I-side
  * substitution swaps the STOCK SpinalHDL `Axi4ReadOnlySlaveAgent` (random cross-burst
  * response reordering, `withArReordering`/`withReadInterleaveInBurst` both default
  * true) for `AxiReadEngine` (strict in-order, no interleave), and was PROVEN via a
  * three-way decisive experiment to expose a genuine, pre-existing, timing-sensitive
  * RTL race in the multi-access/RMW store-retirement path -- NOT a harness artifact:
  *
  *   1. A trivial D-side-only perturbation (`bQueueDepth` 4->5, this file, pristine
  *      tree) leaves `ExecuteLockStepSpec` byte-identical to baseline over 3 runs.
  *   2. A trivial I-side-only perturbation that keeps the STOCK agent but adds a
  *      1-cycle `baseLatency` (no discipline/algorithm change) ALSO leaves it
  *      byte-identical to baseline over 3 runs (one run's single extra failure was the
  *      already-tracked flaky `IRQ: NMI (level 7) through mask 7` test, not a new one).
  *   3. Only the actual STOCK-agent -> `AxiReadEngine` SWAP (same D-side, I-side
  *      changed) reproducibly adds 4-10 extra failures every run, always the same
  *      family: bit-field-to-memory (BFCHG/BFCLR/BFSET/BFINS mem), CAS/CAS2,
  *      ABCD/SBCD-mem, MOVES, cross-line MOVE.L stores -- precisely the tracked "A3
  *      multi-access restartability" structural gap.
  *
  * This rules out "any AXI timing change trips the lock-step gate" (1 and 2 falsify
  * that) and narrows the trigger to the response-ordering DISCIPLINE swap specifically
  * inherent to the I-side consolidation. Deferred as "V1.6b" until that gap is fixed or
  * explicitly quarantined -- see the report for the full writeup. */
object BehavioralMem {
  val DATA_BITS = AxiMemModel.DATA_BITS
  val BYTES     = AxiMemModel.BYTES
  def axiConfig: Axi4Config = AxiMemModel.axiConfig()
  def decoded(addr: Long): Boolean = AxiMemModel.decoded(addr)
}

class BehavioralMemAgent(axi: Axi4, cd: ClockDomain, sharedMem: SparseMemory = null,
                         injectBusErrors: Boolean = false) {
  val model = AxiMemModel.attachFull(axi, cd,
    AxiMemModelConfig(injectBusErrors = injectBusErrors), sharedMem)
  val mem = model.mem
  def pokeByte(addr: Long, value: Int): Unit = model.pokeByte(addr, value)
  def peekByte(addr: Long): Int             = model.peekByte(addr)
  def poke128(addr: Long, data: BigInt): Unit = model.poke128(addr, data)
  def peek128(addr: Long): BigInt             = model.peek128(addr)
}

class Axi4ReadOnlyBehavioralAgent(axi: Axi4ReadOnly, cd: ClockDomain,
                                  sharedMem: SparseMemory = null,
                                  injectBusErrors: Boolean = false) {
  val model = AxiMemModel.attachReadOnly(axi, cd,
    AxiMemModelConfig(injectBusErrors = injectBusErrors), sharedMem)
  val mem = model.mem
  def pokeByte(addr: Long, value: Int): Unit = model.pokeByte(addr, value)
  def peekByte(addr: Long): Int             = model.peekByte(addr)
}
