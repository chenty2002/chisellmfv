#PASS: responder correctly authenticated.
AG((Astate<*0*>=COMMITTED * Apartner<*0*>[0]=1) ->
   (Bpartner<*1*>[1:0]=0 * Bstate<*1*>={WAITING,COMMITTED}));

#PASS/FAIL: initiator correctly authenticated.  This one passes only with
# Lowe's fix.
AG((Bstate<*1*>=COMMITTED * Bpartner<*1*>[1]=0) ->
   (Apartner<*0*>[1:0]=1 * Astate<*0*>=COMMITTED));

#PASS/FAIL: if intruder knows responder's nonce it's because it has explicitly
# initiated conversation with it.  This passes only with Lowe's fix.
AG (Cnonces<*1*>=0 + Bpartner<*1*>[1:0]=2);

#PASS: Only intruders may use the wrong key.
AG(key[1:0]==dest[1:0] + source[1:0]=2);

#PASS: Intruder always knows its nonce.
AG Cnonces<*2*>=1;

#PASS: Intruder may learn both initiator and respondent nonces.
EF(Cnonces<*0*>=1 * Cnonces<*1*>=1);

#PASS: Intruder does not forget nonces.
AG(Cnonces<*0*>=1 -> AG Cnonces<*0*>=1);
AG(Cnonces<*1*>=1 -> AG Cnonces<*1*>=1);

#PASS: Commitment is serious.
AG(Astate<*0*>=COMMITTED -> AG Astate<*0*>=COMMITTED);
AG(Bstate<*1*>=COMMITTED -> AG Bstate<*1*>=COMMITTED);
