package m68k040.decode

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.{Cluster, Size, MemOp}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

/** Task 2: µop expansion queue. Accepts up to 4 µops/cycle (2 decode slots ×
  * up to 2 µops each), emits 2/cycle to rename, FIFO order, flushable. */
class MicroOpQueueSpec extends AnyFunSuite {
  def mk = new MicroOpQueue(depth = 16)

  /** Poke push port: nPush µops with dstReg = tag base + i (tag carries order). */
  def pushBurst(dut: MicroOpQueue, base: Int, nPush: Int): Unit = {
    dut.io.push.valid #= (nPush > 0)
    dut.io.push.count #= nPush
    for (i <- 0 until 4) {
      val u = dut.io.push.uops(i)
      u.valid        #= (i < nPush)
      u.pc           #= 0
      u.op           #= DecOp.MOVE
      u.cluster      #= Cluster.INT
      u.size         #= Size.LONG
      u.memOp        #= MemOp.NONE
      u.srcAReg #= 0; u.srcAValid #= false
      u.srcBReg #= 0; u.srcBValid #= false
      u.dstReg  #= (base + i) & 0x1f; u.dstValid #= true
      u.useImm  #= false; u.imm #= 0
      u.readsNzvc #= false; u.readsX #= false
      u.writesNzvc #= false; u.writesX #= false
      u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
      u.unimplemented #= false
    }
  }

  def idle(dut: MicroOpQueue): Unit = { dut.io.push.valid #= false; dut.io.push.count #= 0 }

  test("FIFO order: variable push counts, pop 2/cycle preserves order", VerilatorTest) {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.io.flush #= false
      idle(dut)
      dut.io.pop.ready #= false
      cd.waitSampling()

      // Push a burst whose tags are 0,1,2,... in order across cycles.
      // Cycle A: 3 µops (tags 0,1,2). Cycle B: 1 µop (tag 3). Cycle C: 4 µops (4,5,6,7).
      val plan = Seq(3, 1, 4)
      var tag = 0
      for (n <- plan) {
        // wait until queue can accept (pushReady)
        var g = 0
        while (!dut.io.push.ready.toBoolean && g < 50) { cd.waitSampling(); g += 1 }
        assert(dut.io.push.ready.toBoolean, "push.ready never asserted")
        pushBurst(dut, tag, n)
        cd.waitSampling()
        tag += n
      }
      idle(dut)
      val total = plan.sum  // 8

      // Now drain 2/cycle and collect tags in order. Read the popped pair on the
      // SAME cycle the pop fires (pop.valid && pop.ready), then advance.
      val got = scala.collection.mutable.ArrayBuffer[Int]()
      dut.io.pop.ready #= true
      sleep(1)
      var guard = 0
      while (got.size < total && guard < 200) {
        if (dut.io.pop.valid.toBoolean && dut.io.pop.ready.toBoolean) {
          got += dut.io.pop.payload(0).dstReg.toInt
          if (dut.io.pop1Valid.toBoolean) got += dut.io.pop.payload(1).dstReg.toInt
        }
        cd.waitSampling(); sleep(1); guard += 1
      }
      dut.io.pop.ready #= false
      assert(got.size == total, s"expected $total µops, got ${got.size}: $got")
      assert(got.toSeq == (0 until total), s"FIFO order violated: $got")
    }
  }

  test("flush clears the queue", VerilatorTest) {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.io.flush #= false
      idle(dut)
      dut.io.pop.ready #= false
      cd.waitSampling()
      var g = 0
      while (!dut.io.push.ready.toBoolean && g < 50) { cd.waitSampling(); g += 1 }
      pushBurst(dut, 0, 4)
      cd.waitSampling()
      idle(dut)
      cd.waitSampling()
      assert(dut.io.pop.valid.toBoolean, "queue should be non-empty before flush")
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      cd.waitSampling()
      assert(!dut.io.pop.valid.toBoolean, "queue must be empty after flush")
    }
  }
}
