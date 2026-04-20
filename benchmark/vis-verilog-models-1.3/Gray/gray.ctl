#FAIL: p and z have the same value in all states.
AG(p==z);

#PASS: if p and z have the same value, they will henceforth remain identical.
(p==z) -> AG(p==z);

#FAIL: p and z may become equal.
EF(p==z);

#PASS: p must go to 1 for r to go to 1
(q=0 * r=0) -> !E(p=0 U r=1);
