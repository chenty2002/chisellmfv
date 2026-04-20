# there is always at least one ball in play
# (passes with or without fairness constraint)
AG(!((ball_1.state=OUT_OF_PLAY) * (ball_2.state=OUT_OF_PLAY)));

# if player_A produces IDLE now, then he will HIT sometime 
# in the future (only passes with fairness constraint)
AG((player_A.out=IDLE) -> AF(player_A.out=HIT));

# Eventually only one ball is in play. Note ^ denotes XOR.
# (should fail with or without fairness constraints)
AF((ball_1.state=OUT_OF_PLAY) ^ (ball_2.state=OUT_OF_PLAY));

# There exists the possibility that eventually only one ball 
# is in play (should pass with or without fairness constraints)
EF((ball_1.state=OUT_OF_PLAY) ^ (ball_2.state=OUT_OF_PLAY));

# there are always two balls in play
# (fails with or without fairness constraint)
AG(!(ball_1.state=OUT_OF_PLAY) * !(ball_2.state=OUT_OF_PLAY));


