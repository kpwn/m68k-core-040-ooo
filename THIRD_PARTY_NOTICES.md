# Third-party notices and unresolved provenance

Original project contributions remain MIT; third-party terms are preserved.
Carry this file and applicable license texts with source and generated-CPU
distribution materials. These notices repair omissions; they are not legal clearance.

## NaxRiscv and Musashi core

NaxRiscv: Copyright Charles Papon, MIT, retained in LICENSE.
https://github.com/SpinalHDL/NaxRiscv

Musashi is an optional upstream submodule, not bundled source in this revision.
Users fetch it directly from:
https://github.com/kstenerud/Musashi/tree/313ebf1bd9f4d0d93341eb5ce21fd8a119e9dbdd
Upstream terms govern that dependency; our MIT grant does not relicense it.

## SoftFloat Release 2b adaptations

The FPU implementation is a derivative work in part of John R. Hauser's
SoftFloat Release 2b, as repackaged for MAME and vendored with Musashi.
This notice covers retained/adapted SoftFloat portions, not unrelated
independently authored logic. Source comments identify relevant algorithms,
conversions, NaN handling and rounding. FpSource.scala and FpRoundPack.scala
explicitly identify hardware transcriptions; other FPU files carrying the
notice use/adapt the helpers and reference algorithms. Local changes include
pipeline organization, precision/exception handling and signaling-NaN preservation.

The original notice is reproduced in LICENSES/SoftFloat-2b.txt and adaptation
headers. Its nonstandard terms remain applicable; this is not relicensing
under MIT or the different license of SoftFloat Release 3.
https://www.jhauser.us/arithmetic/SoftFloat.html

## Host oracle: external dependency and local patch

The older upstream PMMU/FPU files are no longer bundled in this revision.
They are fetched from Musashi by an explicit submodule initialization.
Do not include that checkout or compiled oracle in our release packages.
This addresses our current source-packaging issue without claiming to resolve
upstream's licensing or removing copies in previously published Git history.

Our own tools/musashi/patches/lockstep.patch remains distributed here.
It contains Musashi context (Copyright Karl Stenerud, MIT) and locally adapted
MAME exception-frame code. MAME's src/devices/cpu/m68000/m68kcpu.h credits
Karl Stenerud under BSD-3-Clause; preserve LICENSES/MAME-BSD-3-Clause.txt
for those portions. Local integration changes are MIT. These notices apply
to the patch, not a blanket license assertion for the fetched upstream tree.
Reference: https://github.com/mamedev/mame/blob/mame0287/src/devices/cpu/m68000/m68kcpu.h
Musashi's original MIT notice is retained in LICENSES/Musashi-MIT.txt.

## Other components

Build tools and dependencies retain their own terms. Review copied/generated
library content separately; use of a tool alone does not establish inclusion
of all its code. The SoC, Taxi Ethernet, firmware and FPGA vendor IP have
separate terms not covered by this repository's MIT grant.
