#FAIL:
AG(q0.match=1 -> AF(!(q0.storeaddr[1:0] == q0.readhead[1:0])));
AG(q0.match=1 -> AX(AG(q0.storeaddr[1:0] == q0.readhead[1:0])));
#PASS:
AG(EF(q0.match=1));
#FAIL:
AG(q1.match=1 -> AF(!(q1.storeaddr[1:0] == q1.readhead[1:0])));
AG(q1.match=1 -> AX(AG(q1.storeaddr[1:0] == q1.readhead[1:0])));
#PASS:
AG(EF(q1.match=1));
#PASS:
AG(q0.match=1 ->
   !E((q0.storeaddr[1:0] == q0.readhead[1:0]) U
      EG((q0.storeaddr[1:0] == q0.readhead[1:0]) *
         !(q0.readheadentry[1:0] == q0.writetailentry[1:0])
        )
     )
  );
