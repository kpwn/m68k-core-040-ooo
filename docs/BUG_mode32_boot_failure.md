# BUG: System 7.0.1 fails to boot in 32-bit addressing mode (24-bit reaches the Finder)

**Status**: OPEN. Root cause not yet identified. Several confirmed defects found
and fixed along the way (below); whether any of them is *the* cause is unproven
until the board is re-tested with them in.

**Severity**: HIGH — 24-bit addressing reaches the Finder and runs applications
on the same bitstream, ROM and disk. 32-bit does not.

---

## 1. The symptom, measured

With PRAM set to 32-bit addressing the machine bombs with Sad Mac `0F 63`.
`0x63` is 99, `dsBadPatchHeader`. Caught live by breaking at the ROM's SysError
entry, `0x40802720`, which is the ideal capture point because it runs
`moveml %d0-%sp,0xc30 ; movew %sr,0xc74 ; movel %sp@+,0xc70` before doing
anything else, so D0 still holds the error code:

```
D0 = 0x00000063   A7 = 0x01ff6a7a   SR = 0x2010   VBR = 0x01ff6978
A1 = 0x01fcfa7a A2 = 0x0000e3de A3 = 0x0071bb50 A5 = 0x01ff8210 A6 = 0x01ff8480
low memory [0xCB2] = 0x01   (32-bit mode confirmed active)
```

Two consecutive boots produced an identical register set and an identical
retire-PC trace, so the failure is deterministic.

## 2. What is ruled out, by measurement

* **The SoC.** The owner confirms the v1 core boots 32-bit mode on this same
  SoC, ROM and disk. That exonerates the address decode, the ROM mirror fold,
  the fabric and every peripheral. The defect is in cpu040.
* **The RAM window.** `OFF_RAM_WINDOW_LG2` reads 26, i.e. 64 MB, and JTAG writes
  to 0x01ff6000, 0x02000000 and 0x03ff0000 read back independently. No aliasing.
* **Cache and TLB tags.** `CacheGeometry.scala:37` defines
  `tagBits = 32 - offsetBits - indexBits`, so the tag plus index plus offset
  covers the whole 32-bit address by construction, and the stored tag is the
  full physical page number. `MmuTypes.scala:54` takes the VPN as bits 31..12
  and `Tlb.tagBitsFor` keeps the remainder after the bank and set bits.
* **Address widths generally.** A mechanical sweep of every `UInt`/`Bits(N)`
  declaration found 190 that can only be an address, PC or branch target; 170
  are exactly 32 bits and the other 20 are not addresses (register-file
  indices, the debug CSR aperture, the microcode ROM PC, instruction counters).
* **The vector-fetch line-wrap hole.** In 32-bit mode Mac OS relocates the
  vector table to VBR = 0x01FF6978, which is 8 modulo 16, so vector entries land
  at line offsets 0, 4, 8 and 12 and a LONG at offset 12 fits inside the line.
* **The first bus error is normal.** With vectors 2, 3, 4, 8, 11 and 14 armed,
  the first fault is a bus error at ROM PC `0x408046AA`, fault address
  `0x51001C00`, with A2 = 0x50F01C00 and D2 = 0x00100000 — the ROM walking the
  I/O mirror in 1 MB steps and running off the end of the 0x50000000 window,
  which a real Q700 also faults on. **No bus, address, illegal, privilege,
  F-line or format fault occurs between that probe and the SysError**, so the
  corruption is silent.

## 3. The MMU posture in 32-bit mode

Read from the LIVE registers (`OFF_LIVE_MMU_*` at 0x2160-0x2178 — **not** the
`OFF_ARCH_*` staged shadows at 0x2058-0x2070, which read zero, and which `arch`
does not print at all):

```
TC   = 0x0000C000    MMU enabled, 8 KB pages
SRP  = 0x03FFFA00    URP = 0
DTT0 = ITT0 = 0xF900C060   -> matches 0xF9xxxxxx only
DTT1 = ITT1 = 0x807FC040   -> base 0x80 mask 0x7F -> 0x80000000 upward only
```

**No transparent translation register covers main memory**, so every RAM and ROM
access walks the page tables. Any place the core assumes physical equals virtual
is therefore live in 32-bit mode and inert in 24-bit.

## 4. Confirmed defects found and fixed while investigating

Each has a fail-before / pass-after test; none is yet proven to be *the* cause.

1. **The exception unit did not translate at all.** Every entry frame push,
   every RTE frame pop and every vector fetch used PA = VA
   (`ExceptionUnit.scala:824`, `:874-889`, whose own comment called a real
   translation "a fast-follow"). Now routed through the DTLB with one shared
   translation context per exception episode. Proven with a deliberately
   non-identity map at both 4 KB and 8 KB granules.
2. **An FSAVE or FRESTORE translation fault halted the processor**
   (`ExceptionUnit.scala:2333`, `:2409` → `F_HALT`, feeding `coreHaltedIn`).
   Halting is never the correct 68040 response to a faulting instruction; it
   now raises an access fault. Entry frame pushes and vector fetches correctly
   double-fault instead.
3. **The exception sequencer never checked `DLoadRsp.fault`.** A bus-erroring
   vector fetch was taken as success and the core redirected to the returned
   garbage. Now a double fault, with `OFF_DBL_FAULT_PC`/`VEC` finally driven.
4. **`UmWriteQueue.pageHazard` compared a physical page against a virtual page
   number**, and its flush rollback used a population count where a prefix count
   was needed, which could strand the queue permanently and lose architectural
   U/M writes.

## 5. Instrumentation added

* `OFF_HALT_KIND` (0x140) — the socket-level halt attribution. `OFF_HALT_REASON`
  collapses every fatal cause to FATAL, and the only other attribution was an
  ILA probe needing an `ENABLE_ILA` build. REPL command `halt-kind`.
* `OFF_A7ODD_*` (0x10C-0x120) and `OFF_PCRANGE_*` (0x124-0x13C) — halt lanes
  that stop the core when the committed A7 goes odd, or when a macro retires
  inside a programmed address window. The latter caught the wild jump of the
  24-bit defect at the instruction.

## 6. Measurement traps this cost time to learn

* **The runaway destroys the evidence.** After the bomb the machine executes low
  memory and the stack marches until it reaches I/O space, so the page tables
  read as zeros and any walk taken afterwards is garbage. Breakpoints set on the
  failing code never fire because the collapse beats them.
* **A fatal halt 45 seconds in says nothing about what happened at 4 seconds.**
  Always read the retire trace before calling a halt the first event; here it
  showed the CPU had been alternating between addresses 0 and 4 for the whole
  ring.
* **Page tables live dirty in the D-cache**, so a JTAG walk needs
  `dcache-op push` first — and even that does not help once the tables are gone.
* **JTAG polling visibly glitches scan-out.** Take one capture, not a loop.
