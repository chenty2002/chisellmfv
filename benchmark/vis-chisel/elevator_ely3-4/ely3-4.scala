package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object Dir {
  val UP = 0.U(1.W)
  val DOWN = 1.U(1.W)
}

object Mov {
  val STOPPED = 0.U(1.W)
  val MOVING = 1.U(1.W)
}

object Dr {
  val OPEN = 0.U(2.W)
  val OPENING = 1.U(2.W)
  val CLOSED = 2.U(2.W)
  val CLOSING = 3.U(2.W)
}

object OnOff {
  val ON = 1.U(1.W)
  val OFF = 0.U(1.W)
}

class Main extends Module {
  val io = IO(new Bundle {
    val random_up = Input(UInt(4.W))
    val random_down = Input(UInt(4.W))
    val random = Input(UInt(3.W))
    val r_stop = Input(UInt(3.W))
    val random_push1 = Input(UInt(4.W))
    val init11 = Input(UInt(2.W))
    val random_push2 = Input(UInt(4.W))
    val init22 = Input(UInt(2.W))
    val random_push3 = Input(UInt(4.W))
    val init33 = Input(UInt(2.W))
    
    // Additional outputs to preserve signals
    val stop_next_out = Output(UInt(3.W))
    val inc_out = Output(UInt(3.W))
    val dec_out = Output(UInt(3.W))
    val continue_out = Output(UInt(3.W))
  })
  
  val stop_next = Wire(UInt(3.W))
  val inc = Wire(UInt(3.W))
  val dec = Wire(UInt(3.W))
  val continue = Wire(UInt(3.W))
  
  val init1 = Wire(UInt(2.W))
  val init2 = Wire(UInt(2.W))
  val init3 = Wire(UInt(2.W))
  
  // Choose initial state for each elevator (4 <= init11 ? 3 : init11)
  init1 := Mux(io.init11 >= 4.U, 3.U, io.init11)
  init2 := Mux(io.init22 >= 4.U, 3.U, io.init22)
  init3 := Mux(io.init33 >= 4.U, 3.U, io.init33)
  
  // Instantiate elevators
  val e1 = Module(new Elevator())
  e1.io.stop_next := stop_next(0)
  e1.io.continue := continue(0)
  e1.io.random_push := io.random_push1
  e1.io.random := io.random(0)
  e1.io.r_stop := io.r_stop(0)
  e1.io.init := init1
  
  val e2 = Module(new Elevator())
  e2.io.stop_next := stop_next(1)
  e2.io.continue := continue(1)
  e2.io.random_push := io.random_push2
  e2.io.random := io.random(1)
  e2.io.r_stop := io.r_stop(1)
  e2.io.init := init2
  
  val e3 = Module(new Elevator())
  e3.io.stop_next := stop_next(2)
  e3.io.continue := continue(2)
  e3.io.random_push := io.random_push3
  e3.io.random := io.random(2)
  e3.io.r_stop := io.r_stop(2)
  e3.io.init := init3
  
  // Connect inc and dec from elevators (these are outputs from elevators)
  inc := Cat(e3.io.inc, e2.io.inc, e1.io.inc)
  dec := Cat(e3.io.dec, e2.io.dec, e1.io.dec)
  
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
  
  // Connect outputs to preserve signals
  io.stop_next_out := stop_next
  io.inc_out := inc
  io.dec_out := dec
  io.continue_out := continue
}

class MainControl extends Module {
  val io = IO(new Bundle {
    val inc = Input(UInt(3.W))
    val dec = Input(UInt(3.W))
    val stop_next = Output(UInt(3.W))
    val continue = Output(UInt(3.W))
    val random_up = Input(UInt(4.W))
    val random_down = Input(UInt(4.W))
    val init1 = Input(UInt(2.W))
    val init2 = Input(UInt(2.W))
    val init3 = Input(UInt(2.W))
  })
  
  // Positions of the cars (3 elevators, 2 bits each for 4 floors)
  val locations = RegInit(VecInit(io.init1, io.init2, io.init3))
  
  // Floor buttons (4 floors each for up and down)
  val up_floor_buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val down_floor_buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  
  // Direction for each elevator
  val direction = RegInit(VecInit(Seq.fill(3)(Dir.UP)))
  
  // Floors currently requesting pick-up
  val buttons = Wire(Vec(4, Bool()))
  for (i <- 0 until 4) {
    buttons(i) := (up_floor_buttons(i) === OnOff.ON) || (down_floor_buttons(i) === OnOff.ON)
  }
  
  // Compute bottom and top signals
  val bottom = Wire(Vec(3, Bool()))
  bottom(0) := buttons(0)
  bottom(1) := bottom(0) || buttons(1)
  bottom(2) := bottom(1) || buttons(2)
  
  val top = Wire(Vec(3, Bool()))
  top(2) := buttons(3)
  top(1) := top(2) || buttons(2)
  top(0) := top(1) || buttons(1)
  
  // Compute button_above and button_below for each elevator
  val button_above = Wire(Vec(3, Bool()))
  val button_below = Wire(Vec(3, Bool()))
  val continue_vec = Wire(Vec(3, Bool()))
  val stop_next_vec = Wire(Vec(3, Bool()))
  
  for (i <- 0 until 3) {
    val loc = locations(i)
    button_below(i) := 
      ((loc === 3.U) && bottom(2)) ||
      ((loc === 2.U) && bottom(1)) ||
      ((loc === 1.U) && bottom(0))
    
    button_above(i) :=
      ((loc === 0.U) && top(0)) ||
      ((loc === 1.U) && top(1)) ||
      ((loc === 2.U) && top(2))
    
    continue_vec(i) := (button_above(i) && direction(i) === Dir.UP) ||
                       (button_below(i) && direction(i) === Dir.DOWN)
    
    stop_next_vec(i) := Mux(loc =/= 3.U && direction(i) === Dir.UP,
                           up_floor_buttons(loc + 1.U) === OnOff.ON,
                           Mux(loc =/= 0.U && direction(i) === Dir.DOWN,
                               down_floor_buttons(loc - 1.U) === OnOff.ON,
                               false.B))
  }
  
  // Randomly push floor buttons
  for (i <- 0 until 4) {
    when(io.random_up(i)) {
      up_floor_buttons(i) := OnOff.ON
    }
    when(io.random_down(i)) {
      down_floor_buttons(i) := OnOff.ON
    }
  }
  
  // Turn off scheduled floor buttons
  for (i <- 0 until 3) {
    val loc = locations(i)
    when(loc =/= 3.U && direction(i) === Dir.UP) {
      when(up_floor_buttons(loc + 1.U) === OnOff.ON) {
        up_floor_buttons(loc + 1.U) := OnOff.OFF
      }
    }
    when(loc =/= 0.U && direction(i) === Dir.DOWN) {
      when(down_floor_buttons(loc - 1.U) === OnOff.ON) {
        down_floor_buttons(loc - 1.U) := OnOff.OFF
      }
    }
  }
  
  // Keep track of locations and directions
  for (i <- 0 until 3) {
    when(locations(i) === 3.U) {
      direction(i) := Dir.DOWN
    }
    when(locations(i) === 0.U) {
      direction(i) := Dir.UP
    }
    when(io.inc(i)) {
      locations(i) := locations(i) + 1.U
      direction(i) := Dir.UP
    }
    when(io.dec(i)) {
      locations(i) := locations(i) - 1.U
      direction(i) := Dir.DOWN
    }
  }
  
  // Connect outputs
  io.continue := Cat(continue_vec(2), continue_vec(1), continue_vec(0))
  io.stop_next := Cat(stop_next_vec(2), stop_next_vec(1), stop_next_vec(0))
}

class Elevator extends Module {
  val io = IO(new Bundle {
    val stop_next = Input(Bool())
    val inc = Output(Bool())
    val dec = Output(Bool())
    val continue = Input(Bool())
    val random_push = Input(UInt(4.W))
    val random = Input(Bool())
    val r_stop = Input(Bool())
    val init = Input(UInt(2.W))
  })
  
  // Internal state
  val buttons = RegInit(VecInit(Seq.fill(4)(OnOff.OFF)))
  val location = RegInit(io.init)
  val direction = RegInit(Dir.UP)
  val movement = RegInit(Mov.STOPPED)
  val door = RegInit(Dr.OPEN)
  val open_next = RegInit(false.B)
  
  // Compute bottom and top signals
  val bottom = Wire(Vec(3, Bool()))
  bottom(0) := buttons(0) === OnOff.ON
  bottom(1) := bottom(0) || (buttons(1) === OnOff.ON)
  bottom(2) := bottom(1) || (buttons(2) === OnOff.ON)
  
  val top = Wire(Vec(3, Bool()))
  top(2) := buttons(3) === OnOff.ON
  top(1) := top(2) || (buttons(2) === OnOff.ON)
  top(0) := top(1) || (buttons(1) === OnOff.ON)
  
  val button_below = Wire(Bool())
  val button_above = Wire(Bool())
  
  button_below :=
    (location === 3.U && bottom(2)) ||
    (location === 2.U && bottom(1)) ||
    (location === 1.U && bottom(0))
  
  button_above :=
    (location === 0.U && top(0)) ||
    (location === 1.U && top(1)) ||
    (location === 2.U && top(2))
  
  // Randomly push buttons and turn off button for current floor
  for (i <- 0 until 4) {
    when(i.U === location) {
      buttons(i) := OnOff.OFF
    }.elsewhen(io.random_push(i)) {
      buttons(i) := OnOff.ON
    }
  }
  
  // Record a request to stop at the next floor
  when(io.stop_next) {
    when(direction === Dir.UP) {
      buttons(location + 1.U) := OnOff.ON
    }.otherwise {
      buttons(location - 1.U) := OnOff.ON
    }
  }
  
  // Schedule the door to open at the next floor
  when(door =/= Dr.CLOSED) {
    open_next := false.B
  }.elsewhen(movement === Mov.MOVING &&
             (io.stop_next ||
              (direction === Dir.UP && buttons(location + 1.U) === OnOff.ON) ||
              (direction === Dir.DOWN && buttons(location - 1.U) === OnOff.ON))) {
    open_next := true.B
  }
  
  // Door operation
  switch(door) {
    is(Dr.CLOSED) {
      when(open_next && movement === Mov.STOPPED) {
        door := Dr.OPENING
      }
    }
    is(Dr.OPENING) {
      when(io.random) {
        door := Dr.OPEN
      }
    }
    is(Dr.OPEN) {
      when(io.random) {
        door := Dr.CLOSING
      }
    }
    is(Dr.CLOSING) {
      when(io.random) {
        door := Dr.CLOSED
      }
    }
  }
  
  // Movement control
  val stop_moving = Wire(Bool())
  val start_moving = Wire(Bool())
  
  start_moving := io.continue || (button_above && direction === Dir.UP) ||
                  (button_below && direction === Dir.DOWN)
  stop_moving := io.r_stop && (movement === Mov.MOVING)
  
  io.inc := stop_moving && (direction === Dir.UP)
  io.dec := stop_moving && (direction === Dir.DOWN)
  
  // Move to next floor
  when(door === Dr.CLOSED) {
    switch(movement) {
      is(Mov.STOPPED) {
        when(door === Dr.CLOSED && start_moving && !open_next) {
          movement := Mov.MOVING
        }
      }
      is(Mov.MOVING) {
        when(stop_moving) {
          movement := Mov.STOPPED
          when(direction === Dir.UP) {
            location := location + 1.U
          }
          when(direction === Dir.DOWN) {
            location := location - 1.U
          }
        }
      }
    }
  }
  
  // Determine direction of movement
  switch(direction) {
    is(Dir.UP) {
      when(!button_above && !io.continue) {
        direction := Dir.DOWN
      }
    }
    is(Dir.DOWN) {
      when(!button_below && !io.continue) {
        direction := Dir.UP
      }
    }
  }
  when(location === 3.U) {
    direction := Dir.DOWN
  }
  when(location === 0.U) {
    direction := Dir.UP
  }
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), Array("--target-dir", "generated"))
}