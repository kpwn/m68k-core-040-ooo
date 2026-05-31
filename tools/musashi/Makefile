# tools/musashi/Makefile — build a standalone Musashi-based M68K runner
# used as the golden reference for the SmokeSpec test infrastructure.
#
# Targets:
#   musashi_run  : standalone runner CLI (default)
#   clean        : remove all build artifacts
#
# The Musashi sources (musashi/) are vendored from
# https://github.com/kstenerud/Musashi.  They produce an open-source
# M68K Instruction Set Simulator that we treat as the architectural
# golden reference for ISA correctness.

.PHONY: all musashi_run install clean

HERE         := $(dir $(lastword $(MAKEFILE_LIST)))
MUSASHI_DIR  := $(HERE)musashi
CACHE_DIR    := $(HOME)/.cache/m68k-core-030-inorder
CACHE_BIN    := $(CACHE_DIR)/musashi_run

CC      ?= gcc
CXX     ?= g++
AR      ?= ar
CFLAGS  ?= -O2 -g -Wno-unused -Wno-sign-compare -Wno-parentheses \
           -Wno-format -Wno-implicit-fallthrough -Wno-stringop-truncation \
           -Wno-unknown-warning-option -Wno-unused-parameter \
           -Wno-missing-field-initializers
CXXFLAGS ?= -O2 -g -std=c++17 -Wno-unused -Wno-sign-compare
INCS    := -I$(MUSASHI_DIR) -I$(MUSASHI_DIR)/softfloat -I$(HERE)

all: musashi_run install

# ── install — copy musashi_run into ~/.cache/m68k-core-030-inorder so worktrees ─
# share one binary instead of each rebuilding it (Musashi.scala looks
# in the shared cache before falling back to the in-tree copy).
install: $(HERE)musashi_run
	@mkdir -p $(CACHE_DIR)
	@cp -f $< $(CACHE_BIN)
	@echo "Installed $(CACHE_BIN)"

# Generated files from m68kmake
GEN_SRCS := $(MUSASHI_DIR)/m68kops.c
GEN_HDRS := $(MUSASHI_DIR)/m68kops.h

MUSASHI_SRCS := \
    $(MUSASHI_DIR)/m68kcpu.c \
    $(MUSASHI_DIR)/m68kdasm.c \
    $(MUSASHI_DIR)/softfloat/softfloat.c \
    $(GEN_SRCS)

MUSASHI_OBJS := $(MUSASHI_SRCS:.c=.o)

REF_OBJS := $(HERE)m68k_ref.o

# ── m68kmake — generates m68kops.c/h from the m68k_in.c template ────
$(MUSASHI_DIR)/m68kmake: $(MUSASHI_DIR)/m68kmake.c
	$(CC) -O2 -o $@ $<

$(GEN_SRCS) $(GEN_HDRS): $(MUSASHI_DIR)/m68kmake $(MUSASHI_DIR)/m68k_in.c
	cd $(MUSASHI_DIR) && ./m68kmake

%.o: %.c $(GEN_HDRS)
	$(CC) $(CFLAGS) $(INCS) -c -o $@ $<

# ── libmusashi_ref.a — Musashi core + m68k_ref wrapper ───────────────
$(HERE)m68k_ref.o: $(HERE)m68k_ref.cpp $(HERE)m68k_ref.h $(GEN_HDRS)
	$(CXX) $(CXXFLAGS) $(INCS) -c -o $@ $<

$(HERE)libmusashi_ref.a: $(REF_OBJS) $(MUSASHI_OBJS)
	$(AR) rcs $@ $(REF_OBJS) $(MUSASHI_OBJS)

# ── musashi_run — the standalone ISS CLI ─────────────────────────────
musashi_run: $(HERE)musashi_run

$(HERE)musashi_run: $(HERE)musashi_run.cpp $(HERE)libmusashi_ref.a
	$(CXX) $(CXXFLAGS) $(INCS) -o $@ $(HERE)musashi_run.cpp $(HERE)libmusashi_ref.a -lm

clean:
	rm -f $(HERE)musashi_run $(HERE)libmusashi_ref.a
	rm -f $(MUSASHI_OBJS) $(REF_OBJS)
	rm -f $(MUSASHI_DIR)/m68kmake $(GEN_SRCS) $(GEN_HDRS)
