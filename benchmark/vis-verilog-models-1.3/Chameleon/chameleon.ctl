#PASS:
AG((cham<*0*>=GREEN * cham<*1*>=BLUE) ->
   AX(select[1:0]=0 -> (cham<*0*>=RED * cham<*1*>=RED)));
AG((cham<*1*>=GREEN * cham<*2*>=BLUE) ->
   AX(select[1:0]=1 -> (cham<*1*>=RED * cham<*2*>=RED)));
AG((cham<*2*>=GREEN * cham<*3*>=BLUE) ->
   AX(select[1:0]=2 -> (cham<*2*>=RED * cham<*3*>=RED)));
AG((cham<*3*>=GREEN * cham<*0*>=BLUE) ->
   AX(select[1:0]=3 -> (cham<*3*>=RED * cham<*0*>=RED)));

#PASS:
EF stable=1;

#FAIL: from all initial states a stable configuration is reached.
AF stable=1;
