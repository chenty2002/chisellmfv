#PASS: Once the oven is started it will eventually heat.  This requires a 
# fairness constraint stating that the user does not always goof.
AG(Start=1 -> AF Heat=1);

#PASS: The oven can't be heated if the door is not closed.
A(Heat=0 U Close=1);

#PASS: If an error condition occurs, it must be reset before the oven may heat.
AG((Close=0 * Start=1) -> !E(Error=1 U Heat=1));

#PASS: Heat in the oven is incompatible with open door and error.
AG(Heat=0 + (Close=1 * Error=0));
