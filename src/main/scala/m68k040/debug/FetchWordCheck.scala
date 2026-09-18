package m68k040.debug

import m68k040.services.DecodeFeedService
import spinal.core._

/** Passive observer only: no assignments into the producer or execution controls. */
class FetchWordCheck(feed: DecodeFeedService, cpuReset: Bool) extends Area {
  val enabled = RegInit(False)
  val pc = Reg(UInt(32 bits)) init 0
  val expected = Reg(Bits(16 bits)) init 0
  val clear = Bool(); clear := False
  val hit = RegInit(False)
  val slot = RegInit(False)
  val hitPc = Reg(UInt(32 bits)) init 0
  val hitWord = Reg(Bits(32 bits)) init 0
  val seen = Reg(UInt(32 bits)) init 0

  // The wide equality logic only sees local registers, never the hot feed path.
  val validQ = Vec.fill(2)(RegInit(False))
  val pcQ = Vec.fill(2)(Reg(UInt(32 bits)) init 0)
  val wordQ = Vec.fill(2)(Reg(Bits(16 bits)) init 0)
  for (i <- 0 until 2) {
    val accepted = feed.feed.fire && (if (i == 0) True else feed.slot1Valid) &&
      !feed.feed.payload(i).fault
    validQ(i) := accepted && enabled && !cpuReset && !clear
    when(accepted) {
      pcQ(i) := feed.feed.payload(i).pc
      wordQ(i) := feed.feed.payload(i).words(0)
    }
  }
  val selected = Vec((0 until 2).map(i => validQ(i) && pcQ(i) === pc && enabled))
  val bad = Vec((0 until 2).map(i => selected(i) && wordQ(i) =/= expected))
  val sum = seen.resize(33) + selected(0).asUInt.resize(33) + selected(1).asUInt.resize(33)
  seen := Mux(sum.msb, U(0xffffffffL, 32 bits), sum.resize(32))
  when(!hit && bad.asBits.orR) {
    hit := True
    slot := !bad(0)
    hitPc := Mux(bad(0), pcQ(0), pcQ(1))
    hitWord := expected ## Mux(bad(0), wordQ(0), wordQ(1))
  }
  when(clear || cpuReset) {
    hit := False; slot := False; hitPc := 0; hitWord := 0; seen := 0
    validQ.foreach(_ := False)
  }
}
