package m68k040.debug

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.mmu.MmuControlPlugin
import m68k040.rob.{RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RobPlugin}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import scala.collection.mutable.ArrayBuffer

/** End-to-end service-boundary check: real dbg_axi CSR bank driving the real ROB halt
  * owner. The ROB's detailed macro-boundary behavior remains covered by RobPluginSpec. */
class DebugCtrlRobIntegrationSpec extends AnyFunSuite {

  class Dut(stageArg: Int = 2) extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val alloc = new RobAllocDriverPlugin
    val rob = new RobPlugin
    val mmu: MmuControlPlugin = if (stageArg >= 3) new MmuControlPlugin else null
    val commit = new RenameCommitSinkPlugin
    val intRf: DebugIntRfStubPlugin = if (stageArg >= 3) new DebugIntRfStubPlugin else null
    val nzvcRf: DebugNzvcRfStubPlugin = if (stageArg >= 3) new DebugNzvcRfStubPlugin else null
    val xRf: DebugXRfStubPlugin = if (stageArg >= 3) new DebugXRfStubPlugin else null
    val maps: DebugCommittedMapStubPlugin = if (stageArg >= 3) new DebugCommittedMapStubPlugin else null
    val memory: DebugMemoryStubPlugin = if (stageArg >= 3) new DebugMemoryStubPlugin else null
    val dbg = new DebugCtrlPlugin(porCycles = 4, stage = stageArg)
    val stage3Plugins = if (stageArg >= 3)
      Seq[FiberPlugin](intRf, nzvcRf, xRf, maps, memory) else Seq.empty
    val corePlugins = Seq[FiberPlugin](new ParamPlugin(M68kParams())) ++
      (if (stageArg >= 3) Seq[FiberPlugin](mmu) else Seq.empty) ++
      Seq[FiberPlugin](rsrc, alloc, rob, commit)
    db.on { host.asHostOf(corePlugins ++ stage3Plugins ++ Seq(dbg)) }

    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  test("Stage 3 live system register window reads one coherent halted ROB view") {
    M68kSim().compile(new Dut(stageArg = 3)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false; dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      dut.memory.logic.quiescedDrive #= true
      dut.memory.logic.doneDrive #= false
      dut.memory.logic.errorDrive #= false
      cd.waitSampling(20)
      // Seed a deliberately non-identity system snapshot. S=1,M=1 selects MSP as A7.
      dut.rob.logic.exc.ss.srSys #= 0x30
      dut.rob.logic.committedCcr #= 0x15
      dut.rob.logic.exc.ss.vbr #= 0x00abc000L
      dut.rob.logic.exc.ss.usp #= 0x11110000L
      dut.rob.logic.exc.ss.isp #= 0x22220000L
      dut.rob.logic.exc.ss.msp #= 0x33330000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L
      dut.rob.logic.exc.ss.sfc #= 5; dut.rob.logic.exc.ss.dfc #= 6
      dut.rob.logic.debugLivePcReg #= 0x12345678L
      dut.maps.logic.intMap(0) #= 20
      dut.maps.logic.intMap(8) #= 21
      dut.maps.logic.intMap(15) #= 31
      dut.intRf.logic.values(20) #= 0xd0000020L
      dut.intRf.logic.values(21) #= 0xa0000021L
      dut.intRf.logic.values(31) #= 0xa7000031L
      cd.waitSampling(2)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL, 1)
      var status = 0L; var waited = 0
      while ((status & 1L) == 0 && waited < 100) {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS); waited += 1
      }
      assert((status & 1L) != 0)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_SR) == 0x3015L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_VBR) == 0x00abc000L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_USP) == 0x11110000L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_ISP) == 0x22220000L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_SSP) == 0x33330000L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_DREG0) == 0xd0000020L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_AREG0) == 0xa0000021L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_A7) == 0xa7000031L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_CACR) == 0x80008000L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_SFC) == 5L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_DFC) == 6L)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_LIVE_PC) == 0x12345678L)
    }
  }

  test("Stage 3 dirty apply rejects while running, updates only staged state, and stays halted") {
    M68kSim().compile(new Dut(stageArg = 3)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false; dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      dut.memory.logic.quiescedDrive #= true
      dut.memory.logic.doneDrive #= false
      dut.memory.logic.errorDrive #= false
      dut.maps.logic.intMap(0) #= 20
      dut.maps.logic.intMap(8) #= 21
      dut.maps.logic.nzvcMap #= 7
      dut.maps.logic.xMap #= 9
      cd.waitSampling(20)

      val intWrites = ArrayBuffer[(BigInt, BigInt)]()
      val nzvcWrites = ArrayBuffer[(BigInt, BigInt)]()
      val xWrites = ArrayBuffer[(BigInt, BigInt)]()
      var maintenanceStarts = 0
      fork {
        while (true) {
          cd.waitSampling()
          dut.intRf.logic.writePorts.foreach { port =>
            if (port.valid.toBoolean)
              intWrites += ((port.address.toBigInt, port.data.toBigInt))
          }
          if (dut.nzvcRf.logic.writePort.valid.toBoolean)
            nzvcWrites += ((dut.nzvcRf.logic.writePort.address.toBigInt, dut.nzvcRf.logic.writePort.data.toBigInt))
          if (dut.xRf.logic.writePort.valid.toBoolean)
            xWrites += ((dut.xRf.logic.writePort.address.toBigInt, dut.xRf.logic.writePort.data.toBigInt))
        }
      }
      fork {
        while (!dut.memory.logic.command.valid.toBoolean) cd.waitSampling()
        maintenanceStarts += 1
        cd.waitSampling(5)
        dut.memory.logic.doneDrive #= true
        cd.waitSampling()
        dut.memory.logic.doneDrive #= false
      }

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_APPLY, 2)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_D0, 0xd0d0d0d0L)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_SR, 0x0000a71dL)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_VBR, 0x00fed000L)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_CACR, 0x80008000L)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_PC, 0x12340000L)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_APPLY, 1)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ARCH_STATUS) == 4,
        "running apply was not rejected distinctly")
      assert(dut.dbg.logic.csr.archDirty.toBigInt.bitCount == 5)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_APPLY, 2)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL, 1)
      var coreStatus = 0L; var waited = 0
      while ((coreStatus & 1L) == 0 && waited < 100) {
        coreStatus = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS); waited += 1
      }
      assert((coreStatus & 1L) != 0)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ARCH_APPLY, 1)
      var applyStatus = 0L; waited = 0
      while ((applyStatus & 6L) == 0 && waited < 100) {
        applyStatus = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ARCH_STATUS); waited += 1
      }
      assert(applyStatus == 2, f"apply terminal status 0x$applyStatus%x")
      cd.waitSampling(2)

      assert(intWrites.toSeq == Seq((BigInt(20), BigInt("d0d0d0d0", 16))),
        s"dirty apply touched unexpected integer mappings: $intWrites")
      assert(nzvcWrites.toSeq == Seq((BigInt(7), BigInt(0xd))))
      assert(xWrites.toSeq == Seq((BigInt(9), BigInt(1))))
      assert(dut.rob.logic.exc.ss.srSys.toBigInt == 0xa7)
      assert(dut.rob.logic.committedCcr.toBigInt == 0x1d)
      assert(dut.rob.logic.exc.ss.vbr.toBigInt == 0x00fed000L)
      assert(dut.rob.logic.exc.ss.cacr.toBigInt == 0x80008000L)
      assert(dut.rob.logic.debugLivePcReg.toBigInt == 0x12340000L)
      assert(maintenanceStarts == 1)
      assert(dut.dbg.logic.csr.archDirty.toBigInt == 0)
      coreStatus = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS)
      assert((coreStatus & 1L) != 0 && (coreStatus & 8L) == 0,
        f"architectural apply released halt: STATUS=0x$coreStatus%x")
    }
  }

  test("Stage 4 cache maintenance waits for quiescence and reports reject, done, and error") {
    M68kSim().compile(new Dut(stageArg = 4)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false; dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      dut.memory.logic.quiescedDrive #= false
      dut.memory.logic.doneDrive #= false
      dut.memory.logic.errorDrive #= false
      cd.waitSampling(20)
      val commands = ArrayBuffer[(Int, Boolean, Boolean)]()
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.memory.logic.command.valid.toBoolean) {
            commands += ((dut.memory.logic.command.payload.sel.toInt,
              dut.memory.logic.command.payload.push.toBoolean,
              dut.memory.logic.command.payload.invalidate.toBoolean))
          }
        }
      }

      val features = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_FEATURES)
      assert((features & (1L << 21)) != 0, f"cache-maint-only not advertised: 0x$features%x")
      assert((features & (1L << 11)) == 0, f"unimplemented D-cache probe advertised: 0x$features%x")

      // Running requests fail visibly and never reach the shared maintenance owner.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_DCACHE_OP, 3)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_DCACHE_OP) == 0x10)
      assert(!dut.memory.logic.command.valid.toBoolean)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL, 1)
      var status = 0L; var waited = 0
      while ((status & 1L) == 0 && waited < 100) {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS); waited += 1
      }
      assert((status & 1L) != 0)

      // D push is accepted at halt but cannot launch until SQ/cache quiescence.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_DCACHE_OP, 3)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ICACHE_OP) == 0x5)
      cd.waitSampling(4)
      assert(!dut.memory.logic.command.valid.toBoolean)
      dut.memory.logic.quiescedDrive #= true
      waited = 0
      while (commands.isEmpty && waited < 20) { cd.waitSampling(); waited += 1 }
      assert(commands.head == ((1, true, false)))
      cd.waitSampling()
      dut.memory.logic.errorDrive #= true; dut.memory.logic.doneDrive #= true
      cd.waitSampling()
      dut.memory.logic.errorDrive #= false; dut.memory.logic.doneDrive #= false
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_DCACHE_OP) == 0x26)

      // A subsequent I invalidate clears the prior error and uses the same status.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_ICACHE_OP, 1)
      waited = 0
      while (commands.size < 2 && waited < 20) { cd.waitSampling(); waited += 1 }
      assert(commands(1) == ((2, false, true)))
      cd.waitSampling()
      dut.memory.logic.doneDrive #= true
      cd.waitSampling()
      dut.memory.logic.doneDrive #= false
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_ICACHE_OP) == 0x0a)
    }
  }

  test("dbg_axi manual halt and resume drive the real ROB owner") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(20)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 1)
      var status = 0L
      var waited = 0
      while ((status & 1L) == 0 && waited < 100) {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      }
      assert((status & 1L) != 0, f"ROB did not reach effective halt; STATUS=0x$status%08X")
      assert((status & (1L << 3)) == 0, f"halted STATUS still reports running: 0x$status%08X")
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_REASON.toLong) == 1,
        "plain stop did not report MANUAL")

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1L << 2)
      assert(DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_REASON.toLong) == 0,
        "HALT_CTL.clear-sticky did not clear the reason")
      status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
      assert((status & 1L) != 0, "clearing reports must not release an active halt")

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 0)
      waited = 0
      do {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      } while ((status & (1L << 3)) == 0 && waited < 100)
      assert((status & 1L) == 0 && (status & (1L << 3)) != 0,
        f"ROB did not resume; STATUS=0x$status%08X")
    }
  }

  test("dbg_axi halt-after is automatic, one-shot, and resumes without retrigger") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(20)
      assert(dut.rob.logic.debugHaltState.toEnum == m68k040.rob.DebugHaltState.RUNNING,
        "integration DUT did not leave reset in RUNNING")

      // Absolute target zero is already satisfied at reset. Programming both halves
      // followed by HALT_CTL.arm exercises the full CSR -> service -> ROB path.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong, 0)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_HI.toLong, 0)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1)

      var status = 0L
      var waited = 0
      while ((status & ((1L << 4) | 1L)) != ((1L << 4) | 1L) && waited < 100) {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      }
      assert(dut.rob.logic.debugAutoHaltLatchedReg.toBoolean,
        f"halt-after failed: STATUS=0x$status%08X state=${dut.rob.logic.debugHaltState.toEnum} " +
        s"armed=${dut.rob.logic.haltAfterArmedIn.toBoolean} " +
        s"target=${dut.rob.logic.haltAfterTargetIn.toBigInt} " +
        s"epoch=${dut.rob.logic.haltAfterEpochIn.toInt} " +
        s"cmpArmed=${dut.rob.logic.haltAfterCmpArmedReg.toBoolean} " +
        s"cmpHit=${dut.rob.logic.haltAfterCmpHitReg.toBoolean} " +
        s"pending=${dut.rob.logic.haltAfterComparePending.toBoolean} " +
        s"due=${dut.rob.logic.haltAfterDue.toBoolean}")
      assert((status & 1L) != 0 && (status & (1L << 4)) != 0,
        f"halt-after did not produce an effective automatic halt: STATUS=0x$status%08X")
      assert((DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong) & 1L) == 0,
        "the automatic hit did not consume the one-shot arm")

      // A halted debugger normally programs the next run before continue. Sticky
      // auto-halt status must not continuously clear this new arm.
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_LO.toLong, 10)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_AFTER_HI.toLong, 0)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong, 1)
      assert((DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_HALT_CTL.toLong) & 1L) != 0,
        "a new halt-after target could not be armed while effectively halted")

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 0)
      waited = 0
      do {
        status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
        waited += 1
      } while ((status & (1L << 3)) == 0 && waited < 100)
      assert((status & 1L) == 0 && (status & (1L << 3)) != 0)
      cd.waitSampling(20)
      status = DbgAxiDriver.read(dut.axi, cd, DebugRegMap.OFF_STATUS.toLong)
      assert((status & 1L) == 0, "consumed halt-after target retriggered after resume")
    }
  }

  test("deployed HALT, HALT|STEP, zero sequence accepts exactly one step") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      DbgAxiDriver.idle(dut.axi)
      dut.dbg.logic.initDoneSeen #= false
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(20)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 1)
      var waited = 0
      while (!dut.rob.logic.debugHalted.toBoolean && waited < 100) {
        cd.waitSampling(); waited += 1
      }
      assert(dut.rob.logic.debugHalted.toBoolean)

      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 3)
      DbgAxiDriver.write(dut.axi, cd, DebugRegMap.OFF_CONTROL.toLong, 0)
      waited = 0
      while (dut.rob.logic.debugHaltState.toEnum != m68k040.rob.DebugHaltState.STEP_RUNNING && waited < 100) {
        cd.waitSampling(); waited += 1
      }
      assert(dut.rob.logic.debugHaltState.toEnum == m68k040.rob.DebugHaltState.STEP_RUNNING,
        "the trailing zero write must not cancel an accepted step")
      assert(!dut.rob.logic.debugStepRejected.toBoolean)
    }
  }
}
