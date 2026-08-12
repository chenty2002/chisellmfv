clear -all
analyze -sv jg_smoke.sv
elaborate -top JGSmoke
clock clock
reset reset
set_prove_time_limit 10s
set chisellmfv_outcome [prove -property {JGSmoke.SMOKE_PROVEN}]
report
if {$chisellmfv_outcome ne "proven"} {
  exit 2
}
set chisellmfv_outcome [prove -property {JGSmoke.SMOKE_CEX}]
report
if {$chisellmfv_outcome ne "cex"} {
  exit 3
}
