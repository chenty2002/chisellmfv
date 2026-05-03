package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object Direction {
  val UP = 0.U(1.W)
  val DOWN = 1.U(1.W)
}

object Movement {
  val STOPPED = 0.U(1.W)
  val MOVING = 1.U(1.W)
}

object Door {
  val OPEN = 0.U(2.W)
  val OPENING = 1.U(2.W)
  val CLOSED = 2.U(2.W)
  val CLOSING = 3.U(2.W)
}

object OnOff {
  val ON = 1.U(1.W)
  val OFF = 0.U(1.W)
}

class ElevatorIO extends Bundle {
  val stop_next = Input(Bool())
  val inc = Output(Bool())
  val dec = Output(Bool())
  val continue = Input(Bool())
  val random_push = Input(UInt(3.W))
  val random = Input(Bool())
  val r_stop = Input(Bool())
  val init = Input(UInt(2.W))
  // Debug outputs to preserve signals
  val location_out = Output(UInt(2.W))
  val direction_out = Output(UInt(1.W))
  val movement_out = Output(UInt(1.W))
  val door_out = Output(UInt(2.W))
}

class Elevator extends Module {
  val io = IO(new ElevatorIO())
  
  // Internal registers
  val buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(Door.OPEN)
  val open_next = RegInit(false.B)
  
  // Combinational logic for button_above and button_below
  // Original Verilog: wire [1:3-1] bottom; wire [0:3-2] top;
  // With 3 floors: bottom[1:2], top[0:1] -> 2 elements each
  val bottom = Wire(Vec(2, Bool()))
  val top = Wire(Vec(2, Bool()))
  
  // Original: assign bottom[1] = buttons[0]==ON;
  // Original: assign bottom[2] = bottom[1] || buttons[1]==ON;
  bottom(0) := buttons(0) === OnOff.ON  // bottom[1] in Verilog
  bottom(1) := bottom(0) || (buttons(1) === OnOff.ON)  // bottom[2] in Verilog
  
  // Original: assign top[3-2] = buttons[3-1]==ON; -> top[1] = buttons[2]==ON
  // Original: assign top[0] = top[1] || buttons[1]==ON;
  top(1) := buttons(2) === OnOff.ON  // top[1] in Verilog
  top(0) := top(1) || (buttons(1) === OnOff.ON)  // top[0] in Verilog
  
  // Original: assign button_below = (location==2 && bottom[2]) || (location==1 && bottom[1]);
  val button_below = (location === 2.U && bottom(1)) || (location === 1.U && bottom(0))
  
  // Original: assign button_above = (location==0 && top[0]) || (location==3-2 && top[3-2]);
  val button_above = (location === 0.U && top(0)) || (location === 1.U && top(1))
  
  // Button management - combine random pushes and turning off current floor
  for (i <- 0 until 3) {
    when(i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen(io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Record stop_next request
  when(io.stop_next) {
    when(direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule door to open
  when(door =/= Door.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Movement.MOVING && 
            (io.stop_next || 
             (direction === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
             (direction === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
  // Door operation
  switch(door) {
    is(Door.CLOSED) {
      when(open_next && movement === Movement.STOPPED) {
        door := Door.OPENING
      }
    }
    is(Door.OPENING) {
      when(io.random) {
        door := Door.OPEN
      }
    }
    is(Door.OPEN) {
      when(io.random) {
        door := Door.CLOSING
      }
    }
    is(Door.CLOSING) {
      when(io.random) {
        door := Door.CLOSED
      }
    }
  }
  
  // Movement control
  val start_moving = io.continue || 
                     (button_above && direction === Direction.UP) || 
                     (button_below && direction === Direction.DOWN)
  val stop_moving = io.r_stop && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (direction === Direction.UP)
  io.dec := stop_moving && (direction === Direction.DOWN)
  
  when(door === Door.CLOSED) {
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
          }.otherwise {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Direction control
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
  
  when(location === 2.U) {
    direction := Direction.DOWN
  }
  when(location === 0.U) {
    direction := Direction.UP
  }
  
  // Debug outputs
  io.location_out := location
  io.direction_out := direction
  io.movement_out := movement
  io.door_out := door
}

class MainControlIO extends Bundle {
  val inc = Input(Vec(3, Bool()))
  val dec = Input(Vec(3, Bool()))
  val stop_next = Output(Vec(3, Bool()))
  val continue = Output(Vec(3, Bool()))
  val random_up = Input(UInt(3.W))
  val random_down = Input(UInt(3.W))
  val init1 = Input(UInt(2.W))
  val init2 = Input(UInt(2.W))
  val init3 = Input(UInt(2.W))
  // Debug outputs
  val locations_out = Output(Vec(3, UInt(2.W)))
  val up_buttons_out = Output(Vec(3, UInt(1.W)))
  val down_buttons_out = Output(Vec(3, UInt(1.W)))
}

class MainControl extends Module {
  val io = IO(new MainControlIO())
  
  // Internal registers
  val locations = RegInit(VecInit(io.init1, io.init2, io.init3))
  val up_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val direction = RegInit(VecInit(Seq.fill(3)(Direction.UP)))
  
  // Combinational logic
  val buttons = Wire(Vec(3, Bool()))
  for (i <- 0 until 3) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  // Original Verilog: wire [1:3-1] bottom; wire [0:3-2] top;
  // With 3 floors: bottom[1:2], top[0:1] -> 2 elements each
  val bottom = Wire(Vec(2, Bool()))
  val top = Wire(Vec(2, Bool()))
  
  // Original: assign bottom[1] = buttons[0];
  // Original: assign bottom[2] = bottom[1] || buttons[1];
  bottom(0) := buttons(0)  // bottom[1] in Verilog
  bottom(1) := bottom(0) || buttons(1)  // bottom[2] in Verilog
  
  // Original: assign top[3-2] = buttons[3-1]; -> top[1] = buttons[2]
  // Original: assign top[0] = top[1] || buttons[1];
  top(1) := buttons(2)  // top[1] in Verilog
  top(0) := top(1) || buttons(1)  // top[0] in Verilog
  
  // Compute button_above and button_below for each elevator
  val button_below = Wire(Vec(3, Bool()))
  val button_above = Wire(Vec(3, Bool()))
  
  for (i <- 0 until 3) {
    // Original: assign button_below[i] = ((locations[i]==2) && bottom[2]) || (locations[i]==1 && bottom[1]);
    button_below(i) := (locations(i) === 2.U && bottom(1)) || (locations(i) === 1.U && bottom(0))
    
    // Original: assign button_above[i] = ((locations[i]==0) && top[0]) || ((locations[i]==3-2) && top[3-2]);
    button_above(i) := (locations(i) === 0.U && top(0)) || (locations(i) === 1.U && top(1))
    
    io.continue(i) := (button_above(i) && direction(i) === Direction.UP) ||
                      (button_below(i) && direction(i) === Direction.DOWN)
    
    io.stop_next(i) := Mux(locations(i) =/= 2.U && direction(i) === Direction.UP,
                          up_floor_buttons(locations(i) + 1.U) === OnOff.ON,
                          Mux(locations(i) =/= 0.U && direction(i) === Direction.DOWN,
                              down_floor_buttons(locations(i) - 1.U) === OnOff.ON,
                              false.B))
  }
  
  // Random button pushes
  for (i <- 0 until 3) {
    when(io.random_up(i)) {
      up_floor_buttons(i) := OnOff.ON
    }
    when(io.random_down(i)) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons
  for (i <- 0 until 3) {
    when(locations(i) =/= 2.U && direction(i) === Direction.UP) {
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
  for (i <- 0 until 3) {
    when(locations(i) === 2.U) {
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
  
  // Debug outputs
  io.locations_out := locations
  io.up_buttons_out := up_floor_buttons
  io.down_buttons_out := down_floor_buttons
}

class MainIO extends Bundle {
  val random_up = Input(UInt(3.W))
  val random_down = Input(UInt(3.W))
  val random = Input(UInt(3.W))
  val r_stop = Input(UInt(3.W))
  val random_push1 = Input(UInt(3.W))
  val init11 = Input(UInt(2.W))
  val random_push2 = Input(UInt(3.W))
  val init22 = Input(UInt(2.W))
  val random_push3 = Input(UInt(3.W))
  val init33 = Input(UInt(2.W))
  // Debug outputs to preserve all internal signals
  val e1_location = Output(UInt(2.W))
  val e2_location = Output(UInt(2.W))
  val e3_location = Output(UInt(2.W))
  val mc_locations = Output(Vec(3, UInt(2.W)))
  val up_buttons = Output(Vec(3, UInt(1.W)))
  val down_buttons = Output(Vec(3, UInt(1.W)))
}

class Main extends Module {
  val io = IO(new MainIO())
  
  // Choose initial state for each elevator
  // Original: assign init1 = 3 <= init11 ? 2 : init11;
  val init1 = Mux(io.init11 >= 3.U, 2.U, io.init11)
  val init2 = Mux(io.init22 >= 3.U, 2.U, io.init22)
  val init3 = Mux(io.init33 >= 3.U, 2.U, io.init33)
  
  // Wires for connections
  val stop_next = Wire(Vec(3, Bool()))
  val inc = Wire(Vec(3, Bool()))
  val dec = Wire(Vec(3, Bool()))
  val continue = Wire(Vec(3, Bool()))
  
  // Instantiate elevators
  val e1 = Module(new Elevator())
  e1.io.stop_next := stop_next(0)
  e1.io.continue := continue(0)
  e1.io.random_push := io.random_push1
  e1.io.random := io.random(0)
  e1.io.r_stop := io.r_stop(0)
  e1.io.init := init1
  inc(0) := e1.io.inc
  dec(0) := e1.io.dec
  
  val e2 = Module(new Elevator())
  e2.io.stop_next := stop_next(1)
  e2.io.continue := continue(1)
  e2.io.random_push := io.random_push2
  e2.io.random := io.random(1)
  e2.io.r_stop := io.r_stop(1)
  e2.io.init := init2
  inc(1) := e2.io.inc
  dec(1) := e2.io.dec
  
  val e3 = Module(new Elevator())
  e3.io.stop_next := stop_next(2)
  e3.io.continue := continue(2)
  e3.io.random_push := io.random_push3
  e3.io.random := io.random(2)
  e3.io.r_stop := io.r_stop(2)
  e3.io.init := init3
  inc(2) := e3.io.inc
  dec(2) := e3.io.dec
  
  // Instantiate main control
  val main_control = Module(new MainControl())
  main_control.io.inc := inc
  main_control.io.dec := dec
  stop_next := main_control.io.stop_next
  continue := main_control.io.continue
  main_control.io.random_up := io.random_up
  main_control.io.random_down := io.random_down
  main_control.io.init1 := init1
  main_control.io.init2 := init2
  main_control.io.init3 := init3
  
  // Debug outputs
  io.e1_location := e1.io.location_out
  io.e2_location := e2.io.location_out
  io.e3_location := e3.io.location_out
  io.mc_locations := main_control.io.locations_out
  io.up_buttons := main_control.io.up_buttons_out
  io.down_buttons := main_control.io.down_buttons_out
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}