#PASS: The command lines are 1-hot encoded.
AG!(Lcmd=1 * Ucmd=1 + Lcmd=1 * Ccmd=1 + Lcmd=1 * Ncmd=1 + Ucmd=1 * Ccmd=1
    + Ucmd=1 * Ncmd=1 + Ccmd=1 * Ncmd=1);
#PASS: Each command line may be asserted.
EF Lcmd=1;
EF Ucmd=1;
EF Ncmd=1;
EF Ccmd=1;
