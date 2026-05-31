SBT ?= sbt

.PHONY: compile test-fast test verilog clean musashi test-verilator

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

musashi:
	$(MAKE) -C tools/musashi

test-verilator:
	$(SBT) "testOnly * -- -n m68k040.VerilatorTest"
