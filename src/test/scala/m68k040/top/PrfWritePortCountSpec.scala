package m68k040.top

import m68k040.M68kParams
import m68k040.execute.regfile.{RegFilePlugin, RegFilePluginFp, RegFilePluginInt}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import java.nio.file.Files

/** Physical PRF write-port count of the ELABORATED shipping top (review 10 §5.1).
  *
  * `ShippingCoreConfig.coldWbShare` folds the ROB's cold FMOVEM An write onto BranchEu's int
  * write port and its cold FMOVEM FP write onto DivEu's FP write port. This reads the
  * merged physical write buses straight out of each `RegFilePlugin` after elaboration, so
  * the count is the netlist's, not a reading of the source. */
class PrfWritePortCountSpec extends AnyFunSuite {
  test("physical PRF write ports of M68kSocketTop match coldWbShare") {
    val dirPath = Files.createTempDirectory("prf-wports")
    val t = try SpinalConfig(targetDirectory = dirPath.toString).generateVerilog(
      new M68kSocketTop(M68kParams(), ipcThroughput = true, ipcLateStore = true)).toplevel
    finally { // /tmp is a small tmpfs: never leak the generated netlist
      Files.walk(dirPath).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
    }
    def count(p: RegFilePlugin): Int = p.logic.phys.size
    val rfs = t.socket.core.plugins.collect { case p: RegFilePlugin => p }
    rfs.foreach(p => println(s"[prf-wports] ${p.spec.name}: ${count(p)} physical write port(s) " +
                             s"(coldWbShare=${ShippingCoreConfig.coldWbShare})"))
    val int = rfs.collectFirst { case p: RegFilePluginInt => count(p) }.get
    val fp  = rfs.collectFirst { case p: RegFilePluginFp  => count(p) }.get
    if (ShippingCoreConfig.coldWbShare) { assert(int == 5); assert(fp == 1) }
    else                                { assert(int == 6); assert(fp == 2) }
  }
}
