package m68k040.top

import m68k040.M68kParams
import m68k040.fuzz.FuzzCoreDut
import m68k040.lockstep.ExecuteLockStepSpec
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

import java.lang.reflect.Modifier
import java.nio.file.Files

/** THE SHIPPING-PARITY CHECK (2026-09-30).
  *
  * The corpus (`FuzzCoreDut`) and lock-step (`ExecuteLockStepSpec.FullCoreDut`) harnesses
  * each claim a "shipping" posture (`FUZZ_SHIPPING=1` / `LOCKSTEP_SHIPPING=1`). This
  * ELABORATES `M68kSocketTop` under throughput-v2 and both harness DUTs in that posture,
  * then compares every configurable plugin's scalar fields BY REFLECTION -- i.e. what the
  * instances actually hold after construction, not what the source says they were
  * passed. It prints both configurations field by field.
  *
  * The only permitted difference is TOPOLOGY: `DcachePlugin.socketMerged` (the harnesses
  * have no `AxiDMergePlugin`). Anything else is the "shipping config is not the tested
  * config" defect this project has now hit five times. */
class ShippingConfigParitySpec extends AnyFunSuite {
  private val allowedDiffs = Set("DcachePlugin.socketMerged")

  private def scalar(v: Any): Boolean = v match {
    case null => false
    case _: Boolean | _: Int | _: Long | _: String | _: Double | _: BigInt => true
    case _ => false
  }

  /** className.field -> value, for every scalar field declared on the plugin's class. */
  private def dump(p: FiberPlugin): Map[String, String] = {
    val cls = p.getClass
    val name = cls.getSimpleName
    cls.getDeclaredFields.toSeq.filterNot(f => Modifier.isStatic(f.getModifiers)).flatMap { f =>
      f.setAccessible(true)
      val v = f.get(p)
      if (scalar(v)) Some(s"$name.${f.getName.split("\\$\\$").last}" -> v.toString) else None
    }.toMap
  }

  private def elaborate[T <: Component](top: => T): T = {
    val dir = Files.createTempDirectory("shipping-parity").toString
    SpinalConfig(targetDirectory = dir).generateVerilog(top).toplevel
  }

  private lazy val socket: Map[String, String] = {
    val t = elaborate(new M68kSocketTop(M68kParams(), ipcThroughput = true, ipcLateStore = true))
    val wanted = Set("ItlbPlugin", "IcachePlugin", "DcachePlugin", "RasPlugin", "GsharePlugin",
      "FetchAlignPlugin", "DecodeStage", "RobPlugin", "IssueQueuePlugin", "LsEuPlugin")
    t.socket.core.plugins.filter(p => wanted(p.getClass.getSimpleName)).map(dump).reduce(_ ++ _)
  }

  private def compare(label: String, harness: Map[String, String], enforce: Boolean = true): Unit = {
    val keys = (socket.keySet ++ harness.keySet).toSeq.sorted
    println(f"===== SHIPPING PARITY: M68kSocketTop vs $label =====")
    println(f"${"field"}%-58s ${"SocketTop"}%-12s $label")
    val diffs = keys.flatMap { k =>
      val a = socket.getOrElse(k, "<absent>"); val b = harness.getOrElse(k, "<absent>")
      val mark = if (a == b) "" else if (allowedDiffs(k)) "  (topology, allowed)" else "  <<< DIFFERS"
      println(f"$k%-58s $a%-12s $b$mark")
      if (a != b && !allowedDiffs(k)) Some(s"$k: SocketTop=$a $label=$b") else None
    }
    if (enforce) assert(diffs.isEmpty, s"$label is NOT the shipping configuration:\n  ${diffs.mkString("\n  ")}")
  }

  test("FuzzCoreDut(FUZZ_SHIPPING) matches M68kSocketTop field by field") {
    val d = elaborate(new FuzzCoreDut(forceShipping = true))
    compare("FuzzCoreDut", d.configuredPlugins.map(dump).reduce(_ ++ _))
  }

  test("report only: the DEFAULT FuzzCoreDut (what the corpus gated before FUZZ_SHIPPING)") {
    val d = elaborate(new FuzzCoreDut())
    compare("FuzzCoreDut-DEFAULT", d.configuredPlugins.map(dump).reduce(_ ++ _), enforce = false)
  }

  test("ExecuteLockStepSpec.FullCoreDut(LOCKSTEP_SHIPPING) matches M68kSocketTop field by field") {
    val spec = new ExecuteLockStepSpec
    val d = elaborate(new spec.FullCoreDut(forceShipping = true))
    compare("LockStepDut", d.configuredPlugins.map(dump).reduce(_ ++ _))
  }
}
