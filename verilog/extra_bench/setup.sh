#!/bin/bash
path=TestTop.sv
time=1
threads=16
max_jobs=32

echo "analyze -sv12 ${path} ResetCounter.sv" > verify.tcl
echo "elaborate -bbox_a 65536" >> verify.tcl
echo "reset reset" >> verify.tcl
echo "clock clock" >> verify.tcl
if [ $time -gt 0 ]
then
    echo "set_prove_time_limit ${time}h" >> verify.tcl
else
    echo "set_prove_time_limit 0s" >> verify.tcl
fi
echo "set_engine_threads ${threads}" >> verify.tcl
echo "set_proofgrid_per_engine_max_jobs ${max_jobs}" >> verify.tcl
echo "prove -all -bg" >> verify.tcl
jg -allow_unsupported_OS -tcl verify.tcl -no_gui
