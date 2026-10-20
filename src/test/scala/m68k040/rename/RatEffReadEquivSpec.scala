package m68k040.rename

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** CPU_RAT_EFF_READ: a RatTable that resolves `location` once per arch register must read
  * back exactly what the reference (two muxes + location mux per read port) reads, every
  * cycle, under random writes / commits / rollbacks / reads -- for every shipped shape. */
class RatEffReadEquivSpec extends AnyFunSuite {
  case class Shape(phys: Int, arch: Int, reads: Int, commits: Int)
  class Dut(sh: Shape) extends Component {
    val ref = RatTable(sh.phys, sh.arch, writePorts = 2, commitPorts = sh.commits, readPorts = sh.reads, effRead = false)
    val eff = RatTable(sh.phys, sh.arch, writePorts = 2, commitPorts = sh.commits, readPorts = sh.reads, effRead = true)
    val rollback = in Bool()
    val wv = in Bits(2 bits); val wa = in Vec(UInt(log2Up(sh.arch) bits), 2); val wd = in Vec(UInt(sh.phys bits), 2)
    val cv = in Bits(sh.commits bits); val ca = in Vec(UInt(log2Up(sh.arch) bits), sh.commits)
    val cd = in Vec(UInt(sh.phys bits), sh.commits)
    val ra = in Vec(UInt(log2Up(sh.arch) bits), sh.reads)
    val same = out Bool()
    for (t <- Seq(ref, eff)) {
      t.io.rollback := rollback
      for (i <- 0 until 2) { t.io.writes(i).valid := wv(i); t.io.writes(i).addr := wa(i); t.io.writes(i).data := wd(i) }
      for (i <- 0 until sh.commits) { t.io.commits(i).valid := cv(i); t.io.commits(i).addr := ca(i); t.io.commits(i).data := cd(i) }
      for (i <- 0 until sh.reads) t.io.reads(i).addr := ra(i)
    }
    same := (0 until sh.reads).map(i => ref.io.reads(i).data === eff.io.reads(i).data).reduce(_ && _) &&
            ref.io.committedPhys.asBits === eff.io.committedPhys.asBits
  }

  for (sh <- Seq(Shape(6, 18, 8, 2), Shape(4, 8, 6, 2), Shape(5, 1, 2, 2))) {
    test(s"RatTable effRead == reference ($sh)") {
      M68kSim().withVerilator.compile(new Dut(sh)).doSim(seed = 0xEFF) { d =>
        val rnd = new scala.util.Random(0xEFF + sh.arch)
        d.clockDomain.forkStimulus(10)
        d.rollback #= false; d.wv #= 0; d.cv #= 0
        d.clockDomain.waitSampling(2)
        // committed image has no reset: seed every arch register identically in both tables
        for (a <- 0 until sh.arch) {
          d.cv #= 1; d.ca(0) #= a; d.cd(0) #= a % (1 << sh.phys)
          d.clockDomain.waitSampling()
        }
        for (_ <- 0 until 50000) {
          d.rollback #= rnd.nextInt(16) == 0
          d.wv #= rnd.nextInt(4); d.cv #= rnd.nextInt(1 << sh.commits)
          d.wa.foreach(_ #= rnd.nextInt(sh.arch)); d.wd.foreach(_.randomize())
          d.ca.foreach(_ #= rnd.nextInt(sh.arch)); d.cd.foreach(_.randomize())
          d.ra.foreach(_ #= rnd.nextInt(sh.arch))
          d.clockDomain.waitSampling()
          sleep(1)
          assert(d.same.toBoolean, "effRead RAT read diverged from the reference")
        }
      }
    }
  }
}
