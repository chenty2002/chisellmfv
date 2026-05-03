package llmverify

import chisel3._
import chisel3.util._

// State enumeration for the receiver state machine
object ReceiverState extends ChiselEnum {
  val L0, L1, L2, L3, L4, L5, L6, L7, L8, L9, L10 = Value
}

class daio_receiver extends Module {
  val io = IO(new Bundle {
    val xtal = Input(UInt(4.W))
    val rx_control = Input(UInt(4.W))
    val reset = Input(Bool())
    val bit_in = Input(Bool())
    val preamble_1 = Input(Bool())
    val preamble_2 = Input(Bool())
    val preamble_3 = Input(Bool())
    val carrier_loss = Input(Bool())
    val biphase_violation = Input(Bool())
    
    val clock_out = Output(Bool())
    val rx_status = Output(UInt(4.W))
    val parity = Output(Bool())
    val load_A = Output(Bool())
    val load_B = Output(Bool())
    val load_buff = Output(Bool())
    val shift_reg = Output(UInt(20.W))
    val frame_ofs = Output(UInt(2.W))
  })
  
  // Internal registers
  val bit_count_A = RegInit(0.U(7.W))
  val bit_count_B = RegInit(0.U(7.W))
  val frame_counter = RegInit(0.U(9.W))
  val clock_out_reg = RegInit(0.U(1.W))
  val rx_status_3 = RegInit(0.U(1.W))
  val rx_status_2 = RegInit(0.U(1.W))
  val rx_status_1 = RegInit(0.U(1.W))
  val rx_status_0 = RegInit(0.U(1.W))
  val parity_reg = RegInit(0.U(1.W))
  val load_A_reg = RegInit(0.U(1.W))
  val load_B_reg = RegInit(0.U(1.W))
  val load_buff_reg = RegInit(0.U(1.W))
  val shift_reg_reg = RegInit(0.U(20.W))
  val pc = RegInit(ReceiverState.L0)
  
  // Output assignments
  io.clock_out := clock_out_reg
  io.rx_status := Cat(rx_status_3, rx_status_2, rx_status_1, rx_status_0)
  io.parity := parity_reg
  io.load_A := load_A_reg
  io.load_B := load_B_reg
  io.load_buff := load_buff_reg
  io.shift_reg := shift_reg_reg
  io.frame_ofs := frame_counter(1, 0)
  
  // Main state machine and control logic
  when(io.reset) {
    rx_status_3 := 0.U
    rx_status_2 := 0.U
    load_A_reg := 0.U
    load_B_reg := 0.U
    load_buff_reg := 0.U
    bit_count_A := 0.U
    bit_count_B := 0.U
    frame_counter := 0.U
    pc := ReceiverState.L0
  }.otherwise {
    switch(pc) {
      is(ReceiverState.L0) {
        when(io.rx_control(2)) {
          pc := ReceiverState.L1
        }
      }
      is(ReceiverState.L1) {
        switch(io.rx_control(1, 0)) {
          is("b00".U) { clock_out_reg := io.xtal(0) }
          is("b01".U) { clock_out_reg := io.xtal(1) }
          is("b10".U) { clock_out_reg := io.xtal(2) }
          is("b11".U) { clock_out_reg := io.xtal(3) }
        }
        pc := ReceiverState.L2
      }
      is(ReceiverState.L2) {
        when(io.preamble_1) {
          bit_count_A := 4.U
          pc := ReceiverState.L3
        }
      }
      is(ReceiverState.L3) {
        when(bit_count_A < 32.U) {
          when(bit_count_A === 31.U) {
            load_A_reg := 1.U
          }
          when(bit_count_A === 2.U) {
            load_B_reg := 0.U
          }
          when(bit_count_A === 3.U) {
            load_buff_reg := 1.U
          }
          when(bit_count_A === 5.U) {
            load_buff_reg := 0.U
          }
          bit_count_A := bit_count_A + 1.U
        }.otherwise {
          pc := ReceiverState.L4
        }
      }
      is(ReceiverState.L4) {
        bit_count_B := 1.U
        frame_counter := 1.U
        pc := ReceiverState.L5
      }
      is(ReceiverState.L5) {
        when(bit_count_B < 32.U) {
          when((bit_count_B === 4.U) && !io.preamble_3) {
            rx_status_2 := 1.U
          }
          when(bit_count_B === 31.U) {
            load_B_reg := 1.U
          }
          when(bit_count_B === 2.U) {
            load_A_reg := 0.U
          }
          bit_count_B := bit_count_B + 1.U
        }.otherwise {
          pc := ReceiverState.L6
        }
      }
      is(ReceiverState.L6) {
        when(frame_counter < 191.U) {
          bit_count_A := 1.U
          pc := ReceiverState.L7
        }.otherwise {
          pc := ReceiverState.L0
        }
      }
      is(ReceiverState.L7) {
        when(bit_count_A < 32.U) {
          when((bit_count_A === 4.U) && !io.preamble_2) {
            rx_status_2 := 1.U
          }
          when(bit_count_A === 31.U) {
            load_A_reg := 1.U
          }
          when(bit_count_A === 2.U) {
            load_B_reg := 0.U
          }
          when(bit_count_A === 3.U) {
            when(frame_counter(1, 0) === 0.U) {
              load_buff_reg := 1.U
            }
          }
          when(bit_count_A === 5.U) {
            load_buff_reg := 0.U
          }
          bit_count_A := bit_count_A + 1.U
        }.otherwise {
          pc := ReceiverState.L8
        }
      }
      is(ReceiverState.L8) {
        bit_count_B := 1.U
        pc := ReceiverState.L9
      }
      is(ReceiverState.L9) {
        when(bit_count_B < 32.U) {
          when((bit_count_B === 4.U) && !io.preamble_3) {
            rx_status_2 := 1.U
          }
          when(bit_count_B === 31.U) {
            load_B_reg := 1.U
          }
          when(bit_count_B === 2.U) {
            load_A_reg := 0.U
          }
          bit_count_B := bit_count_B + 1.U
        }.otherwise {
          pc := ReceiverState.L10
        }
      }
      is(ReceiverState.L10) {
        frame_counter := frame_counter + 1.U
        pc := ReceiverState.L6
      }
    }
  }
  
  // Shift register and status updates
  when(io.reset) {
    shift_reg_reg := 0.U
    rx_status_1 := 0.U
    rx_status_0 := 0.U
  }.elsewhen(pc =/= ReceiverState.L0 && pc =/= ReceiverState.L1) {
    shift_reg_reg := Cat(shift_reg_reg(18, 0), io.bit_in)
    when(io.carrier_loss) {
      rx_status_0 := 1.U
    }
    when(io.biphase_violation) {
      rx_status_1 := 1.U
    }
  }
  
  // Parity calculation
  when(io.reset || pc === ReceiverState.L2 || pc === ReceiverState.L4 || 
       pc === ReceiverState.L6 || pc === ReceiverState.L8) {
    parity_reg := 0.U
  }.elsewhen(pc =/= ReceiverState.L0 && pc =/= ReceiverState.L1) {
    parity_reg := parity_reg ^ io.bit_in
  }
}

object VerilogGenerator extends App {
  emitVerilog(new daio_receiver(), args)
}