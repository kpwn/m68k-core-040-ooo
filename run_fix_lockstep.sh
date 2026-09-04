#!/bin/bash
cd /home/qwertyoruiop/tmp/claude-1000/-home-qwertyoruiop-m68k-core-040-ooo/d6b13930-299a-4a94-b445-133553af8a58/scratchpad/wt-ispec
export SBT_OPTS="-Xmx6G -Xss8M -XX:+UseG1GC -Djava.io.tmpdir=/home/qwertyoruiop/tmp"
sbt -batch 'testOnly m68k040.lockstep.ExecuteLockStepSpec' > /home/qwertyoruiop/tmp/fix_lockstep.log 2>&1
echo "EXIT=$?" >> /home/qwertyoruiop/tmp/fix_lockstep.log
