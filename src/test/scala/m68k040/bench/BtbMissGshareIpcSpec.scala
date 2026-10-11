package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._

/** BTB-MISS GSHARE DIRECTION (`CPU_BTBMISS_GSHARE`, `FetchAlignPlugin.btbMissGshare`) --
  * directed full-core A/B.
  *
  * A slot-0 Bcc/DBcc that missed the BTB used to fall through with no history event.
  * With the flag it is an ordinary gshare event: `phtValid` record + one GHR shift with
  * the predicted direction, and a predicted-taken one redirects to the decode-computed
  * target. Properties, checked on both arms:
  *
  *  1. GHR CONSISTENCY. `gs-chain` is a run of FRESH sites (every branch PC executes
  *     once, so it always misses the BTB): forward `beq` ALWAYS taken (a cold PHT counter
  *     is weakly-taken => predicted right) and every 4th site a forward `bne` NEVER taken
  *     (predicted taken => mispredict, a flush and a 0 history bit). The run ends with
  *     several correctly predicted taken sites and no flush, so at the end the
  *     speculative `ghr` must equal the retire-time `ghrArch` bit for bit. Omitting the
  *     shift, shifting the wrong direction, or stamping `phtValid` without a shift (or
  *     vice versa) all leave them different (mutation-checked, see the report).
  *     Additionally every BTB-miss conditional emit must carry exactly one `phtValid`
  *     record and raise exactly one shift whose direction is the predicted one.
  *  2. RECOVERY, BOTH DIRECTIONS. `gs-loops` mixes DBRA / Bcc counted loops, a backward
  *     Bcc never taken and a forward Bcc always taken at many BTB-aliasing sites; the d0
  *     checksum gives each path a distinct weight.
  *  3. The ON arm actually fires and removes most `gs-chain` mispredicts. */
class BtbMissGshareIpcSpec extends CoreBenchHarness {

  private def kChain(sites: Int, label: String = "gs-chain"): Kernel = {
    val setup = Seq("lea 0x00300000,%sp", "moveq #0,%d0", "moveq #1,%d1", "move.l #0x100,%d4")
    // kind A (i%4 != 1): moveq #0 ; beq.s skip (taken) ; add d4 ; skip: add d1   d0 += 1
    // kind B (i%4 == 1, not in the last 8): moveq #0 ; bne.s skip (never) ; add d1 ; skip: nop   d0 += 1
    val body = (0 until sites).map { i =>
      if (i % 4 == 1 && i < sites - 8) s"moveq #0,%d2 ; bne.s .Ck$i ; add.l %d1,%d0 ; .Ck$i: nop"
      else s"moveq #0,%d2 ; beq.s .Ck$i ; add.l %d4,%d0 ; .Ck$i: add.l %d1,%d0"
    }
    val pad = Seq.fill(8)("nop")
    val src = (setup ++ body ++ Seq(pad.mkString(" ; "), ".Cend: bra.s .Cend")).mkString(" ; ")
    val nB = (0 until sites).count(i => i % 4 == 1 && i < sites - 8)
    Kernel(label, src, setup.size + sites * 3 + nB + pad.size,
      warmupInstrs = setup.size,
      verifyRetirement = obs => {
        val got = obs.filter(o => o.archRegValid && o.archRegId == 0).last.archRegWrite & 0xffffffffL
        assert(got == sites.toLong, f"chain went wrong: d0 = 0x$got%x, expected $sites")
      })
  }

  /** Same shape as BtfnIpcSpec's loops: kinds (i % 4) DBRA x3, Bcc x3, backward bne
    * never taken, forward beq always taken. */
  private def kLoops(sites: Int, outer: Int, label: String = "gs-loops"): Kernel = {
    val setup = Seq("lea 0x00300000,%sp", "moveq #0,%d0", "moveq #1,%d1", "move.l #0x100,%d4",
                    s"move.l #$outer,%d7")
    val body = (0 until sites).map { i =>
      i % 4 match {
        case 0 => s"moveq #2,%d6 ; .Lk$i: add.l %d1,%d0 ; dbra %d6,.Lk$i"
        case 1 => s"moveq #3,%d6 ; .Lk$i: add.l %d1,%d0 ; subq.l #1,%d6 ; bne.s .Lk$i"
        case 2 => s".Lk$i: add.l %d1,%d0 ; moveq #0,%d2 ; bne.s .Lk$i"
        case _ => s"moveq #0,%d2 ; beq.s .Lk$i ; add.l %d4,%d0 ; .Lk$i: add.l %d1,%d0"
      }
    }
    val macros = (0 until sites).map(i => Seq(7, 10, 3, 3)(i % 4)).sum
    val dsum   = (0 until sites).map(i => Seq(3L, 3L, 1L, 1L)(i % 4)).sum
    val src = (setup ++ Seq(".Lo: " + body.mkString(" ; ") + " ; subq.l #1,%d7 ; bne.w .Lo",
      ".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(label, src, setup.size + outer * (macros + 2),
      warmupInstrs = setup.size,
      verifyRetirement = obs => {
        val got = obs.filter(o => o.archRegValid && o.archRegId == 0).last.archRegWrite & 0xffffffffL
        assert(got == outer * dsum, f"loops went wrong: d0 = 0x$got%x, expected 0x${outer * dsum}%x")
      })
  }

  final case class Probe(var fires: Long = 0, var ftbOwned: Long = 0, var badStamp: Long = 0,
                         var lastGhr: Int = 0, var lastArch: Int = 0)

  test("BTB-miss gshare: history event exactly once, GHR exact, recovers both directions", VerilatorTest) {
    def build(on: Boolean) = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true, sqSubwordForwarding = true,
      pairCorrectBranch = true, retainRedirectHistory = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, earlyStoreAddress = true, fuseLongMoveLoads = true,
      reserveLateStore = true, detachLateStore = true, forwardOnPublish = true,
      earlyLsNzvcWakeup = true, detachedStoreEntries = 4, earlyAutoStoreAddress = true,
      earlyAutoAnWriteback = true, rasBranchRepair = true, rasBranchSnapshot = true,
      computeDirectTargets = true, deferSlot1Uncond = true, deferSlot1Dbcc = true,
      btbMissGshare = on))

    val chain = kChain(96)
    val loops = kLoops(48, 6)
    val kernels = Seq(chain, loops, kBranchy, kHotLoop)
    val seed = sys.env.get("IPC_SEED").map(_.toInt).getOrElse(1)

    val arms = Seq(false, true).map { on =>
      val compiled = build(on)
      on -> kernels.map { k =>
        val p = Probe()
        dutProbe = d => fork {
          val cd = d.clockDomain
          while (true) {
            cd.waitSampling()
            val fire = d.fa.logic.feed.valid.toBoolean && d.fa.logic.feed.ready.toBoolean
            val miss = d.fa.logic.slot0CondMiss.toBoolean
            if (fire && miss && d.fa.logic.ftqConfirm.toBoolean) p.ftbOwned += 1
            else if (fire && miss) {
              p.fires += 1
              // Exactly one record (slot 0) and exactly one shift, in the predicted direction.
              val predT = d.fa.logic.slot0ComputedPred.toBoolean   // == predTaken for a BTB-miss cond
              val slot1Rec = d.fa.logic.slot1ValidOut.toBoolean && d.fa.logic.feed.payload(1).phtValid.toBoolean
              if (!d.fa.logic.feed.payload(0).phtValid.toBoolean || slot1Rec ||
                  !d.gsh.logic.shiftValid.toBoolean || d.gsh.logic.shiftDir.toBoolean != predT) p.badStamp += 1
            }
            p.lastGhr = d.gsh.logic.ghr.toInt
            p.lastArch = d.gsh.logic.ghrArch.toInt
          }
        }
        val r = try runKernel(compiled, k, seed) finally dutProbe = null
        println(f"[btbmiss] on=$on%-5s ${k.name}%-12s retired=${r.retiredInstrs}%6d cycles=${r.windowCycles}%6d " +
          f"IPC=${r.ipc}%5.3f mispred=${r.retiredMispredicts}%5d fires=${p.fires}%5d ftbOwned=${p.ftbOwned}%4d " +
          f"bad=${p.badStamp}%d ghr=0x${p.lastGhr}%x arch=0x${p.lastArch}%x")
        k.name -> (r, p)
      }.toMap
    }.toMap

    for (on <- Seq(false, true)) {
      val (_, p) = arms(on)(chain.name)
      assert(p.lastGhr == p.lastArch,
        f"[on=$on] chain: speculative GHR 0x${p.lastGhr}%x != architectural 0x${p.lastArch}%x -- " +
        "a BTB-miss conditional shifted history the retire side never saw (or vice versa)")
      for (k <- kernels) assert(arms(on)(k.name)._2.badStamp == 0, s"[on=$on ${k.name}] BTB-miss emit without exactly one history event")
    }
    for (k <- kernels) assert(arms(false)(k.name)._2.fires == 0, s"[${k.name}] slot0CondMiss fired with the flag OFF")
    val cOff = arms(false)(chain.name)._1.retiredMispredicts
    val cOn  = arms(true)(chain.name)._1.retiredMispredicts
    assert(arms(true)(chain.name)._2.fires >= 64, "chain: the BTB-miss gshare path must fire on the fresh beqs")
    assert(cOff >= 60 && cOn <= cOff / 2, s"chain: BTB-miss gshare must remove most fresh taken mispredicts (off=$cOff on=$cOn)")
    assert(arms(true)(loops.name)._2.fires > 0, "loops: BTB-miss gshare never fired")
  }
}
