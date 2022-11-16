TOPMOD  := PQVexRiscvUlx3s
CHIP := 85k
PACKAGE := CABGA381
CONSTRAINTS := PQVexRiscvUlx3s
VLOGFIL := $(TOPMOD).v
PROGPATH := ~/opt/oss-cad-suite/bin/fujprog
OSS_CAD_SUITE_PATH := ~/oss-cad-suite/bin/

.PHONY: all

all: $(TOPMOD).bit

.PHONY: clean
clean:
	rm -rf $(TOPMOD).json $(TOPMOD).config $(TOPMOD).bit

$(TOPMOD).bit: $(TOPMOD).config
	$(OSS_CAD_SUITE_PATH)ecppack $(TOPMOD).config $(TOPMOD).bit

$(TOPMOD).config: $(TOPMOD).json
	$(OSS_CAD_SUITE_PATH)nextpnr-ecp5 \
		--$(CHIP) \
		--package $(PACKAGE) \
    	--json $(TOPMOD).json \
		--lpf $(CONSTRAINTS).lpf \
		--lpf-allow-unconstrained \
		--textcfg $(TOPMOD).config

$(TOPMOD).json: $(TOPMOD).v
	yosys -q -l $(TOPMOD)_yosys.log -p "synth_ecp5 -top $(TOPMOD) -json $(TOPMOD).json" $(TOPMOD).v

prog: $(TOPMOD).bit
	sudo $(PROGPATH) $(TOPMOD).bit
