package llmverify

import chisel3._
import chisel3.util._

// Enum constants
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

// Elevator module
class Elevator extends Module {
  val io = IO(new Bundle {
    val stop_next = Input(Bool())
    val inc = Output(Bool())
    val dec = Output(Bool())
    val continue = Input(Bool())
    val random_push = Input(UInt(5.W))
    val random = Input(Bool())
    val r_stop = Input(Bool())
    val init = Input(UInt(3.W))
    
    // Debug outputs to preserve signals
    val location_debug = Output(UInt(3.W))
    val direction_debug = Output(UInt(1.W))
    val movement_debug = Output(UInt(1.W))
    val door_debug = Output(UInt(2.W))
    val buttons_debug = Output(UInt(5.W))
  })
  
  // Internal registers
  val buttons = RegInit(VecInit(Seq.fill(5)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(DoorState.OPEN)
  val open_next = RegInit(false.B)
  
  // Combinational logic for button detection
  val bottom = Wire(Vec(5, Bool()))
  val top = Wire(Vec(4, Bool()))
  
  bottom(0) := false.B // Initialize unused element
  bottom(1) := buttons(0) === OnOff.ON
  bottom(2) := bottom(1) || (buttons(1) === OnOff.ON)
  bottom(3) := bottom(2) || (buttons(2) === OnOff.ON)
  bottom(4) := bottom(3) || (buttons(3) === OnOff.ON)
  
  top(3) := buttons(4) === OnOff.ON
  top(2) := top(3) || (buttons(3) === OnOff.ON)
  top(1) := top(2) || (buttons(2) === OnOff.ON)
  top(0) := top(1) || (buttons(1) === OnOff.ON)
  
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  button_below := 
    (location === 4.U && bottom(4)) ||
    (location === 3.U && bottom(3)) ||
    (location === 2.U && bottom(2)) ||
    (location === 1.U && bottom(1))
  
  button_above := 
    (location === 0.U && top(0)) ||
    (location === 1.U && top(1)) ||
    (location === 2.U && top(2)) ||
    (location === 3.U && top(3))
  
  // Button management logic
  when(io.stop_next) {
    when(direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Random button pushes and current floor button reset
  for (i <- 0 until 5) {
    when(i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen(io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Door scheduling logic
  when(door =/= DoorState.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Movement.MOVING && 
            (io.stop_next || 
             (direction === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
             (direction === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
  // Door state machine
  switch(door) {
    is(DoorState.CLOSED) {
      when(open_next && movement === Movement.STOPPED) {
        door := DoorState.OPENING
      }
    }
    is(DoorState.OPENING) {
      when(io.random) {
        door := DoorState.OPEN
      }
    }
    is(DoorState.OPEN) {
      when(io.random) {
        door := DoorState.CLOSING
      }
    }
    is(DoorState.CLOSING) {
      when(io.random) {
        door := DoorState.CLOSED
      }
    }
  }
  
  // Movement logic
  val start_moving = Wire(Bool())
  val stop_moving = Wire(Bool())
  
  start_moving := io.continue || 
                  (button_above && direction === Direction.UP) || 
                  (button_below && direction === Direction.DOWN)
  
  stop_moving := io.r_stop && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (direction === Direction.UP)
  io.dec := stop_moving && (direction === Direction.DOWN)
  
  // Movement state machine
  when(door === DoorState.CLOSED) {
    switch(movement) {
      is(Movement.STOPPED) {
        when(door === DoorState.CLOSED && start_moving && !open_next) {
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
  
  // Direction logic
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
  
  when(location === 4.U) {
    direction := Direction.DOWN
  }
  when(location === 0.U) {
    direction := Direction.UP
  }
  
  // Debug outputs
  io.location_debug := location
  io.direction_debug := direction
  io.movement_debug := movement
  io.door_debug := door
  io.buttons_debug := Cat(buttons.reverse)
}

// Main control module
class MainControl extends Module {
  val io = IO(new Bundle {
    val inc = Input(Bool())
    val dec = Input(Bool())
    val stop_next = Output(Bool())
    val continue = Output(Bool())
    val random_up = Input(UInt(5.W))
    val random_down = Input(UInt(5.W))
    val init1 = Input(UInt(3.W))
    
    // Debug outputs to preserve signals
    val locations_debug = Output(UInt(3.W))
    val up_buttons_debug = Output(UInt(5.W))
    val down_buttons_debug = Output(UInt(5.W))
    val direction_debug = Output(UInt(1.W))
  })
  
  // Internal registers
  val locations = RegInit(VecInit(Seq.fill(2)(io.init1))) // Index 0 unused, 1 used
  val up_floor_buttons = RegInit(VecInit(Seq.fill(5)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(5)(OnOff.OFF)))
  val direction = RegInit(VecInit(Seq.fill(2)(Direction.UP))) // Index 0 unused, 1 used
  
  // Combinational logic for button detection
  val buttons = Wire(Vec(5, Bool()))
  for (i <- 0 until 5) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  val bottom = Wire(Vec(5, Bool()))
  val top = Wire(Vec(4, Bool()))
  
  bottom(0) := false.B // Initialize unused element
  bottom(1) := buttons(0)
  bottom(2) := bottom(1) || buttons(1)
  bottom(3) := bottom(2) || buttons(2)
  bottom(4) := bottom(3) || buttons(3)
  
  top(3) := buttons(4)
  top(2) := top(3) || buttons(3)
  top(1) := top(2) || buttons(2)
  top(0) := top(1) || buttons(1)
  
  val button_above = Wire(Bool())
  val button_below = Wire(Bool())
  
  button_below := 
    ((locations(1) === 4.U) && bottom(4)) ||
    ((locations(1) === 3.U) && bottom(3)) ||
    ((locations(1) === 2.U) && bottom(2)) ||
    ((locations(1) === 1.U) && bottom(1))
  
  button_above := 
    ((locations(1) === 0.U) && top(0)) ||
    ((locations(1) === 1.U) && top(1)) ||
    ((locations(1) === 2.U) && top(2)) ||
    ((locations(1) === 3.U) && top(3))
  
  io.continue := (button_above && direction(1) === Direction.UP) ||
                 (button_below && direction(1) === Direction.DOWN)
  
  io.stop_next := Mux(locations(1) =/= 4.U && direction(1) === Direction.UP,
                     Mux(up_floor_buttons(locations(1) + 1.U) === OnOff.ON, true.B, false.B),
                     Mux(locations(1) =/= 0.U && direction(1) === Direction.DOWN,
                         Mux(down_floor_buttons(locations(1) - 1.U) === OnOff.ON, true.B, false.B),
                         false.B))
  
  // Random button pushes
  for (i <- 0 until 5) {
    when(io.random_up(i)) {
      up_floor_buttons(i) := OnOff.ON
    }
    when(io.random_down(i)) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons
  when(locations(1) =/= 4.U && direction(1) === Direction.UP) {
    when(up_floor_buttons(locations(1) + 1.U) === OnOff.ON) {
      up_floor_buttons(locations(1) + 1.U) := OnOff.OFF
    }
  }
  when(locations(1) =/= 0.U && direction(1) === Direction.DOWN) {
    when(down_floor_buttons(locations(1) - 1.U) === OnOff.ON) {
      down_floor_buttons(locations(1) - 1.U) := OnOff.OFF
    }
  }
  
  // Location and direction tracking
  when(locations(1) === 4.U) {
    direction(1) := Direction.DOWN
  }
  when(locations(1) === 0.U) {
    direction(1) := Direction.UP
  }
  
  when(io.inc) {
    locations(1) := locations(1) + 1.U
    direction(1) := Direction.UP
  }
  when(io.dec) {
    locations(1) := locations(1) - 1.U
    direction(1) := Direction.DOWN
  }
  
  // Debug outputs
  io.locations_debug := locations(1)
  io.up_buttons_debug := Cat(up_floor_buttons.reverse)
  io.down_buttons_debug := Cat(down_floor_buttons.reverse)
  io.direction_debug := direction(1)
}

// Main module
class Main extends Module {
  val io = IO(new Bundle {
    val random_up = Input(UInt(5.W))
    val random_down = Input(UInt(5.W))
    val random = Input(Bool())
    val r_stop = Input(Bool())
    val random_push1 = Input(UInt(5.W))
    val init11 = Input(UInt(3.W))
    
    // Debug outputs to preserve internal signals
    val stop_next_debug = Output(Bool())
    val inc_debug = Output(Bool())
    val dec_debug = Output(Bool())
    val continue_debug = Output(Bool())
    val init1_debug = Output(UInt(3.W))
    
    // Additional debug outputs from submodules
    val elevator_location_debug = Output(UInt(3.W))
    val elevator_direction_debug = Output(UInt(1.W))
    val elevator_movement_debug = Output(UInt(1.W))
    val elevator_door_debug = Output(UInt(2.W))
    val elevator_buttons_debug = Output(UInt(5.W))
    val control_locations_debug = Output(UInt(3.W))
    val control_up_buttons_debug = Output(UInt(5.W))
    val control_down_buttons_debug = Output(UInt(5.W))
    val control_direction_debug = Output(UInt(1.W))
  })
  
  val init1 = Wire(UInt(3.W))
  init1 := Mux(io.init11 >= 5.U, 4.U, io.init11)
  
  val stop_next = Wire(Bool())
  val inc = Wire(Bool())
  val dec = Wire(Bool())
  val continue = Wire(Bool())
  
  // Instantiate elevator
  val elevator = Module(new Elevator())
  elevator.io.stop_next := stop_next
  elevator.io.continue := continue
  elevator.io.random_push := io.random_push1
  elevator.io.random := io.random
  elevator.io.r_stop := io.r_stop
  elevator.io.init := init1
  
  inc := elevator.io.inc
  dec := elevator.io.dec
  
  // Instantiate main control
  val main_control = Module(new MainControl())
  main_control.io.inc := inc
  main_control.io.dec := dec
  main_control.io.init1 := init1
  main_control.io.random_up := io.random_up
  main_control.io.random_down := io.random_down
  
  stop_next := main_control.io.stop_next
  continue := main_control.io.continue
  
  // Debug outputs
  io.stop_next_debug := stop_next
  io.inc_debug := inc
  io.dec_debug := dec
  io.continue_debug := continue
  io.init1_debug := init1
  
  // Submodule debug outputs
  io.elevator_location_debug := elevator.io.location_debug
  io.elevator_direction_debug := elevator.io.direction_debug
  io.elevator_movement_debug := elevator.io.movement_debug
  io.elevator_door_debug := elevator.io.door_debug
  io.elevator_buttons_debug := elevator.io.buttons_debug
  io.control_locations_debug := main_control.io.locations_debug
  io.control_up_buttons_debug := main_control.io.up_buttons_debug
  io.control_down_buttons_debug := main_control.io.down_buttons_debug
  io.control_direction_debug := main_control.io.direction_debug
}

// Verilog generator
object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}