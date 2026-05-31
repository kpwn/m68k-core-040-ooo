# Agent guide

- Architecture is fixed by the design doc; do not deviate without a spec update.
- INVARIANT #3: every Database key in `Global` has exactly ONE producer plugin.
  Adding a key means documenting its producer in the comment.
- Plugins communicate ONLY via `host[Service]` and `Global` keys — never reach
  into another plugin's internals.
- Bundles use plain SpinalHDL field types only — never add asOutput/assignDontCare
  overrides or anonymous subclasses to a bundle to satisfy a test; fix the test instead.
- Reserve an isolated workspace before editing: `tools/agent_worktree_pool.sh reserve <name> "<task>"`.
- Gate before handoff: `make SBT=~/sbt/bin/sbt test-fast`. Full `make test` / Verilator runs are PM-serialized.
