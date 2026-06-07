package m68k040.execute

/** Pure-Scala reference for the line-E register-form shifts/rotates, mirroring
  * Musashi's m68k_in.c (asl/asr/lsl/lsr/rol/ror/roxl/roxr, the `s` immediate-count
  * and `r` register-count forms) and condensing FLAG_C/FLAG_X via `(x>>8)&1`.
  *
  * This is the directed-test ORACLE for the RTL barrel shifter. It is itself
  * validated against the LIVE Musashi runner in ShifterSpec so the mirror is
  * proven faithful before the RTL is checked against it.
  *
  * Every C/X computation below reproduces the EXACT Musashi FLAG_C/FLAG_X integer
  * expression, then condenses it with `flagBit` (= `(v>>8)&1`, the same
  * `(FLAG_C>>8)&1` Musashi uses to build the CCR). N = msb of the result; Z = result
  * low-`size` == 0; V = 0 except ASL.
  *
  * tt: 0=ASR/ASL, 1=LSR/LSL, 2=ROXR/ROXL, 3=ROR/ROL. dir: true=left, false=right.
  * size in {8,16,32}. `src` = the input register's low `size` bits (upper ignored).
  * isImm: true => `count` is the 1..8 immediate (the `s` form); false => `count`
  * is Dc & 0x3f (the `r` form). xIn: current X flag bit.
  *
  * `res` is the low-`size` result; `x` is the value Musashi leaves in FLAG_X
  * (== xIn where Musashi leaves X untouched). The EU's writesX mask (False for
  * ROL/ROR) decides whether `x` actually lands. */
object ShiftRef {
  final case class Out(res: Long, n: Int, z: Int, v: Int, c: Int, x: Int)

  private def mask(size: Int): Long = size match {
    case 8  => 0xffL
    case 16 => 0xffffL
    case 32 => 0xffffffffL
  }
  private def msbN(v: Long, size: Int): Int = ((v >>> (size - 1)) & 1L).toInt
  // m68ki_shift_N_table[k]: the high `k` bits set within the size width (k in 0..size).
  private def shTable(size: Int, k: Int): Long = {
    if (k <= 0) 0L
    else if (k >= size) mask(size)
    else (mask(size) ^ ((1L << (size - k)) - 1L)) & mask(size)
  }
  private def nz(res: Long, size: Int): (Int, Int) =
    (msbN(res, size), if ((res & mask(size)) == 0L) 1 else 0)
  // Condense a raw Musashi FLAG_C/FLAG_X integer to its boolean bit ((v>>8)&1).
  private def flagBit(v: Long): Int = ((v >>> 8) & 1L).toInt

  def eval(tt: Int, dir: Boolean, size: Int, src0: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val src = src0 & mask(size)
    (tt, dir) match {
      case (0, false) => asr(size, src, count, isImm, xIn)
      case (0, true)  => asl(size, src, count, isImm, xIn)
      case (1, false) => lsr(size, src, count, isImm, xIn)
      case (1, true)  => lsl(size, src, count, isImm, xIn)
      case (2, false) => roxr(size, src, count, isImm, xIn)
      case (2, true)  => roxl(size, src, count, isImm, xIn)
      case (3, false) => ror(size, src, count, isImm, xIn)
      case (3, true)  => rol(size, src, count, isImm, xIn)
      case _          => throw new IllegalArgumentException(s"bad tt=$tt")
    }
  }

  // ── ASR ──────────────────────────────────────────────────────────────────
  private def asr(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    if (isImm) {
      val shift = count
      var res = src >>> shift
      if (msbN(src, size) == 1) res |= shTable(size, shift)
      res &= m
      val (n, z) = nz(res, size)
      val c = flagBit(src << (9 - shift))             // FLAG_X = FLAG_C = src << (9-shift)
      Out(res, n, z, 0, c, c)
    } else {
      val shift = count
      if (shift != 0) {
        if (shift < size) {
          var res = src >>> shift
          if (msbN(src, size) == 1) res |= shTable(size, shift)
          res &= m
          val (n, z) = nz(res, size)
          val c =
            if (size == 8) flagBit(src << (9 - shift))            // .B: src << (9-shift)
            else flagBit((src >>> (shift - 1)) << 8)             // .W/.L: (src >> (shift-1))<<8
          Out(res, n, z, 0, c, c)
        } else {
          if (msbN(src, size) == 1) Out(m, 1, 0, 0, 1, 1)        // all ones; C=X=N=1, Z=0
          else Out(0, 0, 1, 0, 0, 0)                              // C=X=N=0, Z=1
        }
      } else Out(src, msbN(src, size), if (src == 0) 1 else 0, 0, 0, xIn)  // count0: C=0, X untouched
    }
  }

  // ── ASL ──────────────────────────────────────────────────────────────────
  private def asl(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    if (isImm) {
      val shift = count
      val res = (src << shift) & m
      val (n, z) = nz(res, size)
      val cc = size match {
        case 8  => flagBit(src << shift)              // FLAG_X = FLAG_C = src << shift
        case 16 => flagBit(src >>> (8 - shift))       // FLAG_X = FLAG_C = src >> (8-shift)
        case 32 => flagBit(src >>> (24 - shift))      // FLAG_X = FLAG_C = src >> (24-shift)
      }
      val tbl = shTable(size, shift + 1)
      val sv = src & tbl
      val v =
        if (size == 8) (if (sv == 0L || (sv == tbl && shift < 8)) 0 else 1)
        else (if (sv == 0L || sv == tbl) 0 else 1)
      Out(res, n, z, v, cc, cc)
    } else {
      val shift = count
      if (shift != 0) {
        if (shift < size) {
          val res = (src << shift) & m
          val (n, z) = nz(res, size)
          val cc = size match {
            case 8  => flagBit(src << shift)            // FLAG_X = FLAG_C = src << shift
            case 16 => flagBit((src << shift) >>> 8)    // FLAG_X = FLAG_C = (src << shift) >> 8
            case 32 => flagBit((src >>> (32 - shift)) << 8) // FLAG_X = FLAG_C = (src >> (32-shift))<<8
          }
          val tbl = shTable(size, shift + 1)
          val sv = src & tbl
          val v = if (sv == 0L || sv == tbl) 0 else 1
          Out(res, n, z, v, cc, cc)
        } else {
          // shift >= size: result 0, N=0, Z=1, V = (src != 0),
          // FLAG_X = FLAG_C = ((shift==size ? src&1 : 0))<<8
          val c = if (shift == size) flagBit((src & 1L) << 8) else 0
          val v = if (src != 0L) 1 else 0
          Out(0, 0, 1, v, c, c)
        }
      } else Out(src, msbN(src, size), if (src == 0) 1 else 0, 0, 0, xIn)
    }
  }

  // ── LSR ──────────────────────────────────────────────────────────────────
  private def lsr(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    if (isImm) {
      val shift = count
      val res = (src >>> shift) & m
      val (_, z) = nz(res, size)
      val c = flagBit(src << (9 - shift))             // FLAG_X = FLAG_C = src << (9-shift)
      Out(res, 0, z, 0, c, c)
    } else {
      val shift = count
      if (shift != 0) {
        if (size == 8) {
          if (shift <= 8) {
            val res = (src >>> shift) & m
            val (_, z) = nz(res, size)
            val c = flagBit(src << (9 - shift))
            Out(res, 0, z, 0, c, c)
          } else Out(0, 0, 1, 0, 0, 0)                 // X=C=0, N=0, Z=1
        } else if (size == 16) {
          if (shift <= 16) {
            val res = (src >>> shift) & m
            val (_, z) = nz(res, size)
            val c = flagBit((src >>> (shift - 1)) << 8) // FLAG_C = FLAG_X = (src >> (shift-1))<<8
            Out(res, 0, z, 0, c, c)
          } else Out(0, 0, 1, 0, 0, 0)
        } else {
          if (shift < 32) {
            val res = (src >>> shift) & m
            val (_, z) = nz(res, size)
            val c = flagBit((src >>> (shift - 1)) << 8)
            Out(res, 0, z, 0, c, c)
          } else {
            // shift >= 32: result 0; FLAG_X = FLAG_C = (shift==32 ? GET_MSB_32(src)>>23 : 0)
            // GET_MSB_32(src)>>23 = (src & 0x80000000)>>23 -> bit 8 set iff msb. flagBit -> msb.
            val c = if (shift == 32) msbN(src, 32) else 0
            Out(0, 0, 1, 0, c, c)
          }
        }
      } else Out(src, msbN(src, size), if (src == 0) 1 else 0, 0, 0, xIn)
    }
  }

  // ── LSL ──────────────────────────────────────────────────────────────────
  private def lsl(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    if (isImm) {
      val shift = count
      val res = (src << shift) & m
      val (n, z) = nz(res, size)
      val cc = size match {
        case 8  => flagBit(src << shift)
        case 16 => flagBit(src >>> (8 - shift))
        case 32 => flagBit(src >>> (24 - shift))
      }
      Out(res, n, z, 0, cc, cc)
    } else {
      val shift = count
      if (shift != 0) {
        val withinLimit = if (size == 32) shift < 32 else shift <= size
        if (withinLimit) {
          val res = (src << shift) & m
          val (n, z) = nz(res, size)
          val cc = size match {
            case 8  => flagBit(src << shift)
            case 16 => flagBit((src << shift) >>> 8)
            case 32 => flagBit((src >>> (32 - shift)) << 8)
          }
          Out(res, n, z, 0, cc, cc)
        } else {
          if (size == 32) {
            val c = if (shift == 32) flagBit((src & 1L) << 8) else 0
            Out(0, 0, 1, 0, c, c)
          } else Out(0, 0, 1, 0, 0, 0)                 // .B/.W shift>size: X=C=0
        }
      } else Out(src, msbN(src, size), if (src == 0) 1 else 0, 0, 0, xIn)
    }
  }

  // ── ROR (no X) ─────────────────────────────────────────────────────────────
  private def ror(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    def rorN(v: Long, s: Int): Long = if (s == 0) v & m else (((v & m) >>> s) | ((v & m) << (size - s))) & m
    if (isImm) {
      val origShift = count                 // 1..8
      val shift = origShift % size          // .B: origShift&7; .W/.L: == origShift
      val res = rorN(src, shift)
      val (n, z) = nz(res, size)
      val c = flagBit(src << (9 - origShift)) // FLAG_C = src << (9-orig_shift)
      Out(res, n, z, 0, c, xIn)             // X untouched
    } else {
      val origShift = count                 // 0..63
      val shift = origShift % size
      if (origShift != 0) {
        val res = rorN(src, shift)
        val (n, z) = nz(res, size)
        val c = size match {
          case 8  => flagBit(src << (8 - ((shift - 1) & 7)))      // FLAG_C = src << (8-((shift-1)&7))
          case 16 => flagBit((src >>> ((shift - 1) & 15)) << 8)   // FLAG_C = (src >> ((shift-1)&15))<<8
          case 32 => flagBit((src >>> ((shift - 1) & 31)) << 8)
        }
        Out(res, n, z, 0, c, xIn)
      } else Out(src, msbN(src, size), if (src == 0) 1 else 0, 0, 0, xIn)  // count0: C=0
    }
  }

  // ── ROL (no X) ─────────────────────────────────────────────────────────────
  private def rol(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    def rolN(v: Long, s: Int): Long = if (s == 0) v & m else (((v & m) << s) | ((v & m) >>> (size - s))) & m
    if (isImm) {
      val origShift = count
      val shift = origShift % size
      val res = rolN(src, shift)
      val (n, z) = nz(res, size)
      val cc = size match {
        case 8  => flagBit(src << origShift)          // FLAG_C = src << orig_shift
        case 16 => flagBit(src >>> (8 - shift))       // FLAG_C = src >> (8-shift)
        case 32 => flagBit(src >>> (24 - shift))      // FLAG_C = src >> (24-shift)
      }
      Out(res, n, z, 0, cc, xIn)
    } else {
      val origShift = count
      val shift = origShift % size
      if (origShift != 0) {
        if (shift != 0) {
          val res = rolN(src, shift)
          val (n, z) = nz(res, size)
          val cc = size match {
            case 8  => flagBit(src << shift)                       // FLAG_C = src << shift
            case 16 => flagBit((src << shift) >>> 8)               // FLAG_C = (src << shift)>>8
            case 32 => flagBit((src >>> ((32 - shift) & 0x1f)) << 8) // FLAG_C = (src >> ((32-shift)&0x1f))<<8
          }
          Out(res, n, z, 0, cc, xIn)
        } else {
          // shift==0 (orig a nonzero multiple of size): res = src, FLAG_C = (src&1)<<8
          val (n, z) = nz(src, size)
          Out(src, n, z, 0, flagBit((src & 1L) << 8), xIn)
        }
      } else Out(src, msbN(src, size), if (src == 0) 1 else 0, 0, 0, xIn)  // count0: C=0
    }
  }

  // ── ROXR (through X) ─────────────────────────────────────────────────────────
  private def roxr(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    val w = size + 1                     // (size+1)-bit ring with X at bit `size`
    val wm = (1L << w) - 1L
    def rorW(v: Long, s: Int): Long = if (s == 0) v & wm else (((v & wm) >>> s) | ((v & wm) << (w - s))) & wm
    if (isImm) {
      val shift = count                  // 1..8 (< w for every size)
      val ext = src | (xIn.toLong << size)
      val res = rorW(ext, shift)
      val cx = ((res >>> size) & 1L).toInt   // FLAG_C = FLAG_X = res >> size (the X bit out)
      val low = res & m
      val (n, z) = nz(low, size)
      Out(low, n, z, 0, cx, cx)
    } else {
      val origShift = count
      if (origShift != 0) {
        val shift = origShift % w
        val ext = src | (xIn.toLong << size)
        val res = rorW(ext, shift)
        val cx = ((res >>> size) & 1L).toInt
        val low = res & m
        val (n, z) = nz(low, size)
        Out(low, n, z, 0, cx, cx)
      } else {
        val (n, z) = nz(src, size)            // count0: C=X (unchanged), res = src
        Out(src, n, z, 0, xIn, xIn)
      }
    }
  }

  // ── ROXL (through X) ─────────────────────────────────────────────────────────
  private def roxl(size: Int, src: Long, count: Int, isImm: Boolean, xIn: Int): Out = {
    val m = mask(size)
    val w = size + 1
    val wm = (1L << w) - 1L
    def rolW(v: Long, s: Int): Long = if (s == 0) v & wm else (((v & wm) << s) | ((v & wm) >>> (w - s))) & wm
    if (isImm) {
      val shift = count
      val ext = src | (xIn.toLong << size)
      val res = rolW(ext, shift)
      val cx = ((res >>> size) & 1L).toInt
      val low = res & m
      val (n, z) = nz(low, size)
      Out(low, n, z, 0, cx, cx)
    } else {
      val origShift = count
      if (origShift != 0) {
        val shift = origShift % w
        val ext = src | (xIn.toLong << size)
        val res = rolW(ext, shift)
        val cx = ((res >>> size) & 1L).toInt
        val low = res & m
        val (n, z) = nz(low, size)
        Out(low, n, z, 0, cx, cx)
      } else {
        val (n, z) = nz(src, size)
        Out(src, n, z, 0, xIn, xIn)
      }
    }
  }
}
