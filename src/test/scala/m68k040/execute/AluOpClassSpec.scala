package m68k040.execute

import m68k040.M68kSim
import m68k040.decode.{AluCin, AluOpClass, DecOp, DecodedUop}
import m68k040.isa.{Cluster, Size}
import m68k040.rename.{RenameStage, RenameUopSinkPlugin, RenameCommitDriverPlugin, DecodeUopSourcePlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** FMax "Lever N-A" correctness gate.
  *
  * The lever pre-decodes the ALU op-class control ([[AluOpClass]]) at RENAME time and
  * carries it in the µop, so the ALU EU stops re-deriving `DecOp` equality compares
  * combinationally, in series with its 32-bit adder, at S1.
  *
  * That splits the correctness obligation cleanly in two, and this spec discharges
  * both:
  *
  *   1. `AluOpClass.of(op)` must equal, for EVERY `DecOp` encoding, exactly what the
  *      pre-lever inline expressions computed. Proven two ways: a field-by-field
  *      differential against a VERBATIM transcription of those expressions, and a
  *      whole-datapath differential against a frozen copy of the pre-lever
  *      `AluDatapath` (`AluDatapathRef` below).
  *   2. The µop field `aluCls` really is `AluOpClass.of(op)` for the op the same µop
  *      carries — i.e. `RenameStage` pairs them. Proven live, through the real rename
  *      hardware, for every `DecOp` encoding.
  *
  * Anything the lever changed that is NOT covered here is covered by a plain
  * exhaustive-case argument in the source comment (the `finalNzvc`/`finalX` mux
  * re-association in `AluEuPlugin` is unconditionally value-identical over all four
  * select combinations — see the comment there) plus the existing `AluEuSpec` /
  * `AluDatapathSpec` / `ExecuteLockStepSpec` suites.
  */
class AluOpClassSpec extends AnyFunSuite {

  // ══════════════════════════════════════════════════════════════════════════════
  // Frozen PRE-Lever-N-A reference datapath.
  //
  // Transcribed verbatim from src/main/scala/m68k040/execute/AluDatapath.scala at
  // commit 344c0c5 (the parent of this lever). Everything is derived from `cmd.op`
  // the old way. DO NOT "fix up" this copy to track future AluDatapath changes for
  // its own sake — it exists to pin THIS lever's no-behaviour-change claim. If a
  // later change makes it diverge legitimately, the divergence must be understood
  // and this reference updated deliberately, not reflexively.
  // ══════════════════════════════════════════════════════════════════════════════
  object AluDatapathRef {
    def apply(cmd: AluCmd): AluRsp = {
      val a = cmd.src1.asUInt
      val b = cmd.src2.asUInt

      val isSub   = cmd.op === DecOp.SUB || cmd.op === DecOp.CMP
      val isNeg   = cmd.op === DecOp.NEG
      val isNegx  = cmd.op === DecOp.NEGX
      val isAddx  = cmd.op === DecOp.ADDX
      val isSubx  = cmd.op === DecOp.SUBX
      val isArith = cmd.op === DecOp.ADD || isSub || isNeg || isNegx || isAddx || isSubx
      val aEff = Mux(isNeg || isNegx, U(0, 32 bits), a)
      val bEff = Mux(isNeg || isNegx, ~a, Mux(isSub || isSubx, ~b, b))
      val cin  = Mux(isNegx || isSubx, !cmd.xIn,
                   Mux(isSub || isNeg, True,
                     Mux(isAddx, cmd.xIn, False)))
      val sum32 = aEff + bEff + cin.asUInt.resize(32)

      val swapRes = a(15 downto 0) ## a(31 downto 16)
      val extRes  = Mux(cmd.extByte,
                        a(7 downto 0).asSInt.resize(32).asBits,
                        a(15 downto 0).asSInt.resize(32).asBits).asUInt
      val tasRes  = (a(31 downto 8) ## (a(7 downto 0) | U(0x80, 8 bits)))
      val notRes  = ~a

      val bnRaw   = cmd.src2.asUInt
      val bn      = Mux(cmd.size === Size.LONG, bnRaw(4 downto 0), bnRaw(2 downto 0).resize(5))
      val bmask   = ((U(1, 32 bits) << bn).resize(32)).asBits
      val bTestZ  = (cmd.src1 & bmask) === 0
      val bitRes  = cmd.bitOp.mux(
        B"00" -> cmd.src1,
        B"01" -> (cmd.src1 ^ bmask),
        B"10" -> (cmd.src1 & ~bmask),
        B"11" -> (cmd.src1 | bmask))

      val result = cmd.op.mux(
        DecOp.MOVE -> cmd.src2.asUInt,
        DecOp.AND  -> (a & b),
        DecOp.OR   -> (a | b),
        DecOp.EOR  -> (a ^ b),
        DecOp.NOT  -> notRes,
        DecOp.CLR  -> U(0, 32 bits),
        DecOp.TST  -> a,
        DecOp.SWAP -> swapRes.asUInt,
        DecOp.EXT  -> extRes,
        DecOp.TAS  -> tasRes.asUInt,
        DecOp.BITOP-> bitRes.asUInt,
        default    -> sum32
      )

      def piece(w: Int) = new Area {
        val ext  = (False ## aEff(w - 1 downto 0)).asUInt + (False ## bEff(w - 1 downto 0)).asUInt + cin.asUInt
        val cout = ext(w)
        val n    = result(w - 1)
        val z    = result(w - 1 downto 0) === 0
        val v    = (aEff(w - 1) === bEff(w - 1)) && (result(w - 1) =/= aEff(w - 1))
      }
      val p8 = piece(8); val p16 = piece(16); val p32 = piece(32)
      def bySize[T <: Data](vb: T, vw: T, vl: T): T =
        cmd.size.mux(Size.BYTE -> vb, Size.WORD -> vw, Size.LONG -> vl)

      val nGen   = bySize(p8.n, p16.n, p32.n)
      val zGen   = bySize(p8.z, p16.z, p32.z)
      val vArith = bySize(p8.v, p16.v, p32.v)
      val cout   = bySize(p8.cout, p16.cout, p32.cout)

      val isTas   = cmd.op === DecOp.TAS
      val isBitOp = cmd.op === DecOp.BITOP
      val n     = Mux(isTas, a(7), nGen)
      val z     = Mux(isTas, a(7 downto 0) === 0, Mux(isBitOp, bTestZ, zGen))

      val cFlag = Mux(isArith, Mux(isSub || isNeg || isNegx || isSubx, ~cout, cout), False)
      val vFlag = Mux(isArith, vArith, False)

      val rsp = AluRsp()
      rsp.result := result.asBits
      rsp.nzvc   := n ## z ## vFlag ## cFlag
      rsp.xOut   := cFlag
      rsp
    }
  }

  /** DUT: the live datapath (driven by the pre-decoded class, exactly as the ALU EU
    * drives it) side by side with the frozen reference, plus a field-by-field
    * comparison of `AluOpClass.of` against the verbatim pre-lever expressions.
    *
    * NOTE the deliberate asymmetry: `newRsp` is fed via the TWO-argument
    * `AluDatapath(cmd, cls)` entry point — the one the ALU EU uses — so this DUT
    * exercises the real shipping path, not the convenience overload. */
  class EquivDut extends Component {
    val io = new Bundle {
      val cmd    = in(AluCmd())
      val newRsp = out(AluRsp())
      val refRsp = out(AluRsp())
      // per-field differential vs the verbatim pre-lever expressions
      val badZeroA   = out Bool()
      val badInvB    = out Bool()
      val badCin     = out Bool()
      val badArith   = out Bool()
      val badBorrow  = out Bool()
      val badTas     = out Bool()
      val badBitOp   = out Bool()
      val badExt     = out Bool()
      val badNbcd    = out Bool()
      val badBcd     = out Bool()
      val badCas     = out Bool()
      val badShift   = out Bool()
      val badBf      = out Bool()
    }
    val cls = AluOpClass.of(io.cmd.op)
    io.newRsp := AluDatapath(io.cmd, cls)
    io.refRsp := AluDatapathRef(io.cmd)

    // ── verbatim pre-lever expressions (AluDatapath.scala + AluEuPlugin.scala @344c0c5)
    val op     = io.cmd.op
    val rSub   = op === DecOp.SUB || op === DecOp.CMP
    val rNeg   = op === DecOp.NEG
    val rNegx  = op === DecOp.NEGX
    val rAddx  = op === DecOp.ADDX
    val rSubx  = op === DecOp.SUBX
    val rArith = op === DecOp.ADD || rSub || rNeg || rNegx || rAddx || rSubx
    val rCin   = Mux(rNegx || rSubx, !io.cmd.xIn,
                   Mux(rSub || rNeg, True,
                     Mux(rAddx, io.cmd.xIn, False)))
    // the new cinSel, decoded back to a carry-in (must equal rCin for both xIn values)
    val nCin   = cls.cinSel.mux(
      B(AluCin.ZERO, 2 bits) -> False,
      B(AluCin.ONE,  2 bits) -> True,
      B(AluCin.X,    2 bits) -> io.cmd.xIn,
      B(AluCin.NOTX, 2 bits) -> !io.cmd.xIn)

    io.badZeroA  := cls.zeroA      =/= (rNeg || rNegx)
    io.badInvB   := cls.invB       =/= (rSub || rSubx)
    io.badCin    := nCin           =/= rCin
    io.badArith  := cls.isArith    =/= rArith
    io.badBorrow := cls.borrow     =/= (rSub || rNeg || rNegx || rSubx)
    io.badTas    := cls.isTas      =/= (op === DecOp.TAS)
    io.badBitOp  := cls.isBitOp    =/= (op === DecOp.BITOP)
    // AluEuPlugin.scala @344c0c5 :224 / :248-249 / :373 / :174 / :179
    io.badExt    := cls.isExtended =/= ((op === DecOp.NEGX) || (op === DecOp.ADDX) || (op === DecOp.SUBX))
    io.badNbcd   := cls.isNbcd     =/= (op === DecOp.NBCD)
    io.badBcd    := cls.isBcd      =/= ((op === DecOp.BCD) || (op === DecOp.NBCD))
    io.badCas    := cls.isCasOp    =/= (op === DecOp.CASOP)
    io.badShift  := cls.isShift    =/= (op === DecOp.SHIFT)
    io.badBf     := cls.isBitfield =/= (op === DecOp.BITFIELD)
  }

  val ALL_OPS: Seq[SpinalEnumElement[DecOp.type]] = DecOp.elements.toSeq
  val SIZES = Seq(Size.BYTE, Size.WORD, Size.LONG)

  /** Operand corner set: zero/one/sign boundaries at every width + the values that
    * make a subtract-from-zero, a byte/word carry-out and a signed overflow fire. */
  val CORNERS: Seq[Long] = Seq(
    0x00000000L, 0x00000001L, 0x0000007fL, 0x00000080L, 0x000000ffL,
    0x00007fffL, 0x00008000L, 0x0000ffffL, 0x7fffffffL, 0x80000000L,
    0xffffffffL, 0xfffffffeL, 0x12345678L, 0xdeadbeefL, 0x0000ff00L, 0x01020304L)

  test("AluOpClass.of == the verbatim pre-Lever-N-A inline compares, over EVERY DecOp") {
    M68kSim().compile(new EquivDut).doSim { dut =>
      var checked = 0
      for { op <- ALL_OPS; xIn <- Seq(false, true) } {
        dut.io.cmd.op      #= op
        dut.io.cmd.size    #= Size.LONG
        dut.io.cmd.src1    #= BigInt(0)
        dut.io.cmd.src2    #= BigInt(0)
        dut.io.cmd.xIn     #= xIn
        dut.io.cmd.extByte #= false
        dut.io.cmd.bitOp   #= 0
        sleep(1)
        def chk(b: Bool, name: String): Unit =
          assert(!b.toBoolean, s"AluOpClass.of mismatch on field '$name' for op=$op xIn=$xIn")
        chk(dut.io.badZeroA,  "zeroA");   chk(dut.io.badInvB,   "invB")
        chk(dut.io.badCin,    "cinSel");  chk(dut.io.badArith,  "isArith")
        chk(dut.io.badBorrow, "borrow");  chk(dut.io.badTas,    "isTas")
        chk(dut.io.badBitOp,  "isBitOp"); chk(dut.io.badExt,    "isExtended")
        chk(dut.io.badNbcd,   "isNbcd");  chk(dut.io.badBcd,    "isBcd")
        chk(dut.io.badCas,    "isCasOp"); chk(dut.io.badShift,  "isShift")
        chk(dut.io.badBf,     "isBitfield")
        checked += 1
      }
      // Sanity: the sweep really was exhaustive over the enum, not a stub.
      assert(checked == ALL_OPS.size * 2, s"swept $checked, expected ${ALL_OPS.size * 2}")
      assert(ALL_OPS.size >= 30, s"DecOp only has ${ALL_OPS.size} elements — enum lookup broke")
      println(s"[AluOpClassSpec] field differential: ${ALL_OPS.size} DecOp encodings x 2 xIn = $checked points, 0 mismatches")
    }
  }

  test("AluDatapath(cmd, of(op)) is bit-identical to the frozen pre-Lever-N-A datapath") {
    M68kSim().compile(new EquivDut).doSim { dut =>
      val rnd = new Random(0xA1)
      var points = 0

      def one(op: SpinalEnumElement[DecOp.type], sz: SpinalEnumElement[Size.type],
              s1: Long, s2: Long, xIn: Boolean, extByte: Boolean, bitOp: Int): Unit = {
        dut.io.cmd.op      #= op
        dut.io.cmd.size    #= sz
        dut.io.cmd.src1    #= BigInt(s1 & 0xffffffffL)
        dut.io.cmd.src2    #= BigInt(s2 & 0xffffffffL)
        dut.io.cmd.xIn     #= xIn
        dut.io.cmd.extByte #= extByte
        dut.io.cmd.bitOp   #= bitOp
        sleep(1)
        val nR = dut.io.newRsp.result.toBigInt; val rR = dut.io.refRsp.result.toBigInt
        val nN = dut.io.newRsp.nzvc.toInt;      val rN = dut.io.refRsp.nzvc.toInt
        val nX = dut.io.newRsp.xOut.toBoolean;  val rX = dut.io.refRsp.xOut.toBoolean
        val ctx = f"op=$op sz=$sz src1=0x$s1%08x src2=0x$s2%08x x=$xIn extByte=$extByte bitOp=$bitOp"
        assert(nR == rR, f"result $nR%x != ref $rR%x  [$ctx]")
        assert(nN == rN, f"nzvc  $nN%x != ref $rN%x  [$ctx]")
        assert(nX == rX, s"xOut  $nX != ref $rX  [$ctx]")
        points += 1
      }

      // (a) full corner cross-product on every op / size / xIn / extByte / bitOp face
      for {
        op  <- ALL_OPS
        sz  <- SIZES
        s1  <- CORNERS
        s2  <- CORNERS
        xIn <- Seq(false, true)
      } one(op, sz, s1, s2, xIn, extByte = ((s1 ^ s2) & 1) != 0, bitOp = (s2 & 3).toInt)
      // (b) randomized fill (catches anything the corner lattice misses)
      for (_ <- 0 until 20000) {
        val op = ALL_OPS(rnd.nextInt(ALL_OPS.size))
        val sz = SIZES(rnd.nextInt(SIZES.size))
        one(op, sz, rnd.nextInt().toLong & 0xffffffffL, rnd.nextInt().toLong & 0xffffffffL,
            rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextInt(4))
      }
      println(s"[AluOpClassSpec] datapath differential: $points points, 0 mismatches")
      assert(points > 40000, s"only $points points — sweep collapsed")
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Pairing gate: does the REAL rename hardware carry `aluCls == of(op)`?
  // ══════════════════════════════════════════════════════════════════════════════

  /** Pure-Scala model of `AluOpClass.of`, written independently of the RTL. */
  case class ClsModel(zeroA: Boolean, invB: Boolean, cinSel: Int, isArith: Boolean,
                      borrow: Boolean, isTas: Boolean, isBitOp: Boolean,
                      isExtended: Boolean, isNbcd: Boolean, isBcd: Boolean,
                      isCasOp: Boolean, isShift: Boolean, isBitfield: Boolean)

  def model(e: SpinalEnumElement[DecOp.type]): ClsModel = {
    val isSub  = e == DecOp.SUB || e == DecOp.CMP
    val isNeg  = e == DecOp.NEG
    val isNegx = e == DecOp.NEGX
    val isAddx = e == DecOp.ADDX
    val isSubx = e == DecOp.SUBX
    val nbcd   = e == DecOp.NBCD
    ClsModel(
      zeroA      = isNeg || isNegx,
      invB       = isSub || isSubx,
      cinSel     = if (isNegx || isSubx) AluCin.NOTX
                   else if (isSub || isNeg) AluCin.ONE
                   else if (isAddx) AluCin.X else AluCin.ZERO,
      isArith    = e == DecOp.ADD || isSub || isNeg || isNegx || isAddx || isSubx,
      borrow     = isSub || isNeg || isNegx || isSubx,
      isTas      = e == DecOp.TAS,
      isBitOp    = e == DecOp.BITOP,
      isExtended = isNegx || isAddx || isSubx,
      isNbcd     = nbcd,
      isBcd      = e == DecOp.BCD || nbcd,
      isCasOp    = e == DecOp.CASOP,
      isShift    = e == DecOp.SHIFT,
      isBitfield = e == DecOp.BITFIELD)
  }

  class RenDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new DecodeUopSourcePlugin
    val ren  = new RenameStage
    val sink = new RenameUopSinkPlugin
    val cdrv = new RenameCommitDriverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](dsrc, ren, sink, cdrv)) }
  }

  test("RenameStage pairs aluCls with op for EVERY DecOp encoding (live pairing gate)") {
    M68kSim().compile(new RenDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.sink.logic.out.ready #= true
      dut.dsrc.logic.src.valid #= false
      dut.cdrv.logic.flushIn   #= false
      dut.cdrv.logic.cmd.foreach(_.valid #= false)
      dut.dsrc.logic.s1v       #= false
      cd.waitSampling()

      // wait for the committed-identity + freelist init to complete
      var n = 0
      while (!dut.dsrc.logic.src.ready.toBoolean && n < 500) { cd.waitSampling(); n += 1 }
      assert(dut.dsrc.logic.src.ready.toBoolean, "rename init never completed")

      def poke(u: DecodedUop, op: SpinalEnumElement[DecOp.type], valid: Boolean): Unit = {
        u.valid #= valid; u.pc #= 0; u.nextPc #= 0
        u.op #= op; u.cluster #= Cluster.INT; u.size #= Size.LONG
        u.srcAReg #= 0; u.srcAValid #= false
        u.srcBReg #= 0; u.srcBValid #= false
        u.dstReg  #= 0; u.dstValid  #= false
        u.useImm #= false; u.imm #= 0
        u.readsNzvc #= false; u.readsX #= false
        u.writesNzvc #= false; u.writesX #= false
        u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
        u.unimplemented #= false
      }

      var observed = 0
      // Push every DecOp encoding through BOTH rename slots (slot1 takes the
      // intra-group-bypass path, so check it too), one pair per cycle.
      for (i <- ALL_OPS.indices) {
        val op0 = ALL_OPS(i)
        val op1 = ALL_OPS((i + 1) % ALL_OPS.size)
        poke(dut.dsrc.logic.src.payload(0), op0, valid = true)
        poke(dut.dsrc.logic.src.payload(1), op1, valid = true)
        dut.dsrc.logic.s1v       #= true
        dut.dsrc.logic.src.valid #= true
        // Let the group actually FIRE through rename, then read back. The rename
        // output payload is combinational off the still-held stimulus, so the extra
        // settle avoids any delta-cycle ambiguity about which poke is being observed.
        cd.waitSampling()
        sleep(1)
        assert(dut.sink.logic.out.valid.toBoolean, "rename output went invalid mid-sweep")
        for (s <- 0 until 2) {
          val p = dut.sink.logic.out.payload(s)
          val expOp = if (s == 0) op0 else op1
          assert(p.op.toEnum == expOp, s"slot$s carried op=${p.op.toEnum}, expected $expOp " +
            "(the pairing gate must observe the µop it just pushed)")
          val m = model(expOp)
          val c = p.aluCls
          def chk(got: Boolean, exp: Boolean, name: String): Unit =
            assert(got == exp, s"slot$s op=$expOp: aluCls.$name = $got, expected $exp")
          chk(c.zeroA.toBoolean,      m.zeroA,      "zeroA")
          chk(c.invB.toBoolean,       m.invB,       "invB")
          assert(c.cinSel.toInt == m.cinSel, s"slot$s op=$expOp: cinSel = ${c.cinSel.toInt}, expected ${m.cinSel}")
          chk(c.isArith.toBoolean,    m.isArith,    "isArith")
          chk(c.borrow.toBoolean,     m.borrow,     "borrow")
          chk(c.isTas.toBoolean,      m.isTas,      "isTas")
          chk(c.isBitOp.toBoolean,    m.isBitOp,    "isBitOp")
          chk(c.isExtended.toBoolean, m.isExtended, "isExtended")
          chk(c.isNbcd.toBoolean,     m.isNbcd,     "isNbcd")
          chk(c.isBcd.toBoolean,      m.isBcd,      "isBcd")
          chk(c.isCasOp.toBoolean,    m.isCasOp,    "isCasOp")
          chk(c.isShift.toBoolean,    m.isShift,    "isShift")
          chk(c.isBitfield.toBoolean, m.isBitfield, "isBitfield")
          observed += 1
        }
      }
      dut.dsrc.logic.src.valid #= false
      assert(observed == ALL_OPS.size * 2, s"observed $observed pairings, expected ${ALL_OPS.size * 2}")
      println(s"[AluOpClassSpec] rename pairing gate: $observed (op, aluCls) pairs checked live, 0 mismatches")
    }
  }
}
