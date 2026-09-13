package m68k040.cache

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Pins the SAFETY invariants of the straddle side table.
  *
  * The whole token scheme rests on one asymmetry: a stale/recycled entry must yield
  * NO length (-> live re-classify, today's behaviour), never a WRONG length. These
  * tests exist to keep that true, because the failure they guard against is silent
  * -- a wrong length mis-frames the instruction stream and executes wrong bytes.
  */
class StraddleTableSpec extends AnyFunSuite {
  class Dut extends Component {
    val io = new Bundle {
      val doAlloc   = in Bool()
      val aWay      = in UInt (2 bits); val aSet = in UInt (6 bits)
      val aWord     = in UInt (5 bits); val aLen = in UInt (4 bits)
      val qIdx      = in UInt (4 bits)
      val qWay      = in UInt (2 bits); val qSet = in UInt (6 bits)
      val qWord     = in UInt (5 bits)
      val hit       = out Bool(); val len = out UInt (4 bits)
      val kHit      = out Bool(); val kLen = out UInt (4 bits)
      val allocIdx  = out UInt (4 bits)
      val doKillWS  = in Bool(); val doKillAll = in Bool()
    }
    val t = StraddleTable(entries = 16, wayBits = 2, setBits = 6, wordBits = 5)
    io.allocIdx := t.allocPtr
    when(io.doAlloc)   { t.allocate(io.aWay, io.aSet, io.aWord, io.aLen) }
    when(io.doKillWS)  { t.killWaySet(io.aWay, io.aSet) }
    when(io.doKillAll) { t.killAll() }
    val (h, l) = t.lookup(io.qIdx, io.qWay, io.qSet, io.qWord)
    io.hit := h; io.len := l
    val (kh, kl) = t.lookupByKey(io.qWay, io.qSet, io.qWord)
    io.kHit := kh; io.kLen := kl
  }

  def run(body: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.doAlloc #= false; dut.io.doKillWS #= false; dut.io.doKillAll #= false
    dut.io.aWay #= 0; dut.io.aSet #= 0; dut.io.aWord #= 0; dut.io.aLen #= 0
    dut.io.qIdx #= 0; dut.io.qWay #= 0; dut.io.qSet #= 0; dut.io.qWord #= 0
    dut.clockDomain.waitSampling(2)
    body(dut)
  }

  def alloc(dut: Dut, way: Int, set: Int, word: Int, len: Int): Int = {
    val idx = dut.io.allocIdx.toInt
    dut.io.aWay #= way; dut.io.aSet #= set; dut.io.aWord #= word; dut.io.aLen #= len
    dut.io.doAlloc #= true; dut.clockDomain.waitSampling(); dut.io.doAlloc #= false
    dut.clockDomain.waitSampling(); idx
  }
  def probeKey(dut: Dut, way: Int, set: Int, word: Int): (Boolean, Int) = {
    dut.io.qWay #= way; dut.io.qSet #= set; dut.io.qWord #= word
    sleep(1); (dut.io.kHit.toBoolean, dut.io.kLen.toInt)
  }
  def probe(dut: Dut, idx: Int, way: Int, set: Int, word: Int): (Boolean, Int) = {
    dut.io.qIdx #= idx; dut.io.qWay #= way; dut.io.qSet #= set; dut.io.qWord #= word
    sleep(1); (dut.io.hit.toBoolean, dut.io.len.toInt)
  }

  test("reset: nothing is valid, so every probe misses", VerilatorTest) { run { dut =>
    for (i <- 0 until 16) assert(!probe(dut, i, 0, 0, 0)._1, s"entry $i valid out of reset")
  }}

  test("allocate then read back with the SAME owner hits and returns the length", VerilatorTest) { run { dut =>
    val i = alloc(dut, way = 2, set = 33, word = 17, len = 5)
    val (h, l) = probe(dut, i, 2, 33, 17)
    assert(h, "matching owner must hit"); assert(l == 5, s"len $l != 5")
  }}

  test("ANY owner mismatch misses -- way, set, or word", VerilatorTest) { run { dut =>
    val i = alloc(dut, way = 1, set = 9, word = 4, len = 7)
    assert(!probe(dut, i, 0, 9, 4)._1, "wrong way must miss")
    assert(!probe(dut, i, 1, 8, 4)._1, "wrong set must miss")
    assert(!probe(dut, i, 1, 9, 5)._1, "wrong word must miss")
    assert(probe(dut, i, 1, 9, 4)._1,  "control: exact owner still hits")
  }}

  test("RECYCLING an entry makes the old token miss, not return a stale length", VerilatorTest) { run { dut =>
    // The dangerous case: entry K reallocated to a different line while an old
    // token still points at K. It must MISS, not hand back the previous length.
    val i = alloc(dut, way = 0, set = 5, word = 2, len = 3)
    for (_ <- 0 until 16) alloc(dut, way = 3, set = 60, word = 31, len = 9)  // wrap allocPtr
    val (h, l) = probe(dut, i, 0, 5, 2)
    assert(!h, s"recycled entry must miss for the old owner (got len $l)")
  }}

  test("killWaySet drops only that {way,set}", VerilatorTest) { run { dut =>
    val a = alloc(dut, way = 1, set = 7, word = 1, len = 2)
    val b = alloc(dut, way = 1, set = 8, word = 1, len = 6)
    dut.io.aWay #= 1; dut.io.aSet #= 7
    dut.io.doKillWS #= true; dut.clockDomain.waitSampling(); dut.io.doKillWS #= false
    dut.clockDomain.waitSampling()
    assert(!probe(dut, a, 1, 7, 1)._1, "refilled {way,set} entry must be dropped")
    assert(probe(dut, b, 1, 8, 1)._1,  "a different set must survive")
  }}

  test("lookupByKey: resolves by ADDRESS with no token, and misses on any mismatch", VerilatorTest) { run { dut =>
    alloc(dut, way = 2, set = 40, word = 29, len = 6)
    assert(probeKey(dut, 2, 40, 29) == (true, 6), "exact key must hit with its length")
    assert(!probeKey(dut, 3, 40, 29)._1, "wrong way must miss")
    assert(!probeKey(dut, 2, 41, 29)._1, "wrong set must miss")
    assert(!probeKey(dut, 2, 40, 28)._1, "wrong word must miss")
  }}

  test("re-allocating the SAME key replaces, never duplicates", VerilatorTest) { run { dut =>
    // lookupByKey OR-reduces the matching lengths, so two live entries on one key
    // would hand back a corrupt mixture. allocate() must invalidate the old one.
    alloc(dut, way = 1, set = 3, word = 7, len = 4)
    alloc(dut, way = 1, set = 3, word = 7, len = 9)
    val (h, l) = probeKey(dut, 1, 3, 7)
    assert(h, "re-allocated key must still hit")
    assert(l == 9, s"must be the NEW length, not a mix: got $l (4|9 would be 13)")
  }}

  test("killAll drops everything (CINV / CPUSHA)", VerilatorTest) { run { dut =>
    val a = alloc(dut, way = 0, set = 1, word = 1, len = 4)
    val b = alloc(dut, way = 3, set = 2, word = 2, len = 8)
    dut.io.doKillAll #= true; dut.clockDomain.waitSampling(); dut.io.doKillAll #= false
    dut.clockDomain.waitSampling()
    assert(!probe(dut, a, 0, 1, 1)._1 && !probe(dut, b, 3, 2, 2)._1, "invalidateAll must clear the table")
  }}
}
