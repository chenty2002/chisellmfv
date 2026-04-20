AG(!inr[3:0]=0 -> !E(spl=1 U inr[3:0]=0));
AG(inr[3:0]=0 -> !E(spl=1 U !inr[3:0]=0));
AG(inr[3:0]=b0001 -> !E(spl=1 U inr[3:0]=b0011));
EF(inr[3:0]=b0001 * E(spl=1 U inr[3:0]=b0011));
EF(inr[3:0]=b0001);
EF(E(spl=1 U inr[3:0]=b0011));
