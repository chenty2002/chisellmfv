#PASS:
AG EX TRUE;
AG(stato=RECEIVE -> AX stato={RECEIVE,RX_2_TX});
AG(stato=END_TX -> AX(cts=0 <-> stato=STANDBY));
AG(stato=RX_2_TX -> AX(cts=0 <-> stato=SEND));

#FAIL:
AG EF stato=STARTUP;
AG !v_out[3:0]=b0111;
