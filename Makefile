SBT ?= sbt

.PHONY: compile test-fast test verilog clean

compile:
	$(SBT) compile

test-fast:
	$(SBT) fastTest

test:
	$(SBT) test

verilog:
	$(SBT) "runMain m68k040.top.GenVerilog"

clean:
	$(SBT) clean
	rm -rf simWorkspace generated
