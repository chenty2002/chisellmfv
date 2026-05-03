package llmverify

import chisel3._
import chisel3.util._

object eleLTLM1Constants {
  val elev = 2
  val floor = 3
  val width = 2
}

// Enums
object Direction {
  val UP = 0.U(1.W)
  val DOWN = 1.U(1.W)
}

object Movement {
  val STOPPED = 0.U(1.W)
  val MOVING = 1.U(1.W)
}

object DoorState {
  val OPEN = 0.U(2.W)
  val OPENING = 1.U(2.W)
  val CLOSED = 2.U(2.W)
  val CLOSING = 3.U(2.W)
}

object OnOff {
  val ON = 1.U(1.W)
  val OFF = 0.U(1.W)
}

// Buechi states
object BuechiStates {
  val n3 :: n4 :: n11 :: n13 :: n15 :: n16 :: n18 :: n19 :: n20 :: n21 :: n24 :: n26 :: n31 :: n32 :: n33 :: n38 :: n47 :: nTrap :: Nil = Enum(18)
  val Trap = nTrap  // Alias for compatibility
}

// Simple random number generator
class SimpleRandom(width: Int) extends Module {
  val io = IO(new Bundle {
    val out = Output(UInt(width.W))
  })
  val counter = RegInit(0.U(width.W))
  counter := counter + 1.U
  io.out := counter
}

class BuechiIO extends Bundle {
  val q = Input(Bool())
  val e1dOPENING = Input(Bool())
  val e2dOPENING = Input(Bool())
  val e2dOPEN = Input(Bool())
  val e1dOPEN = Input(Bool())
  val p = Input(Bool())
  val fair0 = Output(Bool())
  val fair1 = Output(Bool())
  val fair2 = Output(Bool())
  val fair3 = Output(Bool())
  val scc = Output(Bool())
}

class Buechi extends Module {
  val io = IO(new BuechiIO)
  
  import BuechiStates._
  
  val state = RegInit(n15)
  
  // Simple random generators
  val rand1 = Module(new SimpleRandom(1))
  val rand2 = Module(new SimpleRandom(2))
  val rand3 = Module(new SimpleRandom(3))
  
  // Nondeterministic choices using random numbers
  val ND_n21_n3_n31_n33 = Wire(UInt())
  val ND_n3_n31 = Wire(UInt())
  val ND_n15_n20 = Wire(UInt())
  val ND_n13_n16_n19_n26_n3_n31_n38_n4 = Wire(UInt())
  val ND_n11_n16_n19_n26_n3_n32_n33_n47 = Wire(UInt())
  val ND_n13_n16_n3_n31 = Wire(UInt())
  val ND_n3_n33 = Wire(UInt())
  val ND_n16_n3 = Wire(UInt())
  val ND_n11_n13_n16_n18_n19_n21_n24_n26_n3_n31_n32_n33_n38_n4_n47 = Wire(UInt())
  val ND_n16_n19_n26_n3 = Wire(UInt())
  val ND_n11_n19_n21_n24_n3_n31_n33_n4 = Wire(UInt())
  val ND_n16_n3_n32_n33 = Wire(UInt())
  val ND_n19_n3_n31_n4 = Wire(UInt())
  val ND_n11_n19_n3_n33 = Wire(UInt())
  val ND_n19_n3 = Wire(UInt())
  val ND_n13_n16_n18_n21_n3_n31_n32_n33 = Wire(UInt())
  
  // Generate random values for nondeterministic choices
  ND_n21_n3_n31_n33 := MuxCase(n3, Seq(
    (rand2.io.out === 0.U) -> n21,
    (rand2.io.out === 1.U) -> n3,
    (rand2.io.out === 2.U) -> n31,
    (rand2.io.out === 3.U) -> n33
  ))
  
  ND_n3_n31 := Mux(rand1.io.out(0), n3, n31)
  ND_n15_n20 := Mux(rand1.io.out(0), n15, n20)
  
  // Simplified random selection for complex ND choices
  ND_n13_n16_n19_n26_n3_n31_n38_n4 := MuxCase(n3, Seq(
    (rand3.io.out === 0.U) -> n13,
    (rand3.io.out === 1.U) -> n16,
    (rand3.io.out === 2.U) -> n19,
    (rand3.io.out === 3.U) -> n26,
    (rand3.io.out === 4.U) -> n31,
    (rand3.io.out === 5.U) -> n38,
    (rand3.io.out === 6.U) -> n4,
    (rand3.io.out === 7.U) -> n3
  ))
  
  ND_n11_n16_n19_n26_n3_n32_n33_n47 := MuxCase(n3, Seq(
    (rand3.io.out === 0.U) -> n11,
    (rand3.io.out === 1.U) -> n16,
    (rand3.io.out === 2.U) -> n19,
    (rand3.io.out === 3.U) -> n26,
    (rand3.io.out === 4.U) -> n32,
    (rand3.io.out === 5.U) -> n33,
    (rand3.io.out === 6.U) -> n47,
    (rand3.io.out === 7.U) -> n3
  ))
  
  ND_n13_n16_n3_n31 := MuxCase(n3, Seq(
    (rand2.io.out === 0.U) -> n13,
    (rand2.io.out === 1.U) -> n16,
    (rand2.io.out === 2.U) -> n31,
    (rand2.io.out === 3.U) -> n3
  ))
  
  ND_n3_n33 := Mux(rand1.io.out(0), n3, n33)
  ND_n16_n3 := Mux(rand1.io.out(0), n16, n3)
  
  // Fairness conditions
  io.fair0 := (state === n31) || (state === n4) || (state === n38) || (state === n13) || (state === n18) || (state === n21) || (state === n24)
  io.fair1 := (state === n4) || (state === n38) || (state === n11) || (state === n19) || (state === n47) || (state === n24) || (state === n26)
  io.fair2 := (state === n32) || (state === n33) || (state === n11) || (state === n18) || (state === n21) || (state === n47) || (state === n24)
  io.fair3 := (state === n32) || (state === n38) || (state === n13) || (state === n16) || (state === n18) || (state === n47) || (state === n26)
  
  io.scc := (state =/= n20) && (state =/= n15)
  
  // State transition logic
  when(state === n20) {
    when(io.q) {
      state := Trap
    } .otherwise {
      state := n3
    }
  } .elsewhen(state === Trap) {
    state := Trap
  } .elsewhen(state === n15) {
    when(!io.p) {
      state := n15
    } .elsewhen(io.p && !io.q) {
      state := ND_n15_n20
    } .otherwise {
      state := n15
    }
  } .otherwise {
    val inputs = Cat(io.e1dOPEN, io.e1dOPENING, io.e2dOPEN, io.e2dOPENING, io.q)
    when(io.q) {
      state := Trap
    } .otherwise {
      switch(inputs) {
        is("b00000".U) { state := ND_n19_n3_n31_n4 }
        is("b00010".U) { state := ND_n3_n31 }
        is("b00100".U) { state := ND_n11_n19_n21_n24_n3_n31_n33_n4 }
        is("b00110".U) { state := ND_n21_n3_n31_n33 }
        is("b01000".U) { state := ND_n19_n3 }
        is("b01010".U) { state := n3 }
        is("b01100".U) { state := ND_n11_n19_n3_n33 }
        is("b01110".U) { state := ND_n3_n33 }
        is("b10000".U) { state := ND_n13_n16_n19_n26_n3_n31_n38_n4 }
        is("b10010".U) { state := ND_n13_n16_n3_n31 }
        is("b10100".U) { state := ND_n11_n13_n16_n18_n19_n21_n24_n26_n3_n31_n32_n33_n38_n4_n47 }
        is("b10110".U) { state := ND_n13_n16_n18_n21_n3_n31_n32_n33 }
        is("b11000".U) { state := ND_n16_n19_n26_n3 }
        is("b11010".U) { state := ND_n16_n3 }
        is("b11100".U) { state := ND_n11_n16_n19_n26_n3_n32_n33_n47 }
        is("b11110".U) { state := ND_n16_n3_n32_n33 }
      }
    }
  }
}

class MainControlIO extends Bundle {
  val inc = Output(Vec(eleLTLM1Constants.elev + 1, Bool()))
  val dec = Output(Vec(eleLTLM1Constants.elev + 1, Bool()))
  val stop_next = Output(Vec(eleLTLM1Constants.elev + 1, Bool()))
  val continue = Output(Vec(eleLTLM1Constants.elev + 1, Bool()))
  val init1 = Input(UInt(eleLTLM1Constants.width.W))
  val init2 = Input(UInt(eleLTLM1Constants.width.W))
  val p = Output(Bool())
}

class MainControl extends Module {
  val io = IO(new MainControlIO)
  import eleLTLM1Constants._
  
  val locations = RegInit(VecInit(Seq.fill(elev + 1)(0.U(width.W))))
  val up_floor_buttons = RegInit(VecInit(Seq.fill(floor)(0.U(1.W))))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(floor)(0.U(1.W))))
  val direction = RegInit(VecInit(Seq.fill(elev + 1)(Direction.UP)))
  
  // Simple random generators
  val rand1 = Module(new SimpleRandom(1))
  
  // Initialize locations
  locations(1) := io.init1
  locations(2) := io.init2
  
  // Random button pushes
  val random_up = Wire(Vec(floor, Bool()))
  val random_down = Wire(Vec(floor, Bool()))
  for (i <- 0 until floor) {
    random_up(i) := rand1.io.out(0)
    random_down(i) := rand1.io.out(0)
  }
  
  // Button logic
  val buttons = Wire(Vec(floor, Bool()))
  for (i <- 0 until floor) {
    buttons(i) := up_floor_buttons(i) === OnOff.ON || down_floor_buttons(i) === OnOff.ON
  }
  
  val button_below = Wire(Vec(elev + 1, Bool()))
  val button_above = Wire(Vec(elev + 1, Bool()))
  
  // Elevator 1 button logic
  button_below(1) := ((locations(1) === 2.U) && (buttons(0) || buttons(1))) ||
                     (locations(1) === 1.U && buttons(0))
  button_above(1) := ((locations(1) === 0.U) && (buttons(2) || buttons(1))) ||
                     ((locations(1) === 1.U) && buttons(2))
  
  // Elevator 2 button logic
  button_below(2) := ((locations(2) === 2.U) && (buttons(0) || buttons(1))) ||
                     (locations(2) === 1.U && buttons(0))
  button_above(2) := ((locations(2) === 0.U) && (buttons(2) || buttons(1))) ||
                     ((locations(2) === 1.U) && buttons(2))
  
  // Continue logic
  for (i <- 1 to elev) {
    io.continue(i) := (button_above(i) && direction(i) === Direction.UP) ||
                      (button_below(i) && direction(i) === Direction.DOWN)
  }
  
  // Stop next logic
  for (i <- 1 to elev) {
    when(locations(i) =/= (floor - 1).U && direction(i) === Direction.UP) {
      io.stop_next(i) := up_floor_buttons(locations(i) + 1.U) === OnOff.ON
    } .elsewhen(locations(i) =/= 0.U && direction(i) === Direction.DOWN) {
      io.stop_next(i) := down_floor_buttons(locations(i) - 1.U) === OnOff.ON
    } .otherwise {
      io.stop_next(i) := false.B
    }
  }
  
  // Output p
  io.p := up_floor_buttons(1) === OnOff.ON
  
  // Sequential logic
  when(true.B) { // Always block
    // Random button pushes
    for (i <- 0 until floor) {
      when(random_up(i)) {
        up_floor_buttons(i) := OnOff.ON
      }
      when(random_down(i)) {
        down_floor_buttons(i) := OnOff.ON
      }
    }
    
    // Turn off scheduled floor buttons
    for (i <- 1 to elev) {
      when(locations(i) =/= (floor - 1).U && direction(i) === Direction.UP) {
        when(up_floor_buttons(locations(i) + 1.U) === OnOff.ON) {
          up_floor_buttons(locations(i) + 1.U) := OnOff.OFF
        }
      }
      when(locations(i) =/= 0.U && direction(i) === Direction.DOWN) {
        when(down_floor_buttons(locations(i) - 1.U) === OnOff.ON) {
          down_floor_buttons(locations(i) - 1.U) := OnOff.OFF
        }
      }
    }
    
    // Update locations and directions
    for (i <- 1 to elev) {
      when(locations(i) === (floor - 1).U) {
        direction(i) := Direction.DOWN
      }
      when(locations(i) === 0.U) {
        direction(i) := Direction.UP
      }
      when(io.inc(i)) {
        locations(i) := locations(i) + 1.U
        direction(i) := Direction.UP
      }
      when(io.dec(i)) {
        locations(i) := locations(i) - 1.U
        direction(i) := Direction.DOWN
      }
    }
  }
  
  // Initialize inc and dec outputs
  for (i <- 1 to elev) {
    io.inc(i) := false.B
    io.dec(i) := false.B
  }
}

class ElevatorIO extends Bundle {
  val stop_next = Input(Bool())
  val inc = Output(Bool())
  val dec = Output(Bool())
  val continue = Input(Bool())
  val init = Input(UInt(eleLTLM1Constants.width.W))
  val location = Output(UInt(eleLTLM1Constants.width.W))
  val direction = Output(UInt())
  val door = Output(UInt(2.W))
}

class Elevator extends Module {
  val io = IO(new ElevatorIO)
  import eleLTLM1Constants._
  
  val buttons = RegInit(VecInit(Seq.fill(floor)(0.U(1.W))))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(DoorState.OPEN)
  val open_next = RegInit(false.B)
  
  // Simple random generators
  val rand1 = Module(new SimpleRandom(1))
  val rand3 = Module(new SimpleRandom(3))
  
  // Random button pushes
  val random_push = Wire(Vec(floor, Bool()))
  for (i <- 0 until floor) {
    random_push(i) := rand3.io.out(0)
  }
  
  val button_below = ((location === 2.U) && (buttons(1) === OnOff.ON || buttons(0) === OnOff.ON)) ||
                      ((location === 1.U) && buttons(0) === OnOff.ON)
  val button_above = ((location === 0.U) && (buttons(2) === OnOff.ON || buttons(1) === OnOff.ON)) ||
                      ((location === 1.U) && buttons(2) === OnOff.ON)
  
  // Sequential logic for buttons
  when(true.B) {
    for (i <- 0 until floor) {
      when(i.U === location) {
        buttons(i) := OnOff.OFF
      } .elsewhen(random_push(i)) {
        buttons(i) := OnOff.ON
      }
    }
    
    // Record stop_next request
    when(io.stop_next) {
      when(direction === Direction.UP) {
        buttons(location + 1.U) := OnOff.ON
      } .otherwise {
        buttons(location - 1.U) := OnOff.ON
      }
    }
  }
  
  // Door open scheduling
  when(true.B) {
    when(door =/= DoorState.CLOSED) {
      open_next := false.B
    } .elsewhen(movement === Movement.MOVING && 
               (io.stop_next || 
                (direction === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
                (direction === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
      open_next := true.B
    }
  }
  
  // Door operation
  val random = rand1.io.out(0)
  when(true.B) {
    switch(door) {
      is(DoorState.CLOSED) {
        when(open_next && movement === Movement.STOPPED) {
          door := DoorState.OPENING
        }
      }
      is(DoorState.OPENING) {
        when(random) {
          door := DoorState.OPEN
        }
      }
      is(DoorState.OPEN) {
        when(random) {
          door := DoorState.CLOSING
        }
      }
      is(DoorState.CLOSING) {
        when(random) {
          door := DoorState.CLOSED
        }
      }
    }
  }
  
  // Movement logic
  val start_moving = io.continue || 
                     (button_above && direction === Direction.UP) || 
                     (button_below && direction === Direction.DOWN)
  val r_stop = rand1.io.out(0)
  val stop_moving = r_stop && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (direction === Direction.UP)
  io.dec := stop_moving && (direction === Direction.DOWN)
  
  when(true.B) {
    when(door === DoorState.CLOSED) {
      switch(movement) {
        is(Movement.STOPPED) {
          when(start_moving && !open_next) {
            movement := Movement.MOVING
          }
        }
        is(Movement.MOVING) {
          when(stop_moving) {
            movement := Movement.STOPPED
            when(direction === Direction.UP) {
              location := location + 1.U
            }
            when(direction === Direction.DOWN) {
              location := location - 1.U
            }
          }
        }
      }
    }
  }
  
  // Direction logic
  when(true.B) {
    switch(direction) {
      is(Direction.UP) {
        when(!button_above && !io.continue) {
          direction := Direction.DOWN
        }
      }
      is(Direction.DOWN) {
        when(!button_below && !io.continue) {
          direction := Direction.UP
        }
      }
    }
    when(location === (floor - 1).U) {
      direction := Direction.DOWN
    }
    when(location === 0.U) {
      direction := Direction.UP
    }
  }
  
  // Outputs
  io.location := location
  io.direction := direction
  io.door := door
}

class Main extends Module {
  val io = IO(new Bundle {
    // Debug outputs to preserve design
    val e1location = Output(UInt(eleLTLM1Constants.width.W))
    val e2location = Output(UInt(eleLTLM1Constants.width.W))
    val e1direction = Output(UInt())
    val e2direction = Output(UInt())
    val e1door = Output(UInt(2.W))
    val e2door = Output(UInt(2.W))
    val p = Output(Bool())
    val q = Output(Bool())
    val fair0 = Output(Bool())
    val fair1 = Output(Bool())
    val fair2 = Output(Bool())
    val fair3 = Output(Bool())
    val scc = Output(Bool())
  })
  
  import eleLTLM1Constants._
  
  val stop_next = Wire(Vec(elev + 1, Bool()))
  val inc = Wire(Vec(elev + 1, Bool()))
  val dec = Wire(Vec(elev + 1, Bool()))
  val continue = Wire(Vec(elev + 1, Bool()))
  
  val init11 = Wire(UInt(width.W))
  val init22 = Wire(UInt(width.W))
  val init1 = Wire(UInt(width.W))
  val init2 = Wire(UInt(width.W))
  
  // Simple random generators
  val rand2 = Module(new SimpleRandom(2))
  
  // Random initialization
  init11 := rand2.io.out
  init22 := rand2.io.out
  init1 := Mux(init11 === 3.U, 2.U, init11)
  init2 := Mux(init22 === 3.U, 2.U, init22)
  
  // Elevator instances
  val e1 = Module(new Elevator)
  val e2 = Module(new Elevator)
  
  // Main control
  val main_control = Module(new MainControl)
  
  // Buechi automaton
  val buechi = Module(new Buechi)
  
  // Connect elevators
  e1.io.stop_next := stop_next(1)
  e1.io.continue := continue(1)
  e1.io.init := init1
  
  e2.io.stop_next := stop_next(2)
  e2.io.continue := continue(2)
  e2.io.init := init2
  
  // Connect main control
  main_control.io.init1 := init1
  main_control.io.init2 := init2
  
  for (i <- 1 to elev) {
    stop_next(i) := main_control.io.stop_next(i)
    continue(i) := main_control.io.continue(i)
    inc(i) := e1.io.inc
    dec(i) := e1.io.dec
    when(i.U === 2.U) {
      inc(i) := e2.io.inc
      dec(i) := e2.io.dec
    }
  }
  
  // Calculate q
  val q = ((e1.io.location === 1.U) && (e1.io.door === DoorState.OPEN) && (e1.io.direction === Direction.UP)) ||
          ((e2.io.location === 1.U) && (e2.io.door === DoorState.OPEN) && (e2.io.direction === Direction.UP))
  
  // Connect Buechi
  buechi.io.q := q
  buechi.io.e1dOPENING := (e1.io.door === DoorState.OPENING)
  buechi.io.e2dOPENING := (e2.io.door === DoorState.OPENING)
  buechi.io.e2dOPEN := (e2.io.door === DoorState.OPEN)
  buechi.io.e1dOPEN := (e1.io.door === DoorState.OPEN)
  buechi.io.p := main_control.io.p
  
  // Debug outputs
  io.e1location := e1.io.location
  io.e2location := e2.io.location
  io.e1direction := e1.io.direction
  io.e2direction := e2.io.direction
  io.e1door := e1.io.door
  io.e2door := e2.io.door
  io.p := main_control.io.p
  io.q := q
  io.fair0 := buechi.io.fair0
  io.fair1 := buechi.io.fair1
  io.fair2 := buechi.io.fair2
  io.fair3 := buechi.io.fair3
  io.scc := buechi.io.scc
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}