# Instruction-buffer write decode: one rotated decoder, not eighty adders

`InstructionBuffer` elaborates to 5,781 lines of Verilog with 1,636 `when_`
conditions for 20 slots of 25 bits (routed: 3,590 LUTs, all logic, 523 FFs).
A large part of that is the write loop, which computes the push address INSIDE
the per-slot loop:

```
for (s <- 0 until BUF_WORDS)            // 20
  when(io.push.fire)
    for (j <- 0 until PUSH_WORDS)       // 4
      phys = (tailBase +^ j) mod BUF_WORDS
      when((j < n) && (phys === s)) { entries(s) := ... }
```

`phys` does not depend on `s`, so the generator emits eighty copies of it:
`_zz_when_InstructionBuffer_l125` through `_zz_when_InstructionBuffer_l125_79`,
each a 5-bit add plus a compare-and-subtract wrap, each followed by its own
5-bit equality against a constant slot index. Four distinct addresses, computed
eighty times. The write-enable path is therefore add -> compare -> subtract ->
compare-to-constant -> AND.

## The identity this uses

The four write targets are CONSECUTIVE, so for slot `s` and pushed word `j`:

    (tailBase + j) mod BUF == s   <=>   tailBase == (s - j) mod BUF

`s` and `j` are Scala loop constants, so `(s - j) mod BUF` is a compile-time
index. One `UIntToOh(tailBase, BUF_WORDS)` decode therefore serves every slot
and every pushed word, and the per-`j` rotation is pure wiring with no logic at
all. Eighty adders, eighty wrap-compares and eighty comparators collapse to one
5->20 decoder plus the four existing `j < n` comparators.

## What must not change

Identical write set, identical data selection, identical cycle. `tailBase` is
already guaranteed `< BUF_WORDS` by its own wrap, so the one-hot is exact over
the whole legal range; out-of-range would decode to zero in both forms. No
change to `count`/`headPtr` update, flush priority, the head read rotate, the
`avail` cap, the push-`ready` contract, or the external interface — FetchAlign
and Aligner are untouched. No added stage, no capacity change, no reset change.

## Gates

Existing IBuf and frontend suites, plus the poisoned-storage ownership harness
written for the payload-reset work (wrap, variable push 0..4, variable shift
0..10, simultaneous push+flush, warm reset). Exact valid-cycle trace parity
against the parent, matched board-copy IPC, `make test-fast`, and a production
generation diff confirming the eighty `_zz_when_InstructionBuffer_l125*` address
computations are gone and no reset assignment changed. Area and timing benefit
are unmeasured until a full-SoC route.
