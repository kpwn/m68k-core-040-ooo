package m68k040.frontend

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

class RasPayloadResetSpec extends AnyFunSuite {
  class CheckedRas extends RasPlugin {
    val check = during build new Area {
      val push, pop, invalidate, save, restore = in Bool()
      val pc = in UInt(32 bits)
      logic.pushValid := push; logic.pushRetPc := pc; logic.popValid := pop
      logic.invalidateAll := invalidate
      logic.checkpointSave := save; logic.checkpointRestore := restore
      logic.ras.foreach(_.simPublic()); logic.ckRas.foreach(_.simPublic())
    }
  }
  class Dut(depth: Int) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val ras = new CheckedRas
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams(rasEntries = depth)), ras)) }
  }
  case class State(data: Vector[Option[Long]], sp: Int, count: Int,
                   checkpoint: Vector[Option[Long]], ckSp: Int, ckCount: Int,
                   arm: Boolean)

  for (depth <- Seq(2, 4, 16)) {
    test(s"poisoned RAS payload respects ownership through resets and checkpoints depth=$depth", VerilatorTest) {
      M68kSim().compile(new Dut(depth)).doSim { dut =>
        SimTimeout(2000000)
        val cd = dut.clockDomain
        val q = dut.ras.check
        val d = dut.ras.logic
        val rng = new scala.util.Random(0x524153L + depth)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        def empty = State(Vector.fill(depth)(None), 0, 0, Vector.fill(depth)(None), 0, 0, false)
        var model = empty
        var cycles = 0; var resets = 0; var validSamples = 0
        def idle(): Unit = {
          q.push #= false; q.pop #= false; q.invalidate #= false
          q.save #= false; q.restore #= false; q.pc #= 0
        }
        def checkState(): Unit = {
          assert(d.rasSp.toInt == model.sp && d.count.toInt == model.count,
            s"RAS ownership live control mismatch cycle=$cycles")
          assert(d.ckRasSp.toInt == model.ckSp && d.ckCount.toInt == model.ckCount,
            s"RAS ownership checkpoint control mismatch cycle=$cycles")
          assert(d.ckSaveArm.toBoolean == model.arm, s"RAS deferred-save mismatch cycle=$cycles")
          assert(d.predValid.toBoolean == (model.count != 0), s"RAS prediction validity mismatch cycle=$cycles")
          for (i <- 1 to model.count) {
            val row = (model.sp - i) & (depth - 1)
            val expected = model.data(row).getOrElse(fail(s"reference exposed unwritten live row=$row"))
            assert(d.ras(row).toLong == expected,
              s"RAS poisoned live payload became owned: cycle=$cycles row=$row expected=$expected got=${d.ras(row).toLong}")
          }
          for (i <- 1 to model.ckCount) {
            val row = (model.ckSp - i) & (depth - 1)
            val expected = model.checkpoint(row).getOrElse(fail(s"reference exposed unwritten checkpoint row=$row"))
            assert(d.ckRas(row).toLong == expected,
              s"RAS poisoned checkpoint payload became owned: cycle=$cycles row=$row")
          }
          val target = if (model.count == 0) 0L else {
            val expected = model.data((model.sp - 1) & (depth - 1)).get
            assert(d.predTarget.toLong == expected, s"RAS valid target mismatch cycle=$cycles")
            validSamples += 1
            expected
          }
          digest.update(s"$cycles:${model.sp}:${model.count}:${model.ckSp}:${model.ckCount}:${model.arm}:$target\n".getBytes("UTF-8"))
        }
        def resetAndPoison(): Unit = {
          cd.waitFallingEdge(); idle(); cd.assertReset()
          // Sampling waits deliberately ignore edges while reset is asserted.
          sleep(30)
          cd.waitFallingEdge(); cd.deassertReset(); sleep(1)
          // Force actual unowned payload flops, never control state. Different
          // live/checkpoint poisons make a stale copy visible, including at POR.
          for (row <- 0 until depth) {
            d.ras(row) #= (0xdad00000L | (resets.toLong << 8) | row)
            d.ckRas(row) #= (0xbad00000L | (resets.toLong << 8) | row)
          }
          model = empty; resets += 1
          sleep(1); checkState()
        }
        def step(op: Int = 0, invalidate: Boolean = false, save: Boolean = false,
                 restore: Boolean = false): Unit = {
          val pc = 0x40000000L | (rng.nextInt().toLong & 0x0ffffffcL)
          cd.waitFallingEdge()
          q.push #= (op == 1); q.pop #= (op == 2); q.pc #= pc
          q.invalidate #= invalidate; q.save #= save; q.restore #= restore
          val old = model
          var data = old.data; var sp = old.sp; var count = old.count
          if (op == 1) { data = data.updated(sp, Some(pc)); sp = (sp + 1) & (depth - 1); count = math.min(count + 1, depth) }
          if (op == 2) { sp = (sp - 1) & (depth - 1); count = math.max(count - 1, 0) }
          if (invalidate) { sp = 0; count = 0 }
          if (restore) { data = old.checkpoint; sp = old.ckSp; count = old.ckCount }
          val capture = old.arm && !restore
          model = State(data, sp, count, if (capture) old.data else old.checkpoint,
            if (capture) old.sp else old.ckSp, if (capture) old.count else old.ckCount, save)
          cd.waitRisingEdge(); sleep(1)
          cycles += 1; checkState()
        }
        cd.forkStimulus(10); idle(); cd.waitSampling(20)
        resetAndPoison()
        step(restore = true) // Restore before any checkpoint must remain empty.
        for (_ <- 0 until depth + 5) step(1)
        step(save = true); step() // Deferred capture of a full stack.
        for (_ <- 0 until 2 * depth + 3) step(1)
        for (_ <- 0 until 3) step(2)
        step(restore = true)
        for (_ <- 0 until depth + 2) step(2)
        step(1, invalidate = true, save = true); step(); step(restore = true)
        step(1); step(save = true); step()
        step(1, save = true); step(restore = true) // Cancel deferred refresh.
        step(save = true); resetAndPoison() // Reset while a save is armed.
        step(restore = true)
        val randomCycles = if (depth == 16) 100000 else 20000
        for (i <- 0 until randomCycles) {
          if (i != 0 && i % 4096 == 0) {
            step(1); step(save = true); step(); resetAndPoison(); step(restore = true)
          }
          if (i % 251 < 24) {
            val combo = i % 251
            step(combo / 8, (combo & 1) != 0, (combo & 2) != 0, (combo & 4) != 0)
          } else step(rng.nextInt(3), rng.nextInt(257) == 0,
            rng.nextInt(7) == 0, rng.nextInt(31) == 0)
        }
        assert(validSamples > randomCycles / 10)
        val hash = digest.digest().map(b => f"${b & 255}%02x").mkString
        println(s"RAS_POISON_TRACE depth=$depth cycles=$cycles resets=$resets valid=$validSamples sha256=$hash")
      }
    }
  }
}
