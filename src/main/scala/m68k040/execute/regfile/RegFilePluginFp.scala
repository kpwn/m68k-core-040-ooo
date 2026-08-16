package m68k040.execute.regfile

class RegFilePluginFp extends RegFilePlugin(RegfileSpec.Fp) with FpRegFileService

/** FPCC {NaN,I,Z,N} physical value store, backing RenameStage's `fpccRat`/`fpccFree`
  * rename class exactly the way `RegFilePluginNzvc` backs `nzvcRat`/`nzvcFree`.
  *
  * Renaming without a value store is not a complete condition-code class: the RAT maps an
  * architectural group onto a 4-bit physical tag, and SOMETHING has to hold the 4 bits that
  * tag names. NZVC's answer is a 4x16 `RegFilePlugin`, and FPCC is the same problem one
  * group over, so it gets the same answer rather than a new mechanism. (The ROB's
  * `nzvcValStore` is a SEPARATE, additional per-entry capture that exists only to fold a
  * committed CCR into an exception frame's stacked SR -- it is not NZVC's rename-visible
  * value storage, and FPCC has no exception-frame analogue until the FPSR task.)
  *
  * Write-only until an FPCC READER lands (FBcc/FScc/FDBcc and FMOVE-from-FPSR are the two
  * consumers; `RenamedUop.readsFpcc` is driven False by every decode path today), so
  * synthesis will trim the storage array until then -- the same, deliberate state
  * `RegFilePluginFp` itself was in before the CPLX FP writeback lane consumed it. */
class RegFilePluginFpcc extends RegFilePlugin(RegfileSpec.Fpcc) with FpccRegFileService
