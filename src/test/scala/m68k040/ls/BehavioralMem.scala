package m68k040.ls

import spinal.core.ClockDomain
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4ReadOnly}
import spinal.lib.sim.SparseMemory
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}

/** COMPATIBILITY SHIM (V1.6). The real implementation lives in `m68k040.sim.AxiMemModel`
  * -- one read engine, one write engine, one protocol checker, one latency model. These
  * names are kept ONLY so the ~60 existing instantiation sites (and `BehavioralMemSpec`)
  * do not have to change in the same commit. New code should use
  * `AxiMemModel.attachFull` / `.attachReadOnly` directly and pass an explicit
  * `AxiMemModelConfig`.
  * The instruction-side program loaders and focused I-cache helpers also use that
  * implementation now. The earlier response-discipline-dependent V1.6b failure family
  * was traced to unguarded front-end run-ahead into SparseMemory's PRNG-filled bytes;
  * finite lock-step images append an explicit `BRA.S -2` guard in the centralized
  * loader. The final consolidation then exposed six deterministic LSU regressions,
  * which were fixed in RTL instead of being masked by the compatibility shim. */
object BehavioralMem {
  val DATA_BITS = AxiMemModel.DATA_BITS
  val BYTES     = AxiMemModel.BYTES
  def axiConfig: Axi4Config = AxiMemModel.axiConfig()
  def decoded(addr: Long): Boolean = AxiMemModel.decoded(addr)
}

class BehavioralMemAgent(axi: Axi4, cd: ClockDomain, sharedMem: SparseMemory = null,
                         injectBusErrors: Boolean = false,
                         // Part 127: full AxiMemModelConfig passthrough so a caller can
                         // give this attach a REALISTIC latency instead of the implicit
                         // zero-latency default. `injectBusErrors` still wins (it is the
                         // pre-existing, widely-used knob); a caller that passes `dcfg`
                         // gets that config with `injectBusErrors` ORed in.
                         dcfg: AxiMemModelConfig = AxiMemModelConfig()) {
  val model = AxiMemModel.attachFull(axi, cd,
    dcfg.copy(injectBusErrors = dcfg.injectBusErrors || injectBusErrors), sharedMem)
  val mem = model.mem
  def pokeByte(addr: Long, value: Int): Unit = model.pokeByte(addr, value)
  def peekByte(addr: Long): Int             = model.peekByte(addr)
  def poke128(addr: Long, data: BigInt): Unit = model.poke128(addr, data)
  def peek128(addr: Long): BigInt             = model.peek128(addr)
  /** Task P4.7: see `AxiMemModel.armWriteFault`. */
  def armWriteFault(addr: Long): Unit = model.armWriteFault(addr)
  /** OPT-IN, DEFAULT-OFF: see `AxiWriteEngine.onByteWrite`. Not calling this leaves the
    * agent byte-for-byte identical to before the hook existed. */
  def setByteWriteObserver(f: (Long, Byte) => Unit): Unit = model.setByteWriteObserver(f)
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
