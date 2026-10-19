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
class InstructionBuffer(retime: Boolean = false,
                        /** `CPU_IBUF_LUTRAM`: hold the payload ring in four 5-row LUTRAM banks
                          * (see ShippingCoreConfig.ibufLutram). */
                        lutram: Boolean = m68k040.top.ShippingCoreConfig.ibufLutram) extends Component {
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
    // CPU_FE_RETIME only. `headPredRaw(i)` is `headPred(i)` WITHOUT the `i < count` gate
    // (the aligner proves the gate redundant for its reads, see Aligner.align `retime`).
    // `shiftEn/shiftA/shiftB/shiftSelA` restate `shift` as
    //     shift = shiftEn ? (shiftSelA ? shiftA : shiftB) : 0
    // so the head-pointer/count adders run on the two EARLY candidates and the LATE
    // select (`suppressSlot1`) passes through one 2:1 mux. `shift` itself stays an input
    // (the sim oracle below checks the two forms agree every cycle).
    val headPredRaw = if (retime) out(Vec(ChunkPredecode(), HEAD_WORDS)) else null
    val shiftEn     = if (retime) in Bool() else null
    val shiftA      = if (retime) in UInt(4 bits) else null
    val shiftB      = if (retime) in UInt(4 bits) else null
    val shiftSelA   = if (retime) in Bool() else null
  }

  val IDXW = log2Up(BUF_WORDS)   // physical slot index width

  // Occupancy owns payload visibility: a push writes word and metadata together.
  // Keep pointer/count reset and the exact zero/BYTE/false output defaults below;
  // unused physical slots themselves need no reset (docs/ibuf-payload-reset.md).
  val entries = if (lutram) null else Vec.fill(BUF_WORDS)(Reg(IbEntry()))
  val count   = Reg(UInt(log2Up(BUF_WORDS + 1) bits)) init 0
  val headPtr = Reg(UInt(IDXW bits)) init 0
  spinal.core.sim.SimPublic(count)

  // Push ready: accept when there's room for a full window.
  // NB: use a width-extending add (+^) so a near-full `count + PUSH_WORDS` does not
  // overflow the `count` width and spuriously report ready while the buffer is FULL
  // (which then fires a push that wraps `count` back down, corrupting the head).
  // CPU_FE_EXACT_RESV: FetchAlign reserves landing space per window by its ACTUAL word
  // count, so a push is only guaranteed to fit its own `n` words (never more than the
  // fetch-issue reservation, which already bounds every landing push).
  io.push.ready := (if (m68k040.top.ShippingCoreConfig.feExactResv) (count +^ io.push.payload.n <= BUF_WORDS)
                    else (count +^ PUSH_WORDS <= BUF_WORDS))

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
  // The push window is contiguous starting at tailBase, so at most one j hits each slot.
  //
  // FMax/area (docs/ibuf-write-decode.md): the address is decoded ONCE, not once per
  // (slot, word) pair. The previous form computed `(tailBase + j) mod BUF_WORDS` inside
  // the per-slot loop, which is independent of `s`, so it emitted EIGHTY copies of that
  // add + compare-and-subtract wrap (`_zz_when_InstructionBuffer_l125` ..`_79`), each
  // with its own 5-bit equality against a constant slot index. Because the four write
  // targets are consecutive,
  //
  //     (tailBase + j) mod BUF_WORDS === s   <=>   tailBase === (s - j) mod BUF_WORDS
  //
  // and `s`/`j` are Scala loop constants, so `(s - j) mod BUF_WORDS` is a compile-time
  // index into a SINGLE one-hot decode of `tailBase`. The per-word rotation is pure
  // wiring. `tailBase` is already `< BUF_WORDS` by its own wrap above, so the one-hot is
  // exact over the entire legal range. Same write set, same data, same cycle.
  val tailOneHot = UIntToOh(tailBase, BUF_WORDS)
  val pushLenOk  = Vec(Bool(), PUSH_WORDS)
  for (j <- 0 until PUSH_WORDS) pushLenOk(j) := U(j) < io.push.payload.n
  if (!lutram) for (s <- 0 until BUF_WORDS) {
    when(io.push.fire) {
      for (j <- 0 until PUSH_WORDS) {
        when(tailOneHot(((s - j) + BUF_WORDS) % BUF_WORDS) && pushLenOk(j)) {
          entries(s).word                := io.push.payload.words(j)
          entries(s).pred.lenWords       := io.push.payload.preds(j).lenWords
          entries(s).pred.ambiguousLine  := io.push.payload.preds(j).ambiguousLine
          entries(s).pred.size           := io.push.payload.preds(j).size   // FMax Lever B
          entries(s).pred.ctrlXfer       := io.push.payload.preds(j).ctrlXfer
        }
      }
    }
  }

  // --- count / headPtr next ---
  if (!retime) {
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
  } else {
    // Candidate next-states: each adder/wrap runs on an early shift candidate; the late
    // select is the LAST mux before the flop. Identical to the form above whenever
    // `shift === (shiftEn ? (shiftSelA ? shiftA : shiftB) : 0)` (asserted below).
    def wrapAdd(a: UInt): UInt = {
      val r = UInt(IDXW bits)
      val s = headPtr +^ a
      when(s >= BUF_WORDS) { r := (s - BUF_WORDS).resize(IDXW) } otherwise { r := s.resize(IDXW) }
      r
    }
    val cw     = log2Up(BUF_WORDS + 1)
    val pushN  = Mux(io.push.fire, io.push.payload.n.resize(cw), U(0, cw bits))
    val base   = count + pushN
    val hpA    = wrapAdd(io.shiftA)
    val hpB    = wrapAdd(io.shiftB)
    val cntA   = base - io.shiftA.resize(cw)
    val cntB   = base - io.shiftB.resize(cw)
    when(io.flush) {
      count   := 0
      headPtr := 0
    } .elsewhen(io.shiftEn) {
      count   := Mux(io.shiftSelA, cntA, cntB)
      headPtr := Mux(io.shiftSelA, hpA, hpB)
    } .otherwise {
      count   := base
    }
    GenerationFlags.simulation {
      val ref = Mux(io.shiftEn, Mux(io.shiftSelA, io.shiftA, io.shiftB), U(0, 4 bits))
      assert(io.shift === ref, "InstructionBuffer: retimed shift candidates diverged from io.shift", FAILURE)
    }
  }

  // --- CPU_IBUF_LUTRAM: the same ring as FOUR LUTRAM banks, bank = slot mod 4, row = slot / 4
  // (BUF_WORDS = 20 = 5 rows x 4 banks, so the mod-20 wrap keeps the bank sequence: slot 19
  // is bank 3, slot 0 bank 0). The four pushed words are consecutive slots, so each bank takes
  // at most ONE write per cycle; the HEAD_WORDS window is consecutive slots, so each bank is
  // read at most three consecutive rows (ports k = 0..2). Logical word i = 4k + d sits in bank
  // (head mod 4 + d) mod 4, port k: a 4:1 rotate per output, no 20:1 mux. Row/bank are bit
  // slices of `headPtr`/`tailBase` (slot = 4 * row + bank), so no wrap adders on addresses
  // beyond a 3-bit mod-5 increment. Read-before-write, no reset: identical to the flop ring
  // (unoccupied rows are masked by `i < count` exactly as before).
  val lr = if (!lutram) null else new Area {
    require(BUF_WORDS == 20 && PUSH_WORDS == 4 && HEAD_WORDS <= 12)
    val ROWS = BUF_WORDS / 4
    val banks = Seq.fill(4)(Mem(IbEntry(), ROWS))
    banks.foreach(_.addAttribute("ram_style", "distributed"))
    def rowInc(r: UInt, k: Int): UInt = {
      // (r + k) mod ROWS for a 3-bit r in 0..ROWS-1 and a constant k
      val t = r.resize(4) + U(k, 4 bits)
      Mux(t >= ROWS, t - ROWS, t).resize(3)
    }
    // writes
    val tb   = tailBase(1 downto 0)
    val tRow = tailBase(IDXW - 1 downto 2)
    for (b <- 0 until 4) {
      val jSel = (U(b, 2 bits) - tb)                      // which pushed word lands in bank b
      val rowN = Mux(U(b, 2 bits) < tb, rowInc(tRow, 1), tRow)
      val w    = io.push.payload.words(jSel)
      val pr   = io.push.payload.preds(jSel)
      val e    = IbEntry()
      e.word := w; e.pred := pr
      banks(b).write(rowN, e, io.push.fire && pushLenOk(jSel))
    }
    // reads
    val hb   = headPtr(1 downto 0)
    val hRow = headPtr(IDXW - 1 downto 2)
    val ports = for (b <- 0 until 4) yield {
      val r0 = Mux(U(b, 2 bits) < hb, rowInc(hRow, 1), hRow)
      (0 until 3).map(k => banks(b).readAsync(rowInc(r0, k)))
    }
    def word(i: Int): IbEntry = {
      val d = i % 4; val k = i / 4
      Vec((0 until 4).map(b => ports(b)(k)))(hb + U(d, 2 bits))
    }
  }

  // --- combinational head outputs: barrel-rotate of HEAD_WORDS slots ---
  if (lutram) for (i <- 0 until HEAD_WORDS) {
    val e = lr.word(i)
    when(U(i) < count) {
      io.head(i)     := e.word
      io.headPred(i) := e.pred
    } .otherwise {
      io.head(i)                     := 0
      io.headPred(i).lenWords        := 0
      io.headPred(i).ambiguousLine   := False
      io.headPred(i).size            := m68k040.isa.Size.BYTE
      io.headPred(i).ctrlXfer        := False
    }
    if (retime) io.headPredRaw(i) := e.pred
  }
  if (!lutram) for (i <- 0 until HEAD_WORDS) {
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

  if (retime && !lutram) {
    for (i <- 0 until HEAD_WORDS) {
      val hp = headPtr +^ i
      val phys = UInt(IDXW bits)
      when(hp >= BUF_WORDS) { phys := (hp - BUF_WORDS).resize(IDXW) } otherwise { phys := hp.resize(IDXW) }
      io.headPredRaw(i) := entries(phys).pred
    }
  }

  io.avail := (count > HEAD_WORDS).mux(
    U(HEAD_WORDS, 4 bits),
    count.resize(4)
  )
  io.cnt := count
}
