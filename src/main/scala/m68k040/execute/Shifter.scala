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

/** Combinational barrel shifter for the 8 line-E shift/rotate ops, structured to
  * mirror the Musashi C (ShiftRef) branch-for-branch so the flags match exactly.
  *
  * All arithmetic happens on `UInt` values masked to the operating size. The flag
  * bit `(FLAG>>8)&1` of Musashi is reproduced via `flagBit` on a 32-bit shifter of
  * the source; ROX uses a 33-bit ring. */
object Shifter {
  def apply(cmd: ShiftCmd): ShiftRsp = new Area {
    val rsp = ShiftRsp()

    // size in {8,16,32} as a width selector
    val isB = cmd.size === Size.BYTE
    val isW = cmd.size === Size.WORD
    val isL = cmd.size === Size.LONG
    val sizeBits = cmd.size.mux(Size.BYTE -> U(8, 7 bits), Size.WORD -> U(16, 7 bits), Size.LONG -> U(32, 7 bits))

    def szMask: Bits = cmd.size.mux(
      Size.BYTE -> B(0x000000ffL, 32 bits),
      Size.WORD -> B(0x0000ffffL, 32 bits),
      Size.LONG -> B(0xffffffffL, 32 bits))
    val mask = szMask.asUInt
    val src  = cmd.data.asUInt & mask                 // low-size source
    val srcMsb = cmd.size.mux(Size.BYTE -> src(7), Size.WORD -> src(15), Size.LONG -> src(31))

    // count: the imm form is 1..8; the reg form is Dc & 0x3f.
    val count = cmd.count                              // already 6 bits (Dc&0x3f or ccc 1..8)
    val countZ = count === 0

    // Generic N/Z of a 32-bit (already low-size) value.
    def nOf(v: UInt): Bool = cmd.size.mux(Size.BYTE -> v(7), Size.WORD -> v(15), Size.LONG -> v(31))
    def zOf(v: UInt): Bool = (v & mask) === 0

    // m68ki_shift_N_table[k]: high `k` bits set within the size width. k in 0..size.
    // Built as: mask & ~( (1<<(size-k)) - 1 ). For k>=size -> mask, k<=0 -> 0.
    def shTable(k: UInt): UInt = {
      val res = UInt(32 bits)
      // size - k, clamped; produce the low-zeros count.
      val lowZeros = (sizeBits - k.resize(7)).asSInt           // size - k, may be <=0
      when(k === 0) {
        res := 0
      } otherwise {
        when(lowZeros <= 0) {
          res := mask
        } otherwise {
          // (1 << lowZeros) - 1  -> low ones; invert within mask.
          val lz = lowZeros.asUInt.resize(6)
          val lowOnes = ((U(1, 33 bits) << lz) - 1).resize(32)
          res := mask & ~lowOnes
        }
      }
      res
    }

    // Default response (overwritten by the selected op).
    rsp.result := src.asBits
    rsp.n := srcMsb
    rsp.z := zOf(src)
    rsp.v := False
    rsp.c := False
    rsp.xOut := cmd.xIn

    // Helper to set the full response.
    def setRsp(result: UInt, n: Bool, z: Bool, v: Bool, c: Bool, x: Bool): Unit = {
      rsp.result := (result & mask).asBits
      rsp.n := n; rsp.z := z; rsp.v := v; rsp.c := c; rsp.xOut := x
    }

    // ── flag-bit helpers (reproduce Musashi's `(FLAG>>8)&1`) ────────────────────
    // wide left/right shift of `src` (66-bit), then index bit 8. Shift amounts are
    // always >= 0 by construction (callers guard the ranges).
    val src66 = src.resize(66)
    def shl(amt: UInt): UInt = (src66 << amt.resize(7)).resize(66)
    def shr(amt: UInt): UInt = (src >> amt)
    def flBit(v: UInt): Bool = v(8)
    // subtract a constant from `count`, clamped to >= 0 representation (used where the
    // C-formula amount is guaranteed >= 0).
    def cMinus(k: Int): UInt = (U(k, 7 bits) - count.resize(7)).resize(7)

    // ── ASR ───────────────────────────────────────────────────────────────────
    val asrArea = new Area {
      // sign-extended right shift: src>>count | (sign ? table[count] : 0), count<size.
      val res = (src >> count) | Mux(srcMsb, shTable(count), U(0, 32 bits))
      val n = nOf(res); val z = zOf(res)
      // imm/reg-.B C: src << (9-count); reg .W/.L C: (src>>(count-1))<<8
      val cImm = flBit(shl(cMinus(9)))
      val cRegB = cImm
      val cRegWL = flBit((shr((count - 1).resize(6)) << 8).resize(66))
    }

    // ── LSR ───────────────────────────────────────────────────────────────────
    val lsrArea = new Area {
      val res = src >> count
      val z = zOf(res)
      val cImm = flBit(shl(cMinus(9)))                                  // src<<(9-count)
      val cRegB = cImm
      val cRegWL = flBit((shr((count - 1).resize(6)) << 8).resize(66))  // (src>>(count-1))<<8
    }

    // ── ASL / LSL share the left result; ASL adds V ─────────────────────────────
    val lshArea = new Area {
      val resW = shl(count) & mask.resize(66)
      val res  = resW.resize(32)
      val n = nOf(res); val z = (resW & mask.resize(66)) === 0
      // C per size:
      //  .B  imm/reg: src << count            -> bit8
      //  .W  imm:     src >> (8-count)         -> bit8 ; reg: (src<<count)>>8 -> bit8
      //  .L  imm:     src >> (24-count)        -> bit8 ; reg: (src>>(32-count))<<8 -> bit8
      val cB    = flBit(shl(count))
      val cWimm = flBit(shr(cMinus(8).resize(6)))
      val cWreg = flBit((shl(count) >> 8).resize(66))
      val cLimm = flBit(shr(cMinus(24).resize(6)))
      val cLreg = flBit((shr(cMinus(32).resize(6)) << 8).resize(66))
      // V (ASL): tbl = table[count+1]; sv = src & tbl; v = !(sv==0 || sv==tbl [|| (.B && sv==tbl && count<8)])
      val tbl = shTable((count + 1).resize(6))
      val sv  = src & tbl
      val vImm = !((sv === 0) || ((sv === tbl) && (!isB || count < 8)))
      val vReg = !((sv === 0) || (sv === tbl))
    }

    // The op/dir mux. Use shiftOp ## dirLeft as a 3-bit selector.
    val sel = cmd.shiftOp @@ cmd.dirLeft                           // {tt[1:0], dir}

    switch(sel) {
      // ASR (tt=0, dir=0)
      is(U"000") {
        when(cmd.isImm) {
          setRsp(asrArea.res, asrArea.n, asrArea.z, False, asrArea.cImm, asrArea.cImm)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, cmd.xIn)        // count0: C=0, X untouched
          } otherwise {
            when(count < sizeBits) {
              val c = Mux(isB, asrArea.cRegB, asrArea.cRegWL)
              setRsp(asrArea.res, asrArea.n, asrArea.z, False, c, c)
            } otherwise {
              when(srcMsb) { setRsp(mask, True, False, False, True, True) }
                .otherwise { setRsp(U(0, 32 bits), False, True, False, False, False) }
            }
          }
        }
      }
      // ASL (tt=0, dir=1)
      is(U"001") {
        when(cmd.isImm) {
          val c = cmd.size.mux(Size.BYTE -> lshArea.cB, Size.WORD -> lshArea.cWimm, Size.LONG -> lshArea.cLimm)
          setRsp(lshArea.res.resize(32), lshArea.n, lshArea.z, lshArea.vImm, c, c)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, cmd.xIn)
          } otherwise {
            when(count < sizeBits) {
              val c = cmd.size.mux(Size.BYTE -> lshArea.cB, Size.WORD -> lshArea.cWreg, Size.LONG -> lshArea.cLreg)
              setRsp(lshArea.res.resize(32), lshArea.n, lshArea.z, lshArea.vReg, c, c)
            } otherwise {
              // shift >= size: result 0, N=0, Z=1, V=(src!=0), C=X=(shift==size ? src&1 : 0)
              val c = (count === sizeBits) && src(0)
              setRsp(U(0, 32 bits), False, True, src =/= 0, c, c)
            }
          }
        }
      }
      // LSR (tt=1, dir=0)
      is(U"010") {
        when(cmd.isImm) {
          setRsp(lsrArea.res, False, lsrArea.z, False, lsrArea.cImm, lsrArea.cImm)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, cmd.xIn)
          } otherwise {
            // .B/.W: within if count<=size; .L: count<32. equal-size edge differs.
            val withinBW = count <= sizeBits
            val withinL  = count < sizeBits
            when((isL && withinL) || (!isL && withinBW)) {
              val c = Mux(isB, lsrArea.cRegB, lsrArea.cRegWL)
              setRsp(lsrArea.res, False, lsrArea.z, False, c, c)
            } otherwise {
              // out of range. .L count>=32: C=X=(count==32 ? msb : 0). .B/.W: 0.
              val cL = (count === sizeBits) && srcMsb
              val c = Mux(isL, cL, False)
              setRsp(U(0, 32 bits), False, True, False, c, c)
            }
          }
        }
      }
      // LSL (tt=1, dir=1)
      is(U"011") {
        when(cmd.isImm) {
          val c = cmd.size.mux(Size.BYTE -> lshArea.cB, Size.WORD -> lshArea.cWimm, Size.LONG -> lshArea.cLimm)
          setRsp(lshArea.res.resize(32), lshArea.n, lshArea.z, False, c, c)
        } otherwise {
          when(countZ) {
            setRsp(src, srcMsb, zOf(src), False, False, cmd.xIn)
          } otherwise {
            val withinBW = count <= sizeBits
            val withinL  = count < sizeBits
            when((isL && withinL) || (!isL && withinBW)) {
              val c = cmd.size.mux(Size.BYTE -> lshArea.cB, Size.WORD -> lshArea.cWreg, Size.LONG -> lshArea.cLreg)
              setRsp(lshArea.res.resize(32), lshArea.n, lshArea.z, False, c, c)
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
        val r = roxr(cmd, src, count, mask, sizeBits, srcMsb)
        rsp := r
      }
      // ROXL (tt=2, dir=1)
      is(U"101") {
        val r = roxl(cmd, src, count, mask, sizeBits, srcMsb)
        rsp := r
      }
      // ROR (tt=3, dir=0)
      is(U"110") {
        val r = ror(cmd, src, count, mask, sizeBits, srcMsb, isB)
        rsp := r
      }
      // ROL (tt=3, dir=1)
      default {  // U"111"
        val r = rol(cmd, src, count, mask, sizeBits, srcMsb, isB)
        rsp := r
      }
    }
  }.rsp

  // shared flag-bit helper for the rotate defs: bit 8 of a wide value.
  private def flb(v: UInt): Bool = v(8)

  // ── ROR ──────────────────────────────────────────────────────────────────────
  private def ror(cmd: ShiftCmd, src: UInt, count: UInt, mask: UInt, sizeBits: UInt, srcMsb: Bool, isB: Bool): ShiftRsp = new Area {
    val rsp = ShiftRsp()
    val src66 = src.resize(66)
    val sMod = cmd.size.mux(Size.BYTE -> count(2 downto 0).resize(6), Size.WORD -> count(3 downto 0).resize(6), Size.LONG -> count(4 downto 0).resize(6))
    val srcLow = src & mask
    val backAmt = (sizeBits - sMod.resize(7)).resize(6)
    val rotated = (((srcLow >> sMod) | (srcLow << backAmt).resize(32)) & mask).resize(32)
    val res = Mux(sMod === 0, srcLow.resize(32), rotated)
    val n = cmd.size.mux(Size.BYTE -> res(7), Size.WORD -> res(15), Size.LONG -> res(31))
    val z = (res & mask) === 0
    // C imm: src << (9-orig); reg .B: src<<(8-((shift-1)&7)); reg .W/.L: (src>>((shift-1)&(size-1)))<<8
    val cImm  = flb((src66 << (U(9, 7 bits) - count.resize(7)).resize(7)).resize(66))
    val cRegB = flb((src66 << (U(8, 7 bits) - ((count - 1) & 7).resize(7)).resize(7)).resize(66))
    val sizeMask6 = (sizeBits.resize(6) - 1)
    val cRegWL = flb(((src >> ((count - 1) & sizeMask6)) << 8).resize(66))
    when(cmd.isImm) {
      rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cImm; rsp.xOut := cmd.xIn
    } otherwise {
      when(count === 0) {
        rsp.result := (srcLow & mask).asBits; rsp.n := srcMsb; rsp.z := (srcLow & mask) === 0; rsp.v := False; rsp.c := False; rsp.xOut := cmd.xIn
      } otherwise {
        val c = Mux(isB, cRegB, cRegWL)
        rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := c; rsp.xOut := cmd.xIn
      }
    }
  }.rsp

  // ── ROL ──────────────────────────────────────────────────────────────────────
  private def rol(cmd: ShiftCmd, src: UInt, count: UInt, mask: UInt, sizeBits: UInt, srcMsb: Bool, isB: Bool): ShiftRsp = new Area {
    val rsp = ShiftRsp()
    val src66 = src.resize(66)
    val sMod = cmd.size.mux(Size.BYTE -> count(2 downto 0).resize(6), Size.WORD -> count(3 downto 0).resize(6), Size.LONG -> count(4 downto 0).resize(6))
    val srcLow = src & mask
    val backAmt = (sizeBits - sMod.resize(7)).resize(6)
    val rotated = (((srcLow << sMod).resize(32) | (srcLow >> backAmt)) & mask).resize(32)
    val res = Mux(sMod === 0, srcLow.resize(32), rotated)
    val n = cmd.size.mux(Size.BYTE -> res(7), Size.WORD -> res(15), Size.LONG -> res(31))
    val z = (res & mask) === 0
    val cImmB = flb((src66 << count).resize(66))                                  // src << orig_shift
    val cImmW = flb(src >> (U(8, 7 bits) - sMod.resize(7)).resize(6))
    val cImmL = flb(src >> (U(24, 7 bits) - sMod.resize(7)).resize(6))
    val cRegB = flb((src66 << sMod).resize(66))
    val cRegW = flb(((src66 << sMod).resize(66) >> 8).resize(66))
    val cRegL = flb(((src >> ((U(32, 7 bits) - sMod.resize(7)) & 0x1f).resize(6)) << 8).resize(66))
    when(cmd.isImm) {
      val c = cmd.size.mux(Size.BYTE -> cImmB, Size.WORD -> cImmW, Size.LONG -> cImmL)
      rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := c; rsp.xOut := cmd.xIn
    } otherwise {
      when(count === 0) {
        rsp.result := (srcLow & mask).asBits; rsp.n := srcMsb; rsp.z := (srcLow & mask) === 0; rsp.v := False; rsp.c := False; rsp.xOut := cmd.xIn
      } otherwise {
        when(sMod === 0) {
          // nonzero orig multiple of size: res=src, C=(src&1)
          rsp.result := (srcLow & mask).asBits; rsp.n := srcMsb; rsp.z := (srcLow & mask) === 0; rsp.v := False; rsp.c := src(0); rsp.xOut := cmd.xIn
        } otherwise {
          val c = cmd.size.mux(Size.BYTE -> cRegB, Size.WORD -> cRegW, Size.LONG -> cRegL)
          rsp.result := (res & mask).asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := c; rsp.xOut := cmd.xIn
        }
      }
    }
  }.rsp

  // ── ROXR ──────────────────────────────────────────────────────────────────────
  private def roxr(cmd: ShiftCmd, src: UInt, count: UInt, mask: UInt, sizeBits: UInt, srcMsb: Bool): ShiftRsp = new Area {
    val rsp = ShiftRsp()
    // ring width w = size+1; X sits at bit `size`. ext = src | (x << size).
    val wBits = (sizeBits + 1).resize(7)                              // 9/17/33
    val xShifted = (cmd.xIn.asUInt.resize(66) << sizeBits.resize(6)).resize(66)   // x at bit `size`
    val ext = src.resize(66) | xShifted
    // shift = count % w (reg) ; imm count is 1..8 (< w always)
    val shift = (cmd.isImm ? count.resize(7) | rmod(count, wBits)).resize(6)
    // res = (ext >> shift) | (ext << (w - shift)), within w bits.
    val backAmt = (wBits - shift.resize(7)).resize(6)
    val rsh = ext >> shift
    val lsh = (ext << backAmt).resize(66)
    val ringMask = (((U(1, 67 bits) << wBits) - 1).resize(66))
    val res = ((rsh | lsh) & ringMask)
    val cx = res(sizeBits.resize(6))                                  // bit `size` = X out
    val low = res.resize(32) & mask
    val n = cmd.size.mux(Size.BYTE -> low(7), Size.WORD -> low(15), Size.LONG -> low(31))
    val z = (low & mask) === 0
    when(cmd.isImm) {
      rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
    } otherwise {
      when(count === 0) {
        rsp.result := (src & mask).asBits; rsp.n := srcMsb; rsp.z := (src & mask) === 0; rsp.v := False; rsp.c := cmd.xIn; rsp.xOut := cmd.xIn
      } otherwise {
        rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
      }
    }
  }.rsp

  // ── ROXL ──────────────────────────────────────────────────────────────────────
  private def roxl(cmd: ShiftCmd, src: UInt, count: UInt, mask: UInt, sizeBits: UInt, srcMsb: Bool): ShiftRsp = new Area {
    val rsp = ShiftRsp()
    val wBits = (sizeBits + 1).resize(7)
    val xShifted = (cmd.xIn.asUInt.resize(66) << sizeBits.resize(6)).resize(66)
    val ext = src.resize(66) | xShifted
    val shift = (cmd.isImm ? count.resize(7) | rmod(count, wBits)).resize(6)
    val backAmt = (wBits - shift.resize(7)).resize(6)
    val lsh = (ext << shift).resize(66)
    val rsh = ext >> backAmt
    val ringMask = (((U(1, 67 bits) << wBits) - 1).resize(66))
    val res = ((lsh | rsh) & ringMask)
    val cx = res(sizeBits.resize(6))
    val low = res.resize(32) & mask
    val n = cmd.size.mux(Size.BYTE -> low(7), Size.WORD -> low(15), Size.LONG -> low(31))
    val z = (low & mask) === 0
    when(cmd.isImm) {
      rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
    } otherwise {
      when(count === 0) {
        rsp.result := (src & mask).asBits; rsp.n := srcMsb; rsp.z := (src & mask) === 0; rsp.v := False; rsp.c := cmd.xIn; rsp.xOut := cmd.xIn
      } otherwise {
        rsp.result := low.asBits; rsp.n := n; rsp.z := z; rsp.v := False; rsp.c := cx; rsp.xOut := cx
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
