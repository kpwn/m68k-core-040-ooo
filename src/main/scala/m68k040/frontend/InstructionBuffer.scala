package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.lib._

case class IbEntry() extends Bundle {
  val word = Bits(16 bits)
  val pred = ChunkPredecode()
}

/** CIRCULAR head/tail ring (LEVER 1): the instruction buffer is a circular ring of
  * BUF_WORDS slots. Entries stay in FIXED physical slots; a `headPtr` marks the
  * physical slot of logical word 0. Logical word `i` lives at physical slot
  * `(headPtr + i) mod BUF_WORDS`.
  *
  *   - SHIFT advances `headPtr` by `io.shift` (mod BUF) and drops `shift` from `count`.
  *   - PUSH writes `n` words at the tail = physical slot `(headPtr + count) mod BUF`
  *     (the tail slot is INDEPENDENT of shift this cycle: after shift the new head is
  *     headPtr+shift, the new tail logical position is count-shift, so the physical
  *     tail = headPtr+shift+(count-shift) = headPtr+count, unchanged), then count
  *     becomes (count - shift + n).
  *   - HEAD outputs are a barrel-rotate of HEAD_WORDS slots (a small `headPtr`-indexed
  *     read), NOT a rebuild of all BUF_WORDS entries.
  *   - FLUSH resets count and the head pointer to 0.
  *
  * This ELIMINATES the per-cycle O(BUF_WORDS) shift mux that was regen2's binding FMax
  * limiter in the shift-register IBuf (entries_0..entries_17 rebuilt combinationally
  * each cycle): only the `n` pushed slots are written each cycle (a small enable-decode),
  * and only HEAD_WORDS slots feed the read rotate.
  *
  * The external interface (head/headPred/avail/cnt/push/shift/flush) is byte-identical
  * to the shift-register version, so FetchAlign/Aligner are unchanged.
  *
  * Depth-3 / push-4 context (adapted from the push-8 reference f1d47a2): the buffer must
  * absorb up to THREE in-flight 4-word windows (12 words) on top of the live head, while
  * the FetchAlign issue reservation (cnt + (ringCount+1)*4 <= BUF_WORDS) keeps the
  * deepest (3rd) outstanding fetch from overflowing. Sized 20 so the 3rd outstanding
  * fetch can issue with the live head still at cnt<=8 (cnt+12<=20). HEAD_WORDS (the
  * aligner visibility window) is unchanged at 10, PUSH stays 4 words (NARROW window).
  */
class InstructionBuffer extends Component {
  val BUF_WORDS  = 20
  val HEAD_WORDS = 10
  val PUSH_WORDS = 4

  val io = new Bundle {
    val push = slave(Stream(new Bundle {
      val words = Vec(Bits(16 bits), PUSH_WORDS)
      val preds = Vec(ChunkPredecode(), PUSH_WORDS)
      val n     = UInt(3 bits)
    }))
    val head     = out Vec(Bits(16 bits), HEAD_WORDS)
    val headPred = out Vec(ChunkPredecode(), HEAD_WORDS)
    val avail    = out UInt(4 bits)
    val shift    = in  UInt(4 bits)
    val flush    = in  Bool()
    // Raw occupancy (depth-3 issue throttle: FetchAlign reserves landing space for ALL
    // outstanding windows, not just the single push, so in-flight responses cannot
    // overflow the buffer). avail caps at HEAD_WORDS, so it cannot serve this purpose.
    val cnt      = out UInt(log2Up(BUF_WORDS + 1) bits)
  }

  val IDXW = log2Up(BUF_WORDS)   // physical slot index width

  // Registers. `entries` are RegInit'd to a benign zero value: SpinalSim randomizes
  // uninit Regs per-seed, and the head barrel-rotate reads HEAD_WORDS slots
  // combinationally (gating the OUT-of-window ones to 0), so an uninit slot that the
  // rotate transiently reads (then muxes away) would otherwise be seed-random garbage
  // -> flaky lock-step. Deterministic reset makes the buffer seed-stable.
  val entries = Vec.fill(BUF_WORDS) {
    val e = Reg(IbEntry())
    e.word init 0
    e.pred.simple init False
    e.pred.lenWords init 0
    e.pred.ambiguousLine init False
    // FMax Lever B: deterministic reset value, same reasoning as the fields above (an
    // uninitialised Reg randomises per-seed in sim and makes lock-step flaky). BYTE, not
    // LONG: `word init 0` pairs it with opword 0x0000 = `ORI.B #imm,D0`, and
    // `OperationDecoder.decode(0x0000).size === Size.BYTE` — so the reset state satisfies
    // the pairing invariant `pred.size === decode(word).size` rather than violating it.
    e.pred.size init m68k040.isa.Size.BYTE
    e
  }
  val count   = Reg(UInt(log2Up(BUF_WORDS + 1) bits)) init 0
  val headPtr = Reg(UInt(IDXW bits)) init 0
  spinal.core.sim.SimPublic(count)

  // Push ready: accept when there's room for a full window.
  // NB: use a width-extending add (+^) so a near-full `count + PUSH_WORDS` does not
  // overflow the `count` width and spuriously report ready while the buffer is FULL
  // (which then fires a push that wraps `count` back down, corrupting the head).
  io.push.ready := (count +^ PUSH_WORDS <= BUF_WORDS)

  // --- next-state pointers/count (combinational) ---
  val afterShift = count - io.shift   // shift <= count by construction
  val newHeadPtr = UInt(IDXW bits)
  val hpPlusShift = (headPtr +^ io.shift)        // width-extended, may exceed BUF_WORDS
  when(hpPlusShift >= BUF_WORDS) {
    newHeadPtr := (hpPlusShift - BUF_WORDS).resize(IDXW)
  } .otherwise {
    newHeadPtr := hpPlusShift.resize(IDXW)
  }

  // Tail physical slot = (headPtr + count) mod BUF_WORDS. Independent of shift this
  // cycle (see the class comment). Push word j writes physical (tailBase + j) mod BUF.
  val tailBase = UInt(IDXW bits)
  val hpPlusCount = (headPtr +^ count)
  when(hpPlusCount >= BUF_WORDS) {
    tailBase := (hpPlusCount - BUF_WORDS).resize(IDXW)
  } .otherwise {
    tailBase := hpPlusCount.resize(IDXW)
  }

  // --- write the (up to PUSH_WORDS) pushed entries into their tail slots ---
  // Each physical slot is written iff a push fires AND some pushed word j maps to it.
  // We compute, per slot, the matching j (if any) and its enable. The push window is
  // contiguous starting at tailBase, so at most one j hits each slot.
  for (s <- 0 until BUF_WORDS) {
    when(io.push.fire) {
      for (j <- 0 until PUSH_WORDS) {
        // physical slot for pushed word j = (tailBase + j) mod BUF_WORDS
        val phys = UInt(IDXW bits)
        val tb = (tailBase +^ j)
        when(tb >= BUF_WORDS) {
          phys := (tb - BUF_WORDS).resize(IDXW)
        } .otherwise {
          phys := tb.resize(IDXW)
        }
        when((U(j) < io.push.payload.n) && (phys === s)) {
          entries(s).word                := io.push.payload.words(j)
          entries(s).pred.simple         := io.push.payload.preds(j).simple
          entries(s).pred.lenWords       := io.push.payload.preds(j).lenWords
          entries(s).pred.ambiguousLine  := io.push.payload.preds(j).ambiguousLine
          entries(s).pred.size           := io.push.payload.preds(j).size   // FMax Lever B
          entries(s).pred.ctrlXfer       := io.push.payload.preds(j).ctrlXfer
        }
      }
    }
  }

  // --- count / headPtr next ---
  when(io.flush) {
    count   := 0
    headPtr := 0
  } .otherwise {
    when(io.push.fire) {
      count := afterShift + io.push.payload.n.resize(log2Up(BUF_WORDS + 1))
    } .otherwise {
      count := afterShift
    }
    headPtr := newHeadPtr
  }

  // --- combinational head outputs: barrel-rotate of HEAD_WORDS slots ---
  for (i <- 0 until HEAD_WORDS) {
    // physical slot for logical word i = (headPtr + i) mod BUF_WORDS
    val phys = UInt(IDXW bits)
    val hp = (headPtr +^ i)
    when(hp >= BUF_WORDS) {
      phys := (hp - BUF_WORDS).resize(IDXW)
    } .otherwise {
      phys := hp.resize(IDXW)
    }
    when(U(i) < count) {
      io.head(i)     := entries(phys).word
      io.headPred(i) := entries(phys).pred
    } .otherwise {
      io.head(i)                     := 0
      io.headPred(i).simple          := False
      io.headPred(i).lenWords        := 0
      io.headPred(i).ambiguousLine   := False
      // FMax Lever B: this default is LOAD-BEARING and must be BYTE, not LONG. It pairs
      // with the co-located `io.head(i) := 0` above, and the invariant every consumer
      // relies on is `headPred(i).size === OperationDecoder.decode(head(i)).size`;
      // `decode(0x0000).size === Size.BYTE` (opword 0x0000 is `ORI.B #imm,D0`).
      io.headPred(i).size            := m68k040.isa.Size.BYTE
      // Control-transfer gate: a beyond-`count` head word does not exist, so it must
      // never enable a prediction. False is also the honest classification of the
      // co-located `io.head(i) := 0` (opword 0x0000 is ORI.B, not a control transfer),
      // so the `headPred(i).ctrlXfer === classify(head(i)).ctrlXfer` invariant holds
      // here exactly as the `size` invariant above does.
      io.headPred(i).ctrlXfer        := False
    }
  }

  io.avail := (count > HEAD_WORDS).mux(
    U(HEAD_WORDS, 4 bits),
    count.resize(4)
  )
  io.cnt := count
}
