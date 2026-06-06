package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, RegFilePluginInt, RegFilePluginNzvc, RegFileReadPort, RegFileWritePort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Test-only source: drives BranchEu.issue from IO, owns an NZVC write port to
  * preload the flag value, and exposes completion + a NZVC read port for sanity.
  */
class BranchEuSourcePlugin extends FiberPlugin {
  var nzW: RegFileWritePort = null
  // The branch EU now reads the int PRF (ibranch target base), so the int regfile
  // is in the DUT and needs ≥1 write port for its init sweep. Own one (idle here).
  var intW: RegFileWritePort = null

  during setup {
    nzW = host[NzvcRegFileService].newWrite(latency = 1)
    intW = host[IntRegFileService].newWrite(latency = 1)
  }

  val logic = during build new Area {
    val eu = host[BranchEuService]
    intW.valid := False; intW.address := 0; intW.data := 0

    // ---- issue inputs (sim-driven) ----
    val iValid    = in Bool ()
    val iCond     = in Bits (4 bits)
    val iPc       = in UInt (32 bits)
    val iDisp     = in Bits (32 bits)
    val iPNzvcSrc = in UInt (4 bits)
    val iRobId    = in UInt (6 bits)

    val ctx = IqContext()
    val uop = ctx.uop
    // drive EVERY RenamedUop field (defaults for fields not exercised)
    uop.valid        := False
    uop.cluster      := m68k040.isa.Cluster.INT
    uop.op           := m68k040.decode.DecOp.MOVE
    uop.size         := m68k040.isa.Size.WORD
    uop.useImm       := False
    uop.imm          := 0
    uop.unimplemented:= False
    uop.anInc := 0; uop.stkPush := False; uop.ccrRestore := False
    uop.dstArch      := 0
    uop.psrcA        := 0; uop.psrcAValid := False
    uop.psrcB        := 0; uop.psrcBValid := False
    uop.psrcC := 0; uop.psrcCValid := False
    uop.pdst         := 0; uop.pdstValid  := False; uop.pdstOld := 0
    uop.pNzvcDst     := 0; uop.writesNzvc := False; uop.pNzvcOld := 0
    uop.readsX       := False; uop.pXSrc := 0
    uop.pXDst        := 0; uop.writesX := False; uop.pXOld := 0
    // branch fields
    uop.isBranch     := True
    uop.ibranch      := False
    uop.cond         := iCond
    uop.pc           := iPc
    uop.branchDisp   := iDisp
    uop.pNzvcSrc     := iPNzvcSrc
    uop.readsNzvc    := True
    uop.isTrapv      := False
    uop.faultUsesNextPc := False
    uop.nextPc       := iPc + 2
    ctx.robId        := iRobId

    eu.issue.valid   := iValid
    eu.issue.payload := ctx

    // ---- NZVC preload write ----
    val wValid = in Bool ()
    val wAddr  = in UInt (4 bits)
    val wData  = in Bits (4 bits)
    nzW.valid   := wValid
    nzW.address := wAddr
    nzW.data    := wData

    // ---- completion observation ----
    val cValid      = out Bool ();        cValid      := eu.completion.valid
    val cRob        = out UInt (6 bits);  cRob        := eu.completion.payload.robId
    val cMispredict = out Bool ();        cMispredict := eu.completion.payload.mispredict
    val cNextPc     = out UInt (32 bits); cNextPc     := eu.completion.payload.nextPc
  }
}

class BranchEuSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val eu     = new BranchEuPlugin
    val src    = new BranchEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, eu, src)) }
  }

  // Reference condition table (matches the 68k Bcc encoding / Musashi ground truth).
  // nzvc bits: N=bit3, Z=bit2, V=bit1, C=bit0
  def expectedTaken(cond: Int, nzvc: Int): Boolean = {
    val n = ((nzvc >> 3) & 1) == 1
    val z = ((nzvc >> 2) & 1) == 1
    val v = ((nzvc >> 1) & 1) == 1
    val c = ((nzvc >> 0) & 1) == 1
    cond match {
      case 0  => true              // T
      case 1  => false             // F
      case 2  => !c && !z          // HI
      case 3  => c || z            // LS
      case 4  => !c                // CC/HS
      case 5  => c                 // CS/LO
      case 6  => !z                // NE
      case 7  => z                 // EQ
      case 8  => !v                // VC
      case 9  => v                 // VS
      case 10 => !n                // PL
      case 11 => n                 // MI
      case 12 => n == v            // GE
      case 13 => n != v            // LT
      case 14 => !z && (n == v)    // GT
      case 15 => z || (n != v)     // LE
    }
  }

  // A spread of NZVC values that exercise every condition both ways.
  val nzvcVectors: Seq[Int] = Seq(
    0x0, // ----
    0x1, // C
    0x2, // V
    0x3, // VC
    0x4, // Z
    0x5, // ZC
    0x8, // N
    0x9, // NC
    0xA, // NV
    0xC, // NZ
    0xF, // NZVC
    0x6, // ZV
    0xB, // NVC
    0xD, // NZC
    0xE  // NZV
  )

  test("16 conditions x representative NZVC: taken / nextPc / mispredict", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val s = dut.src.logic

      // idle
      s.iValid #= false
      s.wValid #= false; s.wAddr #= 0; s.wData #= 0
      s.iCond #= 0; s.iPc #= 0; s.iDisp #= 0; s.iPNzvcSrc #= 0; s.iRobId #= 0
      cd.waitSampling(80) // PRF init-zero sweep

      // We use NZVC physreg addresses 0..15, each preloaded with its own value
      // index (addr k holds value k & 0xF for the vectors we use). Simpler:
      // preload a single address per issue right before issuing.
      var rob = 0
      var failures = List.empty[String]

      for (cond <- 0 until 16) {
        for ((nzvc, vi) <- nzvcVectors.zipWithIndex) {
          val pAddr = vi % 16
          val pc    = 0x1000L + (cond * 0x100L) + (vi * 0x10L)
          val disp  = ((cond * 4 + vi * 2 + 6) & 0xFFFE) // even, small positive

          // Preload the NZVC physreg with this vector's value.
          s.wValid #= true; s.wAddr #= pAddr; s.wData #= nzvc
          cd.waitSampling()
          s.wValid #= false
          cd.waitSampling(2) // let write settle into RAM

          // Issue the branch reading pAddr.
          s.iValid #= true
          s.iCond #= cond
          s.iPc #= BigInt(pc)
          s.iDisp #= BigInt(disp)
          s.iPNzvcSrc #= pAddr
          s.iRobId #= rob
          cd.waitSampling()
          s.iValid #= false

          // Completion fires at S1 (one cycle after accept). Sample over a few cycles.
          var saw = false
          var gotMis = false
          var gotNext = BigInt(0)
          for (_ <- 0 until 4 if !saw) {
            if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == rob) {
              saw = true
              gotMis = dut.src.logic.cMispredict.toBoolean
              gotNext = dut.src.logic.cNextPc.toBigInt
            }
            cd.waitSampling()
          }

          val taken = expectedTaken(cond, nzvc)
          val expNext = if (taken) BigInt(pc) + 2 + BigInt(disp) else BigInt(pc) + 2
          if (!saw) failures ::= s"cond=$cond nzvc=0x${nzvc.toHexString} rob=$rob: NO completion"
          else {
            if (gotMis != taken)
              failures ::= s"cond=$cond nzvc=0x${nzvc.toHexString}: mispredict=$gotMis expected taken=$taken"
            if (gotNext != expNext)
              failures ::= f"cond=$cond nzvc=0x${nzvc.toHexString}: nextPc=0x${gotNext}%x expected 0x${expNext}%x (taken=$taken)"
          }
          rob = (rob + 1) & 0x3F
        }
      }

      assert(failures.isEmpty,
        s"branch condition eval mismatches (${failures.size}):\n${failures.reverse.mkString("\n")}")
    }
  }
}
