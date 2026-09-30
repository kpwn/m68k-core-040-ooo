package m68k040.top

import m68k040.cache.{DcachePlugin, IcachePlugin}
import m68k040.mmu.ItlbPlugin
import m68k040.decode.DecodeStage
import m68k040.execute.LsEuPlugin
import m68k040.execute.iq.IssueQueuePlugin
import m68k040.frontend.{FetchAlignPlugin, GsharePlugin, RasPlugin}
import m68k040.rob.RobPlugin

/** THE SHIPPING CORE'S PLUGIN CONSTRUCTORS, in one place.
  *
  * `ShippingCoreConfig` centralised the KNOB VALUES; this centralises the CONSTRUCTOR
  * CALLS that consume them. Before it existed, `M68kSocketTop` built its LS EU, issue
  * queue, front end, decode, ROB and D-cache with one set of arguments and every sim
  * harness (`FuzzCoreDut` -- the 933-program corpus --, `ExecuteLockStepSpec`,
  * `CoreBenchHarness`) re-typed its own. The corpus therefore gated a DEFAULT store
  * pipeline (one detached store entry, no detached late stores, no forward-on-publish,
  * pretranslated probe hints ON) and a front end with the slot-1 prediction knobs OFF --
  * none of which is what the board runs. That was found 2026-09-30 and is the FIFTH
  * instance of "the shipping configuration is not the tested configuration".
  *
  * Rule: `M68kSocketTop` calls these, and a harness that claims to be "shipping" calls
  * the SAME functions. A harness may override a knob only by passing it BY NAME. */
object ShippingPlugins {
  def specLoadWakeup(ipcThroughput: Boolean): Boolean =
    ipcThroughput && SocketTopConfig.SPEC_LOAD_WAKEUP

  /** Every `LsEuPlugin` knob the shipping top sets, as data, so a harness can bisect ONE
    * knob by name (`.copy(...)`) off the exact shipping values instead of re-typing the
    * constructor. Knobs not listed keep the plugin's own default in BOTH places. */
  case class LsEuKnobs(alignedLoadFallThrough: Boolean, earlyIntWakeup: Boolean,
                       sqSubwordForwarding: Boolean, reserveLateStore: Boolean,
                       detachLateStore: Boolean, forwardOnPublish: Boolean,
                       earlyNzvcWakeup: Boolean, detachedStoreEntries: Int,
                       earlyAutoStoreAddress: Boolean, earlyStoreDataWake: Boolean,
                       earlyAutoAnWriteback: Boolean, specLoadWakeup: Boolean,
                       lsOooIssue: Boolean) {
    def build(): LsEuPlugin = new LsEuPlugin(
      alignedLoadFallThrough = alignedLoadFallThrough, earlyIntWakeup = earlyIntWakeup,
      sqSubwordForwarding = sqSubwordForwarding, reserveLateStore = reserveLateStore,
      detachLateStore = detachLateStore, forwardOnPublish = forwardOnPublish,
      earlyNzvcWakeup = earlyNzvcWakeup, detachedStoreEntries = detachedStoreEntries,
      earlyAutoStoreAddress = earlyAutoStoreAddress, earlyStoreDataWake = earlyStoreDataWake,
      earlyAutoAnWriteback = earlyAutoAnWriteback, specLoadWakeup = specLoadWakeup,
      lsOooIssue = lsOooIssue)
  }

  def lsEuKnobs(ipcThroughput: Boolean, ipcLateStore: Boolean,
                specLoadWakeup: Boolean, lsOooIssue: Boolean): LsEuKnobs = LsEuKnobs(
    alignedLoadFallThrough = ipcThroughput, earlyIntWakeup = ipcThroughput,
    sqSubwordForwarding = ipcThroughput, reserveLateStore = ipcThroughput,
    detachLateStore = ipcThroughput, forwardOnPublish = ipcThroughput,
    earlyNzvcWakeup = ipcThroughput, detachedStoreEntries = if (ipcThroughput) 4 else 1,
    earlyAutoStoreAddress = ipcLateStore, earlyStoreDataWake = ipcLateStore,
    // Early An write-back at S1 instead of LS completion. See the note at SocketTop.
    earlyAutoAnWriteback = ipcThroughput,
    specLoadWakeup = specLoadWakeup,
    // Out-of-order LS issue needs the LS-side inhibited barrier; same switch as the IQ.
    lsOooIssue = lsOooIssue)

  def lsEu(ipcThroughput: Boolean, ipcLateStore: Boolean,
           specLoadWakeup: Boolean, lsOooIssue: Boolean): LsEuPlugin =
    lsEuKnobs(ipcThroughput, ipcLateStore, specLoadWakeup, lsOooIssue).build()

  case class IqKnobs(earlyStoreAddress: Boolean, earlyAutoStoreAddress: Boolean,
                     loadBypassUnreadyLoad: Boolean, specLoadWakeup: Boolean) {
    def build(): IssueQueuePlugin = new IssueQueuePlugin(earlyStoreAddress = earlyStoreAddress,
      earlyAutoStoreAddress = earlyAutoStoreAddress,
      loadBypassUnreadyLoad = loadBypassUnreadyLoad, specLoadWakeup = specLoadWakeup)
  }

  def iqKnobs(ipcThroughput: Boolean, ipcLateStore: Boolean,
              specLoadWakeup: Boolean, loadBypassUnreadyLoad: Boolean): IqKnobs =
    IqKnobs(earlyStoreAddress = ipcThroughput, earlyAutoStoreAddress = ipcLateStore,
      loadBypassUnreadyLoad = loadBypassUnreadyLoad, specLoadWakeup = specLoadWakeup)

  def issueQueue(ipcThroughput: Boolean, ipcLateStore: Boolean,
                 specLoadWakeup: Boolean, loadBypassUnreadyLoad: Boolean): IssueQueuePlugin =
    iqKnobs(ipcThroughput, ipcLateStore, specLoadWakeup, loadBypassUnreadyLoad).build()

  def itlb(): ItlbPlugin = new ItlbPlugin(victimEntries = ShippingCoreConfig.itlbVictimEntries)

  /** `socketMerged` is TOPOLOGY (the D port goes through `AxiDMergePlugin`), not
    * behaviour, and is the one argument a harness without the merge must differ in.
    * `allowPretranslatedProbeHints = false` IS behaviour: the LSU has no physical
    * address at probe launch, so the probe is resolved later by the DTLB. */
  def dcache(socketMerged: Boolean,
             allowPretranslatedProbeHints: Boolean = shippingPretranslatedProbeHints): DcachePlugin =
    new DcachePlugin(socketMerged = socketMerged,
      allowPretranslatedProbeHints = allowPretranslatedProbeHints)

  /** The shipping value; a parameter above only so a harness can bisect it by name. */
  val shippingPretranslatedProbeHints: Boolean = false

  def icache(predecodeWords: Int): IcachePlugin = new IcachePlugin(predecodeWords)

  def ras(): RasPlugin = new RasPlugin(branchRepair = ShippingCoreConfig.rasBranchRepair)

  def gshare(ipcThroughput: Boolean): GsharePlugin =
    new GsharePlugin(retainRedirectHistory = ipcThroughput)

  def fetchAlign(ipcThroughput: Boolean): FetchAlignPlugin =
    new FetchAlignPlugin(enableFetchDirected = true,
      trainSlot1Conditional = ipcThroughput, deferTakenSlot1Conditional = ipcThroughput,
      computeDirectTargets = ShippingCoreConfig.computeDirectTargets,
      deferSlot1Uncond = ShippingCoreConfig.deferSlot1Uncond,
      deferSlot1Dbcc = ShippingCoreConfig.deferSlot1Dbcc)

  def decode(ipcThroughput: Boolean): DecodeStage =
    new DecodeStage(allowSlot1Prediction = ipcThroughput, fuseLongMoveLoads = ipcThroughput)

  def rob(detailedPerf: Boolean, pcRangeEnable: Boolean, lsOooIssue: Boolean): RobPlugin =
    new RobPlugin(detailedPerf = detailedPerf, pcRangeEnable = pcRangeEnable,
      rasBranchRepair = ShippingCoreConfig.rasBranchRepair,
      lsOooIssue = lsOooIssue)
}
