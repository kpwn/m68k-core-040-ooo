#!/usr/bin/env python3
"""Temporarily inject one §14 nonblocking-cache fault, run a test, restore RTL.

Run in an isolated worktree with no other build using that worktree, for example:
  STRESS_ARMS=hotChaos STRESS_SEEDS=1 STRESS_OPS=4000 \
    python3 tools/nb_mutation_proof.py 1 sbt \
      'testOnly m68k040.cache.DcacheNonBlockingSpec -- -z "hot door CHAOS"'

The wrapped test should fail for a functional reason. This script returns its exit
status unchanged so survivors and compilation failures remain visible to the caller.
"""
import pathlib, subprocess, sys
p = pathlib.Path(__file__).resolve().parent.parent / 'src/main/scala/m68k040/cache/DcachePlugin.scala'
base = p.read_text()
mut = {
  1: ('(stS1Valid && stS1Set === eset(k) && !nbStoreHold) || (stS2Valid && stS2Set === eset(k)) ||',
      'False || False ||'),
  2: ('!blocked(k) && !coldWriteTo(line(k)) && !wtPipeTo(line(k)) && arDelayOk(k)',
      'arDelayOk(k)'),
  3: ('!lCamAny && !lSetBusy && lFreeOk && lWbOk &&', '!lCamAny && lFreeOk && lWbOk &&'),
  4: ('when(instOH(k) && instAny) { st(k) := ST(LINGER); linger(k) := 3 }',
      'when(instOH(k) && instAny) { st(k) := ST(FREE); linger(k) := 0 }'),
  5: ('missOff      := MuxOH(oh, woff)', 'missOff      := woff(0)'),
  6: ('val stgVLine = RegNext(Mux(s1VFromS3, stS3MergedLine, Mux(s1VFromS3D1, stS3WriteLineD1, rdData(s1Vw))))',
      'val stgVLine = RegNext(Mux(s1VFromS3D1, stS3WriteLineD1, rdData(s1Vw)))'),
  7: ('mstrb(k)  := mstrb(k) | pendingMergeStrb\n          mdirty(k) := True',
      'mstrb(k)  := mstrb(k) | pendingMergeStrb'),
  8: ('when(arFire) { for (k <- 0 until N) when(arIdx === U(k, idxW bits)) { st(k) := ST(WAIT_R) } }',
      'when(arFire) { for (k <- 0 until N) when(arIdx === U(k, idxW bits)) { st(k) := ST(WAIT_AR) } }'),
  9: ('nbCmdReady := !rqNonEmpty && !serialPending &&\n                    (loadCmdPort.payload.ooOk || quietForSerial)',
      'nbCmdReady := !rqNonEmpty'),
 10: ('(stgValid && stgSet === stS1Set) || (ldS1Valid && ldS1Set === stS1Set) ||',
      '(ldS1Valid && ldS1Set === stS1Set) ||'),
}
i = int(sys.argv[1])
old, new = mut[i]
if base.count(old) != 1:
  raise SystemExit(f'mutant {i}: expected one source match, found {base.count(old)}')
cmd = sys.argv[2:]
if not cmd:
  raise SystemExit('provide command')
mutated = base.replace(old, new, 1)
p.write_text(mutated)
try:
  print(f'MUTANT {i} applied; command={cmd}', flush=True)
  rc = subprocess.call(cmd)
  print(f'MUTANT {i} command exit={rc}', flush=True)
finally:
  p.write_text(base)
  print(f'MUTANT {i} source restored', flush=True)
sys.exit(rc)
