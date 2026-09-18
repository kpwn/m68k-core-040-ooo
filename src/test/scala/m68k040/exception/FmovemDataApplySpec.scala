package m68k040.exception

import m68k040.{M68kSim, VerilatorTest}
import m68k040.cache.CacheMode
import m68k040.decode.SysKind
import m68k040.mmu.MmuControlPlugin
import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Fiber
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import org.scalatest.funsuite.AnyFunSuite

class FmovemDataApplySpec extends AnyFunSuite {
  val base = 0x20ffdL
  val seeds = (0 until 8).map(i => BigInt("40008000000000000100",16) + i)
  class Dut extends Component {
    val trigger = in Bool()
    val store = in Bool()
    val drain = in Bool()
    val xReady = in Bool()
    val xValid = in Bool()
    val xPpn = in UInt(20 bits)
    val xFault = in Bool()
    val memReady = in Bool()
    val memValid = in Bool()
    val memData = in Bits(32 bits)
    val memFault = in Bool()
    val xReq = out Bool()
    val xVpn = out UInt(20 bits)
    val ldReq = out Bool()
    val stReq = out Bool()
    val memPa = out UInt(32 bits)
    val stData = out Bits(32 bits)
    val done = out Bool()
    val faultVector = out UInt(8 bits)
    val faultPc = out UInt(32 bits)
    val faultVa = out UInt(32 bits)
    val ssw = out UInt(16 bits)
    val fpValues = out Vec(Bits(80 bits),8)
    val anValue = out UInt(32 bits)
    val bankValue = out UInt(32 bits)
    val host = new PluginHost
    val mmu = new MmuControlPlugin
    host.asHostOf(Seq[FiberPlugin](mmu))
    val logic = Fiber build new Area {
      val ss = new SystemState
      val fp = Vec(seeds.map(v => Reg(Bits(80 bits)) init B(v,80 bits)))
      val an = Reg(UInt(32 bits)) init U(base,32 bits)
      val aux = Vec(B(0x81,32 bits),B(0,32 bits),B(0,32 bits))
      val exc = new ExceptionUnit(ss=ss, mmuCtrl=mmu,
        entryTrigger=False, entryVector=U(0,8 bits), entryPc=U(0,32 bits),
        rteTrigger=False, rtePc=U(0,32 bits), committedCcr=U(0,5 bits),
        sysTrigger=trigger, sysKind=SysKind.FMOVEM_DATA.asBits.asUInt.resize(4),
        sysVal=B(base,32 bits), sysAux=aux,
        sysRc=Mux(store,U(0x67,12 bits),U(0x1f,12 bits)),
        sysPc=U(0x1000,32 bits), sysNextPc=U(0x1006,32 bits))
      exc.sqDrained := drain
      exc.dcQuiesced := drain
      exc.committedA7In := an
      exc.fmFpReadValue := fp(exc.fmFpReadReg)
      when(exc.fmFpWrite.valid) { fp(exc.fmFpWrite.payload.fpReg) := exc.fmFpWrite.payload.value }
      when(exc.fmAnWrite.valid) { an := exc.fmAnWrite.payload }
      when(exc.a7WriteValid) { an := exc.a7WriteData }
      exc.dxReqReady := xReady
      exc.dxRspValid := xValid
      exc.dxRspPpn := xPpn
      exc.dxRspFault := xFault
      exc.dxRspToken := 0x100
      exc.dxRspCacheMode := CacheMode.COPYBACK
      exc.dcLoadCmd.ready := memReady
      exc.dcStore.ready := memReady
      exc.dcLoadRsp.valid := memValid && !store
      exc.dcLoadRsp.payload.data := memData
      exc.dcLoadRsp.payload.line := 0
      exc.dcLoadRsp.payload.fault := memFault
      exc.dcStoreAck := memValid && store
      exc.dcStoreErr := memFault
      xReq := exc.dxReqValid; xVpn := exc.dxReqVpn
      ldReq := exc.dcLoadCmd.valid; stReq := exc.dcStore.valid
      memPa := Mux(store,exc.dcStore.payload.paddr,exc.dcLoadCmd.payload.paddr)
      stData := exc.dcStore.payload.data
      done := exc.redirectValid && exc.redirectPc === 0x1006
      faultVector := exc.curVec; faultPc := exc.curPc; faultVa := exc.curFault; ssw := exc.curSsw
      fpValues := fp; anValue := an; bankValue := ss.a7
    }
  }
  test("serialized owner drains, stages FP loads and preserves An on precise byte faults", VerilatorTest) {
    val compiled = M68kSim().withVerilator.compile(new Dut)
    for (store <- Seq(false,true); failure <- 0 until 3) {
      compiled.doSim(seed=68045+failure+(if(store) 3 else 0)) { dut =>
        val cd=dut.clockDomain; cd.forkStimulus(10)
        dut.trigger #= false; dut.store #= store; dut.drain #= false
        dut.xReady #= false; dut.xValid #= false; dut.xPpn #= 0; dut.xFault #= false
        dut.memReady #= false; dut.memValid #= false; dut.memData #= 0; dut.memFault #= false
        cd.waitSampling(5)
        dut.trigger #= true; cd.waitSampling(); dut.trigger #= false
        for (_ <- 0 until 5) {
          assert(!dut.xReq.toBoolean && !dut.ldReq.toBoolean && !dut.stReq.toBoolean)
          cd.waitSampling()
        }
        dut.drain #= true
        def await(p: => Boolean): Unit = {
          var n=0; while(!p && n<80) { cd.waitSampling(); n+=1 }
          assert(p,"serialized owner did not progress")
        }
        val last=if(failure==0) 23 else 17
        for(i <- 0 to last) {
          val va=if(store) base-12*(i/12+1)+i%12 else base+i
          val reg=if(store) { if(i<12) 7 else 0 } else { if(i<12) 0 else 7 }
          val value=if(store) seeds(reg) else BigInt("c1230123456789abcdef",16)+reg
          val image=((value >> 64)<<80) | (value & ((BigInt(1)<<64)-1))
          val byte=((image >> (8*(11-i%12))) & 255).toInt
          await(dut.xReq.toBoolean)
          assert(dut.xVpn.toLong==(va>>12))
          dut.xReady #= true; cd.waitSampling(); dut.xReady #= false
          cd.waitSampling(2)
          dut.xPpn #= ((va>>12)^0x55555L)
          dut.xFault #= (failure==1 && i==17)
          dut.xValid #= true; cd.waitSampling(); dut.xValid #= false
          if(!(failure==1 && i==17)) {
            await(if(store) dut.stReq.toBoolean else dut.ldReq.toBoolean)
            assert(dut.memPa.toLong==((((va>>12)^0x55555L)<<12)|(va&0xfff)))
            if(store) assert(dut.stData.toLong==byte)
            cd.waitSampling(2)
            dut.memReady #= true; cd.waitSampling(); dut.memReady #= false
            cd.waitSampling(2)
            dut.memData #= byte; dut.memFault #= (failure==2 && i==17)
            dut.memValid #= true; cd.waitSampling(); dut.memValid #= false
          }
          // Even a fully loaded first FP register is not architectural yet.
          assert(dut.anValue.toLong==base)
          for(r <- 0 until 8) assert(dut.fpValues(r).toBigInt==seeds(r))
        }
        if(failure==0) {
          await(dut.done.toBoolean)
          val end=base+(if(store) -24 else 24)
          assert(dut.anValue.toLong==end)
          cd.waitSampling(3)
          assert(dut.anValue.toLong==end && dut.bankValue.toLong==end)
          for(r <- 0 until 8) {
            val expected=if(!store && (r==0 || r==7)) BigInt("c1230123456789abcdef",16)+r else seeds(r)
            assert(dut.fpValues(r).toBigInt==expected)
          }
        } else {
          await(dut.faultVector.toInt==2 && dut.faultPc.toLong==0x1000)
          assert(dut.faultPc.toLong==0x1000)
          val failedVa=if(store) base-24+5 else base+17
          assert(dut.faultVa.toLong==failedVa)
          assert(dut.ssw.toInt==((if(failure==1)0x400 else 0)|(if(store)0 else 0x100)|0x25))
          assert(dut.anValue.toLong==base)
          for(r <- 0 until 8) assert(dut.fpValues(r).toBigInt==seeds(r))
        }
      }
    }
  }
}
