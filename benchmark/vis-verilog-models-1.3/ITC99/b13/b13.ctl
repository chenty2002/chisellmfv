#PASS: There is no reset for "tre" and "add_mpx2."
AG(tre=1 -> AG(tre=1));
EF(tre=1);
AG(add_mpx2=1 -> AG(add_mpx2=1));
EF(add_mpx2=1);

#PASS: a certain signal is always asserted before another.
!E(mux_en=0 U soc=1);
!E(soc=0 U load_dato=1);
!E(tx_end=0 U confirm=1);
!E(send_data=0 U rdy=1);
!E(rdy=0 U shot=1);
#!E(send_data=0 U shot=1);  # implied by transitivity
!E(shot=0 U load=1);
!E(load=0 U send=1);
!E(send=0 U confirm=1);
!E(load=0 U error=1);
!E(tx_conta[9:0]=0 U tx_end=1);
