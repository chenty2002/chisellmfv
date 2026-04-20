#PASS: From strong taken it is impossible to transition to not taken.
# From strong not taken it is impossible to transition to taken.
AG((state_bank0<*0*>[1:0]=3 -> AX(state_bank0<*0*>[1]=1)) *
   (state_bank0<*1*>[1:0]=3 -> AX(state_bank0<*1*>[1]=1)) *
   (state_bank0<*2*>[1:0]=3 -> AX(state_bank0<*2*>[1]=1)) *
   (state_bank0<*3*>[1:0]=3 -> AX(state_bank0<*3*>[1]=1)) *
   (state_bank1<*0*>[1:0]=3 -> AX(state_bank1<*0*>[1]=1)) *
   (state_bank1<*1*>[1:0]=3 -> AX(state_bank1<*1*>[1]=1)) *
   (state_bank1<*2*>[1:0]=3 -> AX(state_bank1<*2*>[1]=1)) *
   (state_bank1<*3*>[1:0]=3 -> AX(state_bank1<*3*>[1]=1)) *
   (state_bank2<*0*>[1:0]=3 -> AX(state_bank2<*0*>[1]=1)) *
   (state_bank2<*1*>[1:0]=3 -> AX(state_bank2<*1*>[1]=1)) *
   (state_bank2<*2*>[1:0]=3 -> AX(state_bank2<*2*>[1]=1)) *
   (state_bank2<*3*>[1:0]=3 -> AX(state_bank2<*3*>[1]=1)) *
   (state_bank3<*0*>[1:0]=3 -> AX(state_bank3<*0*>[1]=1)) *
   (state_bank3<*1*>[1:0]=3 -> AX(state_bank3<*1*>[1]=1)) *
   (state_bank3<*2*>[1:0]=3 -> AX(state_bank3<*2*>[1]=1)) *
   (state_bank3<*3*>[1:0]=3 -> AX(state_bank3<*3*>[1]=1)) *
   (state_bank0<*0*>[1:0]=0 -> AX(state_bank0<*0*>[1]=0)) *
   (state_bank0<*1*>[1:0]=0 -> AX(state_bank0<*1*>[1]=0)) *
   (state_bank0<*2*>[1:0]=0 -> AX(state_bank0<*2*>[1]=0)) *
   (state_bank0<*3*>[1:0]=0 -> AX(state_bank0<*3*>[1]=0)) *
   (state_bank1<*0*>[1:0]=0 -> AX(state_bank1<*0*>[1]=0)) *
   (state_bank1<*1*>[1:0]=0 -> AX(state_bank1<*1*>[1]=0)) *
   (state_bank1<*2*>[1:0]=0 -> AX(state_bank1<*2*>[1]=0)) *
   (state_bank1<*3*>[1:0]=0 -> AX(state_bank1<*3*>[1]=0)) *
   (state_bank2<*0*>[1:0]=0 -> AX(state_bank2<*0*>[1]=0)) *
   (state_bank2<*1*>[1:0]=0 -> AX(state_bank2<*1*>[1]=0)) *
   (state_bank2<*2*>[1:0]=0 -> AX(state_bank2<*2*>[1]=0)) *
   (state_bank2<*3*>[1:0]=0 -> AX(state_bank2<*3*>[1]=0)) *
   (state_bank3<*0*>[1:0]=0 -> AX(state_bank3<*0*>[1]=0)) *
   (state_bank3<*1*>[1:0]=0 -> AX(state_bank3<*1*>[1]=0)) *
   (state_bank3<*2*>[1:0]=0 -> AX(state_bank3<*2*>[1]=0)) *
   (state_bank3<*3*>[1:0]=0 -> AX(state_bank3<*3*>[1]=0)));

#PASS: If all lines agree on taken/not taken, the prediction has to agree
# as well on the next clock cycle. To avoid referring to stall, we weaken
# the property and look only at the case in which the prediction already
# agrees in the current state.
AG((state_bank3<*0*>[1]=1 * state_bank3<*1*>[1]=1 * state_bank3<*2*>[1]=1 *
    state_bank3<*3*>[1]=1 * prediction[3]=1 -> AX(prediction[3]=1)) *
   (state_bank2<*0*>[1]=1 * state_bank2<*1*>[1]=1 * state_bank2<*2*>[1]=1 *
    state_bank2<*3*>[1]=1 * prediction[2]=1 -> AX(prediction[2]=1)) *
   (state_bank1<*0*>[1]=1 * state_bank1<*1*>[1]=1 * state_bank1<*2*>[1]=1 *
    state_bank1<*3*>[1]=1 * prediction[1]=1 -> AX(prediction[1]=1)) *
   (state_bank0<*0*>[1]=1 * state_bank0<*1*>[1]=1 * state_bank0<*2*>[1]=1 *
    state_bank0<*3*>[1]=1 * prediction[0]=1 -> AX(prediction[0]=1)) *
   (state_bank3<*0*>[1]=0 * state_bank3<*1*>[1]=0 * state_bank3<*2*>[1]=0 *
    state_bank3<*3*>[1]=0 * prediction[3]=0 -> AX(prediction[3]=0)) *
   (state_bank2<*0*>[1]=0 * state_bank2<*1*>[1]=0 * state_bank2<*2*>[1]=0 *
    state_bank2<*3*>[1]=0 * prediction[2]=0 -> AX(prediction[2]=0)) *
   (state_bank1<*0*>[1]=0 * state_bank1<*1*>[1]=0 * state_bank1<*2*>[1]=0 *
    state_bank1<*3*>[1]=0 * prediction[1]=0 -> AX(prediction[1]=0)) *
   (state_bank0<*0*>[1]=0 * state_bank0<*1*>[1]=0 * state_bank0<*2*>[1]=0 *
    state_bank0<*3*>[1]=0 * prediction[0]=0 -> AX(prediction[0]=0))
);

#FAIL: If all lines agree on taken/not taken, the prediction has to agree
# as well on the next clock cycle. It fails because it ignores stalls.
AG((state_bank3<*0*>[1]=1 * state_bank3<*1*>[1]=1 * state_bank3<*2*>[1]=1 *
    state_bank3<*3*>[1]=1 -> AX(prediction[3]=1)) *
   (state_bank2<*0*>[1]=1 * state_bank2<*1*>[1]=1 * state_bank2<*2*>[1]=1 *
    state_bank2<*3*>[1]=1 -> AX(prediction[2]=1)) *
   (state_bank1<*0*>[1]=1 * state_bank1<*1*>[1]=1 * state_bank1<*2*>[1]=1 *
    state_bank1<*3*>[1]=1 -> AX(prediction[1]=1)) *
   (state_bank0<*0*>[1]=1 * state_bank0<*1*>[1]=1 * state_bank0<*2*>[1]=1 *
    state_bank0<*3*>[1]=1 -> AX(prediction[0]=1)) *
   (state_bank3<*0*>[1]=0 * state_bank3<*1*>[1]=0 * state_bank3<*2*>[1]=0 *
    state_bank3<*3*>[1]=0 -> AX(prediction[3]=0)) *
   (state_bank2<*0*>[1]=0 * state_bank2<*1*>[1]=0 * state_bank2<*2*>[1]=0 *
    state_bank2<*3*>[1]=0 -> AX(prediction[2]=0)) *
   (state_bank1<*0*>[1]=0 * state_bank1<*1*>[1]=0 * state_bank1<*2*>[1]=0 *
    state_bank1<*3*>[1]=0 -> AX(prediction[1]=0)) *
   (state_bank0<*0*>[1]=0 * state_bank0<*1*>[1]=0 * state_bank0<*2*>[1]=0 *
    state_bank0<*3*>[1]=0 -> AX(prediction[0]=0))
);
