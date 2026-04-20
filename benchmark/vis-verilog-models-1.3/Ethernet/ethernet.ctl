AG ( ( L0.reqState = 1 ) -> AF ( ( L0.ack<1> = 0 * L0.ack<0> = 1 ) + (L0.ack<1> = 0 * L0.ack<0> = 0 ) ) );
AG ( ( L1.reqState = 1 ) -> AF ( ( L1.ack<1> = 0 * L1.ack<0> = 1 ) + (L1.ack<1> = 0 * L1.ack<0> = 0 ) ) );

AG ( ( L0.ack<1> = 0 * L0.ack<0> = 1 ) -> AF ( FR0.frm_ready = 1 * FR1.frm_ready = 1 ) );
AG ( ( L1.ack<1> = 0 * L1.ack<0> = 1 ) -> AF ( FR0.frm_ready = 1 * FR1.frm_ready = 1 ) );

AG ( ( L0.reqState = 1 ) -> AF (L0.reqState = 0 ) );
AG ( ( L1.reqState = 1 ) -> AF (L1.reqState = 0 ) );


