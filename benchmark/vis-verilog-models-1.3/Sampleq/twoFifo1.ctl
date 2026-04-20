#FAIL:
AG(match=1 -> AX(AG(storeaddr[1:0] == readhead[1:0])));
AG(match=1 -> AF(!(storeaddr[1:0] == readhead[1:0])));
#PASS:
AG(EF(match=1));
AG(match=1 ->
   !E((storeaddr[1:0] == readhead[1:0]) U
      EG((storeaddr[1:0] == readhead[1:0]) *
         !(readheadentry[1:0] == writetailentry[1:0])
        )
     )
  );
