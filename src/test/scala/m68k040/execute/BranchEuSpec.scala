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
    // RTD-btb-training-fix directed test inputs: drive ibranch/isReturn/anInc/pdstValid
    // independently so the classification test can reproduce JMP/JSR (ibranch, not a
    // return) vs RTS/RTR (ibranch, isReturn, real anInc-folded A7 postinc) vs RTD
    // (ibranch, isReturn, but anInc=0 -- the exact shape that used to be misclassified).
    val iIbranch    = in Bool ()
    val iIsReturn   = in Bool ()
    val iAnInc      = in UInt (3 bits)
    val iPdstValid  = in Bool ()

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
    uop.anInc := iAnInc; uop.stkPush := False; uop.ccrRestore := False
    uop.isReturn     := iIsReturn
    uop.dstArch      := 0
    uop.psrcA        := 0; uop.psrcAValid := False
    uop.psrcB        := 0; uop.psrcBValid := False
    uop.psrcC := 0; uop.psrcCValid := False
    uop.pdst         := 0; uop.pdstValid  := iPdstValid; uop.pdstOld := 0
    uop.pNzvcDst     := 0; uop.writesNzvc := False; uop.pNzvcOld := 0
    uop.readsX       := False; uop.pXSrc := 0
    uop.pXDst        := 0; uop.writesX := False; uop.pXOld := 0
    // Task #191-adjacent fix: RenamedUop.indexLong/indexScale (AGU indexed-EA fields)
    // postdate this directed test's "drive EVERY field" list -- a conditional branch µop
    // never uses AGU indexing at all, so these are simple, safe defaults.
    uop.indexLong := False; uop.indexScale := 0
    // branch fields
    uop.isBranch     := True
    uop.ibranch      := iIbranch
    uop.cond         := iCond
    uop.pc           := iPc
    uop.branchDisp   := iDisp
    uop.pNzvcSrc     := iPNzvcSrc
    uop.readsNzvc    := True
    uop.isCondTrap   := False
    uop.isScc        := False
    uop.isDbcc       := False
    uop.faultUsesNextPc := False
    // Fetch-time prediction: NOT predicted (predictor-inert) -> mispredict == taken.
    uop.predTaken    := False
    uop.predTarget   := 0
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
    // RTD-btb-training-fix: the BTB/FTB training-eligibility decision (BranchEuPlugin's
    // isBtbBranch, see the `isReturn` classification there). Both Btb.scala AND
    // Ftb.scala subscribe to this SAME retire-time completion.payload.isBranch signal
    // (via the shared BtbUpdateService), so exercising it here at the EU boundary
    // covers both training targets without needing two separate harnesses.
    val cIsBranch   = out Bool ();        cIsBranch   := eu.completion.payload.isBranch
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
      s.iIbranch #= false; s.iIsReturn #= false; s.iAnInc #= 0; s.iPdstValid #= false
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

  // RTD-btb-training-fix (real bug, found during a broader hardware investigation):
  // RTD (Return and Deallocate) cracks its A7 update into a SEPARATE dropped ADD µop
  // (its 4+disp16 deallocation amount can't fit anInc's 3 bits), so its trailing
  // ibranch always carries anInc=0 -- structurally IDENTICAL, on that one field, to a
  // plain JMP/JSR ibranch. BranchEuPlugin used to classify "is this a return, excluded
  // from BTB/FTB training" as `ibranch && anInc =/= 0`, which correctly excluded RTS
  // (anInc=4) / RTR (anInc=6) but WRONGLY included RTD -- letting it train the BTB/FTB
  // with the popped return address (a last-target predictor of a return is correct for
  // the one call that trained it and wrong for every other call to the same
  // subroutine; the RAS is the dedicated return predictor instead). The fix adds a
  // real, anInc-independent `isReturn` classification field, threaded from decode.
  //
  // Both Btb.scala and Ftb.scala subscribe to the SAME retire-time
  // completion.payload.isBranch decision (via BtbUpdateService), so exercising it here
  // at the branch-EU boundary covers both training targets in one directed test.
  test("RTD-shaped ibranch (isReturn, anInc=0) is excluded from BTB/FTB training " +
       "like RTS/RTR, while a structurally-identical anInc=0 non-return ibranch " +
       "(JMP/JSR-shaped) IS trained", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val s = dut.src.logic

      s.iValid #= false
      s.wValid #= false; s.wAddr #= 0; s.wData #= 0
      s.iCond #= 0; s.iPc #= 0; s.iDisp #= 0; s.iPNzvcSrc #= 0; s.iRobId #= 0
      s.iIbranch #= false; s.iIsReturn #= false; s.iAnInc #= 0; s.iPdstValid #= false
      cd.waitSampling(80) // PRF init-zero sweep

      case class Case(name: String, isReturn: Boolean, anInc: Int, pdstValid: Boolean,
                       expectTrained: Boolean, expectAnWrite: Boolean)
      val cases = Seq(
        // JMP/JSR-shaped: ibranch, NOT a return, no folded A7 write. Must be TRAINED.
        Case("JMP/JSR-shaped (isReturn=F, anInc=0)",  isReturn = false, anInc = 0, pdstValid = false,
             expectTrained = true,  expectAnWrite = false),
        // RTS-shaped: real anInc=4 postinc folded into this ibranch. Must be EXCLUDED.
        Case("RTS-shaped (isReturn=T, anInc=4)",      isReturn = true,  anInc = 4, pdstValid = true,
             expectTrained = false, expectAnWrite = true),
        // RTR-shaped: real anInc=6 postinc folded into this ibranch. Must be EXCLUDED.
        Case("RTR-shaped (isReturn=T, anInc=6)",      isReturn = true,  anInc = 6, pdstValid = true,
             expectTrained = false, expectAnWrite = true),
        // RTD-shaped: THE BUG CASE. anInc=0 (the A7 deallocation is a separate, un-
        // fused ADD µop -- see MicroOpAssembler's rtdA7/rtdBranch), pdstValid=False (no
        // A7 write folded into this ibranch either), yet it IS architecturally a return
        // and must be EXCLUDED. Structurally identical to the JMP/JSR case above on
        // BOTH anInc and pdstValid -- only isReturn differs, proving the classification
        // now genuinely depends on isReturn and not on anInc as a proxy.
        Case("RTD-shaped (isReturn=T, anInc=0)",      isReturn = true,  anInc = 0, pdstValid = false,
             expectTrained = false, expectAnWrite = false)
      )

      var rob = 0
      var failures = List.empty[String]
      for (c <- cases) {
        val pc = 0x2000L + rob * 0x10L
        s.iValid #= true
        s.iCond #= 0                 // ibranch always redirects regardless of cond
        s.iPc #= BigInt(pc)
        s.iDisp #= 0
        s.iPNzvcSrc #= 0
        s.iRobId #= rob
        s.iIbranch #= true
        s.iIsReturn #= c.isReturn
        s.iAnInc #= c.anInc
        s.iPdstValid #= c.pdstValid
        cd.waitSampling()
        s.iValid #= false

        // completion (S1, combinational off s1Valid) and wbObs (S2, RegNext(s1Valid) --
        // one cycle further behind) fire on DIFFERENT cycles, so sample both over a
        // fixed window WITHOUT an early exit (an early exit on completion would cut
        // the window off before wbObs's later pulse ever arrives). robId isn't exposed
        // on wbObs, but with iValid held for exactly one cycle per case and a wide gap
        // between issues, at most one wbObs pulse is ever in flight.
        var saw = false
        var gotIsBranch = false
        var gotAnWrite = false
        for (_ <- 0 until 6) {
          if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == rob) {
            saw = true
            gotIsBranch = dut.src.logic.cIsBranch.toBoolean
          }
          if (dut.eu.logic.wbObs.valid.toBoolean) gotAnWrite = dut.eu.logic.wbObs.anWrite.toBoolean
          cd.waitSampling()
        }

        if (!saw) failures ::= s"${c.name}: NO completion"
        else {
          if (gotIsBranch != c.expectTrained)
            failures ::= s"${c.name}: BTB/FTB-trained=$gotIsBranch expected ${c.expectTrained}"
          if (gotAnWrite != c.expectAnWrite)
            failures ::= s"${c.name}: A7 anWrite=$gotAnWrite expected ${c.expectAnWrite}"
        }
        rob = (rob + 1) & 0x3F
      }

      assert(failures.isEmpty,
        s"RTD BTB/FTB-training-classification mismatches (${failures.size}):\n${failures.reverse.mkString("\n")}")
    }
  }
}
