#Any state when reset is asserted it goes to reset state
AG (EF(Resetn=1));

#Any where in the state when customer is 3 write_accept_n_reg is 1
AG(customer[1:0]= 3 -> write_accept_n_reg = 1);

#It is possible to have customer 0 and Icache_request_n 0 and always stay
# in state where custormer is 1
EF(customer[1:0]=0 * Icache_request_n=0 -> AG(customer[1:0] = 1 ));

#It is possible to have customer 0 and Dcache_request_n 0 and always stay
# in state where custormer is 2
EF(customer[1:0]=0 * Dcache_request_n=0 -> AG(customer[1:0] = 2 ));

#It is possible to have customer 0 and write_request_n 0 and always stay
# in state where custormer is 3
#EF(customer[1:0]=0 * write_request_n=0 -> AG(customer[1:0] = 3 * mem_address_reg[7:0] == Dcache_address[7:0] * data_in_next_cycle = 0 ));

#It is possible to have customer 0 and write_request_n 0 and always stay
# in state where custormer is 3
EF(customer[1:0]=0 * write_request_n=0 -> AG(customer[1:0] = 3 * mem_address_reg[15:8] == Dcache_address[15:8] * data_in_next_cycle = 0 ));

#It is possible to have customer 0 and write_request_n 0 and always stay
# in state where custormer is 3
#EF(customer[1:0]=0 * write_request_n=0 -> AG(customer[1:0] = 3 * mem_address_reg[23:16] == Dcache_address[23:16] * data_in_next_cycle = 0 ));

#It is possible to have customer 0 and write_request_n 0 and always stay
# in state where custormer is 3
#EF(customer[1:0]=0 * write_request_n=0 -> AG(customer[1:0] = 3 * mem_address_reg[31:24] == Dcache_address[31:24] * data_in_next_cycle = 0 ));

#It is possible that state when mem_data_read_ack = 1 and 
#Icache_done_n_reg =0 reaches after customer is 1 
AX(customer[1:0]=1) -> EF(mem_data_read_ack =1 * Icache_done_n_reg=0);

#It is also possible that state when mem_data_read_ack = 1 and 
#Icache_done_n_reg =0 reaches after customer is 1 
AX(customer[1:0]=1) -> !EF(data_in_next_cycle=0 * customer[1:0]=3);

#Later point if write_accept_n_reg, mem_control_reg = 3'b010 and if
#write_size == 0 and if mem_address_reg[1:0] =3 mem_valid_reg = 3'b001

AF(write_accept_n_reg=1 * mem_control_reg[2:0]=2 * write_size=0) -> AF(mem_address_reg[1:0] =3 * mem_valid_reg[2:0]=1);

#Globally true that when write_size is deasserted and mem_address_reg is 0
# then it is possible to get mem_valid_reg=4
AG(write_size=0 * mem_address_reg[1:0]=0 -> EF(mem_valid_reg[2:0]=4));

#There is a state that Icache_request_n=0 and next state is reached when
#Dcache_request_n=0 and after that nextstate is write_request_n=0, that
#means it is in state when customer is 0
EF(Icache_request_n=0 * EX(Dcache_request_n=0) * EX(write_request_n=0))->EF(customer[1:0] = 0);

