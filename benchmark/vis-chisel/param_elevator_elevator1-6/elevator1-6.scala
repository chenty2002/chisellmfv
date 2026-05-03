package llmverify

import chisel3._
import chisel3.util._

// Enum definitions - using object constants instead of Bundle classes
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

// Main module - Connects elevators to controller
class Main extends Module {
  val io = IO(new Bundle {
    val random_up = Input(UInt(6.W))
    val random_down = Input(UInt(6.W))
    val random = Input(UInt(1.W))
    val r_stop = Input(UInt(1.W))
    val random_push1 = Input(UInt(6.W))
    val init11 = Input(UInt(3.W))
    
    // Additional outputs for debugging/verification
    val stop_next_out = Output(UInt(1.W))
    val inc_out = Output(UInt(1.W))
    val dec_out = Output(UInt(1.W))
    val continue_out = Output(UInt(1.W))
    val location_out = Output(UInt(3.W))
  })
  
  val stop_next = Wire(UInt(1.W))
  val inc = Wire(UInt(1.W))
  val dec = Wire(UInt(1.W))
  val continue = Wire(UInt(1.W))
  
  // Choose initial state for each elevator
  val init1 = Mux(io.init11 >= 6.U, 5.U, io.init11)
  
  // Instantiate elevator
  val elevator = Module(new Elevator())
  elevator.io.stop_next := stop_next
  elevator.io.continue := continue
  elevator.io.random_push := io.random_push1
  elevator.io.random := io.random(0)
  elevator.io.r_stop := io.r_stop(0)
  elevator.io.init := init1
  
  val inc_elevator = elevator.io.inc
  val dec_elevator = elevator.io.dec
  
  // Instantiate main control
  val main_control = Module(new MainControl())
  main_control.io.inc := Cat(0.U(1.W), inc_elevator)
  main_control.io.dec := Cat(0.U(1.W), dec_elevator)
  main_control.io.random_up := io.random_up
  main_control.io.random_down := io.random_down
  main_control.io.init1 := init1
  
  stop_next := main_control.io.stop_next(1)
  inc := main_control.io.inc(1)
  dec := main_control.io.dec(1)
  continue := main_control.io.continue(1)
  
  // Connect outputs for verification
  io.stop_next_out := stop_next
  io.inc_out := inc
  io.dec_out := dec
  io.continue_out := continue
  io.location_out := elevator.io.location
}

// Main control module - Deals with floor buttons and communicates with elevator cars
class MainControl extends Module {
  val io = IO(new Bundle {
    val inc = Input(UInt(2.W))  // [1:1] in Verilog
    val dec = Input(UInt(2.W))  // [1:1] in Verilog
    val stop_next = Output(UInt(2.W))  // [1:1] in Verilog
    val continue = Output(UInt(2.W))  // [1:1] in Verilog
    val random_up = Input(UInt(6.W))
    val random_down = Input(UInt(6.W))
    val init1 = Input(UInt(3.W))
    
    // Additional outputs for verification
    val locations_out = Output(UInt(3.W))
    val up_buttons_out = Output(UInt(6.W))
    val down_buttons_out = Output(UInt(6.W))
  })
  
  // Positions of the cars (only one car in this case)
  val locations = RegInit(VecInit(Seq(io.init1)))
  
  // Floor buttons
  val up_floor_buttons = RegInit(VecInit(Seq.fill(6)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(6)(OnOff.OFF)))
  
  // Direction for each car
  val direction = RegInit(VecInit(Seq(Direction.UP)))
  
  // Compute if each floor has requests
  val buttons = Wire(Vec(6, Bool()))
  for (i <- 0 until 6) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  // Compute bottom and top signals
  val bottom = Wire(Vec(6, Bool()))
  val top = Wire(Vec(5, Bool()))
  
  bottom(0) := buttons(0)
  for (i <- 1 until 6) {
    bottom(i) := bottom(i-1) || buttons(i)
  }
  
  top(4) := buttons(5)
  for (i <- 3 to 0 by -1) {
    top(i) := top(i+1) || buttons(i+1)
  }
  
  // Check for buttons above and below current location
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  val loc = locations(0)
  button_below := 
    ((loc === 5.U) && bottom(5)) ||
    ((loc === 4.U) && bottom(4)) ||
    ((loc === 3.U) && bottom(3)) ||
    ((loc === 2.U) && bottom(2)) ||
    ((loc === 1.U) && bottom(1))
    
  button_above :=
    ((loc === 0.U) && top(0)) ||
    ((loc === 1.U) && top(1)) ||
    ((loc === 2.U) && top(2)) ||
    ((loc === 3.U) && top(3)) ||
    ((loc === 4.U) && top(4))
  
  // Schedule next pickup for each elevator - initialize all elements
  val continue_vec = WireInit(VecInit(Seq(false.B, false.B)))
  val stop_next_vec = WireInit(VecInit(Seq(false.B, false.B)))
  
  continue_vec(1) := (button_above && (direction(0) === Direction.UP)) ||
                     (button_below && (direction(0) === Direction.DOWN))
                     
  stop_next_vec(1) := Mux((loc =/= 5.U) && (direction(0) === Direction.UP),
                         up_floor_buttons(loc + 1.U) === OnOff.ON,
                         Mux((loc =/= 0.U) && (direction(0) === Direction.DOWN),
                             down_floor_buttons(loc - 1.U) === OnOff.ON,
                             false.B))
  
  io.continue := Cat(continue_vec(0), continue_vec(1))
  io.stop_next := Cat(stop_next_vec(0), stop_next_vec(1))
  
  // Randomly push floor buttons
  for (i <- 0 until 6) {
    when (io.random_up(i)) {
      up_floor_buttons(i) := OnOff.ON
    }
    when (io.random_down(i)) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons
  when ((loc =/= 5.U) && (direction(0) === Direction.UP)) {
    when (up_floor_buttons(loc + 1.U) === OnOff.ON) {
      up_floor_buttons(loc + 1.U) := OnOff.OFF
    }
  }
  when ((loc =/= 0.U) && (direction(0) === Direction.DOWN)) {
    when (down_floor_buttons(loc - 1.U) === OnOff.ON) {
      down_floor_buttons(loc - 1.U) := OnOff.OFF
    }
  }
  
  // Keep track of locations and directions
  when (loc === 5.U) {
    direction(0) := Direction.DOWN
  }
  when (loc === 0.U) {
    direction(0) := Direction.UP
  }
  when (io.inc(1)) {
    locations(0) := loc + 1.U
    direction(0) := Direction.UP
  }
  when (io.dec(1)) {
    locations(0) := loc - 1.U
    direction(0) := Direction.DOWN
  }
  
  // Connect outputs for verification
  io.locations_out := locations(0)
  io.up_buttons_out := Cat(up_floor_buttons.reverse)
  io.down_buttons_out := Cat(down_floor_buttons.reverse)
}

// Elevator module - Individual elevator car
class Elevator extends Module {
  val io = IO(new Bundle {
    val stop_next = Input(Bool())
    val inc = Output(Bool())
    val dec = Output(Bool())
    val continue = Input(Bool())
    val random_push = Input(UInt(6.W))
    val random = Input(Bool())
    val r_stop = Input(Bool())
    val init = Input(UInt(3.W))
    
    // Additional outputs for verification
    val location = Output(UInt(3.W))
    val direction_out = Output(UInt(1.W))
    val movement_out = Output(UInt(1.W))
    val door_out = Output(UInt(2.W))
    val buttons_out = Output(UInt(6.W))
  })
  
  // Internal state
  val buttons = RegInit(VecInit(Seq.fill(6)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(Door.OPEN)
  val open_next = RegInit(false.B)
  
  // Compute bottom and top signals for buttons
  val bottom = Wire(Vec(6, Bool()))
  val top = Wire(Vec(5, Bool()))
  
  bottom(0) := buttons(0) === OnOff.ON
  for (i <- 1 until 6) {
    bottom(i) := bottom(i-1) || (buttons(i) === OnOff.ON)
  }
  
  top(4) := buttons(5) === OnOff.ON
  for (i <- 3 to 0 by -1) {
    top(i) := top(i+1) || (buttons(i+1) === OnOff.ON)
  }
  
  // Check for buttons above and below
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  button_below := 
    (location === 5.U && bottom(5)) ||
    (location === 4.U && bottom(4)) ||
    (location === 3.U && bottom(3)) ||
    (location === 2.U && bottom(2)) ||
    (location === 1.U && bottom(1))
    
  button_above :=
    (location === 0.U && top(0)) ||
    (location === 1.U && top(1)) ||
    (location === 2.U && top(2)) ||
    (location === 3.U && top(3)) ||
    (location === 4.U && top(4))
  
  // Randomly push buttons, but turn off button for current floor
  for (i <- 0 until 6) {
    when (i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen (io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Record request to stop at next floor
  when (io.stop_next) {
    when (direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule door to open at next floor
  when (door =/= Door.CLOSED) {
    open_next := false.B
  }.elsewhen ((movement === Movement.MOVING) &&
              (io.stop_next ||
               (direction === Direction.UP && (buttons(location + 1.U) === OnOff.ON)) ||
               (direction === Direction.DOWN && (buttons(location - 1.U) === OnOff.ON)))) {
    open_next := true.B
  }
  
  // Door operation
  switch (door) {
    is (Door.CLOSED) {
      when (open_next && (movement === Movement.STOPPED)) {
        door := Door.OPENING
      }
    }
    is (Door.OPENING) {
      when (io.random) {
        door := Door.OPEN
      }
    }
    is (Door.OPEN) {
      when (io.random) {
        door := Door.CLOSING
      }
    }
    is (Door.CLOSING) {
      when (io.random) {
        door := Door.CLOSED
      }
    }
  }
  
  // Movement control
  val start_moving = Wire(Bool())
  val stop_moving = Wire(Bool())
  
  start_moving := io.continue ||
                  (button_above && (direction === Direction.UP)) ||
                  (button_below && (direction === Direction.DOWN))
                  
  stop_moving := io.r_stop && (movement === Movement.MOVING)
  
  io.inc := stop_moving && (direction === Direction.UP)
  io.dec := stop_moving && (direction === Direction.DOWN)
  
  // Update movement and location
  when (door === Door.CLOSED) {
    switch (movement) {
      is (Movement.STOPPED) {
        when ((door === Door.CLOSED) && start_moving && !open_next) {
          movement := Movement.MOVING
        }
      }
      is (Movement.MOVING) {
        when (stop_moving) {
          movement := Movement.STOPPED
          when (direction === Direction.UP) {
            location := location + 1.U
          }
          when (direction === Direction.DOWN) {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Determine direction of movement
  switch (direction) {
    is (Direction.UP) {
      when (!button_above && !io.continue) {
        direction := Direction.DOWN
      }
    }
    is (Direction.DOWN) {
      when (!button_below && !io.continue) {
        direction := Direction.UP
      }
    }
  }
  
  when (location === 5.U) {
    direction := Direction.DOWN
  }
  when (location === 0.U) {
    direction := Direction.UP
  }
  
  // Connect outputs for verification
  io.location := location
  io.direction_out := direction
  io.movement_out := movement
  io.door_out := door
  io.buttons_out := Cat(buttons.reverse)
}

// Object to generate Verilog - renamed to match expected name
object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}