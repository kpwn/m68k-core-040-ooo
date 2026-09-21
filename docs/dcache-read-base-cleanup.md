# D-cache read-address cleanup

The shared data/tag/valid/dirty RAM read port uses `rdSet` and `rdEn`.
Previously its default address was zero, replaced by the registered store
set only under `stS1Valid && !storeMissBarrier && !loadMissStoreBarrier`.
The same predicate enabled the read. This unnecessarily put store eligibility
on the high-fanout RAM address as well as on the read enable.

The store now supplies `stS1Set` as the unconditional base address. Its read
enable, advancement checks and all other owners' priorities are unchanged.

Equivalence follows by ownership:

- With no higher-priority owner, `rdEn` is still the original store predicate.
  When true, both implementations use `stS1Set`.
- Probe, load, shadow replay, refill replay and maintenance each override both
  address and enable. Their behavior is unchanged.
- When `rdEn` is false, all four synchronous RAM outputs hold their previous
  values; a nonzero address on their disabled ports has no architectural effect.

No new state, cycle, timing exception, service or Global key is added. This is
not a relaxation of load/store hazards, cache maintenance or memory ordering.
Simulation assertions retain the original store-enable predicate and verify
its enabled address when no other owner wins. Fixed-seed contention/no-contention
tests record cycle-exact enabled read addresses and store progress for comparison
against the unmodified parent; they deliberately exclude disabled addresses.
