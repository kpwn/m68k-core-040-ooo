package m68k040.execute.fpu

import m68k040.{M68kSim, VerilatorTest}
import m68k040.cache.CacheMode
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class FmovemColdBackendSpec extends AnyFunSuite {
  test("composed cold backend crosses pages and retains exact fault provenance", VerilatorTest) {
    M68kSim().withVerilator.compile(new FmovemColdBackend).doSim(seed = 68044) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.request.valid #= false
      dut.io.request.payload.mask #= 0x81
      dut.io.request.payload.address #= 0x20ffc
      dut.io.request.payload.store #= true
      dut.io.request.payload.predecrement #= false
      dut.io.request.payload.postincrement #= false
      dut.io.supervisor #= false
      dut.io.pageSize8K #= false
      dut.io.cacheEnabled #= true
      dut.io.fpReadValue #= BigInt("c1230123456789abcdef",16)
      dut.io.loaded.ready #= false
      dut.io.translation.ready #= false
      dut.io.translated.valid #= false
      dut.io.translated.payload.ppn #= 0
      dut.io.translated.payload.token #= 0x100
      dut.io.translated.payload.cacheMode #= CacheMode.COPYBACK
      dut.io.translated.payload.fault #= false
      dut.io.memory.ready #= false
      dut.io.memoryResponse.valid #= false
      dut.io.memoryResponse.payload.data #= 0
      dut.io.memoryResponse.payload.fault #= false
      dut.io.result.ready #= false
      cd.waitSampling(5)
      def await(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < 30) { cd.waitSampling(); n += 1 }
        assert(p, "composed backend did not progress")
      }
      val bytes = Seq(0xc1,0x23,0,0,1,0x23,0x45,0x67,0x89,0xab,0xcd,0xef)
      for (failure <- 0 until 3) {
        dut.io.supervisor #= false
        dut.io.pageSize8K #= false
        dut.io.cacheEnabled #= true
        dut.io.request.valid #= true
        cd.waitSampling()
        dut.io.request.valid #= false
        dut.io.supervisor #= true
        dut.io.pageSize8K #= true
        dut.io.cacheEnabled #= false
        val lastByte = if (failure == 0) 23 else 17
        for (i <- 0 to lastByte) {
          val va = 0x20ffcL+i
          val ppn = (va >> 12) ^ 0x55555L
          await(dut.io.translation.valid.toBoolean)
          assert(dut.io.translation.payload.vpn.toLong == (va >> 12))
          assert(!dut.io.translation.payload.supervisor.toBoolean)
          assert(dut.io.translation.payload.write.toBoolean)
          dut.io.translation.ready #= true
          cd.waitSampling()
          dut.io.translation.ready #= false
          cd.waitSampling(2)
          dut.io.translated.payload.ppn #= ppn
          dut.io.translated.payload.fault #= (failure == 1 && i == 17)
          dut.io.translated.valid #= true
          cd.waitSampling()
          dut.io.translated.valid #= false
          if (!(failure == 1 && i == 17)) {
            await(dut.io.memory.valid.toBoolean)
            assert(dut.io.memory.payload.vaddr.toLong == va)
            assert(dut.io.memory.payload.paddr.toLong == ((ppn << 12) | (va & 0xfff)))
            assert(dut.io.memory.payload.cacheMode.toEnum == CacheMode.COPYBACK)
            assert(dut.io.memory.payload.data.toInt == bytes(i%12))
            assert(dut.io.memory.payload.store.toBoolean)
            cd.waitSampling(2)
            dut.io.memory.ready #= true
            cd.waitSampling()
            dut.io.memory.ready #= false
            cd.waitSampling(2)
            dut.io.memoryResponse.payload.fault #= (failure == 2 && i == 17)
            dut.io.memoryResponse.valid #= true
            cd.waitSampling()
            dut.io.memoryResponse.valid #= false
          }
        }
        await(dut.io.result.valid.toBoolean)
        assert(dut.io.result.payload.fault.toBoolean == (failure != 0))
        assert(dut.io.translationFault.toBoolean == (failure == 1))
        assert(dut.io.result.payload.completed.toInt == (if (failure == 0) 2 else 1))
        if (failure != 0) assert(dut.io.byteFaultAddress.toLong == 0x2100dL)
        for (_ <- 0 until 4) {
          assert(!dut.io.translation.valid.toBoolean && !dut.io.memory.valid.toBoolean)
          assert(!dut.io.loaded.valid.toBoolean)
          cd.waitSampling()
        }
        dut.io.result.ready #= true
        cd.waitSampling()
        dut.io.result.ready #= false
        cd.waitSampling()
      }
    }
  }
}
