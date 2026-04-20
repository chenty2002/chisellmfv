# This formula works for 3 or 4 floors because of the width of "location."
#FAIL:
AG((main_control.up_floor_buttons<*1*>=ON) ->
   AF(e1.location[0:1]=2 * e1.door=OPEN * e1.direction=UP));
