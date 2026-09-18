package m68k040.execute.fpu

import m68k040.{M68kSim, VerilatorTest}
import m68k040.cache.CacheMode
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class FmovemColdMemorySpec extends AnyFunSuite {
  test("cold memory preserves translation context, page offsets and precise faults", VerilatorTest) {
    M68kSim().withVerilator.compile(new FmovemColdMemory).doSim(seed = 68043) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.command.valid #= false
      dut.io.command.payload.address #= 0
      dut.io.command.payload.store #= false
      dut.io.command.payload.data #= 0
      dut.io.supervisor #= false
      dut.io.pageSize8K #= false
      dut.io.cacheEnabled #= false
      dut.io.translation.ready #= false
      dut.io.translated.valid #= false
      dut.io.translated.payload.token #= 0x100
      dut.io.translated.payload.ppn #= 0
      dut.io.translated.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.io.translated.payload.fault #= false
      dut.io.memory.ready #= false
      dut.io.memoryResponse.valid #= false
      dut.io.memoryResponse.payload.data #= 0
      dut.io.memoryResponse.payload.fault #= false
      cd.waitSampling(5)
      val rng = new scala.util.Random(68043)
      for (va <- Seq(0x20fffL, 0x21000L, 0x21fffL, 0xffffffffL);
           page8 <- Seq(false,true); sup <- Seq(false,true);
           enabled <- Seq(false,true); store <- Seq(false,true);
           mode <- Seq(CacheMode.WRITETHROUGH, CacheMode.COPYBACK, CacheMode.INHIBITED);
           failure <- 0 until 3) {
        val ppn = 0xabcdf // odd PPN bit 0 must be ignored for 8 KiB pages
        val byte = rng.nextInt(256)
        assert(dut.io.command.ready.toBoolean)
        dut.io.command.payload.address #= va
        dut.io.command.payload.store #= store
        dut.io.command.payload.data #= byte
        dut.io.supervisor #= sup
        dut.io.pageSize8K #= page8
        dut.io.cacheEnabled #= enabled
        dut.io.command.valid #= true
        cd.waitSampling()
        dut.io.command.valid #= false
        // Change all live context after acceptance: the captured request owns it.
        dut.io.command.payload.address #= 0
        dut.io.supervisor #= !sup
        dut.io.pageSize8K #= !page8
        dut.io.cacheEnabled #= !enabled
        cd.waitSampling()
        for (_ <- 0 until 1+rng.nextInt(3)) {
          assert(dut.io.translation.valid.toBoolean)
          assert(dut.io.translation.payload.vpn.toLong == (va >> 12))
          assert(dut.io.translation.payload.supervisor.toBoolean == sup)
          assert(dut.io.translation.payload.write.toBoolean == store)
          assert(dut.io.translation.payload.token.toInt == 0x100)
          assert(!dut.io.memory.valid.toBoolean)
          cd.waitSampling()
        }
        dut.io.translation.ready #= true
        cd.waitSampling()
        dut.io.translation.ready #= false
        cd.waitSampling()
        // Shared translation replies for another owner cannot complete this byte.
        dut.io.translated.payload.token #= 7
        dut.io.translated.valid #= true
        cd.waitSampling()
        dut.io.translated.valid #= false
        cd.waitSampling()
        assert(!dut.io.memory.valid.toBoolean && !dut.io.response.valid.toBoolean)
        dut.io.translated.payload.token #= 0x100
        dut.io.translated.payload.ppn #= ppn
        dut.io.translated.payload.cacheMode #= mode
        dut.io.translated.payload.fault #= (failure == 1)
        dut.io.translated.valid #= true
        cd.waitSampling()
        dut.io.translated.valid #= false
        sleep(1)
        if (failure != 1) {
          val expectedPa = if (page8) ((ppn.toLong & ~1L) << 12) | (va & 0x1fff)
                           else (ppn.toLong << 12) | (va & 0xfff)
          for (_ <- 0 until 1+rng.nextInt(4)) {
            assert(dut.io.memory.valid.toBoolean)
            assert(dut.io.memory.payload.vaddr.toLong == va)
            assert(dut.io.memory.payload.paddr.toLong == expectedPa)
            assert(dut.io.memory.payload.store.toBoolean == store)
            assert(dut.io.memory.payload.data.toInt == byte)
            assert(dut.io.memory.payload.cacheMode.toEnum ==
              (if (enabled) mode else CacheMode.INHIBITED))
            assert(!dut.io.response.valid.toBoolean)
            cd.waitSampling()
          }
          dut.io.memory.ready #= true
          cd.waitSampling()
          dut.io.memory.ready #= false
          cd.waitSampling(1+rng.nextInt(3))
          assert(!dut.io.response.valid.toBoolean)
          dut.io.memoryResponse.payload.data #= (byte ^ 0xa5)
          dut.io.memoryResponse.payload.fault #= (failure == 2)
          dut.io.memoryResponse.valid #= true
          cd.waitSampling()
          dut.io.memoryResponse.valid #= false
          sleep(1)
        }
        assert(dut.io.response.valid.toBoolean)
        assert(dut.io.response.payload.fault.toBoolean == (failure != 0))
        assert(dut.io.translationFault.toBoolean == (failure == 1))
        assert(!dut.io.memory.valid.toBoolean)
        if (failure != 1) assert(dut.io.response.payload.data.toInt == (byte ^ 0xa5))
        cd.waitSampling()
        sleep(1)
        assert(!dut.io.response.valid.toBoolean)
      }
    }
  }
}
