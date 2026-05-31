package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.lib._

case class IbEntry() extends Bundle {
  val word = Bits(16 bits)
  val pred = ChunkPredecode()
}

class InstructionBuffer extends Component {
  val BUF_WORDS = 12
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
  }

  // Registers
  val entries = Vec(Reg(IbEntry()), BUF_WORDS)
  val count   = Reg(UInt(log2Up(BUF_WORDS + 1) bits)) init 0

  // Push ready: accept when there's room for a full window
  io.push.ready := (count + 4 <= BUF_WORDS)

  // Compute next state combinationally
  val afterShift = count - io.shift   // shift <= count by construction

  // Build next entries as a Vec of signals (not registers)
  val entriesNext = Vec(IbEntry(), BUF_WORDS)

  // Default: shift-down the existing entries
  for (i <- 0 until BUF_WORDS) {
    val srcIdx = i + io.shift   // UInt arithmetic
    when(srcIdx < BUF_WORDS) {
      entriesNext(i) := entries(srcIdx)
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
      val dst = afterShift + j
      when(U(j) < io.push.payload.n) {
        when(dst < BUF_WORDS) {
          entriesNext(dst).word         := io.push.payload.words(j)
          entriesNext(dst).pred.simple  := io.push.payload.preds(j).simple
          entriesNext(dst).pred.lenWords := io.push.payload.preds(j).lenWords
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
}
