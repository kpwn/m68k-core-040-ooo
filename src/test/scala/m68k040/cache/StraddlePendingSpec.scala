package m68k040.cache

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Pins the pending-record behaviour, in particular the two places where a sloppy
  * implementation would silently produce a WRONG resolution rather than none:
  * successor matching by exact line identity (not "adjacent set", which aliases
  * every 64th line), and eviction killing speculation about a line that is gone.
  */
class StraddlePendingSpec extends AnyFunSuite {
  class Dut extends Component {
    val io = new Bundle {
      val doAlloc = in Bool()
      val aWay = in UInt (2 bits); val aSet = in UInt (6 bits); val aKey = in UInt (26 bits)
      val aT0 = in Bits (16 bits); val aT1 = in Bits (16 bits); val aT2 = in Bits (16 bits)
      val qKey = in UInt (26 bits)
      val hit  = out Bool()
      val hWay = out UInt (2 bits); val hSet = out UInt (6 bits)
      val hT0  = out Bits (16 bits); val hT2 = out Bits (16 bits)
      val doClear = in Bool(); val doKillWS = in Bool(); val doKillAll = in Bool()
    }
    val p = StraddlePending(records = 4, wayBits = 2, setBits = 6, lineKeyBits = 26)
    when(io.doAlloc) { p.allocate(io.aWay, io.aSet, io.aKey, io.aT0, io.aT1, io.aT2) }
    val hits = p.successorHits(io.qKey)
    io.hit  := hits.asBits.orR
    io.hWay := p.selectU(hits, i => p.way(i))
    io.hSet := p.selectU(hits, i => p.set(i))
    io.hT0  := p.selectB(hits, i => p.tail(i)(0))
    io.hT2  := p.selectB(hits, i => p.tail(i)(2))
    when(io.doClear)   { p.clear(hits) }
    when(io.doKillWS)  { p.killWaySet(io.aWay, io.aSet) }
    when(io.doKillAll) { p.killAll() }
  }

  def run(body: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.doAlloc #= false; dut.io.doClear #= false
    dut.io.doKillWS #= false; dut.io.doKillAll #= false
    dut.io.aWay #= 0; dut.io.aSet #= 0; dut.io.aKey #= 0
    dut.io.aT0 #= 0; dut.io.aT1 #= 0; dut.io.aT2 #= 0; dut.io.qKey #= 0
    dut.clockDomain.waitSampling(2); body(dut)
  }
  def alloc(dut: Dut, way: Int, set: Int, key: Int, t: (Int, Int, Int)): Unit = {
    dut.io.aWay #= way; dut.io.aSet #= set; dut.io.aKey #= key
    dut.io.aT0 #= t._1; dut.io.aT1 #= t._2; dut.io.aT2 #= t._3
    dut.io.doAlloc #= true; dut.clockDomain.waitSampling(); dut.io.doAlloc #= false
    dut.clockDomain.waitSampling()
  }
  def probe(dut: Dut, key: Int): Boolean = { dut.io.qKey #= key; sleep(1); dut.io.hit.toBoolean }

  test("the SUCCESSOR line matches; the line itself and unrelated lines do not", VerilatorTest) { run { dut =>
    alloc(dut, 1, 5, key = 0x1000, t = (0x4E71, 0x203C, 0xFFFF))
    assert(probe(dut, 0x1001), "successor (key+1) must match")
    assert(!probe(dut, 0x1000), "the line itself must not match")
    assert(!probe(dut, 0x1002), "key+2 must not match")
    assert(!probe(dut, 0x0FFF), "the predecessor must not match")
  }}

  test("match is by exact line identity, NOT adjacent set (no 64th-line aliasing)", VerilatorTest) { run { dut =>
    // Same set index, different line: keys 0x1000 and 0x1040 both map to set 0 in a
    // 64-set cache. A set-based successor test would wrongly fire here.
    alloc(dut, 0, 0, key = 0x1000, t = (1, 2, 3))
    assert(!probe(dut, 0x1041), "a same-set line 64 lines away must NOT match")
    assert(probe(dut, 0x1001),  "control: the true successor still matches")
  }}

  test("a matched record yields ITS way/set and tail words for resolution", VerilatorTest) { run { dut =>
    alloc(dut, 3, 41, key = 0x2222, t = (0xAAAA, 0xBBBB, 0xCCCC))
    dut.io.qKey #= 0x2223; sleep(1)
    assert(dut.io.hit.toBoolean)
    assert(dut.io.hWay.toInt == 3 && dut.io.hSet.toInt == 41, "location must come from the record")
    assert(dut.io.hT0.toInt == 0xAAAA && dut.io.hT2.toInt == 0xCCCC, "tail words must survive")
  }}

  test("clear retires the matched record so it resolves only once", VerilatorTest) { run { dut =>
    alloc(dut, 1, 1, key = 0x30, t = (1, 2, 3))
    dut.io.qKey #= 0x31; sleep(1); assert(dut.io.hit.toBoolean)
    dut.io.doClear #= true; dut.clockDomain.waitSampling(); dut.io.doClear #= false
    dut.clockDomain.waitSampling()
    assert(!probe(dut, 0x31), "a cleared record must not match again")
  }}

  test("eviction kills speculation: killWaySet and killAll drop pending records", VerilatorTest) { run { dut =>
    alloc(dut, 2, 9, key = 0x50, t = (1, 2, 3))
    dut.io.aWay #= 2; dut.io.aSet #= 9
    dut.io.doKillWS #= true; dut.clockDomain.waitSampling(); dut.io.doKillWS #= false
    dut.clockDomain.waitSampling()
    assert(!probe(dut, 0x51), "a refill of that {way,set} must drop the pending record")
    alloc(dut, 0, 0, key = 0x60, t = (4, 5, 6))
    dut.io.doKillAll #= true; dut.clockDomain.waitSampling(); dut.io.doKillAll #= false
    dut.clockDomain.waitSampling()
    assert(!probe(dut, 0x61), "CINV/CPUSHA must drop pending records too")
  }}

  test("re-allocating the same lineKey replaces, never leaves two matches", VerilatorTest) { run { dut =>
    alloc(dut, 0, 0, key = 0x70, t = (0x1111, 0, 0))
    alloc(dut, 1, 2, key = 0x70, t = (0x9999, 0, 0))
    dut.io.qKey #= 0x71; sleep(1)
    assert(dut.io.hit.toBoolean, "still matches")
    assert(dut.io.hT0.toInt == 0x9999, s"must be the NEW record, not 0x1111|0x9999 = ${0x1111|0x9999}")
    assert(dut.io.hWay.toInt == 1 && dut.io.hSet.toInt == 2, "and its location")
  }}
}
