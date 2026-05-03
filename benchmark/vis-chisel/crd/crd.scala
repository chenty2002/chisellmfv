package llmverify

import chisel3._
import chisel3.util._

// Enum definitions using Chisel UInt constants
object TrafficStatus {
  val no_cars = 0.U(2.W)
  val car_waiting = 1.U(2.W)
  val cars_passing = 2.U(2.W)
}

object TrafficSignal {
  val stop = 0.U(2.W)
  val go = 1.U(2.W)
  val slow = 2.U(2.W)
}

object PoliceSignal {
  val go_slow = 0.U(2.W)
  val go_A = 1.U(2.W)
  val go_B = 2.U(2.W)
}

object CarStatus {
  val STOPPED_init = 0.U(2.W)
  val STOPPED = 1.U(2.W)
  val GO_init = 2.U(2.W)
  val GO = 3.U(2.W)
}

object PoliceState {
  val go_A_init = 0.U(2.W)
  val go_A_state = 1.U(2.W)
  val go_B_init = 2.U(2.W)
  val go_B_state = 3.U(2.W)
}

object Status {
  val GOOD = 0.U(1.W)
  val BAD = 1.U(1.W)
}

object Prop1Status {
  val OK = 0.U(1.W)
  val NOT_OK = 1.U(1.W)
}

class Collision extends Module {
  val io = IO(new Bundle {
    val status_A = Input(UInt(2.W))
    val status_B = Input(UInt(2.W))
    val state = Output(UInt(1.W))
  })
  
  val state = RegInit(Status.GOOD)
  
  when(state === Status.GOOD && 
       io.status_A === TrafficStatus.cars_passing && 
       io.status_B === TrafficStatus.cars_passing) {
    state := Status.BAD
  }
  
  io.state := state
}

class Starvation extends Module {
  val io = IO(new Bundle {
    val stat = Input(UInt(2.W))
    val state = Output(UInt(1.W))
  })
  
  val state = RegInit(Prop1Status.OK)
  
  switch(state) {
    is(Prop1Status.OK) {
      when(io.stat === TrafficStatus.car_waiting) {
        state := Prop1Status.NOT_OK
      }
    }
    is(Prop1Status.NOT_OK) {
      when(io.stat === TrafficStatus.cars_passing) {
        state := Prop1Status.OK
      }
    }
  }
  
  io.state := state
}

class Road extends Module {
  val io = IO(new Bundle {
    val signal = Input(UInt(2.W))
    val status = Output(UInt(2.W))
    // Add outputs to preserve internal signals
    val state_out = Output(UInt(2.W))
    val r_state_out = Output(UInt(1.W))
  })
  
  val state = RegInit(CarStatus.STOPPED_init)
  
  // Simple LFSR for non-deterministic behavior
  val lfsr_reg = RegInit(1.U(16.W))
  val lfsr_next = Cat(lfsr_reg(14,0), lfsr_reg(15) ^ lfsr_reg(13) ^ lfsr_reg(12) ^ lfsr_reg(10))
  lfsr_reg := lfsr_next
  val r_state = lfsr_reg(0)
  
  // Use Mux cascade to ensure complete assignment (mirroring Verilog ternary operator)
  val status = Mux(state === CarStatus.STOPPED_init, TrafficStatus.no_cars,
                Mux(state === CarStatus.STOPPED, TrafficStatus.car_waiting,
                Mux(state === CarStatus.GO_init, TrafficStatus.cars_passing,
                Mux(state === CarStatus.GO, TrafficStatus.no_cars,
                    TrafficStatus.no_cars)))) // default case
  
  switch(state) {
    is(CarStatus.STOPPED_init) {
      when(r_state === 1.U) {
        state := CarStatus.STOPPED
      }
    }
    is(CarStatus.STOPPED) {
      when(io.signal === TrafficSignal.go) {
        state := CarStatus.GO_init
      }
    }
    is(CarStatus.GO_init) {
      when(io.signal === TrafficSignal.stop) {
        state := CarStatus.STOPPED_init
      }.elsewhen(r_state === 1.U) {
        state := CarStatus.GO
      }
    }
    is(CarStatus.GO) {
      state := CarStatus.STOPPED_init
    }
  }
  
  io.status := status
  io.state_out := state
  io.r_state_out := r_state
}

class Policeman extends Module {
  val io = IO(new Bundle {
    val status_A = Input(UInt(2.W))
    val status_B = Input(UInt(2.W))
    val signal = Output(UInt(2.W))
    // Add outputs to preserve internal signals
    val state_out = Output(UInt(2.W))
    val r_state_out = Output(UInt(1.W))
    val ri_state_out = Output(UInt(1.W))
  })
  
  // Simple LFSR for non-deterministic behavior
  val lfsr_reg = RegInit(2.U(16.W))
  val lfsr_next = Cat(lfsr_reg(14,0), lfsr_reg(15) ^ lfsr_reg(13) ^ lfsr_reg(12) ^ lfsr_reg(10))
  lfsr_reg := lfsr_next
  val r_state = lfsr_reg(0)
  val ri_state = lfsr_reg(1)
  
  // Initialize state based on ri_state (using reset logic)
  val state = RegInit(Mux(ri_state === 0.U, PoliceState.go_A_init, PoliceState.go_B_init))
  
  val signal = Wire(UInt(2.W))
  when(state === PoliceState.go_A_init || state === PoliceState.go_B_init) {
    signal := PoliceSignal.go_slow
  }.elsewhen(state === PoliceState.go_A_state) {
    signal := PoliceSignal.go_A
  }.otherwise {
    signal := PoliceSignal.go_B
  }
  
  switch(state) {
    is(PoliceState.go_A_init) {
      when(r_state === 1.U) {
        state := PoliceState.go_A_state
      }
    }
    is(PoliceState.go_B_init) {
      when(r_state === 1.U) {
        state := PoliceState.go_B_state
      }
    }
    is(PoliceState.go_A_state) {
      when(signal === PoliceSignal.go_A && io.status_B === TrafficStatus.car_waiting) {
        state := PoliceState.go_B_init
      }
    }
    is(PoliceState.go_B_state) {
      when(signal === PoliceSignal.go_B && io.status_A === TrafficStatus.car_waiting) {
        state := PoliceState.go_A_init
      }
    }
  }
  
  io.signal := signal
  io.state_out := state
  io.r_state_out := r_state
  io.ri_state_out := ri_state
}

class Environment extends Module {
  val io = IO(new Bundle {
    val status_A = Output(UInt(2.W))
    val test = Output(UInt(1.W))
    // Add outputs to preserve internal signals
    val signal_A = Output(UInt(2.W))
    val signal_B = Output(UInt(2.W))
    val status_B = Output(UInt(2.W))
    val police_signal = Output(UInt(2.W))
    val collision_state = Output(UInt(1.W))
    val starvation_state = Output(UInt(1.W))
  })
  
  val police = Module(new Policeman())
  val road_A = Module(new Road())
  val road_B = Module(new Road())
  val col = Module(new Collision())
  val starv = Module(new Starvation())
  
  // Connect policeman
  police.io.status_A := road_A.io.status
  police.io.status_B := road_B.io.status
  
  val signal_A = Wire(UInt(2.W))
  val signal_B = Wire(UInt(2.W))
  
  when(police.io.signal === PoliceSignal.go_A) {
    signal_A := TrafficSignal.go
  }.elsewhen(police.io.signal === PoliceSignal.go_slow) {
    signal_A := TrafficSignal.slow
  }.otherwise {
    signal_A := TrafficSignal.stop
  }
  
  when(police.io.signal === PoliceSignal.go_B) {
    signal_B := TrafficSignal.go
  }.elsewhen(police.io.signal === PoliceSignal.go_slow) {
    signal_B := TrafficSignal.slow
  }.otherwise {
    signal_B := TrafficSignal.stop
  }
  
  // Connect roads
  road_A.io.signal := signal_A
  road_B.io.signal := signal_B
  
  // Connect collision and starvation modules
  col.io.status_A := road_A.io.status
  col.io.status_B := road_B.io.status
  
  starv.io.stat := road_A.io.status
  
  val test = Wire(UInt(1.W))
  when(road_A.io.status === TrafficStatus.cars_passing && road_B.io.status === TrafficStatus.cars_passing) {
    test := 0.U
  }.otherwise {
    test := 1.U
  }
  
  io.status_A := road_A.io.status
  io.test := test
  io.signal_A := signal_A
  io.signal_B := signal_B
  io.status_B := road_B.io.status
  io.police_signal := police.io.signal
  io.collision_state := col.io.state
  io.starvation_state := starv.io.state
}

object VerilogGenerator extends App {
  emitVerilog(new Environment(), args)
}