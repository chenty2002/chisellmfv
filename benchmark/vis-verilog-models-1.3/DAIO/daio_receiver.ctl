#PASS: rx_status[3] is never assigned except in the initial block.
AG(rx_status[3]=0);
#PASS:
!E(load_A=0 U load_B=1);
!E(!(bit_count_A[6:0]=4) U (load_A=1 + load_B=1 + load_buff=1));
#FAIL:
!E(load_B=0 U load_A=1);
