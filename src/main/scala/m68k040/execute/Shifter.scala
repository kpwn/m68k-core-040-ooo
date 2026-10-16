package m68k040.execute

import m68k040.isa.Size
import spinal.core._

/** Line-E barrel-shifter command. `shiftOp` = tt (0=ASR/ASL, 1=LSR/LSL,
  * 2=ROXR/ROXL, 3=ROR/ROL); `dirLeft` = d (true=left). `count` is the raw shift
  * count: for the immediate form it is 1..8 (the caller maps ccc==0 -> 8); for the
  * register form it is Dc, masked to 6 bits here. `isImm` selects the form. `xIn`
  * is the current X flag (used by ROX and by count-0 X preservation). */
case class ShiftCmd() extends Bundle {
  val shiftOp = UInt(2 bits)
  val dirLeft = Bool()
  val size    = Size()
  val data    = Bits(32 bits)
  val count   = UInt(6 bits)
  val isImm   = Bool()
  val xIn     = Bool()
}

/** Barrel-shifter result + CCR. `result` low `size` bits valid (upper raw; the EU
  * merges). n/z/v/c/xOut are the computed CCR bits. xOut is the X VALUE Musashi
  * leaves (== xIn where X is untouched); the EU's writesX mask (False for ROL/ROR)
  * decides whether it lands. */
case class ShiftRsp() extends Bundle {
  val result = Bits(32 bits)
  val n      = Bool()
  val z      = Bool()
  val v      = Bool()
  val c      = Bool()
  val xOut   = Bool()
}

/** Registered midpoint of the 2-stage barrel shifter. Holds the cmd fields stage 2
  * still needs PLUS every VARIABLE-SHIFT output (result barrel-shifts, the per-op
  * carry-flag 66-bit shifted words pre-bit-8-index, the four rotate words, the ROX
  * ring). Stage 2 does only bit-indexing / compares / muxing on these — no variable
  * shift. Keeps the deep funnel-shifter cone entirely in stage 1. */
case class ShiftStage1() extends Bundle {
  // passthrough cmd fields
  val shiftOp = UInt(2 bits); val dirLeft = Bool(); val size = Size()
  val isImm   = Bool();       val xIn = Bool();     val count = UInt(6 bits)
  // size-derived (cheap, recomputed-or-carried)
  val mask    = UInt(32 bits); val sizeBits = UInt(7 bits)
  val src     = UInt(32 bits); val srcMsb = Bool()
  // ASR
  val asrRes  = UInt(32 bits); val asrCImmWord = UInt(66 bits); val asrCRegWLWord = UInt(66 bits)
  // LSR
  val lsrRes  = UInt(32 bits); val lsrCImmWord = UInt(66 bits); val lsrCRegWLWord = UInt(66 bits)
  // ASL/LSL (left)
  val lshResW = UInt(66 bits)
  val lshCBWord = UInt(66 bits); val lshCWimmWord = UInt(32 bits); val lshCWregWord = UInt(66 bits)
  val lshCLimmWord = UInt(32 bits); val lshCLregWord = UInt(66 bits)
  val lshTbl = UInt(32 bits)   // shTable(count+1) for ASL V
  // rotates: carry the four rotated/ring words + the per-op carry words.
  val rorRes = UInt(32 bits); val rorCImmWord = UInt(66 bits); val rorCRegBWord = UInt(66 bits); val rorCRegWLWord = UInt(66 bits)
  val rolRes = UInt(32 bits); val rolCImmBWord = UInt(66 bits); val rolCImmWWord = UInt(32 bits); val rolCImmLWord = UInt(32 bits)
  val rolCRegBWord = UInt(66 bits); val rolCRegWWord = UInt(66 bits); val rolCRegLWord = UInt(66 bits)
  val rolSMod = UInt(6 bits)   // rol needs sMod===0 distinction in stage2
  val roxrRes = UInt(32 bits); val roxrCx = Bool()
  val roxlRes = UInt(32 bits); val roxlCx = Bool()
}

/** Combinational barrel shifter for the 8 line-E shift/rotate ops, structured to
  * mirror the Musashi C (ShiftRef) branch-for-branch so the flags match exactly.
  *
  * All arithmetic happens on `UInt` values masked to the operating size. The flag
  * bit `(FLAG>>8)&1` of Musashi is reproduced via `flagBit` on a 32-bit shifter of
  * the source; ROX uses a 33-bit ring.
  *
  * 2-STAGE PIPELINE: `stage1(cmd)` performs every variable shift/rotate (the deep
  * funnel cone) and packs the results into a `ShiftStage1` midpoint; `stage2(s1)`
  * does only shallow bit-indexing / compares / muxing. `apply = stage2 ∘ stage1`
  * preserves the original combinational signature so all callers/tests are unchanged.
  */
/** Registered midpoint of the FIRST half of stage 1 (FMax #3). Holds the masked
  * source + size-derived constants + every COUNT-DERIVED SHIFT AMOUNT (the `rmod`
  * ROX reductions, the rotate `sMod`, the `cMinus` flag-bit offsets, the rotate
  * back-amounts). stage1b then performs only the variable barrel shifts/rotates
  * USING these registered amounts. Splitting the deep `s1Src2(count) -> rmod ->
  * variable-shift -> s2Stage1` cone at the amount/shift boundary halves the funnel
  * cone the ROXL/ROXR path had (the post-fmax#1/#2 limiter: s1Src2 -> s2Stage1
  * roxlRes, 19 levels). All values here are cheap (small adders/subtracts + the
  * rmod subtract-reduce); the barrel shifts (the wide cone) move to stage1b. */
case class ShiftStage1a() extends Bundle {
  // passthrough cmd fields
  val shiftOp = UInt(2 bits); val dirLeft = Bool(); val size = Size()
  val isImm   = Bool();       val xIn = Bool();     val count = UInt(6 bits)
  // size-derived
  val mask    = UInt(32 bits); val sizeBits = UInt(7 bits)
  val src     = UInt(32 bits); val srcMsb = Bool()
  // ASR/LSR/ASL/LSL flag-bit shift amounts (cMinus(k) = k - count)
  val cMinus8  = UInt(7 bits); val cMinus9 = UInt(7 bits)
  val cMinus24 = UInt(7 bits); val cMinus32 = UInt(7 bits)
  val countM1  = UInt(6 bits)              // (count - 1) for the reg-form WL flag word
  val countP1  = UInt(6 bits)              // (count + 1) for the ASL V shTable index
  // rotate sMod + back amount (shared layout; ROR/ROL recompute their own rotated word)
  val rotSMod   = UInt(6 bits)             // count mod size (the rotate distance)
  val rotBack   = UInt(6 bits)             // size - sMod (the wrap-around amount)
  val sizeMask6 = UInt(6 bits)             // sizeBits-1 (mask for the reg-form WL word)
  val rorRegBAmt = UInt(7 bits)            // (8 - ((count-1)&7)) for the ROR reg-byte word
  // ROX reduced shift amount + width
  val wBits    = UInt(7 bits)              // sizeBits+1 (9/17/33)
  val roxShift = UInt(6 bits)             // imm ? count : count mod wBits
  val roxBack  = UInt(6 bits)             // wBits - roxShift
  // ROL imm/reg flag-bit shift amounts
  val rolCImmWAmt = UInt(6 bits)          // (8  - sMod) for the ROL imm word flag
  val rolCImmLAmt = UInt(6 bits)          // (24 - sMod) for the ROL imm long flag
  val rolCRegLAmt = UInt(6 bits)          // ((32 - sMod) & 0x1f) for the ROL reg long flag
}

object Shifter {
  def apply(cmd: ShiftCmd): ShiftRsp = stage2(stage1(cmd))
  // stage1 (the original one-shot midpoint) preserved for the unit tests / callers:
  // it is now the composition of the two split halves.
  def stage1(cmd: ShiftCmd): ShiftStage1 = stage1b(stage1a(cmd))

  // ── STAGE 1a: masked source + size + every count-derived SHIFT AMOUNT (cheap) ──
  def stage1a(cmd: ShiftCmd): ShiftStage1a = new Area {
    val a = ShiftStage1a()
    val sizeBits = cmd.size.mux(Size.BYTE -> U(8, 7 bits), Size.WORD -> U(16, 7 bits), Size.LONG -> U(32, 7 bits))
    def szMask: Bits = cmd.size.mux(
      Size.BYTE -> B(0x000000ffL, 32 bits),
      Size.WORD -> B(0x0000ffffL, 32 bits),
      Size.LONG -> B(0xffffffffL, 32 bits))
    val mask = szMask.asUInt
    val src  = cmd.data.asUInt & mask
    val srcMsb = cmd.size.mux(Size.BYTE -> src(7), Size.WORD -> src(15), Size.LONG -> src(31))
    val count = cmd.count
    def cMinus(k: Int): UInt = (U(k, 7 bits) - count.resize(7)).resize(7)
    val sMod = cmd.size.mux(Size.BYTE -> count(2 downto 0).resize(6), Size.WORD -> count(3 downto 0).resize(6), Size.LONG -> count(4 downto 0).resize(6))
    val wBits = (sizeBits + 1).resize(7)
    val roxShift = (cmd.isImm ? count.resize(7) | rmod(count, wBits)).resize(6)

    a.shiftOp := cmd.shiftOp; a.dirLeft := cmd.dirLeft; a.size := cmd.size
    a.isImm := cmd.isImm;     a.xIn := cmd.xIn;         a.count := count
    a.mask := mask;           a.sizeBits := sizeBits
    a.src := src;             a.srcMsb := srcMsb
    a.cMinus8  := cMinus(8);  a.cMinus9  := cMinus(9)
    a.cMinus24 := cMinus(24); a.cMinus32 := cMinus(32)
    a.countM1  := (count - 1).resize(6)
    a.countP1  := (count + 1).resize(6)
    a.rotSMod   := sMod
    a.rotBack   := (sizeBits - sMod.resize(7)).resize(6)
    a.sizeMask6 := (sizeBits.resize(6) - 1)
    a.rorRegBAmt := (U(8, 7 bits) - ((count - 1) & 7).resize(7)).resize(7)
    a.wBits    := wBits
    a.roxShift := roxShift
    a.roxBack  := (wBits - roxShift.resize(7)).resize(6)
    a.rolCImmWAmt := (U(8, 7 bits)  - sMod.resize(7)).resize(6)
    a.rolCImmLAmt := (U(24, 7 bits) - sMod.resize(7)).resize(6)
    a.rolCRegLAmt := ((U(32, 7 bits) - sMod.resize(7)) & 0x1f).resize(6)
  }.a

  // ── STAGE 1b: all variable shifts (the deep funnel cone), using the registered
  // shift amounts from stage1a ──────────────────────────────────────────────────
  def stage1b(a: ShiftStage1a): ShiftStage1 = new Area {
    val s1 = ShiftStage1()

    // size in {8,16,32} as a width selector (from the registered stage1a)
    val isB = a.size === Size.BYTE
    val sizeBits = a.sizeBits

    val mask = a.mask
    val src  = a.src                                  // low-size source (registered)
    val srcMsb = a.srcMsb

    // count: the imm form is 1..8; the reg form is Dc & 0x3f.
    val count = a.count                                // registered

    // m68ki_shift_N_table[k]: high `k` bits set within the size width. k in 0..size.
    def shTable(k: UInt): UInt = {
      val res = UInt(32 bits)
      val lowZeros = (sizeBits - k.resize(7)).asSInt           // size - k, may be <=0
      when(k === 0) {
        res := 0
      } otherwise {
        when(lowZeros <= 0) {
          res := mask
        } otherwise {
          val lz = lowZeros.asUInt.resize(6)
          val lowOnes = ((U(1, 33 bits) << lz) - 1).resize(32)
          res := mask & ~lowOnes
        }
      }
      res
    }

    // ── flag-bit helpers (reproduce Musashi's `(FLAG>>8)&1`) ────────────────────
    val src66 = src.resize(66)
    def shl(amt: UInt): UInt = (src66 << amt.resize(7)).resize(66)
    def shr(amt: UInt): UInt = (src >> amt)

    // passthrough + size-derived (all from the registered stage1a)
    s1.shiftOp  := a.shiftOp
    s1.dirLeft  := a.dirLeft
    s1.size     := a.size
    s1.isImm    := a.isImm
    s1.xIn      := a.xIn
    s1.count    := count
    s1.mask     := mask
    s1.sizeBits := sizeBits
    s1.src      := src
    s1.srcMsb   := srcMsb

    // ── ASR ───────────────────────────────────────────────────────────────────
    s1.asrRes        := (src >> count) | Mux(srcMsb, shTable(count), U(0, 32 bits))
    s1.asrCImmWord   := shl(a.cMinus9)
    s1.asrCRegWLWord := (shr(a.countM1) << 8).resize(66)

    // ── LSR ───────────────────────────────────────────────────────────────────
    s1.lsrRes        := src >> count
    s1.lsrCImmWord   := shl(a.cMinus9)
    s1.lsrCRegWLWord := (shr(a.countM1) << 8).resize(66)

    // ── ASL / LSL share the left result ─────────────────────────────────────────
    s1.lshResW       := shl(count) & mask.resize(66)
    s1.lshCBWord     := shl(count)
    s1.lshCWimmWord  := shr(a.cMinus8.resize(6))
    s1.lshCWregWord  := (shl(count) >> 8).resize(66)
    s1.lshCLimmWord  := shr(a.cMinus24.resize(6))
    s1.lshCLregWord  := (shr(a.cMinus32.resize(6)) << 8).resize(66)
    s1.lshTbl        := shTable(a.countP1)

    // ── ROR ─────────────────────────────────────────────────────────────────────
    {
      val sMod = a.rotSMod
      val srcLow = src & mask
      val backAmt = a.rotBack
      val rotated = (((srcLow >> sMod) | (srcLow << backAmt).resize(32)) & mask).resize(32)
      s1.rorRes        := Mux(sMod === 0, srcLow.resize(32), rotated)
      s1.rorCImmWord   := (src66 << a.cMinus9).resize(66)
      s1.rorCRegBWord  := (src66 << a.rorRegBAmt).resize(66)
      s1.rorCRegWLWord := ((src >> ((count - 1) & a.sizeMask6)) << 8).resize(66)
    }

    // ── ROL ─────────────────────────────────────────────────────────────────────
    {
      val sMod = a.rotSMod
      val srcLow = src & mask
      val backAmt = a.rotBack
      val rotated = (((srcLow << sMod).resize(32) | (srcLow >> backAmt)) & mask).resize(32)
      s1.rolRes        := Mux(sMod === 0, srcLow.resize(32), rotated)
      s1.rolCImmBWord  := (src66 << count).resize(66)
      s1.rolCImmWWord  := (src >> a.rolCImmWAmt)
      s1.rolCImmLWord  := (src >> a.rolCImmLAmt)
      s1.rolCRegBWord  := (src66 << sMod).resize(66)
      s1.rolCRegWWord  := ((src66 << sMod).resize(66) >> 8).resize(66)
      s1.rolCRegLWord  := ((src >> a.rolCRegLAmt) << 8).resize(66)
      s1.rolSMod       := sMod
    }

    // ── ROXR ────────────────────────────────────────────────────────────────────
    {
      val wBits = a.wBits                                              // 9/17/33
      val xShifted = (a.xIn.asUInt.resize(66) << sizeBits.resize(6)).resize(66)
      val ext = src.resize(66) | xShifted
      val shift = a.roxShift
      val backAmt = a.roxBack
      val rsh = ext >> shift
      val lsh = (ext << backAmt).resize(66)
      val ringMask = (((U(1, 67 bits) << wBits) - 1).resize(66))
      val res = ((rsh | lsh) & ringMask)
      s1.roxrCx  := res(sizeBits.resize(6))
      s1.roxrRes := (res.resize(32) & mask).resize(32)
    }

    // ── ROXL ────────────────────────────────────────────────────────────────────
    {
      val wBits = a.wBits
      val xShifted = (a.xIn.asUInt.resize(66) << sizeBits.resize(6)).resize(66)
      val ext = src.resize(66) | xShifted
      val shift = a.roxShift
      val backAmt = a.roxBack
      val lsh = (ext << shift).resize(66)
      val rsh = ext >> backAmt
      val ringMask = (((U(1, 67 bits) << wBits) - 1).resize(66))
      val res = ((lsh | rsh) & ringMask)
      s1.roxlCx  := res(sizeBits.resize(6))
      s1.roxlRes := (res.resize(32) & mask).resize(32)
    }
  }.s1

  // ── STAGE 2: bit-index + compare + mux (no variable shift) ───────────────────
  def stage2(s1: ShiftStage1): ShiftRsp = new Area {
    val rsp = ShiftRsp()

    val isB = s1.size === Size.BYTE
    val isW = s1.size === Size.WORD
    val isL = s1.size === Size.LONG
    val mask = s1.mask
    val sizeBits = s1.sizeBits
    val src = s1.src
    val srcMsb = s1.srcMsb
    val count = s1.count
    val countZ = count === 0

    def nOf(v: UInt): Bool = s1.size.mux(Size.BYTE -> v(7), Size.WORD -> v(15), Size.LONG -> v(31))
    def zOf(v: UInt): Bool = (v & mask) === 0
    def flBit(v: UInt): Bool = v(8)

    // Default response (overwritten by the selected op).
    rsp.result := src.asBits
    rsp.n := srcMsb
    rsp.z := zOf(src)
    rsp.v := False
    rsp.c := False
    rsp.xOut := s1.xIn

    def setRsp(result: UInt, n: Bool, z: Bool, v: Bool, c: Bool, x: Bool): Unit = {
      rsp.result := (result & mask).asBits
      rsp.n := n; rsp.z := z; rsp.v := v; rsp.c := c; rsp.xOut := x
    }

    // ── ASR derived ─────────────────────────────────────────────────────────────
    val asrRes = s1.asrRes
    val asrN = nOf(asrRes); val asrZ = zOf(asrRes)
    val asrCImm = flBit(s1.asrCImmWord)
    val asrCRegB = asrCImm
    val asrCRegWL = flBit(s1.asrCRegWLWord)

    // ── LSR derived ─────────────────────────────────────────────────────────────
    val lsrRes = s1.lsrRes
    val lsrZ = zOf(lsrRes)
    val lsrCImm = flBit(s1.lsrCImmWord)
    val lsrCRegB = lsrCImm
    val lsrCRegWL = flBit(s1.lsrCRegWLWord)

    // ── ASL/LSL derived ─────────────────────────────────────────────────────────
    val lshRes = s1.lshResW.resize(32)
    val lshN = nOf(lshRes); val lshZ = (s1.lshResW & mask.resize(66)) === 0
    val lshCB    = flBit(s1.lshCBWord)
    val lshCWimm = flBit(s1.lshCWimmWord)
    val lshCWreg = flBit(s1.lshCWregWord)
    val lshCLimm = flBit(s1.lshCLimmWord)
    val lshCLreg = flBit(s1.lshCLregWord)
    val lshTbl = s1.lshTbl
    val lshSv  = src & lshTbl
    val lshVImm = !((lshSv === 0) || ((lshSv === lshTbl) && (!isB || count < 8)))
    val lshVReg = !((lshSv === 0) || (lshSv === lshTbl))

    // The op/dir mux. Use shiftOp ## dirLeft as a 3-bit selector.
    val sel = s1.shiftOp @@ s1.dirLeft                           // {tt[1:0], dir}

    switch(sel) {
      // ASR (tt=0, dir=0)
      is(U"000") {
        when(s1.isImm) {
          setRsp(asrRes, asrN, asrZ, False, asrCImm, asrCImm)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, s1.xIn)
          } otherwise {
            when(count < sizeBits) {
              val c = Mux(isB, asrCRegB, asrCRegWL)
              setRsp(asrRes, asrN, asrZ, False, c, c)
            } otherwise {
              when(srcMsb) { setRsp(mask, True, False, False, True, True) }
                .otherwise { setRsp(U(0, 32 bits), False, True, False, False, False) }
            }
          }
        }
      }
      // ASL (tt=0, dir=1)
      is(U"001") {
        when(s1.isImm) {
          val c = s1.size.mux(Size.BYTE -> lshCB, Size.WORD -> lshCWimm, Size.LONG -> lshCLimm)
          setRsp(lshRes, lshN, lshZ, lshVImm, c, c)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, s1.xIn)
          } otherwise {
            when(count < sizeBits) {
              val c = s1.size.mux(Size.BYTE -> lshCB, Size.WORD -> lshCWreg, Size.LONG -> lshCLreg)
              setRsp(lshRes, lshN, lshZ, lshVReg, c, c)
            } otherwise {
              val c = (count === sizeBits) && src(0)
              setRsp(U(0, 32 bits), False, True, src =/= 0, c, c)
            }
          }
        }
      }
      // LSR (tt=1, dir=0)
      is(U"010") {
        when(s1.isImm) {
          setRsp(lsrRes, False, lsrZ, False, lsrCImm, lsrCImm)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, s1.xIn)
          } otherwise {
            val withinBW = count <= sizeBits
            val withinL  = count < sizeBits
            when((isL && withinL) || (!isL && withinBW)) {
              val c = Mux(isB, lsrCRegB, lsrCRegWL)
              setRsp(lsrRes, False, lsrZ, False, c, c)
            } otherwise {
              val cL = (count === sizeBits) && srcMsb
              val c = Mux(isL, cL, False)
              setRsp(U(0, 32 bits), False, True, False, c, c)
            }
          }
        }
      }
      // LSL (tt=1, dir=1)
      is(U"011") {
        when(s1.isImm) {
          val c = s1.size.mux(Size.BYTE -> lshCB, Size.WORD -> lshCWimm, Size.LONG -> lshCLimm)
          setRsp(lshRes, lshN, lshZ, False, c, c)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, s1.xIn)
          } otherwise {
            val withinBW = count <= sizeBits
            val withinL  = count < sizeBits
            when((isL && withinL) || (!isL && withinBW)) {
              val c = s1.size.mux(Size.BYTE -> lshCB, Size.WORD -> lshCWreg, Size.LONG -> lshCLreg)
              setRsp(lshRes, lshN, lshZ, False, c, c)
            } otherwise {
              val cL = (count === sizeBits) && src(0)
              val c = Mux(isL, cL, False)
              setRsp(U(0, 32 bits), False, True, False, c, c)
            }
          }
        }
      }
      // ROXR (tt=2, dir=0)
      is(U"100") {
        val cx = s1.roxrCx
        val low = s1.roxrRes
        val n = nOf(low); val z = zOf(low)
        when(s1.isImm) {
          rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
        } otherwise {
          when(countZ) {
            rsp.result := (src & mask).asBits; rsp.n := srcMsb; rsp.z := (src & mask) === 0; rsp.v := False; rsp.c := s1.xIn; rsp.xOut := s1.xIn
          } otherwise {
            rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
          }
        }
      }
      // ROXL (tt=2, dir=1)
      is(U"101") {
        val cx = s1.roxlCx
        val low = s1.roxlRes
        val n = nOf(low); val z = zOf(low)
        when(s1.isImm) {
          rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
        } otherwise {
          when(countZ) {
            rsp.result := (src & mask).asBits; rsp.n := srcMsb; rsp.z := (src & mask) === 0; rsp.v := False; rsp.c := s1.xIn; rsp.xOut := s1.xIn
          } otherwise {
            rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
          }
        }
      }
      // ROR (tt=3, dir=0)
      is(U"110") {
        val res = s1.rorRes
        val n = nOf(res); val z = zOf(res)
        val cImm  = flBit(s1.rorCImmWord)
        val cRegB = flBit(s1.rorCRegBWord)
        val cRegWL = flBit(s1.rorCRegWLWord)
        when(s1.isImm) {
          rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cImm; rsp.xOut := s1.xIn
        } otherwise {
          when(countZ) {
            rsp.result := (src & mask).asBits; rsp.n := srcMsb; rsp.z := (src & mask) === 0; rsp.v := False; rsp.c := False; rsp.xOut := s1.xIn
          } otherwise {
            val c = Mux(isB, cRegB, cRegWL)
            rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := c; rsp.xOut := s1.xIn
          }
        }
      }
      // ROL (tt=3, dir=1)
      default {  // U"111"
        val res = s1.rolRes
        val n = nOf(res); val z = zOf(res)
        val cImmB = flBit(s1.rolCImmBWord)
        val cImmW = flBit(s1.rolCImmWWord)
        val cImmL = flBit(s1.rolCImmLWord)
        val cRegB = flBit(s1.rolCRegBWord)
        val cRegW = flBit(s1.rolCRegWWord)
        val cRegL = flBit(s1.rolCRegLWord)
        when(s1.isImm) {
          val c = s1.size.mux(Size.BYTE -> cImmB, Size.WORD -> cImmW, Size.LONG -> cImmL)
          rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := c; rsp.xOut := s1.xIn
        } otherwise {
          when(countZ) {
            rsp.result := (src & mask).asBits; rsp.n := srcMsb; rsp.z := (src & mask) === 0; rsp.v := False; rsp.c := False; rsp.xOut := s1.xIn
          } otherwise {
            when(s1.rolSMod === 0) {
              rsp.result := (src & mask).asBits; rsp.n := srcMsb; rsp.z := (src & mask) === 0; rsp.v := False; rsp.c := src(0); rsp.xOut := s1.xIn
            } otherwise {
              val c = s1.size.mux(Size.BYTE -> cRegB, Size.WORD -> cRegW, Size.LONG -> cRegL)
              rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := c; rsp.xOut := s1.xIn
            }
          }
        }
      }
    }
  }.rsp

  // count % w for w in {9,17,33} and count 0..63. Subtract-based reduce (at most 7
  // subtractions: 63/9 = 7). Returns a 7-bit value < w.
  private def rmod(count: UInt, w: UInt): UInt = {
    var acc = count.resize(7)
    for (_ <- 0 until 7) {
      acc = Mux(acc >= w, (acc - w).resize(7), acc)
    }
    acc
  }
}

/** CPU_SHIFT_FUNNEL stage-1a midpoint: the masked source, the class-shaped FUNNEL WORD
  * `d` and the funnel right-shift amount `r` -- instead of the ten separate count-derived
  * amounts the per-op shifters needed. */
case class ShiftFunnelStage1a() extends Bundle {
  val shiftOp = UInt(2 bits); val dirLeft = Bool(); val size = Size()
  val isImm   = Bool();       val xIn = Bool();     val count = UInt(6 bits)
  val src     = UInt(32 bits); val srcMsb = Bool()
  val d       = Bits(66 bits)  // funnel word D[0..65] (see ShifterFunnel)
  val r       = UInt(6 bits)   // right-shift amount, 0..33 on every live path
  val lshTbl  = UInt(32 bits)  // ASL V: shTable(count+1) (count-only, built in S1)
}

/** CPU_SHIFT_FUNNEL stage-1 midpoint: the ONE funnel window plus the ASL V inputs. */
case class ShiftFunnelStage1() extends Bundle {
  val shiftOp = UInt(2 bits); val dirLeft = Bool(); val size = Size()
  val isImm   = Bool();       val xIn = Bool();     val count = UInt(6 bits)
  val src     = UInt(32 bits); val srcMsb = Bool()
  val o       = Bits(34 bits)  // ({D, guard} >> r)[33:0]: [0]=bit below, [s:1]=result, [s+1]=bit above
  val lshSvZero = Bool()       // (src & shTable(count+1)) == 0
  val lshSvAll  = Bool()       // (src & shTable(count+1)) == shTable(count+1)
}

/** CPU_SHIFT_FUNNEL: the line-E shifter with ONE funnel instead of ten barrel shifters.
  *
  * The per-op `Shifter` builds ASR/LSR/LSL results, two-shift ROR/ROL/ROXR/ROXL rotates and
  * ~17 separate "(FLAG>>8)&1" words, every one of them a variable shift of the SAME source.
  * Every result here is a window of one class-shaped 66-bit word `D` (built from the source
  * with constant indices only) shifted right by one amount `r`:
  *   LSR      D[j] = src[j] (j<32), 0 above               r = count
  *   ASR      D[j] = src[j] (j<s),  msb above             r = count
  *   ASL/LSL  D[j] = src[j-32] (32<=j<64), 0 elsewhere    r = 32 - count
  *   ROR/ROL  D[j] = src[j mod s]                         r = sMod / s - sMod
  *   ROXR/L   D[j] = ring[j mod (s+1)], ring = {X, src}   r = k / (s+1) - k
  * With W = {D, guard=0} and O = W >> r: result = O[s:1]; the last bit shifted out of a
  * right shift is O[0]; of a left shift (and the new ROX X) O[s+1]; a ROR's C is the
  * result MSB and a ROL's the result LSB. Stage 2 keeps the per-op count/size special
  * cases verbatim. Equivalence vs `Shifter`: ShifterFunnelEquivSpec. */
object ShifterFunnel {
  def apply(cmd: ShiftCmd): ShiftRsp = stage2(stage1b(stage1a(cmd)))

  def stage1a(cmd: ShiftCmd): ShiftFunnelStage1a = new Area {
    val a = ShiftFunnelStage1a()
    val isB = cmd.size === Size.BYTE
    val isW = cmd.size === Size.WORD
    val sizeBits = cmd.size.mux(Size.BYTE -> U(8, 7 bits), Size.WORD -> U(16, 7 bits), Size.LONG -> U(32, 7 bits))
    val mask = cmd.size.mux(
      Size.BYTE -> B(0x000000ffL, 32 bits),
      Size.WORD -> B(0x0000ffffL, 32 bits),
      Size.LONG -> B(0xffffffffL, 32 bits)).asUInt
    val src  = cmd.data.asUInt & mask
    val srcMsb = cmd.size.mux(Size.BYTE -> src(7), Size.WORD -> src(15), Size.LONG -> src(31))
    val count = cmd.count
    val sMod = cmd.size.mux(Size.BYTE -> count(2 downto 0).resize(6), Size.WORD -> count(3 downto 0).resize(6), Size.LONG -> count(4 downto 0).resize(6))
    val wBits = (sizeBits + 1).resize(7)
    val roxShift = (cmd.isImm ? count.resize(7) | rmod(count, wBits)).resize(6)

    val tt = cmd.shiftOp
    val left = cmd.dirLeft
    val isShiftCls = !tt(1)                     // ASR/ASL/LSR/LSL
    val isRox = tt === 2
    val isRot = tt === 3
    // Per-bit funnel word: constant source indices per (class, size).
    val x = cmd.xIn
    def rot(j: Int, s: Int): Bool = src(j % s)
    def rox(j: Int, s: Int): Bool = { val i = j % (s + 1); if (i == s) x else src(i) }
    val d = Bits(66 bits)
    for (j <- 0 until 66) {
      val lsr = if (j < 32) src(j) else False
      val asr = Bool()
      asr := (if (j < 32) src(j) else srcMsb)
      if (j >= 8 && j < 32) when(isB || (isW && Bool(j >= 16))) { asr := srcMsb }
      val lsl = if (j >= 32 && j < 64) src(j - 32) else False
      val rotJ = cmd.size.mux(Size.BYTE -> rot(j, 8), Size.WORD -> rot(j, 16), Size.LONG -> rot(j, 32))
      val roxJ = cmd.size.mux(Size.BYTE -> rox(j, 8), Size.WORD -> rox(j, 16), Size.LONG -> rox(j, 32))
      d(j) := Mux(isShiftCls, Mux(left, lsl, Mux(tt(0), lsr, asr)), Mux(isRot, rotJ, roxJ))
    }
    val rotBack = (sizeBits - sMod.resize(7)).resize(6)
    val roxBack = (wBits - roxShift.resize(7)).resize(6)
    val r = UInt(6 bits)
    when(isShiftCls) {
      r := Mux(left, (U(32, 7 bits) - count.resize(7)).resize(6), count)
    } elsewhen(isRot) {
      r := Mux(left, rotBack, sMod)
    } otherwise {
      r := Mux(left, roxBack, roxShift)
    }

    a.shiftOp := cmd.shiftOp; a.dirLeft := cmd.dirLeft; a.size := cmd.size
    a.isImm := cmd.isImm;     a.xIn := cmd.xIn;         a.count := count
    a.src := src;             a.srcMsb := srcMsb
    a.d := d;                 a.r := r
    // ASL V table: a function of count and size only, so it is built here in parallel
    // with the (deeper) ROX `rmod` amount, keeping S1a's cone to the funnel + compares.
    def shTable(k: UInt): UInt = {
      val res = UInt(32 bits)
      val lowZeros = (sizeBits - k.resize(7)).asSInt
      when(k === 0) {
        res := 0
      } otherwise {
        when(lowZeros <= 0) {
          res := mask
        } otherwise {
          val lz = lowZeros.asUInt.resize(6)
          val lowOnes = ((U(1, 33 bits) << lz) - 1).resize(32)
          res := mask & ~lowOnes
        }
      }
      res
    }
    a.lshTbl := shTable((count + 1).resize(6))
  }.a

  def stage1b(a: ShiftFunnelStage1a): ShiftFunnelStage1 = new Area {
    val s1 = ShiftFunnelStage1()
    val sizeBits = a.size.mux(Size.BYTE -> U(8, 7 bits), Size.WORD -> U(16, 7 bits), Size.LONG -> U(32, 7 bits))
    val mask = a.size.mux(
      Size.BYTE -> B(0x000000ffL, 32 bits),
      Size.WORD -> B(0x0000ffffL, 32 bits),
      Size.LONG -> B(0xffffffffL, 32 bits)).asUInt
    // The one funnel: W = {D, guard 0} (67 bits) >> r, keep 34 bits.
    val w = (a.d ## False).asUInt
    s1.o := (w >> a.r).resize(34).asBits
    val tbl = a.lshTbl
    val sv  = a.src & tbl
    s1.lshSvZero := sv === 0
    s1.lshSvAll  := sv === tbl
    s1.shiftOp := a.shiftOp; s1.dirLeft := a.dirLeft; s1.size := a.size
    s1.isImm := a.isImm;     s1.xIn := a.xIn;         s1.count := a.count
    s1.src := a.src;         s1.srcMsb := a.srcMsb
  }.s1

  def stage2(s1: ShiftFunnelStage1): ShiftRsp = new Area {
    val rsp = ShiftRsp()
    val isB = s1.size === Size.BYTE
    val isL = s1.size === Size.LONG
    val sizeBits = s1.size.mux(Size.BYTE -> U(8, 7 bits), Size.WORD -> U(16, 7 bits), Size.LONG -> U(32, 7 bits))
    val mask = s1.size.mux(
      Size.BYTE -> B(0x000000ffL, 32 bits),
      Size.WORD -> B(0x0000ffffL, 32 bits),
      Size.LONG -> B(0xffffffffL, 32 bits)).asUInt
    val src = s1.src
    val srcMsb = s1.srcMsb
    val count = s1.count
    val countZ = count === 0
    def nOf(v: UInt): Bool = s1.size.mux(Size.BYTE -> v(7), Size.WORD -> v(15), Size.LONG -> v(31))
    def zOf(v: UInt): Bool = (v & mask) === 0

    val win    = s1.o(32 downto 1).asUInt & mask     // the shifted/rotated result
    val winN   = nOf(win)
    val winZ   = win === 0
    val cBelow = s1.o(0)                              // last bit out of a right shift
    val cAbove = s1.size.mux(Size.BYTE -> s1.o(9), Size.WORD -> s1.o(17), Size.LONG -> s1.o(33))
    val vImm = !(s1.lshSvZero || (s1.lshSvAll && (!isB || count < 8)))
    val vReg = !(s1.lshSvZero || s1.lshSvAll)

    rsp.result := src.asBits
    rsp.n := srcMsb
    rsp.z := zOf(src)
    rsp.v := False
    rsp.c := False
    rsp.xOut := s1.xIn
    def setRsp(result: UInt, n: Bool, z: Bool, v: Bool, c: Bool, x: Bool): Unit = {
      rsp.result := (result & mask).asBits
      rsp.n := n; rsp.z := z; rsp.v := v; rsp.c := c; rsp.xOut := x
    }
    def setWin(v: Bool, c: Bool, x: Bool, n: Bool = null): Unit = {
      rsp.result := win.asBits
      rsp.n := (if (n == null) winN else n); rsp.z := winZ; rsp.v := v; rsp.c := c; rsp.xOut := x
    }
    def setCount0(c: Bool, x: Bool): Unit = setRsp(src, srcMsb, zOf(src), False, c, x)

    val sel = s1.shiftOp @@ s1.dirLeft
    switch(sel) {
      is(U"000") {  // ASR
        when(s1.isImm) { setWin(False, cBelow, cBelow) } otherwise {
          when(countZ) { setCount0(False, s1.xIn) } otherwise {
            when(count < sizeBits) { setWin(False, cBelow, cBelow) } otherwise {
              when(srcMsb) { setRsp(mask, True, False, False, True, True) }
                .otherwise { setRsp(U(0, 32 bits), False, True, False, False, False) }
            }
          }
        }
      }
      is(U"001") {  // ASL
        when(s1.isImm) { setWin(vImm, cAbove, cAbove) } otherwise {
          when(countZ) { setCount0(False, s1.xIn) } otherwise {
            when(count < sizeBits) { setWin(vReg, cAbove, cAbove) } otherwise {
              val c = (count === sizeBits) && src(0)
              setRsp(U(0, 32 bits), False, True, src =/= 0, c, c)
            }
          }
        }
      }
      is(U"010") {  // LSR
        when(s1.isImm) { setWin(False, cBelow, cBelow, False) } otherwise {
          when(countZ) { setCount0(False, s1.xIn) } otherwise {
            when((isL && count < sizeBits) || (!isL && count <= sizeBits)) {
              setWin(False, cBelow, cBelow, False)
            } otherwise {
              val c = Mux(isL, (count === sizeBits) && srcMsb, False)
              setRsp(U(0, 32 bits), False, True, False, c, c)
            }
          }
        }
      }
      is(U"011") {  // LSL
        when(s1.isImm) { setWin(False, cAbove, cAbove) } otherwise {
          when(countZ) { setCount0(False, s1.xIn) } otherwise {
            when((isL && count < sizeBits) || (!isL && count <= sizeBits)) {
              setWin(False, cAbove, cAbove)
            } otherwise {
              val c = Mux(isL, (count === sizeBits) && src(0), False)
              setRsp(U(0, 32 bits), False, True, False, c, c)
            }
          }
        }
      }
      is(U"100", U"101") {  // ROXR / ROXL
        when(!s1.isImm && countZ) { setCount0(s1.xIn, s1.xIn) } otherwise { setWin(False, cAbove, cAbove) }
      }
      is(U"110") {  // ROR
        when(!s1.isImm && countZ) { setCount0(False, s1.xIn) } otherwise { setWin(False, winN, s1.xIn) }
      }
      default {     // ROL
        when(!s1.isImm && countZ) { setCount0(False, s1.xIn) } otherwise { setWin(False, win(0), s1.xIn) }
      }
    }
  }.rsp

  private def rmod(count: UInt, w: UInt): UInt = {
    var acc = count.resize(7)
    for (_ <- 0 until 7) {
      acc = Mux(acc >= w, (acc - w).resize(7), acc)
    }
    acc
  }
}
