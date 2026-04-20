#PASS: players 0 and 1 cannot both win.
AG(!(win=1 * lose=1));

#PASS: the first player may win from a winning position.
AG((turn=0 * winning=1) -> EF win=1);

#PASS: the second  player may win from a winning position.
AG((turn=1 * winning=1) -> EF lose=1);

#FAIL: the first player always wins from a winning position.
AG((turn=0 * winning=1) -> AF win=1);

#PASS: the second player always wins from a winning position.
AG((turn=1 * winning=1) -> AF lose=1);

#PASS: termination is inevitable.
AF(win=1 + lose=1);
