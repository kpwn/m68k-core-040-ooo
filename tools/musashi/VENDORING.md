# Musashi reference model (lock-step oracle)

The vendored snapshot records upstream kstenerud/Musashi commit
`313ebf1bd9f4d0d93341eb5ce21fd8a119e9dbdd` in `musashi/README.md`.
It is a host-side verification oracle, not synthesized into the CPU.
Local adaptations are present; that hash does not identify every current byte.

Do not refresh it by copying an arbitrary sibling workspace. Retain all
upstream notices and record the source revision and changes.
The directory is not uniformly MIT: SoftFloat Release 2b has separate terms,
and the older MAME-derived PMMU/FPU code needs provenance review.
See `../../THIRD_PARTY_NOTICES.md`.
