# CTL properties for McMillan's synchronous arbiter, 16 cells.
#
# Author: Fabio Somenzi <Fabio@Colorado.EDU>

# PASS: mutual exclusion
AG((ack[0] =1 -> (ack[15:1] =b000000000000000)) *
   (ack[1] =1 -> (ack[15:2] =b00000000000000 * ack[0]=0)) *
   (ack[2] =1 -> (ack[15:3] =b0000000000000 * ack[1:0] =b00)) *
   (ack[3] =1 -> (ack[15:4] =b000000000000 * ack[2:0] =b000)) *
   (ack[4] =1 -> (ack[15:5] =b00000000000 * ack[3:0] =b0000)) *
   (ack[5] =1 -> (ack[15:6] =b0000000000 * ack[4:0] =b00000)) *
   (ack[6] =1 -> (ack[15:7] =b000000000 * ack[5:0] =b000000)) *
   (ack[7] =1 -> (ack[15:8] =b00000000 * ack[6:0] =b0000000)) *
   (ack[8] =1 -> (ack[15:9] =b0000000 * ack[7:0] =b00000000)) *
   (ack[9] =1 -> (ack[15:10]=b000000 * ack[8:0] =b000000000)) *
   (ack[10]=1 -> (ack[15:11]=b00000 * ack[9:0] =b0000000000)) *
   (ack[11]=1 -> (ack[15:12]=b0000 * ack[10:0]=b00000000000)) *
   (ack[12]=1 -> (ack[15:13]=b000 * ack[11:0]=b000000000000)) *
   (ack[13]=1 -> (ack[15:14]=b00 * ack[12:0]=b0000000000000)) *
   (ack[14]=1 -> (ack[15]   = 0 * ack[13:0]=b00000000000000)) *
   (ack[15]=1 -> (               ack[14:0]=b000000000000000)));

# PASS: persistent requests are eventually acknowledged
AG(AF(lreq[0] =1 -> ack[0] =1));
AG(AF(lreq[1] =1 -> ack[1] =1));
AG(AF(lreq[2] =1 -> ack[2] =1));
AG(AF(lreq[3] =1 -> ack[3] =1));
AG(AF(lreq[4] =1 -> ack[4] =1));
AG(AF(lreq[5] =1 -> ack[5] =1));
AG(AF(lreq[6] =1 -> ack[6] =1));
AG(AF(lreq[7] =1 -> ack[7] =1));
AG(AF(lreq[8] =1 -> ack[8] =1));
AG(AF(lreq[9] =1 -> ack[9] =1));
AG(AF(lreq[10]=1 -> ack[10]=1));
AG(AF(lreq[11]=1 -> ack[11]=1));
AG(AF(lreq[12]=1 -> ack[12]=1));
AG(AF(lreq[13]=1 -> ack[13]=1));
AG(AF(lreq[14]=1 -> ack[14]=1));
AG(AF(lreq[15]=1 -> ack[15]=1));

#PASS: No acknowledgement without request
AG(ack[0] =1 -> lreq[0] =1);
AG(ack[1] =1 -> lreq[1] =1);
AG(ack[2] =1 -> lreq[2] =1);
AG(ack[3] =1 -> lreq[3] =1);
AG(ack[4] =1 -> lreq[4] =1);
AG(ack[5] =1 -> lreq[5] =1);
AG(ack[6] =1 -> lreq[6] =1);
AG(ack[7] =1 -> lreq[7] =1);
AG(ack[8] =1 -> lreq[8] =1);
AG(ack[9] =1 -> lreq[9] =1);
AG(ack[10]=1 -> lreq[10]=1);
AG(ack[11]=1 -> lreq[11]=1);
AG(ack[12]=1 -> lreq[12]=1);
AG(ack[13]=1 -> lreq[13]=1);
AG(ack[14]=1 -> lreq[14]=1);
AG(ack[15]=1 -> lreq[15]=1);
