# From any reachable state, when Reset is asserted always goes to the
#  initial state
AG (EF(rRst_n=0));

# When State is Idle and WorkMau is true AccessMode[0] is not true,
#Valid bit is false and Match bit is false then some later point (AF) we
#reach a state where Vecotor is 001000 
AF((State[2:0] = 0 * rWorkMAU = 1 * rAccessMode[0]=0 * rValid=0 * rMatch =0) -> (AF(Write=0 * BCURequest_n=0 * BCUWriteRequest_n=1 * BCUDataOE=0 * CacheDataSelect=0 * MAUNotReady_n=0)));

# When State is Idle and WorkMau is true AccessMode[0] is not true,
#Valid bit is false and Match bit is false then some later point (AF) we
#will reach state 2
AF((State[2:0] = 0 * rWorkMAU = 1 * rAccessMode[0]=0 * rValid=0 * rMatch =0) -> (AF(State[2:0] = 2)));

#Later in point when WorkMau is true then in two clock cycle we come to
#State 3
AF(rWorkMAU=1 -> (AX (AX(State[2:0] = 3))));

#At later point the state reaches to Idle state in three clock cycle
AF(rWorkMAU=1 -> (AX (AX (AX(State[2:0] = 0)))));

#At later point the state reaches to Idle state in two clock cycle
AF(rWorkMAU=1 -> (AX (AX(State[2:0] = 0))));

#It is possible to get to a state 5 (Write Miss State) from any other state
AF(rWorkMAU = 1 -> (AF(State[2:0] = 5)));
 
#It is possible to have WorkMAU to be asserted in three states after that
# it is also possible to have ReadDoneFromBCU_n deasserted  and after two
#steps a state where AccessMode[0] is not asserted. ie. from ideal state
#goes to either state 1, 4 and 5 and go to state 2 and 3 and back to state
 
EF(rWorkMAU=1 * EX(rWorkMAU=1 * EX(rWorkMAU=1)) -> (EF(rReadDoneFromBCU_n =0 * EX(EX(rAccessMode[0] = 0)))));

#At later point starting from state 2 where AccessMode[0] is 0 and in three
#it reaches to state 3
AF(rWorkMAU=1 * rAccessMode[0]=0 -> AX(AX(AX(Write=1 * BCURequest_n=1 * BCUWriteRequest_n=1 * BCUDataOE=0 * CacheDataSelect=1 * MAUNotReady_n=0))));

#Eventually it reaches to Write Miss state
EF(Write=0 * BCURequest_n=1 * BCUWriteRequest_n=0 * BCUDataOE=1 * CacheDataSelect=0 * MAUNotReady_n=0);

#Eventually it reaches to Read_hit state
EF(Write=0 * BCURequest_n=1 * BCUWriteRequest_n=1 * BCUDataOE=0 * CacheDataSelect=0 * MAUNotReady_n=0);

#Eventually it reaches to Read_miss State
EF(Write=0 * BCURequest_n=0 * BCUWriteRequest_n=1 * BCUDataOE=0 * CacheDataSelect=0 * MAUNotReady_n=0);

#Eventually goes to Read Data State
EF(Write=1 * BCURequest_n =1 * BCUWriteRequest_n =1 * BCUDataOE=0 * CacheDataSelect=1 * MAUNotReady_n=0);

#Goes to Write hit state
EF(Write=1 * BCURequest_n=1 * BCUWriteRequest_n=0 * BCUDataOE=1 * CacheDataSelect=0 * MAUNotReady_n=0);

#Goes to Idle State
EF(Write=0 * BCURequest_n=1 * BCUWriteRequest_n=1 * BCUDataOE=0 * CacheDataSelect=0 * MAUNotReady_n=1);

#Later Point Idle state is true
AF(Write=0 * BCURequest_n=1 * BCUWriteRequest_n=1 * BCUDataOE=0 * CacheDataSelect=0 * MAUNotReady_n=1);

#Next State of state2 is state 3 if ReadDoneFromBCU_n is false.
AX(State[2:0] =2 * rReadDoneFromBCU_n=0 -> AX(State[2:0]=3));
