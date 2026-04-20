# Starvation
AG((status_A=car_waiting) -> AF (status_A=cars_passing));

# Starvation 2
AG((starv.state = NOT_OK) -> AF (starv.state =OK));

# Safety
AG(!(status_A=cars_passing) + ! (status_B=cars_passing));
