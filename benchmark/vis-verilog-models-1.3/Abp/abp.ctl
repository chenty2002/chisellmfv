AG ((r.rcvmsg=1) -> A ((r.rcvmsg=1) U ((r.rcvmsg=0) * A ((r.rcvmsg=0) U (s.sndmsg=1))))) ;
AG ((s.sndmsg=1) * (s.smsg=ONE) -> A ((s.sndmsg=1) U ((s.sndmsg=0) * A ((s.sndmsg=0) U ((r.rcvmsg=1) * (r.rmsg=ONE)))))) ;
AG ((s.sndmsg=1) * (s.smsg=ZERO) -> A ((s.sndmsg=1) U ((s.sndmsg=0) * A ((s.sndmsg=0) U ((r.rcvmsg=1) * (r.rmsg=ZERO)))))) ;
