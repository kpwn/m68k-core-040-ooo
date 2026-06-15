package m68k040.execute

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RenameCommitSinkPlugin}
import m68k040.rename.RenamedUop
import m68k040.decode.DecOp
import m68k040.isa.{Cluster, Size, MemOp}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.lockstep.WhiteboxCapture
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Backend whitebox integration: wires the WHOLE execute backend
  * (rename-source → DispatchPlugin → RobPlugin + IssueQueuePlugin → 2 AluEuPlugins
  * → ROB completion) with NO frontend, samples the EU writeback-obs and ROB
  * commit-obs every cycle into a `WhiteboxCapture.Handle`, and asserts the
  * reconstructed per-retired-instruction CommitObservation stream matches the
  * hand-computed expectations for a small program (MOVEQ #10, MOVEQ #3, ADD). */
class BackendWhiteboxSpec extends AnyFunSuite {

  /** Ties off the IQ flush port (no flush exercised here). */
  class IqFlushTiePlugin extends FiberPlugin {
    val logic = during build new Area { host[IssueQueueService].flushPort := False }
  }

  /** Wires IQ issue ports to the two EUs and the EU completions to the ROB. */
  class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin) extends FiberPlugin {
    val logic = during build new Area {
      val iq  = host[IssueQueueService]
      val rob = host[RobPlugin]
      // IQ issue (producer) → EU issue (plain-wire, EU drives ready).
      eu0.issue << iq.issue(0)
      eu1.issue << iq.issue(1)
      eu0.srSysIn := U(0, 8 bits); eu1.srSysIn := U(0, 8 bits)  // MOVE-from-SR srSys input (unused here)
      // SLOW-ALU (shift, lat2) dynamic wakeup (mirrors top/FullCoreSynth). This corpus
      // has no shifts, so it is inert, but wired for consistency / latent-deadlock safety.
      iq.aluSlowWakeup(0).valid   := eu0.slowWakeup.valid
      iq.aluSlowWakeup(0).payload := eu0.slowWakeup.payload
      iq.aluSlowWakeup(1).valid   := eu1.slowWakeup.valid
      iq.aluSlowWakeup(1).payload := eu1.slowWakeup.payload
      // Branch issue port (BR2): no branch EU in this no-branch DUT (BR4 adds it) —
      // tie its ready off so the IQ's issue(2) Stream is driven. The straight-line
      // ALU corpus has no branches, so this port never carries a valid uop.
      iq.issue(2).ready := False
      // LS issue port (LS3): no LS EU in this DUT — tie its ready off so the IQ's
      // issue(3) Stream is fully driven. (No LS uops in this corpus.)
      iq.issue(3).ready := False
      // CPLX issue port (4): no DivEu in this DUT — tie its ready off so the IQ's
      // issue(4) Stream is fully driven. (No CHK/DIV uops in this corpus.)
      iq.issue(4).ready := False
      // EU completion (Flow) → ROB completion ports (both directionless plain
      // wires; drive the ROB's completion from the EU's completion).
      rob.logic.completion(0).valid   := eu0.completion.valid
      rob.logic.completion(0).payload := eu0.completion.payload
      rob.logic.completion(1).valid   := eu1.completion.valid
      rob.logic.completion(1).payload := eu1.completion.payload
    }
  }

  class Dut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val rsrc  = new RenameUopSourcePlugin
    val rob   = new RobPlugin
    val disp  = new m68k040.dispatch.DispatchPlugin
    val iq    = new IssueQueuePlugin
    val eu0   = new AluEuPlugin
    val eu1   = new AluEuPlugin
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val wire   = new BackendWiringPlugin(eu0, eu1)
    val ftie   = new IqFlushTiePlugin
    val csink  = new RenameCommitSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()),
      rsrc, rob, disp, iq, eu0, eu1, rfInt, rfNzvc, rfX, wire, ftie, csink)) }
  }

  /** Poke a RenamedUop slot. */
  def pokeUop(
      u: RenamedUop,
      valid: Boolean = true,
      pc: Long = 0,
      op: spinal.core.SpinalEnumElement[DecOp.type] = DecOp.MOVE,
      useImm: Boolean = false, imm: Long = 0,
      dstArch: Int = 0,
      psrcA: Int = 0, psrcAValid: Boolean = false,
      psrcB: Int = 0, psrcBValid: Boolean = false,
      pdst: Int = 0, pdstValid: Boolean = true, pdstOld: Int = 0,
      pNzvcSrc: Int = 0, readsNzvc: Boolean = false,
      pNzvcDst: Int = 0, writesNzvc: Boolean = false, pNzvcOld: Int = 0,
      pXSrc: Int = 0, readsX: Boolean = false,
      pXDst: Int = 0, writesX: Boolean = false, pXOld: Int = 0
  ): Unit = {
    u.valid #= valid
    u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= op
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= useImm; u.imm #= BigInt(imm & 0xffffffffL)
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.dstArch #= dstArch
    u.psrcA #= psrcA; u.psrcAValid #= psrcAValid
    u.psrcB #= psrcB; u.psrcBValid #= psrcBValid
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= pNzvcSrc; u.readsNzvc #= readsNzvc
    u.pNzvcDst #= pNzvcDst; u.writesNzvc #= writesNzvc; u.pNzvcOld #= pNzvcOld
    u.pXSrc #= pXSrc; u.readsX #= readsX
    u.pXDst #= pXDst; u.writesX #= writesX; u.pXOld #= pXOld
    // Drive EVERY RenamedUop field — the EU/IQ fast path reads several of these (isMovea
    // muxes the writeback to src2; toCcr muxes the flags; isShift routes the slow path),
    // so leaving any undriven gives it a nondeterministic per-netlist value and a flaky
    // result (this corpus is plain MOVE/ADD: all the op-flavour flags are false). Fields
    // added by later ISA slices (MOVEA, toCcr, shifts, mem-RMW, line-4/5) must default here.
    u.memOp #= MemOp.NONE
    u.psrcC #= 0; u.psrcCValid #= false
    u.ibranch #= false; u.anInc #= 0; u.stkPush #= false; u.ccrRestore #= false
    u.toCcr #= false; u.isCondTrap #= false
    u.faultAddr #= 0; u.sswInstr #= false
    u.divSigned #= false; u.divIsRem #= false
    u.shiftOp #= 0; u.shiftDir #= false; u.extByte #= false
    u.isMovea #= false; u.isScc #= false; u.isDbcc #= false
    u.div64 #= false; u.bcdSub #= false; u.bitOp #= 0
    u.eaAuto #= m68k040.decode.EaAuto.NONE; u.eaDelta #= 0
    u.indexLong #= false; u.indexScale #= 0
    u.leaAddr #= false; u.fromCcr #= false; u.fromSr #= false
    u.needsSupervisor #= false; u.keepCommit #= false
    u.firstOfInstr #= true
  }

  test("backend whitebox reconstructs CommitObservations for MOVEQ/MOVEQ/ADD", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle

      // Per-cycle sampler: EU writeback-obs (join key) + ROB commit-obs (order/pc).
      cd.onSamplings {
        // EU writebacks (record into the robId→Wb map).
        for (eu <- Seq(dut.eu0, dut.eu1)) {
          val w = eu.logic.wbObs
          if (w.valid.toBoolean) {
            handle.onWb(
              w.robId.toInt,
              WhiteboxCapture.Wb(
                dstArch   = w.dstArch.toInt,
                result    = w.result.toLong & 0xffffffffL,
                intWrite  = w.intWrite.toBoolean,
                nzvc      = w.nzvc.toInt,
                nzvcWrite = w.nzvcWrite.toBoolean,
                x         = if (w.x.toBoolean) 1 else 0,
                xWrite    = w.xWrite.toBoolean))
          }
        }
        // ROB commits, in retire order: slot 0 then slot 1.
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL)
        }
      }

      // init
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(80) // PRF init sweep

      // ── Hand-built program (each instruction one robId, all 2-byte PCs) ──
      // I0: MOVEQ #10 -> D0   (pdst p0)   ; sets N=0,Z=0,V=0,C=0 ; no X
      // I1: MOVEQ #3  -> D1   (pdst p1)
      // I2: ADD  D1,D0 -> D2  (src1=p0=10, src2=p1=3) = 13 ; flags+X from ADD
      def push1(pc: Long, configure: RenamedUop => Unit): Unit = {
        configure(dut.rsrc.logic.src.payload(0))
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
        dut.rsrc.logic.src.valid #= false
      }

      // I0: MOVEQ #10 -> D0 (arch 0), pdst=0, writes NZVC (p0)
      push1(0x100, u => pokeUop(u, pc = 0x100, op = DecOp.MOVE, useImm = true, imm = 10,
        dstArch = 0, pdst = 0, pdstValid = true, pdstOld = 0,
        pNzvcDst = 0, writesNzvc = true))
      cd.waitSampling(3)

      // I1: MOVEQ #3 -> D1 (arch 1), pdst=1, writes NZVC (p1)
      push1(0x102, u => pokeUop(u, pc = 0x102, op = DecOp.MOVE, useImm = true, imm = 3,
        dstArch = 1, pdst = 1, pdstValid = true, pdstOld = 1,
        pNzvcDst = 1, writesNzvc = true))
      cd.waitSampling(3)

      // I2: ADD D1,D0 -> D2 (arch 2): src1=p0 (10), src2=p1 (3); writes int+NZVC+X
      push1(0x104, u => pokeUop(u, pc = 0x104, op = DecOp.ADD, useImm = false,
        dstArch = 2, psrcA = 0, psrcAValid = true, psrcB = 1, psrcBValid = true,
        pdst = 2, pdstValid = true, pdstOld = 2,
        pNzvcSrc = 0, readsNzvc = false, pNzvcDst = 2, writesNzvc = true, pNzvcOld = 0,
        pXDst = 2, writesX = true, pXOld = 0))

      // Let the whole program retire.
      cd.waitSampling(40)

      val res = handle.result
      assert(res.size == 3, s"expected 3 commits, got ${res.size}: $res")

      // ── Expected (hand-computed) ──
      // I0: D0 = 10. MOVE flags: N=0 (bit31=0), Z=0 (10!=0), V=0, C=0 -> nzvc=0.
      //     CCR after I0 = 0x00 (X stays 0).
      val c0 = res(0)
      assert(c0.pc == 0x102, s"I0 pc dut=0x${c0.pc.toHexString} expected 0x102")
      assert(c0.archRegValid && c0.archRegId == 0, s"I0 archReg ${c0.archRegId}/${c0.archRegValid}")
      assert(c0.archRegWrite == 10, s"I0 value ${c0.archRegWrite} expected 10")
      assert(c0.ccr == 0x00, s"I0 ccr 0x${c0.ccr.toHexString} expected 0x00")

      // I1: D1 = 3. CCR after I1 = 0x00.
      val c1 = res(1)
      assert(c1.pc == 0x104, s"I1 pc dut=0x${c1.pc.toHexString} expected 0x104")
      assert(c1.archRegValid && c1.archRegId == 1, s"I1 archReg ${c1.archRegId}/${c1.archRegValid}")
      assert(c1.archRegWrite == 3, s"I1 value ${c1.archRegWrite} expected 3")
      assert(c1.ccr == 0x00, s"I1 ccr 0x${c1.ccr.toHexString} expected 0x00")

      // I2: D2 = 10 + 3 = 13. ADD.L 10+3: no carry-out (C=0->X=0), no overflow (V=0),
      //     N=0 (bit31=0), Z=0 (13!=0). nzvc = 0. X bit set to C=0.
      //     CCR after I2 = 0x00.
      val c2 = res(2)
      assert(c2.pc == 0x106, s"I2 pc dut=0x${c2.pc.toHexString} expected 0x106")
      assert(c2.archRegValid && c2.archRegId == 2, s"I2 archReg ${c2.archRegId}/${c2.archRegValid}")
      assert(c2.archRegWrite == 13, s"I2 value ${c2.archRegWrite} expected 13")
      assert(c2.ccr == 0x00, s"I2 ccr 0x${c2.ccr.toHexString} expected 0x00")
    }
  }
}
