#PASS: responder correctly authenticated.
AG((Astate<*0*>=COMMITTED * Apartner<*0*>[2:0]=2) ->
   (Bpartner<*2*>[2:0]=0 * Bstate<*2*>={WAITING,COMMITTED}));
AG((Astate<*0*>=COMMITTED * Apartner<*0*>[2:0]=3) ->
   (Bpartner<*3*>[2:0]=0 * Bstate<*3*>={WAITING,COMMITTED}));
AG((Astate<*1*>=COMMITTED * Apartner<*1*>[2:0]=2) ->
   (Bpartner<*2*>[2:0]=1 * Bstate<*2*>={WAITING,COMMITTED}));
AG((Astate<*1*>=COMMITTED * Apartner<*1*>[2:0]=3) ->
   (Bpartner<*3*>[2:0]=1 * Bstate<*3*>={WAITING,COMMITTED}));

#PASS: initiator correctly authenticated.
AG((Bstate<*2*>=COMMITTED * Bpartner<*2*>[2:0]=0) ->
   (Apartner<*0*>[2:0]=2 * Astate<*0*>=COMMITTED));
AG((Bstate<*2*>=COMMITTED * Bpartner<*2*>[2:0]=1) ->
   (Apartner<*1*>[2:0]=2 * Astate<*1*>=COMMITTED));
AG((Bstate<*3*>=COMMITTED * Bpartner<*3*>[2:0]=0) ->
   (Apartner<*0*>[2:0]=3 * Astate<*0*>=COMMITTED));
AG((Bstate<*3*>=COMMITTED * Bpartner<*3*>[2:0]=1) ->
   (Apartner<*1*>[2:0]=3 * Astate<*1*>=COMMITTED));

#PASS: if intruder knows responder's nonce it's because it has explicitly
# initiated conversation with it.
AG(Cnonces<*2*>=0 + Bpartner<*2*>[2:0]=4);
AG(Cnonces<*3*>=0 + Bpartner<*3*>[2:0]=4);

#PASS: Only intruders may use the wrong key.
AG(key[2:0]==dest[2:0] + source[2:0]=4);

#PASS: Intruder always knows its nonce.
AG(Cnonces<*4*>=1);

#PASS: Intruder may learn both initiator and respondent nonces.
EF(Cnonces<*0*>=1 * Cnonces<*1*>=1 * Cnonces<*2*>=1 * Cnonces<*3*>=1);

#PASS: Intruder does not forget nonces.
AG(Cnonces<*0*>=1 -> AG Cnonces<*0*>=1);
AG(Cnonces<*1*>=1 -> AG Cnonces<*1*>=1);
AG(Cnonces<*2*>=1 -> AG Cnonces<*2*>=1);
AG(Cnonces<*3*>=1 -> AG Cnonces<*3*>=1);

#PASS: Commitment is serious.
AG(Astate<*0*>=COMMITTED -> AG Astate<*0*>=COMMITTED);
AG(Astate<*1*>=COMMITTED -> AG Astate<*1*>=COMMITTED);
AG(Bstate<*2*>=COMMITTED -> AG Bstate<*2*>=COMMITTED);
AG(Bstate<*3*>=COMMITTED -> AG Bstate<*3*>=COMMITTED);
