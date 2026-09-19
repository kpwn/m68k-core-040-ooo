SBT ?= sbt

.PHONY: compile test-fast test verilog clean musashi test-verilator check-publication

compile:
	$(SBT) compile

check-publication:
	python3 tools/test_check_publication.py
	python3 tools/check_publication.py

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
