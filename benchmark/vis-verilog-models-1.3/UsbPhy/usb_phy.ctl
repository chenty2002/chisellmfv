#PASS:
AG(RxValid_o=1 -> AF (i_rx_phy.se0=0 + RxActive_o=0));

#PASS: RxError_o is tied to ground...
AG RxError_o=0;

#PASS:
AG EF RxValid_o=1;
AG EF RxActive_o=1;

#PASS:
AG(i_rx_phy.dpll_state[1:0]=0 -> i_rx_phy.fs_ce_d=0);

#FAIL: because of a possible reset.
AG(i_rx_phy.fs_ce_d=1 -> i_rx_phy.dpll_state[1]=1);

#FAIL:
AG(RxValid_o=1 -> RxActive_o=1);

