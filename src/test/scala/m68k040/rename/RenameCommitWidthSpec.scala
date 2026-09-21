package m68k040.rename

import m68k040.{M68kSim, VerilatorTest}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import org.scalatest.funsuite.AnyFunSuite

/** Real two-wide rename allocation feeding wider, age-ordered commit batches.
  * Checks every committed map and every free record, not just the last writer. */
class RenameCommitWidthSpec extends AnyFunSuite {
  class Dut(width: Int, prepared: Boolean = false) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val source = new DecodeUopSourcePlugin
    val rename = new RenameStage(retireWidth = width, preparedRetirement = prepared)
    val sink = new RenameUopSinkPlugin
    val commit = new RenameCommitDriverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](source, rename, sink, commit)) }
  }

  private def zero(data: Data): Unit = data.flatten.foreach {
    case b: Bool => b #= false
    case e: SpinalEnumCraft[_] =>
      val typed = e.asInstanceOf[SpinalEnumCraft[SpinalEnum]]
      typed #= typed.spinalEnum.elements.head.asInstanceOf[SpinalEnumElement[SpinalEnum]]
    case b: BitVector => b #= BigInt(0)
    case other => fail(s"unhandled field $other")
  }
  case class Record(intArch: Int, fpArch: Int, fresh: Vector[Int], old: Vector[Int], writes: Vector[Boolean])

  for ((width, prepared) <- Seq((2, false), (4, false), (4, true), (8, true), (16, true)))
      test(s"$width commit lanes prepared=$prepared preserve all maps and WAW frees across recovery", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut(width, prepared)).doSim { d =>
      SimTimeout(1000000)
      val cd = d.clockDomain
      cd.forkStimulus(10)
      val source = d.source.logic.src
      val output = d.sink.logic.out
      val commands = d.commit.logic.cmd
      val ren = d.rename.logic
      val frees = Seq(ren.intFree, ren.nzvcFree, ren.xFree, ren.fpFree, ren.fpccFree)
      val counts = Vector(m68k040.Global.PHYS_INT_REGS_DEFAULT - m68k040.isa.Isa.ARCH_INT_REGS,
        15, 15, 8, 15)
      val maps = Vector(Array.tabulate(m68k040.isa.Isa.ARCH_INT_REGS)(identity),
        Array(0), Array(0), Array.tabulate(8)(identity), Array(0))
      source.valid #= false; d.source.logic.s1v #= false
      output.ready #= true; d.commit.logic.flushIn #= false
      source.payload.foreach(zero); commands.foreach(zero)
      d.commit.logic.preparation.foreach { p =>
        p.begin #= false; p.abort #= false; p.publish #= false; p.writes.foreach(zero)
      }
      cd.waitSamplingWhere(source.ready.toBoolean); sleep(1)

      def checkMaps(): Unit = {
        val observed = Vector(ren.intRat.io.committedPhys, ren.nzvcRat.io.committedPhys,
          ren.xRat.io.committedPhys, ren.fpRat.io.committedPhys, ren.fpccRat.io.committedPhys)
        for (bank <- maps.indices; arch <- maps(bank).indices)
          assert(observed(bank)(arch).toInt == maps(bank)(arch), s"bank $bank arch $arch")
      }
      def allocate(n: Int, start: Int, waw: Boolean, readOnly: Boolean = false,
                   bankMask: Int = 31): Vector[Record] = {
        val writes = Vector.tabulate(5)(bank => !readOnly && (bankMask & (1 << bank)) != 0)
        (0 until n by 2).flatMap { base =>
          val lanes = math.min(2, n - base)
          output.ready #= false
          for (lane <- 0 until 2) {
            val u = source.payload(lane)
            zero(u)
            val intArch = if (waw) 0 else (start + base + lane) % maps(0).length
            val fpArch = if (waw) 0 else (start + base + lane) % 8
            u.valid #= (lane < lanes)
            u.srcAReg #= intArch; u.srcAValid #= true
            u.fpSrcAReg #= fpArch; u.usesFpSrcA #= true
            u.readsNzvc #= true; u.readsX #= true; u.readsFpcc #= true
            u.dstReg #= intArch; u.dstValid #= writes(0)
            u.fpDstReg #= fpArch; u.writesFp #= writes(3)
            u.writesNzvc #= writes(1); u.writesX #= writes(2); u.writesFpcc #= writes(4)
          }
          source.valid #= true; d.source.logic.s1v #= (lanes == 2)
          cd.waitSamplingWhere(source.ready.toBoolean)
          source.valid #= false; sleep(1)
          assert(output.valid.toBoolean)
          val records = (0 until lanes).map { lane =>
            val u = output.payload(lane)
            val intArch = if (waw) 0 else (start + base + lane) % maps(0).length
            val fpArch = if (waw) 0 else (start + base + lane) % 8
            if (readOnly) {
              assert(u.psrcA.toInt == maps(0)(intArch))
              assert(u.pNzvcSrc.toInt == maps(1)(0) && u.pXSrc.toInt == maps(2)(0))
              assert(u.pFpSrcA.toInt == maps(3)(fpArch) && u.pFpccSrc.toInt == maps(4)(0))
            }
            Record(intArch, fpArch,
              Vector(u.pdst.toInt, u.pNzvcDst.toInt, u.pXDst.toInt, u.pFpDst.toInt, u.pFpccDst.toInt),
              Vector(u.pdstOld.toInt, u.pNzvcOld.toInt, u.pXOld.toInt, u.pFpOld.toInt, u.pFpccOld.toInt), writes)
          }
          output.ready #= true; cd.waitSampling(); sleep(1)
          records
        }.toVector
      }
      def putRecord(c: spinal.lib.Flow[m68k040.rob.CommitSlot], r: Record): Unit = {
        zero(c); c.valid #= true
        c.intWrite #= r.writes(0); c.intArch #= r.intArch; c.intNew #= r.fresh(0); c.intOld #= r.old(0)
        c.nzvcWrite #= r.writes(1); c.nzvcNew #= r.fresh(1); c.nzvcOld #= r.old(1)
        c.xWrite #= r.writes(2); c.xNew #= r.fresh(2); c.xOld #= r.old(2)
        c.fpWrite #= r.writes(3); c.fpArchDst #= r.fpArch; c.fpNew #= r.fresh(3); c.fpOld #= r.old(3)
        c.fpccWrite #= r.writes(4); c.fpccNew #= r.fresh(4); c.fpccOld #= r.old(4)
      }
      def publish(records: Vector[Record], lanes: Seq[Int]): Unit = {
        assert(records.size == lanes.size)
        commands.foreach(_.valid #= false)
        d.commit.logic.preparation.foreach { p =>
          cd.waitSampling(2); sleep(1) // drain only earlier, already committed frees
          val before = frees.map(_.count.toInt)
          val groups = if (records.isEmpty) Vector(Vector.empty[Record]) else records.grouped(2).toVector
          for ((group, chunk) <- groups.zipWithIndex) {
            p.begin #= (chunk == 0); p.writes.foreach(_.valid #= false)
            group.zipWithIndex.foreach { case (r, lane) => putRecord(p.writes(lane), r) }
            cd.waitSampling(); sleep(1)
            checkMaps()
            assert(frees.map(_.count.toInt) == before, "preparation released an uncommitted register")
          }
          p.begin #= false; p.writes.foreach(_.valid #= false)
        }
        def commitNow(batch: Vector[Record], positions: Seq[Int], wholeImage: Boolean): Unit = {
          commands.foreach(_.valid #= false)
          d.commit.logic.preparation.foreach(_.publish #= wholeImage)
          for ((r, lane) <- batch.zip(positions)) {
            val c = commands(lane)
            putRecord(c, r)
            val addresses = Vector(r.intArch, 0, 0, r.fpArch, 0)
            for (bank <- maps.indices if r.writes(bank)) {
              assert(r.old(bank) == maps(bank)(addresses(bank)), s"broken old-map chain bank=$bank")
              maps(bank)(addresses(bank)) = r.fresh(bank)
            }
          }
          val before = frees.map(_.count.toInt)
          val priorPending = frees.map(_.reclaim.count(_.valid.toBoolean))
          cd.waitSampling(); commands.foreach(_.valid #= false)
          d.commit.logic.preparation.foreach(_.publish #= false); sleep(1)
          checkMaps()
          for (bank <- frees.indices) {
            assert(frees(bank).count.toInt == before(bank) + priorPending(bank),
              "only prior committed frees may become available on this edge")
            val validLanes = batch.zip(positions).collect { case (r, lane) if r.writes(bank) => lane }
            assert(frees(bank).reclaim.map(_.valid.toBoolean).toVector == commands.indices.map(validLanes.contains).toVector)
            batch.zip(positions).filter(_._1.writes(bank)).foreach { case (r, lane) =>
              assert(frees(bank).reclaim(lane).payload.toInt == r.old(bank), "lost intermediate WAW free")
            }
          }
        }
        if (prepared && records.size >= 3) {
          // The fixed-endpoint shadow survives ordinary consumption of a prefix,
          // including same-register WAWs in all five classes. Free each old ID once.
          commitNow(records.take(1), Seq(0), wholeImage = false)
          commitNow(records.drop(1), lanes.drop(1), wholeImage = true)
        } else commitNow(records, lanes, wholeImage = prepared)
      }
      checkMaps()
      // Exhaust every sparse-lane mask, repeating to wrap the free rings and
      // alternating same-destination WAW chains with distinct int/FP destinations.
      val masks = if (width <= 4) 0 until (1 << width)
        else Seq(0, 1, 3, 7, 15, 0x55, 0xaa, (1 << width) - 1)
      for (round <- 0 until 8; mask <- masks) {
        val lanes = (0 until width).filter(i => (mask & (1 << i)) != 0)
        val start = round * (1 << width) + mask
        val bankMask = if (lanes.size > 8) 1 else if (round % 2 == 0) 31 else 1 << ((mask + round) % 5)
        val records = allocate(lanes.size, start, waw = round % 2 == 0, bankMask = bankMask)
        if (lanes.size <= 6) allocate(2, start + 5, waw = round % 2 == 0) // younger discarded mappings
        publish(records, lanes)
        d.commit.logic.flushIn #= true
        cd.waitSampling(); sleep(1)
        checkMaps()
        frees.zip(counts).foreach { case (f, count) =>
          assert(f.count.toInt == count && f.reclaim.forall(!_.valid.toBoolean))
        }
        cd.waitSampling(); d.commit.logic.flushIn #= false; sleep(1)
        allocate(2, start, waw = round % 2 == 0, readOnly = true)
      }
      // Two maximum-width groups (consecutive commit clocks in ordinary mode).
      // The FP pool is completely allocated before the four-wide case retires.
      val burst = allocate(2 * width, 0, waw = true, bankMask = if (width > 4) 1 else 31)
      publish(burst.take(width), 0 until width)
      publish(burst.drop(width), 0 until width)
      d.commit.logic.flushIn #= true
      cd.waitSampling(); sleep(1)
      checkMaps()
      frees.zip(counts).foreach { case (f, count) => assert(f.count.toInt == count) }
      d.commit.logic.flushIn #= false
      d.commit.logic.preparation.foreach { p =>
        val discarded = allocate(scala.math.min(width, 8), 0, waw = true)
        val before = frees.map(_.count.toInt)
        for ((group, chunk) <- discarded.grouped(2).zipWithIndex) {
          p.begin #= (chunk == 0); p.writes.foreach(_.valid #= false)
          group.zipWithIndex.foreach { case (r, lane) => putRecord(p.writes(lane), r) }
          cd.waitSampling(); sleep(1)
          checkMaps()
          assert(frees.map(_.count.toInt) == before)
        }
        p.begin #= false; p.writes.foreach(_.valid #= false)
        p.abort #= true; d.commit.logic.flushIn #= true
        cd.waitSampling(); sleep(1)
        p.abort #= false; d.commit.logic.flushIn #= false
        checkMaps()
        frees.zip(counts).foreach { case (f, count) => assert(f.count.toInt == count) }
        allocate(2, 0, waw = true, readOnly = true)
      }
      // Reset with all commit/reclaim lanes occupied, then verify identity again.
      publish(allocate(width, 0, waw = true, bankMask = if (width > 8) 1 else 31), 0 until width)
      cd.assertReset(); sleep(40); cd.deassertReset()
      cd.waitSamplingWhere(source.ready.toBoolean); sleep(1)
      maps.foreach(a => a.indices.foreach(i => a(i) = i))
      checkMaps()
      frees.zip(counts).foreach { case (f, count) => assert(f.count.toInt == count) }
    }
  }
}
