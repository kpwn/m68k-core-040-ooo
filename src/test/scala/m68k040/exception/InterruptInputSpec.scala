package m68k040.exception

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin,
                    RenameCommitSinkPlugin, CommitTraceSinkPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Task 2: the external interrupt inputs (iplIn / iackAvec / iackVector) are
  * owned by InterruptControlPlugin and READ by the ROB recognition logic. Poking
  * the shared control changes what the ROB sees at its recognition point. Default
  * idle (ipl=0) so every existing test is unchanged. */
class InterruptInputSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val intCtrl = new InterruptControlPlugin
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), intCtrl, rsrc, drv, rob, csink, tsink)) }
  }

  test("interrupt inputs reach the ROB recognition point") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling(4)

      // Default idle.
      assert(dut.rob.logic.iplIn.toInt == 0, "iplIn must default 0")
      assert(!dut.rob.logic.iackAvec.toBoolean, "iackAvec must default False")
      assert(dut.rob.logic.iackVector.toInt == 0, "iackVector must default 0")

      // Poke the shared control -> the ROB sees it.
      dut.intCtrl.logic.iplIn      #= 5
      dut.intCtrl.logic.iackAvec   #= true
      dut.intCtrl.logic.iackVector #= 0x42
      cd.waitSampling(2)
      assert(dut.rob.logic.iplIn.toInt == 5, s"iplIn=${dut.rob.logic.iplIn.toInt}")
      assert(dut.rob.logic.iackAvec.toBoolean, "iackAvec must follow the poke")
      assert(dut.rob.logic.iackVector.toInt == 0x42, f"iackVector=0x${dut.rob.logic.iackVector.toInt}%x")

      // Autovector mode: a different level.
      dut.intCtrl.logic.iplIn    #= 7
      dut.intCtrl.logic.iackAvec #= true
      cd.waitSampling(2)
      assert(dut.rob.logic.iplIn.toInt == 7, "NMI level reaches the ROB")
    }
  }
}
