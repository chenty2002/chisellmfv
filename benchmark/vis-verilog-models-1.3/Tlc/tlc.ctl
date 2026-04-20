#PASS: Light is not green both directions at the same time
AG ( !((farm_light = GREEN) * (hwy_light = GREEN)) );

#PASS: If car is present on farm road, and timer is long, then eventually the 
#farm light will turn green.
AG(((car_present = YES) * (timer.state = LONG)) -> AF(farm_light = GREEN));

#PASS: Regardless of what happens on the farm road, the highway will always be 
#green in the future.
AG(AF(hwy_light = GREEN));

#PASS: Even if a car is present on farm road, this does not guarantee that 
#eventually the farm light will turn green. It could be that a car approaches, 
#and then backs away, all before the timer goes long.  Having the system satisfy
#this property is not necessary for safety, it just maximizes the time that
#the highway light is green.
!(AG((car_present = YES) -> AF(farm_light = GREEN)));

#FAIL: The opposite of the above formula.  Demonstrates the fair CTL debugger.
AG((car_present = YES) -> AF(farm_light = GREEN));
