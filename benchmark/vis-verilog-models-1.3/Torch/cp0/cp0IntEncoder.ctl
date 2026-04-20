\define Cause31		ExceptionCause_v2[4:0]=31

#FAIL:
AG(\Cause31 -> AX( \Cause31 + AG !\Cause31));

#PASS:
AG(EF(\Cause31) * EF(!\Cause31));
