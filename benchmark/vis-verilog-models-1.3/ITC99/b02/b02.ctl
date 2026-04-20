#PASS:
AG EX TRUE;
AG EF stato=A;
AG(AX(U=0) + AX(U=1));
AG(stato=D -> AX:2(stato=B));
AG(U=1 -> AX U=0);
AG AF U=0;
AG EF U=1;
AG(U=1 -> stato=B);

#FAIL:
AG !stato=G;
AG AF U=1;
