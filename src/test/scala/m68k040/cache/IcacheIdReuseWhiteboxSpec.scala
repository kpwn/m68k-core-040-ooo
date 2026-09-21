package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed whitebox mechanism test for the SoC boot-hang campaign documented in
  * `macqd700-soc`'s `docs/BUG_calibration_word_misplaced_0d00.md` (Parts 53-59,
  * round-13 recommendation #2/S7). NOT a bug-discovery test -- Part 52 already
  * established this class of race does not arise naturally from this DUT's own
  * fetch stream in sim (the natural allocator only ever reallocates a speculative
  * MSHR slot once `mshrComplete(e) && (mshrErr(e) || mshrPoison(e))`, i.e. strictly
  * AFTER its outstanding AXI response has actually landed -- see the "Completed
  * errors and poisoned silent fills allocate nothing and free locally" comment in
  * `IcachePlugin.scala`). What it verifies directly, by construction, is the
  * MECHANISM question round-13's own hardware round was launched to help settle:
  * does the R-channel accept gate (`pfRspMatch`, `IcachePlugin.scala` around the
  * `axi.r.ready := demandRspMatch || pfRspMatch` line) accept an R beat purely on
  * RID, with NO check that the beat's data actually belongs to the address the
  * owning slot CURRENTLY believes it is waiting on?
  *
  * `pfRspMatch = ridIsPf && mshrValid(rIdx) && mshrArSent(rIdx) && !mshrComplete(rIdx)`
  * -- by inspection this is already visibly ID-only (no `mshrPa`/`mshrSet`/`mshrTag`
  * term at all). This test proves it BEHAVIORALLY rather than just by code reading:
  * it drives a real speculative allocation to get a slot genuinely live+ARsent for
  * a real address, then pokes that slot's own identity registers (`mshrSet`/
  * `mshrTag`/`mshrPa`) to a DIFFERENT address -- the "this slot got silently
  * reallocated out from under its own in-flight AXI ID" scenario Part 57 S7
  * theorized -- and shows the gate accepts a same-RID beat anyway, marking the
  * slot's fill complete with no complaint. If a real 68040-SoC race can ever
  * reallocate a slot before its own outstanding response truly lands (the open
  * hardware question rounds 53-59 have been chasing), THIS is the gate that would
  * silently swallow it.
  *
  * Part 61 ADDENDUM: `IcachePlugin.scala` now carries the fix this mechanism proof
  * motivated -- a per-slot generation counter (`mshrGen`/`mshrArSentGen`) that closes
  * `pfRspMatch`/`demandRspMatch` against exactly this class of mismatch, plus a
  * `staleDrain` path that accepts-and-discards (rather than permanently ignores) any
  * response that fails the tightened gate, breaking the real, hardware-proven circular
  * deadlock (Part 56 S8) this whole campaign exists to close. The MECHANISM test above
  * is kept UNCHANGED and still passes post-fix: it pokes `mshrTag`/`mshrPa` directly
  * WITHOUT touching `mshrGen`, so `genMatch` reads True throughout (identity and
  * generation only ever change together at a real allocate site in this RTL) and the
  * gate still accepts the beat by RID alone -- this documents, honestly, that the fix
  * closes the GENERATION-REUSE-ACROSS-AN-OUTSTANDING-TRANSACTION class of bug (the one
  * Part 57's real hardware capture actually exhibits), not a hypothetical same-
  * generation address-corruption bug that Part 56 S2's static proof already shows this
  * RTL cannot construct on its own (identity and generation are co-written at every
  * real write site). The new "FIX" test below constructs the generation-mismatch shape
  * directly (poking `mshrGen` the same way this file already established as this test
  * class's own methodology for reaching otherwise-unreachable states) and proves the
  * gate now drains-but-does-not-credit it, while the slot's own real, current request
  * still completes normally afterward. */
class IcacheIdReuseWhiteboxSpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin(IcachePredecodeConfig.fromEnvironment)
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  def simConfig = SimConfig.withVerilator

  private val waitCapCycles = 4000
  def waitUntilBounded(cd: ClockDomain, what: String)(cond: => Boolean): Unit = {
    var waited = 0
    while (!cond) {
      assert(waited < waitCapCycles, s"bounded sim wait expired after $waitCapCycles cycles waiting for: $what")
      cd.waitSampling()
      waited += 1
    }
  }

  /** Fire the demand `cmdIn` and wait only for it to be ACCEPTED -- deliberately
    * does NOT wait for `rspOut`, because this test holds every R response back by
    * hand so it can control exactly when (and with what payload) the speculative
    * slot's response arrives. */
  def issueDemandNoWait(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    dut.probe.logic.cmdIn.valid #= true
    dut.probe.logic.cmdIn.payload.pc #= pc
    cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
    dut.probe.logic.cmdIn.valid #= false
  }

  test("MECHANISM: pfRspMatch accepts an R beat by RID alone, even after the owning " +
       "slot's own address identity has been reallocated out from under it",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val axi = dut.icache.logic.axi

      // ---- AR channel: accept every request immediately, record (id, address). ----
      case class Ar(id: Int, address: Long)
      val arLog = scala.collection.mutable.ArrayBuffer[Ar]()
      axi.ar.ready #= true
      axi.r.valid  #= false
      axi.r.payload.data #= 0
      axi.r.payload.id   #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean) {
          arLog += Ar(axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong)
        }
      }

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // ---- open the speculative window with a demand miss, WITHOUT letting the
      // ---- demand's own response ever arrive (it stays held forever on purpose:
      // ---- this test only cares about the speculative slot's R-channel accept). ----
      val base = 0x5000L
      issueDemandNoWait(dut, cd, base)

      val pfIdx = AxiIds.I_SPEC_BASE // == 1, the first speculative MSHR slot
      waitUntilBounded(cd, "speculative slot's own AR to fire")(
        arLog.exists(_.id == pfIdx))
      // Give the control-file writes (mshrValid/mshrArSent) a cycle to settle after
      // the AR fire before sampling them.
      cd.waitSampling(2)

      assert(IcacheArrayProbe.mshrValid(dut.icache, pfIdx),
        "setup failed: speculative slot never went live")
      assert(IcacheArrayProbe.mshrArSent(dut.icache, pfIdx),
        "setup failed: speculative slot's AR never registered as sent")
      assert(!IcacheArrayProbe.mshrComplete(dut.icache, pfIdx),
        "setup failed: speculative slot already complete before any R beat -- test can't isolate the gate")

      val realAr = arLog.filter(_.id == pfIdx)
      assert(realAr.size == 1, s"expected exactly one AR on the speculative ID, got: $realAr")
      val realAddress = realAr.head.address
      val realSet = IcacheArrayProbe.mshrSet(dut.icache, pfIdx)
      val realTag = dut.icache.logic.mshrTag(pfIdx).toBigInt

      // ---- THE CONSTRUCTED WORST CASE: reallocate this slot's own address IDENTITY
      // (its TAG -- the field a future lookup actually compares against to decide
      // HIT/MISS) to a DIFFERENT line, entirely by hand -- exactly what the natural
      // allocator (per IcachePlugin.scala's own invariant) can never do while
      // mshrArSent && !mshrComplete. This stands in for a hypothesized SoC-level race
      // (Part 57 S7) where the CPU's own bookkeeping believes a slot has moved on to
      // a new line while the fabric still has the OLD line's AR outstanding and will
      // eventually deliver ITS response under the same RID.
      //
      // Deliberately leaves `mshrSet` untouched (only `mshrTag`/`mshrPa` move): the
      // repo-wide M4 safety net (`IcachePlugin.scala`, "ONE FILL OWNER PER SET")
      // asserts no two live MSHR entries ever share a `mshrSet` value, which is an
      // invariant about SLOT ALLOCATION, not about this test's target -- the R-channel
      // accept gate's blindness to whether the RESPONSE data matches the slot's own
      // TAG. Colliding `mshrSet` with another already-live speculative slot (which the
      // prefetcher's own next-line stream makes likely) would trip that unrelated
      // assertion and mask the result this test exists to show.
      val geo = CacheGeometry.l1i040
      val fakeTag = (realTag + (BigInt(1) << (geo.tagBits - 2))) % (BigInt(1) << geo.tagBits)
      val fakeAddress = realAddress ^ (1L << 30) // a different line by construction

      dut.icache.logic.mshrTag(pfIdx) #= fakeTag
      dut.icache.logic.mshrPa(pfIdx)  #= fakeAddress
      cd.waitSampling()
      assert(dut.icache.logic.mshrTag(pfIdx).toBigInt == fakeTag,
        "poke of mshrTag did not hold -- some other RTL driver is fighting the test; " +
        "re-derive the scenario, do not weaken the check")

      // ---- Now deliver the REAL fabric's response for the REAL (old) address, on
      // ---- the same RID the slot was originally issued under. A magic pattern
      // ---- stands in for "genuine ROM/DRAM content the old address would return" --
      // ---- the exact bytes don't matter, only that the gate's ACCEPT decision does
      // ---- not depend on them either. ----
      val magicLo = BigInt("AAAAAAAA" * 8, 16)
      val magicHi = BigInt("55555555" * 8, 16)

      def sendBeat(data: BigInt, last: Boolean): Boolean = {
        axi.r.valid #= true
        axi.r.payload.id #= pfIdx
        axi.r.payload.data #= data
        axi.r.payload.resp #= 0
        axi.r.payload.last #= last
        cd.waitSampling()
        val accepted = axi.r.ready.toBoolean
        axi.r.valid #= false
        accepted
      }

      // Hold the beat until it is actually accepted (bounded), then check whether
      // acceptance ever happened WITHOUT reference to the (now mismatched) address.
      def sendBeatBounded(data: BigInt, last: Boolean): Unit = {
        var waited = 0
        var accepted = false
        while (!accepted) {
          assert(waited < waitCapCycles, "R beat with a live PF RID was never accepted -- " +
            "if this is a NEW failure, the gate has gained an address check and this " +
            "test's premise (Part 56 S8's ID-only finding) needs re-verification, not silencing")
          accepted = sendBeat(data, last)
          waited += 1
        }
      }

      sendBeatBounded(magicLo, last = false)
      sendBeatBounded(magicHi, last = true)

      cd.waitSampling(2)

      // ---- THE PROOF: the gate accepted both beats (mshrComplete went True) purely
      // ---- because the RID matched a live, AR-sent, incomplete slot -- with NO
      // ---- regard for the fact this test moved that slot's own address identity to
      // ---- a completely different line in between. ----
      assert(IcacheArrayProbe.mshrComplete(dut.icache, pfIdx),
        "pfRspMatch did NOT accept the stale-RID beat -- the gate is no longer " +
        "ID-only (contradicts Part 56 S8); update this test's premise, don't just " +
        "delete the assertion")

      val gotLo = dut.icache.logic.fillLo.getBigInt(pfIdx)
      val gotHi = dut.icache.logic.fillHi.getBigInt(pfIdx)
      assert(gotLo == magicLo && gotHi == magicHi,
        f"accepted beat landed with the WRONG data -- got lo=0x$gotLo%x hi=0x$gotHi%x, " +
        f"expected the magic pattern lo=0x$magicLo%x hi=0x$magicHi%x")

      // And the slot's own address bookkeeping still says the NEW (fake) tag, not
      // the real address the accepted data actually came from on the bus -- i.e. the
      // fill that will shortly be INSTALLED under (realSet, fakeTag) is really the
      // real (old) address's bytes. This is the silent-corruption shape: whichever
      // line (realSet, fakeTag) nominally is, it is about to receive some OTHER
      // line's data, and future lookups against it will HIT and serve those wrong
      // bytes -- while the true owner of `realAddress`'s response has now been
      // permanently consumed by the wrong slot and will never arrive.
      assert(dut.icache.logic.mshrTag(pfIdx).toBigInt == fakeTag,
        "mshrTag drifted during the R beats -- re-derive rather than assume")
      assert(realAddress != fakeAddress, "sanity: the constructed addresses must differ")
      assert(realTag != fakeTag, "sanity: the constructed tags must differ")

      simSuccess()
    }
  }

  test("FIX: a stale-generation response is drained (unblocking the bus) but NOT " +
       "credited to the current occupant, and the slot's own real, current request " +
       "still completes normally afterward",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val axi = dut.icache.logic.axi

      case class Ar(id: Int, address: Long)
      val arLog = scala.collection.mutable.ArrayBuffer[Ar]()
      axi.ar.ready #= true
      axi.r.valid  #= false
      axi.r.payload.data #= 0
      axi.r.payload.id   #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean) {
          arLog += Ar(axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong)
        }
      }

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // ---- Same setup as the MECHANISM test above: get a real speculative slot
      // ---- live+ARsent for a real address. ----
      val base = 0x5000L
      issueDemandNoWait(dut, cd, base)

      val pfIdx = AxiIds.I_SPEC_BASE
      waitUntilBounded(cd, "speculative slot's own AR to fire")(arLog.exists(_.id == pfIdx))
      cd.waitSampling(2)

      assert(IcacheArrayProbe.mshrValid(dut.icache, pfIdx), "setup failed: slot never went live")
      assert(IcacheArrayProbe.mshrArSent(dut.icache, pfIdx), "setup failed: AR never registered as sent")
      assert(!IcacheArrayProbe.mshrComplete(dut.icache, pfIdx), "setup failed: slot already complete")

      // ---- Happy-path plumbing check: the generation the AR was actually sent under
      // ---- must equal the slot's current generation (this is the ordinary,
      // ---- non-poked case -- `genMatch` must be transparently True here, or every
      // ---- existing I-cache test would already be failing). ----
      val genAtArm = dut.icache.logic.mshrGen(pfIdx).toBigInt
      assert(dut.icache.logic.mshrArSentGen(pfIdx).toBigInt == genAtArm,
        "setup failed: mshrArSentGen did not latch the AR's own generation at AR-fire " +
        "-- the fix's happy-path plumbing is broken, not just its stale-case behavior")

      val staleDrainCount0 = dut.icache.logic.staleDrainCount.toBigInt

      // ---- THE CONSTRUCTED WORST CASE (Part 57 S7's real hardware-inferred shape):
      // ---- the slot's generation advances (as it would if a future bug -- or the
      // ---- still-unattributed real SoC race -- let a reallocation happen without the
      // ---- normal mshrArSent reset tracking it) while `mshrArSentGen` still holds the
      // ---- OLD generation the outstanding AR truly belongs to. Constructed the same
      // ---- way the MECHANISM test constructs its own worst case: poke state the
      // ---- natural allocator provably cannot reach on its own (Part 56 S2), standing
      // ---- in for the hypothesized race. ----
      dut.icache.logic.mshrGen(pfIdx) #= (genAtArm + 1) % 16
      cd.waitSampling()
      assert(dut.icache.logic.mshrGen(pfIdx).toBigInt == (genAtArm + 1) % 16,
        "poke of mshrGen did not hold")
      assert(dut.icache.logic.mshrArSentGen(pfIdx).toBigInt == genAtArm,
        "sanity: mshrArSentGen must still read the OLD generation")

      val staleLo = BigInt("DEADBEEF" * 8, 16)
      val staleHi = BigInt("CAFEBABE" * 8, 16)

      def sendBeat(data: BigInt, last: Boolean): Boolean = {
        axi.r.valid #= true
        axi.r.payload.id #= pfIdx
        axi.r.payload.data #= data
        axi.r.payload.resp #= 0
        axi.r.payload.last #= last
        cd.waitSampling()
        val accepted = axi.r.ready.toBoolean
        axi.r.valid #= false
        accepted
      }

      // The whole point of the fix: this must be accepted (drained) essentially
      // immediately -- a real orphan must NEVER be left stuck on the bus, or the exact
      // deadlock this fix exists to close (Part 56 S8) reappears.
      assert(sendBeat(staleLo, last = false),
        "stale-generation beat 0 was NOT drained -- axi.r.ready stayed low, which is " +
        "the exact permanent-block condition (Part 56 S8 step 1) this fix exists to close")
      assert(sendBeat(staleHi, last = true),
        "stale-generation beat 1 (last) was NOT drained")

      cd.waitSampling(2)

      // ---- THE PROOF: drained, but NOT credited. ----
      assert(!IcacheArrayProbe.mshrComplete(dut.icache, pfIdx),
        "stale-generation beats were wrongly CREDITED -- mshrComplete went True for a " +
        "response that did not belong to the current generation; the fix's genMatch " +
        "term is not doing its job")
      assert(!dut.icache.logic.mshrBeat(pfIdx).toBoolean,
        "stale-generation beats perturbed mshrBeat -- the current occupant's own " +
        "in-flight burst tracking must be untouched by a drained orphan")
      assert(IcacheArrayProbe.mshrValid(dut.icache, pfIdx) && IcacheArrayProbe.mshrArSent(dut.icache, pfIdx),
        "the slot's own live/ARsent bookkeeping was disturbed by draining an orphan " +
        "-- it must stay exactly as it was, still waiting for its REAL response")
      val gotLoAfterStale = dut.icache.logic.fillLo.getBigInt(pfIdx)
      val gotHiAfterStale = dut.icache.logic.fillHi.getBigInt(pfIdx)
      assert(gotLoAfterStale != staleLo && gotHiAfterStale != staleHi,
        "the drained stale beats' data leaked into fillLo/fillHi anyway -- staleDrain " +
        "must not write the line file")
      assert(dut.icache.logic.staleDrainCount.toBigInt == (staleDrainCount0 + 2) % 256,
        "staleDrainCount did not advance by exactly 2 for the two drained beats")

      // ---- THE OTHER HALF OF THE PROOF (explicitly required by this task): the
      // ---- slot's real, CURRENT request must still be able to complete normally
      // ---- afterward -- draining the orphan must not have broken the happy path.
      // ---- Stand-in for "the real AR for the new generation gets (re)sent": latch
      // ---- `mshrArSentGen` up to the current generation, exactly what the real
      // ---- `axi.ar.fire` handler does the moment the slot's OWN AR is actually
      // ---- accepted. ----
      dut.icache.logic.mshrArSentGen(pfIdx) #= dut.icache.logic.mshrGen(pfIdx).toBigInt
      cd.waitSampling()

      val realLo = BigInt("11111111" * 8, 16)
      val realHi = BigInt("22222222" * 8, 16)
      assert(sendBeat(realLo, last = false), "the slot's real beat 0 was not accepted after re-arming")
      assert(sendBeat(realHi, last = true), "the slot's real beat 1 (last) was not accepted after re-arming")
      cd.waitSampling(2)

      assert(IcacheArrayProbe.mshrComplete(dut.icache, pfIdx),
        "the slot's own real, current response was not credited even after its " +
        "generation matched again -- the fix broke the happy path")
      val gotLoReal = dut.icache.logic.fillLo.getBigInt(pfIdx)
      val gotHiReal = dut.icache.logic.fillHi.getBigInt(pfIdx)
      assert(gotLoReal == realLo && gotHiReal == realHi,
        f"the slot's real fill data is wrong -- got lo=0x$gotLoReal%x hi=0x$gotHiReal%x, " +
        f"expected lo=0x$realLo%x hi=0x$realHi%x")

      simSuccess()
    }
  }
}
