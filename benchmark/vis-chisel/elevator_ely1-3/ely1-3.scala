package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
class Direction extends Bundle {
  val value = UInt(1.W)
}
object Direction {
  val UP = 0.U
  val DOWN = 1.U
}

class Movement extends Bundle {
  val value = UInt(1.W)
}
object Movement {
  val STOPPED = 0.U
  val MOVING = 1.U
}

class DoorState extends Bundle {
  val value = UInt(2.W)
}
object DoorState {
  val OPEN = 0.U
  val OPENING = 1.U
  val CLOSED = 2.U
  val CLOSING = 3.U
}

class OnOff extends Bundle {
  val value = UInt(1.W)
}
object OnOff {
  val ON = 1.U
  val OFF = 0.U
}

//*****************************************************************************
// Connect the elevators to the controller and choose a random initial
// floor for each elevator car.
//*****************************************************************************
class Main extends Module {
  val io = IO(new Bundle {
    val random_up = Input(UInt(3.W))
    val random_down = Input(UInt(3.W))
    val random = Input(UInt(1.W))
    val r_stop = Input(UInt(1.W))
    val random_push1 = Input(UInt(3.W))
    val init11 = Input(UInt(2.W))
  })
  
  val init1 = Wire(UInt(2.W))
  
  // Choose initial state for each elevator
  init1 := Mux(io.init11 >= 3.U, 2.U, io.init11)
  
  val e1 = Module(new Elevator())
  e1.io.random_push := io.random_push1
  e1.io.random := io.random
  e1.io.r_stop := io.r_stop
  e1.io.init := init1
  
  val main_control = Module(new MainControl())
  main_control.io.inc := e1.io.inc
  main_control.io.dec := e1.io.dec
  main_control.io.random_up := io.random_up
  main_control.io.random_down := io.random_down
  main_control.io.init1 := init1
  
  // Connect control signals back to elevator
  e1.io.stop_next := main_control.io.stop_next
  e1.io.continue := main_control.io.continue
}

//*****************************************************************************
// Deal with floor buttons and communicate with elevator cars.
// This module receives one bit of inc and dec from each car, and controls
// each car via one bit of stop_next and continue.  It keeps track of the
// car positions, which is redundant because each car stores its own position.
//*****************************************************************************
class MainControl extends Module {
  val io = IO(new Bundle {
    val inc = Input(UInt(1.W))
    val dec = Input(UInt(1.W))
    val stop_next = Output(UInt(1.W))
    val continue = Output(UInt(1.W))
    val random_up = Input(UInt(3.W)) // nondeterministic requests to go up
    val random_down = Input(UInt(3.W)) // nondeterministic requests to go down
    val init1 = Input(UInt(2.W)) // initial position of elevator cars
  })
  
  val locations = RegInit(VecInit(Seq.fill(2)(0.U(2.W)))) // positions of the cars
  val up_floor_buttons = RegInit(VecInit(Seq.fill(3)(0.U(1.W)))) // up b. at floors
  val down_floor_buttons = RegInit(VecInit(Seq.fill(3)(0.U(1.W)))) // down b. at floors
  val direction = RegInit(VecInit(Seq.fill(2)(Direction.UP)))
  
  // Initialize
  locations(1) := io.init1
  
  // Compute if each elevator should continue in the same direction.
  val buttons = Wire(Vec(3, UInt(1.W)))
  for (i <- 0 until 3) {
    buttons(i) := Mux(up_floor_buttons(i) === OnOff.ON | down_floor_buttons(i) === OnOff.ON, 1.U, 0.U)
  }
  
  val bottom = Wire(Vec(3, UInt(1.W)))
  val top = Wire(Vec(3, UInt(1.W)))
  
  // Initialize all elements of the vectors
  bottom(0) := 0.U
  bottom(1) := buttons(0)
  bottom(2) := bottom(1) | buttons(1)
  
  top(0) := top(1) | buttons(1)
  top(1) := buttons(2)
  top(2) := 0.U
  
  // Schedule the next pickup for each elevator car.
  val button_below = Wire(UInt(1.W))
  val button_above = Wire(UInt(1.W))
  
  button_below := Mux((locations(1) === 2.U) & (bottom(2) =/= 0.U), 1.U, 0.U) |
                   Mux((locations(1) === 1.U) & (bottom(1) =/= 0.U), 1.U, 0.U)
  button_above := Mux((locations(1) === 0.U) & (top(0) =/= 0.U), 1.U, 0.U) |
                   Mux((locations(1) === 1.U) & (top(1) =/= 0.U), 1.U, 0.U)
  
  io.continue := Mux((button_above =/= 0.U) & (direction(1) === Direction.UP), 1.U, 0.U) |
                  Mux((button_below =/= 0.U) & (direction(1) === Direction.DOWN), 1.U, 0.U)
  
  io.stop_next := Mux(locations(1) =/= 2.U & direction(1) === Direction.UP,
                      Mux(up_floor_buttons(locations(1) + 1.U) === OnOff.ON, 1.U, 0.U),
                      Mux(locations(1) =/= 0.U & direction(1) === Direction.DOWN,
                          Mux(down_floor_buttons(locations(1) - 1.U) === OnOff.ON, 1.U, 0.U),
                          0.U))
  
  // Randomly push floor buttons.
  for (i <- 0 until 3) {
    when(io.random_up(i)) {
      up_floor_buttons(i) := OnOff.ON
    }
    when(io.random_down(i)) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons.
  when(locations(1) =/= 2.U & direction(1) === Direction.UP) {
    when(up_floor_buttons(locations(1) + 1.U) === OnOff.ON) {
      up_floor_buttons(locations(1) + 1.U) := OnOff.OFF
    }
  }
  when(locations(1) =/= 0.U & direction(1) === Direction.DOWN) {
    when(down_floor_buttons(locations(1) - 1.U) === OnOff.ON) {
      down_floor_buttons(locations(1) - 1.U) := OnOff.OFF
    }
  }
  
  // Keep track of locations and directions.
  when(locations(1) === 2.U) {
    direction(1) := Direction.DOWN
  }
  when(locations(1) === 0.U) {
    direction(1) := Direction.UP
  }
  when(io.inc =/= 0.U) {
    locations(1) := locations(1) + 1.U
    direction(1) := Direction.UP
  }
  when(io.dec =/= 0.U) {
    locations(1) := locations(1) - 1.U
    direction(1) := Direction.DOWN
  }
}

//*****************************************************************************
// Elevator module
//*****************************************************************************
class Elevator extends Module {
  val io = IO(new Bundle {
    val stop_next = Input(UInt(1.W))
    val continue = Input(UInt(1.W))
    val random_push = Input(UInt(3.W))
    val random = Input(UInt(1.W))
    val r_stop = Input(UInt(1.W))
    val init = Input(UInt(2.W))
    val inc = Output(UInt(1.W))
    val dec = Output(UInt(1.W))
  })
  
  val buttons = RegInit(VecInit(Seq.fill(3)(0.U(1.W))))
  val location = RegInit(0.U(2.W))
  val direction = RegInit(Direction.UP)
  val movement = RegInit(Movement.STOPPED)
  val door = RegInit(DoorState.OPEN)
  val open_next = RegInit(0.U(1.W))
  
  // Initialize
  location := io.init
  
  val button_above = Wire(UInt(1.W))
  val button_below = Wire(UInt(1.W))
  
  button_below := Mux((location === 2.U) & ((buttons(1) === OnOff.ON) | (buttons(0) === OnOff.ON)), 1.U, 0.U) |
                   Mux((location === 1.U) & (buttons(0) === OnOff.ON), 1.U, 0.U)
  button_above := Mux((location === 0.U) & ((buttons(2) === OnOff.ON) | (buttons(1) === OnOff.ON)), 1.U, 0.U) |
                   Mux((location === 1.U) & (buttons(2) === OnOff.ON), 1.U, 0.U)
  
  // Randomly push buttons. But when door is open turn button off for that floor.
  for (i <- 0 until 3) {
    when(i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen(io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Record a request to stop at the next floor.
  when(io.stop_next =/= 0.U) {
    when(direction === Direction.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule the door to open at the next floor.
  when(door =/= DoorState.CLOSED) {
    open_next := 0.U
  }.elsewhen(movement === Movement.MOVING &
             ((io.stop_next =/= 0.U) |
              (direction === Direction.UP & buttons(location + 1.U) === OnOff.ON) |
              (direction === Direction.DOWN & buttons(location - 1.U) === OnOff.ON))) {
    open_next := 1.U
  }
  
  // Door operation
  switch(door) {
    is(DoorState.CLOSED) {
      when(open_next =/= 0.U & movement === Movement.STOPPED) {
        door := DoorState.OPENING
      }
    }
    is(DoorState.OPENING) {
      when(io.random =/= 0.U) {
        door := DoorState.OPEN
      }
    }
    is(DoorState.OPEN) {
      when(io.random =/= 0.U) {
        door := DoorState.CLOSING
      }
    }
    is(DoorState.CLOSING) {
      when(io.random =/= 0.U) {
        door := DoorState.CLOSED
      }
    }
  }
  
  // Move to next floor
  val stop_moving = Wire(UInt(1.W))
  val start_moving = Wire(UInt(1.W))
  
  start_moving := io.continue |
                  (button_above & direction === Direction.UP) |
                  (button_below & direction === Direction.DOWN)
  stop_moving := io.r_stop & (movement === Movement.MOVING)
  
  io.inc := stop_moving & (direction === Direction.UP)
  io.dec := stop_moving & (direction === Direction.DOWN)
  
  when(door === DoorState.CLOSED) {
    switch(movement) {
      is(Movement.STOPPED) {
        when(door === DoorState.CLOSED & start_moving =/= 0.U & open_next === 0.U) {
          movement := Movement.MOVING
        }
      }
      is(Movement.MOVING) {
        when(stop_moving =/= 0.U) {
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
  
  // Determine direction of movement
  switch(direction) {
    is(Direction.UP) {
      when((button_above === 0.U) & (io.continue === 0.U)) {
        direction := Direction.DOWN
      }
    }
    is(Direction.DOWN) {
      when((button_below === 0.U) & (io.continue === 0.U)) {
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
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}