package m68k040.debug

import m68k040.execute.regfile._
import m68k040.services.{CommittedMapService, DebugMemoryService, DebugHistoryService,
  DebugBranchEvent, DebugExceptionEvent, DebugMemoryCommand}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin
import scala.collection.mutable.ArrayBuffer

/** Test-only integer PRF with the same setup-time port-allocation contract as the real
  * RegFilePlugin. Values are pokeable so AXI tests can prove committed-map selection. */
class DebugIntRfStubPlugin extends FiberPlugin with IntRegFileService {
  override val spec: RegfileSpec = RegfileSpec.Int
  private val reads = ArrayBuffer[RegFileReadPort]()
  private val writes = ArrayBuffer[RegFileWritePort]()
  override def newRead(forceNoBypass: Boolean = false): RegFileReadPort = {
    val p = RegFileReadPort(spec.addressWidth, spec.dataWidth); reads += p; p
  }
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0) = {
    val p = RegFileWritePort(spec.addressWidth, spec.dataWidth); writes += p; p
  }
  override def newBypass() = RegFileBypassPort(spec.addressWidth, spec.dataWidth)

  val logic = during build new Area {
    val values = in(Vec.fill(spec.depth)(Bits(spec.dataWidth bits)))
    reads.foreach(r => r.data := values(r.addr))
    val write = writes.head
    write.valid.simPublic(); write.address.simPublic(); write.data.simPublic()
  }
}

class DebugNzvcRfStubPlugin extends FiberPlugin with NzvcRegFileService {
  override val spec: RegfileSpec = RegfileSpec.Nzvc
  private var write: RegFileWritePort = null
  override def newRead(forceNoBypass: Boolean = false) = RegFileReadPort(spec.addressWidth, spec.dataWidth)
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0) = {
    write = RegFileWritePort(spec.addressWidth, spec.dataWidth); write
  }
  override def newBypass() = RegFileBypassPort(spec.addressWidth, spec.dataWidth)
  val logic = during build new Area {
    val writePort = write
    writePort.valid.simPublic(); writePort.address.simPublic(); writePort.data.simPublic()
  }
}

class DebugXRfStubPlugin extends FiberPlugin with XRegFileService {
  override val spec: RegfileSpec = RegfileSpec.X
  private var write: RegFileWritePort = null
  override def newRead(forceNoBypass: Boolean = false) = RegFileReadPort(spec.addressWidth, spec.dataWidth)
  override def newWrite(latency: Int = 1, sharingKey: Any = null, priority: Int = 0) = {
    write = RegFileWritePort(spec.addressWidth, spec.dataWidth); write
  }
  override def newBypass() = RegFileBypassPort(spec.addressWidth, spec.dataWidth)
  val logic = during build new Area {
    val writePort = write
    writePort.valid.simPublic(); writePort.address.simPublic(); writePort.data.simPublic()
  }
}

/** Test-only committed map producer. Non-identity pokes prove that debug reads never
  * assume architectural index equals physical index. */
class DebugCommittedMapStubPlugin extends FiberPlugin with CommittedMapService {
  val logic = during build new Area {
    val intMap = in(Vec.fill(20)(UInt(6 bits)))
    val nzvcMap = in(UInt(4 bits))
    val xMap = in(UInt(4 bits))
  }
  override def intPhys: Vec[UInt] = logic.intMap
  override def nzvcPhys: UInt = logic.nzvcMap
  override def xPhys: UInt = logic.xMap
}

class DebugMemoryStubPlugin extends FiberPlugin with DebugMemoryService {
  private var cmdWire: spinal.lib.Flow[DebugMemoryCommand] = null
  during setup {
    cmdWire = spinal.lib.Flow(DebugMemoryCommand())
    cmdWire.valid.allowOverride; cmdWire.valid := False
    cmdWire.payload.flatten.foreach(_.allowOverride)
    cmdWire.payload.push := False; cmdWire.payload.invalidate := False; cmdWire.payload.sel := 0
  }
  val logic = during build new Area {
    val quiescedDrive = in(Bool())
    val doneDrive = in(Bool())
    val errorDrive = in(Bool())
    val command = out(spinal.lib.Flow(DebugMemoryCommand()))
    command := cmdWire
  }
  override def quiesced: Bool = logic.quiescedDrive
  override def done: Bool = logic.doneDrive
  override def error: Bool = logic.errorDrive
  override def request(cmd: spinal.lib.Flow[DebugMemoryCommand]): Unit = cmdWire := cmd
}

class DebugHistoryStubPlugin extends FiberPlugin with DebugHistoryService {
  val logic = during build new Area {
    val pcValid = in(Vec.fill(2)(Bool()))
    val pc = in(Vec.fill(2)(UInt(32 bits)))
    val macroPc = Vec.fill(2)(spinal.lib.Flow(UInt(32 bits)))
    for (i <- 0 until 2) { macroPc(i).valid := pcValid(i); macroPc(i).payload := pc(i) }

    val branchValid = in(Bool())
    val branchPc = in(UInt(32 bits)); val branchNextPc = in(UInt(32 bits))
    val branchTaken = in(Bool()); val branchMispredicted = in(Bool())
    val branchType = in(UInt(2 bits))
    val branch = spinal.lib.Flow(DebugBranchEvent())
    branch.valid := branchValid
    branch.payload.pc := branchPc; branch.payload.nextPc := branchNextPc
    branch.payload.taken := branchTaken; branch.payload.mispredicted := branchMispredicted
    branch.payload.branchType := branchType

    val exceptionValid = in(Bool()); val exceptionVector = in(UInt(8 bits))
    val exceptionPc = in(UInt(32 bits)); val faultAddress = in(UInt(32 bits))
    val handlerPc = in(UInt(32 bits))
    val exception = spinal.lib.Flow(DebugExceptionEvent())
    exception.valid := exceptionValid; exception.payload.vector := exceptionVector
    exception.payload.exceptionPc := exceptionPc
    exception.payload.faultAddress := faultAddress; exception.payload.handlerPc := handlerPc
  }
  override def macroRetirePc = logic.macroPc
  override def branchRetire = logic.branch
  override def exceptionEntry = logic.exception
}
