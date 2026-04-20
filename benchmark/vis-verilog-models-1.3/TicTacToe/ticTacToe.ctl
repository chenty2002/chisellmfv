#
# Sanity checks.
#
#PASS: The reset state specifies an empty board.
b<*0*>=EMPTY * b<*1*>=EMPTY * b<*2*>=EMPTY * b<*3*>=EMPTY * b<*4*>=EMPTY *
b<*5*>=EMPTY * b<*6*>=EMPTY * b<*7*>=EMPTY * b<*8*>=EMPTY;
#PASS: Only values from 0 to 8 are in register move.
AG(move[3]=0 + move[2:0]=0);
#PASS: an occupied cell may not become free.
AG(b<*0*>=EMPTY + !EF(b<*0*>=EMPTY));
AG(b<*1*>=EMPTY + !EF(b<*1*>=EMPTY));
AG(b<*2*>=EMPTY + !EF(b<*2*>=EMPTY));
AG(b<*3*>=EMPTY + !EF(b<*3*>=EMPTY));
AG(b<*4*>=EMPTY + !EF(b<*4*>=EMPTY));
AG(b<*5*>=EMPTY + !EF(b<*5*>=EMPTY));
AG(b<*6*>=EMPTY + !EF(b<*6*>=EMPTY));
AG(b<*7*>=EMPTY + !EF(b<*7*>=EMPTY));
AG(b<*8*>=EMPTY + !EF(b<*8*>=EMPTY));
#PASS: X and O cannot both win.
AG(!(winX=1 * winO=1));
#
# Some more interesting properties.
#
#PASS: Three in a row cause the game to stop and a win for either player.
AG((b<*0*>=X * b<*1*>=X * b<*2*>=X) -> winX=1); # top row
AG((b<*0*>=O * b<*1*>=O * b<*2*>=O) -> winO=1);
AG((b<*3*>=X * b<*4*>=X * b<*5*>=X) -> winX=1); # middle row
AG((b<*3*>=O * b<*4*>=O * b<*5*>=O) -> winO=1);
AG((b<*6*>=X * b<*7*>=X * b<*8*>=X) -> winX=1); # bottom row
AG((b<*6*>=O * b<*7*>=O * b<*8*>=O) -> winO=1);
AG((b<*0*>=X * b<*3*>=X * b<*6*>=X) -> winX=1); # left column
AG((b<*0*>=O * b<*3*>=O * b<*6*>=O) -> winO=1);
AG((b<*1*>=X * b<*4*>=X * b<*7*>=X) -> winX=1); # middle column
AG((b<*1*>=O * b<*4*>=O * b<*7*>=O) -> winO=1);
AG((b<*2*>=X * b<*5*>=X * b<*8*>=X) -> winX=1); # right column
AG((b<*2*>=O * b<*5*>=O * b<*8*>=O) -> winO=1);
AG((b<*0*>=X * b<*4*>=X * b<*8*>=X) -> winX=1); # diagonal
AG((b<*0*>=O * b<*4*>=O * b<*8*>=O) -> winO=1);
AG((b<*2*>=X * b<*4*>=X * b<*6*>=X) -> winX=1); # antidiagonal
AG((b<*2*>=O * b<*4*>=O * b<*6*>=O) -> winO=1);
#FAIL: Cell 8 may be forever empty.
!EG(b<*8*>=EMPTY);
#FAIL: Cell 8 may be forever empty only if one of the player wins (under the
# fairness constraints).
EG(b<*8*>=EMPTY * winX=0 * winO=0);
#FAIL: a game may not terminate.  (There may be illegal moves, but the
# fairness constraints prevent infiniely many such moves.)
EG(finished=0);
#FAIL: X cannot win.
AG(winX=0);
#FAIL: O cannot win.
AG(winO=0);
#FAIL: X always wins.
AF(winX=1);
#FAIL: O always wins.
AF(winO=1);
#FAIL: ties are not possible.
AF(winX=1 + winO=1);
#FAIL: players take turns.  (Illegal moves do not cause turn to change.)
AG(turn=X -> AX(turn=O));
AG(turn=O -> AX(turn=X));
#FAIL: There cannot be more Os than Xs.
EF(b<*0*>=X * b<*1*>=X * b<*2*>=EMPTY * b<*3*>=O * b<*4*>=EMPTY * b<*5*>=O *
   b<*6*>=O * b<*7*>=EMPTY * b<*8*>=EMPTY);
#PASS: From the specified position X is guaranteed to win.
AG((b<*0*>=X * b<*1*>=X * b<*2*>=EMPTY * b<*3*>=O * b<*4*>=X * b<*5*>=O *
    b<*6*>=O * b<*7*>=EMPTY * b<*8*>=EMPTY) -> AF(winX=1));
#FAIL: From the specified position X is guaranteed to win.
AG((b<*0*>=X * b<*1*>=X * b<*2*>=EMPTY * b<*3*>=O * b<*4*>=X * b<*5*>=O *
    b<*6*>=O * b<*7*>=EMPTY * b<*8*>=EMPTY) -> AF(winO=1));
#FAIL: From the specified position X is guaranteed to win.
AG((b<*0*>=X * b<*1*>=X * b<*2*>=EMPTY * b<*3*>=O * b<*4*>=X * b<*5*>=O *
    b<*6*>=O * b<*7*>=EMPTY * b<*8*>=EMPTY) -> EF(winO=1));
