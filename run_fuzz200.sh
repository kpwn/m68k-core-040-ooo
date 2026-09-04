#!/bin/bash
cd /home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/d6b13930-299a-4a94-b445-133553af8a58/scratchpad/wt-ispec
export SBT_OPTS="-Xmx4G -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp"
FUZZ_SEED_START=0 FUZZ_SEED_COUNT=200 FUZZ_MINIMIZE=0 \
  sbt -batch 'testOnly m68k040.fuzz.FuzzLockStepSpec' > /home/qwertyoruiop/tmp/fix_fuzz200.log 2>&1
echo "EXIT=$?" >> /home/qwertyoruiop/tmp/fix_fuzz200.log
