package llmverify

import chisel3._
import chisel3.util._

// Enum definitions using object constants
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
    val random_push = Input(UInt(3.W))
    val random = Input(Bool())
    val r_stop = Input(Bool())
    val init = Input(UInt(2.W))
    
    // Debug outputs to preserve signals
    val location_debug = Output(UInt(2.W))
    val direction_debug = Output(UInt(1.W))
    val movement_debug = Output(UInt(1.W))
    val door_debug = Output(UInt(2.W))
    val buttons_debug = Output(UInt(3.W))
  })
  
  // Registers
  val buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(DoorState.OPEN)
  val open_next = RegInit(false.B)
  
  // Combinational logic
  val bottom = Wire(Vec(3, Bool()))
  val top = Wire(Vec(3, Bool()))
  
  bottom(0) := buttons(0) === OnOff.ON
  bottom(1) := bottom(0) || (buttons(1) === OnOff.ON)
  bottom(2) := bottom(1) || (buttons(2) === OnOff.ON)
  
  top(2) := buttons(2) === OnOff.ON
  top(1) := top(2) || (buttons(1) === OnOff.ON)
  top(0) := top(1) || (buttons(0) === OnOff.ON)
  
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  button_below := (location === 2.U && bottom(2)) || (location === 1.U && bottom(1))
  button_above := (location === 0.U && top(0)) || (location === 2.U && top(2))
  
  // Button handling
  when(io.stop_next) {
    when(direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Random button pushes and turn off current floor button
  for (i <- 0 until 3) {
    when(i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen(io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Door state machine
  when(door =/= DoorState.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Movement.MOVING && 
            (io.stop_next || 
             (direction === Direction.UP && buttons(location + 1.U) === OnOff.ON) ||
             (direction === Direction.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
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
  
  // Movement control
  val start_moving = Wire(Bool())
  val stop_moving = Wire(Bool())
  
  start_moving := io.continue || 
                  (button_above && direction === Direction.UP) || 
                  (button_below && direction === Direction.DOWN)
  stop_moving := io.r_stop && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (direction === Direction.UP)
  io.dec := stop_moving && (direction === Direction.DOWN)
  
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
  io.location_debug := location
  io.direction_debug := direction
  io.movement_debug := movement
  io.door_debug := door
  io.buttons_debug := Cat(buttons(2), buttons(1), buttons(0))
}

// Main control module
class MainControl extends Module {
  val io = IO(new Bundle {
    val inc = Input(Bool())
    val dec = Input(Bool())
    val stop_next = Output(Bool())
    val continue = Output(Bool())
    val random_up = Input(UInt(3.W))
    val random_down = Input(UInt(3.W))
    val init1 = Input(UInt(2.W))
    
    // Debug outputs
    val locations_debug = Output(UInt(2.W))
    val up_buttons_debug = Output(UInt(3.W))
    val down_buttons_debug = Output(UInt(3.W))
    val direction_debug = Output(UInt(1.W))
  })
  
  // Registers
  val locations = RegInit(io.init1)
  val up_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(3)(OnOff.OFF)))
  val direction = RegInit(Direction.UP)
  
  // Combinational logic
  val buttons = Wire(Vec(3, Bool()))
  for (i <- 0 until 3) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  val bottom = Wire(Vec(3, Bool()))
  val top = Wire(Vec(3, Bool()))
  
  bottom(0) := buttons(0)
  bottom(1) := bottom(0) || buttons(1)
  bottom(2) := bottom(1) || buttons(2)
  
  top(2) := buttons(2)
  top(1) := top(2) || buttons(1)
  top(0) := top(1) || buttons(0)
  
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  button_below := ((locations === 2.U) && bottom(2)) || (locations === 1.U && bottom(1))
  button_above := ((locations === 0.U) && top(0)) || ((locations === 2.U) && top(2))
  
  io.continue := (button_above && direction === Direction.UP) ||
                 (button_below && direction === Direction.DOWN)
  
  io.stop_next := Mux((locations =/= 2.U) && (direction === Direction.UP),
                      Mux(up_floor_buttons(locations + 1.U) === OnOff.ON, true.B, false.B),
                      Mux((locations =/= 0.U) && (direction === Direction.DOWN),
                          Mux(down_floor_buttons(locations - 1.U) === OnOff.ON, true.B, false.B),
                          false.B))
  
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
  when((locations =/= 2.U) && (direction === Direction.UP)) {
    when(up_floor_buttons(locations + 1.U) === OnOff.ON) {
      up_floor_buttons(locations + 1.U) := OnOff.OFF
    }
  }
  when((locations =/= 0.U) && (direction === Direction.DOWN)) {
    when(down_floor_buttons(locations - 1.U) === OnOff.ON) {
      down_floor_buttons(locations - 1.U) := OnOff.OFF
    }
  }
  
  // Update locations and directions
  when(locations === 2.U) {
    direction := Direction.DOWN
  }
  when(locations === 0.U) {
    direction := Direction.UP
  }
  when(io.inc) {
    locations := locations + 1.U
    direction := Direction.UP
  }
  when(io.dec) {
    locations := locations - 1.U
    direction := Direction.DOWN
  }
  
  // Debug outputs
  io.locations_debug := locations
  io.up_buttons_debug := Cat(up_floor_buttons(2), up_floor_buttons(1), up_floor_buttons(0))
  io.down_buttons_debug := Cat(down_floor_buttons(2), down_floor_buttons(1), down_floor_buttons(0))
  io.direction_debug := direction
}

// Main module
class Main extends Module {
  val io = IO(new Bundle {
    val random_up = Input(UInt(3.W))
    val random_down = Input(UInt(3.W))
    val random = Input(Bool())
    val r_stop = Input(Bool())
    val random_push1 = Input(UInt(3.W))
    val init11 = Input(UInt(2.W))
    
    // Debug outputs to preserve internal signals
    val stop_next_debug = Output(Bool())
    val inc_debug = Output(Bool())
    val dec_debug = Output(Bool())
    val continue_debug = Output(Bool())
    val init1_debug = Output(UInt(2.W))
    
    // Additional debug outputs from submodules
    val elevator_location = Output(UInt(2.W))
    val elevator_direction = Output(UInt(1.W))
    val elevator_movement = Output(UInt(1.W))
    val elevator_door = Output(UInt(2.W))
    val elevator_buttons = Output(UInt(3.W))
    val control_location = Output(UInt(2.W))
    val control_up_buttons = Output(UInt(3.W))
    val control_down_buttons = Output(UInt(3.W))
    val control_direction = Output(UInt(1.W))
  })
  
  // Wires
  val stop_next = Wire(Bool())
  val inc = Wire(Bool())
  val dec = Wire(Bool())
  val continue = Wire(Bool())
  
  // Choose initial state for each elevator
  val init1 = Wire(UInt(2.W))
  init1 := Mux(3.U <= io.init11, 2.U, io.init11)
  
  // Instantiate elevator
  val e1 = Module(new Elevator())
  e1.io.stop_next := stop_next
  e1.io.continue := continue
  e1.io.random_push := io.random_push1
  e1.io.random := io.random
  e1.io.r_stop := io.r_stop
  e1.io.init := init1
  
  inc := e1.io.inc
  dec := e1.io.dec
  
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
  
  // Additional debug outputs from submodules
  io.elevator_location := e1.io.location_debug
  io.elevator_direction := e1.io.direction_debug
  io.elevator_movement := e1.io.movement_debug
  io.elevator_door := e1.io.door_debug
  io.elevator_buttons := e1.io.buttons_debug
  io.control_location := main_control.io.locations_debug
  io.control_up_buttons := main_control.io.up_buttons_debug
  io.control_down_buttons := main_control.io.down_buttons_debug
  io.control_direction := main_control.io.direction_debug
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}