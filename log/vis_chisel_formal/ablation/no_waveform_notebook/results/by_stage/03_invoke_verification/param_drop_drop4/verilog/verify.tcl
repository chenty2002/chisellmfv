analyze -sv12 TestTop.sv ResetCounter.sv
elaborate -bbox_a 65536
reset reset
clock clock
set_prove_time_limit 1h
set_engine_threads 16
set_proofgrid_per_engine_max_jobs 32
prove -all -bg
