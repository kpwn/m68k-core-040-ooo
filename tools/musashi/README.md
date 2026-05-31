# Musashi reference model (lock-step oracle)

The lock-step harness (spec ch 11) compares architectural state against Musashi
at every retired instruction. Reuse the integration already built in the sibling
repo rather than rebuilding:

    cp -r /home/qwertyoruiop/m68k-core-030-inorder/tools/musashi/* .

Then follow that copy's build instructions. This directory is the vendoring point;
the lock-step bridge is implemented in the verification-harness plan, not here.
