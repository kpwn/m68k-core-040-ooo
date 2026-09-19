# Third-party notices and unresolved provenance

Original project contributions remain MIT; third-party terms are preserved.
Carry this file and applicable license texts with source and generated-CPU
distribution materials. These notices repair omissions; they are not legal clearance.

## NaxRiscv and Musashi core

NaxRiscv: Copyright Charles Papon, MIT, retained in LICENSE.
https://github.com/SpinalHDL/NaxRiscv

Musashi core: Copyright Karl Stenerud, MIT, retained in applicable source headers.
Recorded upstream snapshot:
https://github.com/kstenerud/Musashi/tree/313ebf1bd9f4d0d93341eb5ce21fd8a119e9dbdd
Do not apply MIT indiscriminately to every bundled file.

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

## Open: older MAME-derived PMMU/FPU in the host oracle

tools/musashi/musashi/m68kmmu.h carries a MAME copyright/restrictions header,
crediting R. Belmont and Nicola Salmoria and the MAME Team. m68kfpu.c also
needs exact-origin review. A modern MAME file's BSD header alone does not
establish the license of every contribution in these older vendored copies.
Redistribution clearance remains unresolved. These files are host verification
code, not directly compiled into FPGA RTL. Do not describe the entire oracle
as MIT or BSD until this is resolved.

## Other components

Build tools and dependencies retain their own terms. Review copied/generated
library content separately; use of a tool alone does not establish inclusion
of all its code. The SoC, Taxi Ethernet, firmware and FPGA vendor IP have
separate terms not covered by this repository's MIT grant.

