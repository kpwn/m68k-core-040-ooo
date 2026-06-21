package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.lib._

case class IbEntry() extends Bundle {
  val word = Bits(16 bits)
  val pred = ChunkPredecode()
}

class InstructionBuffer extends Component {
  // Depth-2 fetch (VARIANT 1): the buffer must absorb up to TWO in-flight 4-word
  // windows (8 words) on top of the live head. Bumped 12 -> 16 so a 2nd outstanding
  // fetch can be issued without the IBuf back-pressuring the aligner head. HEAD_WORDS
  // (the aligner visibility window) is unchanged at 10.
  val BUF_WORDS = 16
  val HEAD_WORDS = 10

  val io = new Bundle {
    val push = slave(Stream(new Bundle {
      val words = Vec(Bits(16 bits), 4)
      val preds = Vec(ChunkPredecode(), 4)
      val n     = UInt(3 bits)
    }))
    val head     = out Vec(Bits(16 bits), HEAD_WORDS)
    val headPred = out Vec(ChunkPredecode(), HEAD_WORDS)
    val avail    = out UInt(4 bits)
    val shift    = in  UInt(4 bits)
    val flush    = in  Bool()
    // Raw occupancy (depth-2 issue throttle: FetchAlign reserves landing space for ALL
    // outstanding windows, not just the single push, so 2 in-flight responses cannot
    // overflow the buffer). avail caps at HEAD_WORDS, so it cannot serve this purpose.
    val cnt      = out UInt(log2Up(BUF_WORDS + 1) bits)
  }

  // Registers. `entries` are RegInit'd to a benign zero/non-simple value: SpinalSim
  // randomizes uninit Regs per-seed, and a `head(i)` read of a slot that count
  // claims valid (e.g. a transient during refill) would otherwise return seed-random
  // garbage -> flaky lock-step. Deterministic reset makes the buffer seed-stable.
  val entries = Vec.fill(BUF_WORDS) {
    val e = Reg(IbEntry())
    e.word init 0
    e.pred.simple init False
    e.pred.lenWords init 0
    e
  }
  val count   = Reg(UInt(log2Up(BUF_WORDS + 1) bits)) init 0
  spinal.core.sim.SimPublic(count)

  // Push ready: accept when there's room for a full window.
  // NB: use a width-extending add (+^) — `count` is only wide enough to hold
  // BUF_WORDS (4 bits, max 15), so a plain `count + 4` OVERFLOWS at count=12
  // (12+4=16 wraps to 0) and spuriously reports ready while the buffer is FULL,
  // which then fires a push that wraps `count` back down (corrupting the head).
  // This only surfaces when the buffer fills to BUF_WORDS under sustained
  // back-pressure (e.g. the post-mispredict rename freelist re-init stall).
  io.push.ready := (count +^ 4 <= BUF_WORDS)

  // Compute next state combinationally
  val afterShift = count - io.shift   // shift <= count by construction

  // Build next entries as a Vec of signals (not registers)
  val entriesNext = Vec(IbEntry(), BUF_WORDS)

  // Default: shift-down the existing entries
  val idxW = log2Up(BUF_WORDS + 1)   // wide enough to represent BUF_WORDS itself
  for (i <- 0 until BUF_WORDS) {
    // Width the index to idxW so `< BUF_WORDS` is NOT an out-of-range constant (for
    // small i the raw width could not reach BUF_WORDS, which SpinalHDL flags).
    val srcIdx = U(i, idxW bits) + io.shift.resize(idxW)
    when(srcIdx < BUF_WORDS) {
      entriesNext(i) := entries(srcIdx.resize(log2Up(BUF_WORDS)))
    } .otherwise {
      entriesNext(i).word := 0
      entriesNext(i).pred.simple   := False
      entriesNext(i).pred.lenWords := 0
    }
  }

  // If push fires, overwrite slots [afterShift .. afterShift+n-1] with incoming words
  val countNext = UInt(log2Up(BUF_WORDS + 1) bits)
  when(io.push.fire) {
    for (j <- 0 until 4) {
      val dst = (afterShift + j).resize(idxW)
      when(U(j) < io.push.payload.n) {
        when(dst < BUF_WORDS) {
          // Resize the dynamic write index to the Vec address width. With BUF_WORDS=16
          // `dst` is wider than log2Up(BUF_WORDS)=4 bits (afterShift is 5-bit), which
          // SpinalHDL rejects for a Vec WRITE access; the `dst < BUF_WORDS` guard makes
          // the truncation lossless (when in range dst fits in 4 bits exactly).
          val dstIx = dst.resize(log2Up(BUF_WORDS))
          entriesNext(dstIx).word         := io.push.payload.words(j)
          entriesNext(dstIx).pred.simple  := io.push.payload.preds(j).simple
          entriesNext(dstIx).pred.lenWords := io.push.payload.preds(j).lenWords
        }
      }
    }
    countNext := afterShift + io.push.payload.n.resize(log2Up(BUF_WORDS + 1))
  } .otherwise {
    countNext := afterShift
  }

  // Register the next values; flush overrides count to 0
  when(io.flush) {
    count := 0
  } .otherwise {
    count := countNext
  }

  for (i <- 0 until BUF_WORDS) {
    when(!io.flush) {
      entries(i) := entriesNext(i)
    }
  }

  // Combinational outputs
  for (i <- 0 until HEAD_WORDS) {
    when(U(i) < count) {
      io.head(i)          := entries(i).word
      io.headPred(i)      := entries(i).pred
    } .otherwise {
      io.head(i)          := 0
      io.headPred(i).simple   := False
      io.headPred(i).lenWords := 0
    }
  }

  io.avail := (count > HEAD_WORDS).mux(
    U(HEAD_WORDS, 4 bits),
    count.resize(4)
  )
  io.cnt := count
}
