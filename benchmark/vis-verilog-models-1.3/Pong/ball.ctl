#PASS:
AG(EF(SSMoveBall=LoadY));
AG(SSMoveBall=IncD -> AF(SSMoveBall=LoadY));
#PASS:
AG(!(TopBall=1 * BotBall=1));
AG(!(LftBall=1 * RgtBall=1));
AG(MiddleBallX=1 -> VertBall=1);
AG(MiddleBallY=1 -> HorzBall=1);
AG(MTopBall=1 -> TopBall=1);
AG(CollisionMTop=1 -> CollisionTop=1);
#FAIL:
AG(!(CollisionTop=1 * CollisionBot=1));
