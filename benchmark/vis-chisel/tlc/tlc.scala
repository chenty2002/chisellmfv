package llmverify

import chisel3._
import chisel3.util._

// Enums for the traffic light controller
object BooleanEnum extends ChiselEnum {
  val YES, NO = Value
}

object TimerState extends ChiselEnum {
  val START, SHORT, LONG = Value
}

object Color extends ChiselEnum {
  val GREEN, YELLOW, RED = Value
}

/*
 * There is a single, coupled sensor that detects the presence of a car
 * in either direction of the farm road.  At each clock tick, it non-
 * deterministically reports that a car is present or not.
 */
class Sensor extends Module {
  val io = IO(new Bundle {
    val car_present = Output(BooleanEnum())
  })
  
  // Use a simple LFSR for pseudo-random behavior
  val lfsr = RegInit(1.U(8.W))
  val rand_choice = lfsr(0)
  
  // Update LFSR
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  
  // Initialize to NO
  val car_present_reg = RegInit(BooleanEnum.NO)
  
  when(rand_choice === 0.U) {
    car_present_reg := BooleanEnum.NO
  }.otherwise {
    car_present_reg := BooleanEnum.YES
  }
  
  io.car_present := car_present_reg
}

/*
 * From the START state, the timer produces the signal "short"
 * after a non-deterministic amount of time. The signal "short"
 * remains asserted until the timer is reset (via the signal "start"). 
 * From the SHORT state, the timer produces the signal "long"
 * after a non-deterministic amount of time. The signal "long"
 * remains asserted until the timer is reset (via the signal "start"). 
 */
class Timer extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val short = Output(Bool())
    val long = Output(Bool())
  })
  
  // Use LFSR for non-deterministic behavior
  val lfsr = RegInit(1.U(8.W))
  val rand_choice = lfsr(0)
  
  // Update LFSR
  lfsr := Cat(lfsr(6), lfsr(5), lfsr(4), lfsr(3), lfsr(2), lfsr(1), lfsr(0), lfsr(7) ^ lfsr(5) ^ lfsr(4) ^ lfsr(3))
  
  val state = RegInit(TimerState.START)
  
  // short could as well be assigned to be just (state == SHORT)
  io.short := (state === TimerState.SHORT) || (state === TimerState.LONG)
  io.long := (state === TimerState.LONG)
  
  when(io.start) {
    state := TimerState.START
  }.otherwise {
    switch(state) {
      is(TimerState.START) {
        when(rand_choice === 1.U) {
          state := TimerState.SHORT
        }
      }
      is(TimerState.SHORT) {
        when(rand_choice === 1.U) {
          state := TimerState.LONG
        }
      }
      // if LONG, remains LONG until start signal received
    }
  }
}

/*
 * Farm light stays RED until it is enabled by the highway control. At
 * this point, it resets the timer, and moves to GREEN.  It stays in GREEN
 * until there are no cars, or the long timer expires.  At this point, it
 * moves to YELLOW and resets the timer.  It stays in YELLOW until the short
 * timer expires.  At this point, it moves to RED and enables the highway
 * controller. 
 */
class FarmControl extends Module {
  val io = IO(new Bundle {
    val car_present = Input(BooleanEnum())
    val enable_farm = Input(Bool())
    val short_timer = Input(Bool())
    val long_timer = Input(Bool())
    val farm_light = Output(Color())
    val farm_start_timer = Output(Bool())
    val enable_hwy = Output(Bool())
  })
  
  val farm_light_reg = RegInit(Color.RED)
  
  io.farm_start_timer := ((farm_light_reg === Color.GREEN) && 
                          ((io.car_present === BooleanEnum.NO) || io.long_timer)) ||
                         ((farm_light_reg === Color.RED) && io.enable_farm)
  
  io.enable_hwy := (farm_light_reg === Color.YELLOW) && io.short_timer
  
  switch(farm_light_reg) {
    is(Color.GREEN) {
      when((io.car_present === BooleanEnum.NO) || io.long_timer) {
        farm_light_reg := Color.YELLOW
      }
    }
    is(Color.YELLOW) {
      when(io.short_timer) {
        farm_light_reg := Color.RED
      }
    }
    is(Color.RED) {
      when(io.enable_farm) {
        farm_light_reg := Color.GREEN
      }
    }
  }
  
  io.farm_light := farm_light_reg
}

/*
 * Highway light stays RED until it is enabled by the farm control. At
 * this point, it resets the timer, and moves to GREEN.  It stays in GREEN
 * until there are cars and the long timer expires.  At this point, it
 * moves to YELLOW and resets the timer.  It stays in YELLOW until the short
 * timer expires.  At this point, it moves to RED and enables the farm
 * controller. 
 */
class HwyControl extends Module {
  val io = IO(new Bundle {
    val car_present = Input(BooleanEnum())
    val enable_hwy = Input(Bool())
    val short_timer = Input(Bool())
    val long_timer = Input(Bool())
    val hwy_light = Output(Color())
    val hwy_start_timer = Output(Bool())
    val enable_farm = Output(Bool())
  })
  
  val hwy_light_reg = RegInit(Color.GREEN)
  
  io.hwy_start_timer := ((hwy_light_reg === Color.GREEN) && 
                          ((io.car_present === BooleanEnum.YES) && io.long_timer)) ||
                         ((hwy_light_reg === Color.RED) && io.enable_hwy)
  
  io.enable_farm := (hwy_light_reg === Color.YELLOW) && io.short_timer
  
  switch(hwy_light_reg) {
    is(Color.GREEN) {
      when((io.car_present === BooleanEnum.YES) && io.long_timer) {
        hwy_light_reg := Color.YELLOW
      }
    }
    is(Color.YELLOW) {
      when(io.short_timer) {
        hwy_light_reg := Color.RED
      }
    }
    is(Color.RED) {
      when(io.enable_hwy) {
        hwy_light_reg := Color.GREEN
      }
    }
  }
  
  io.hwy_light := hwy_light_reg
}

/*
 * Module main ties together the underlying modules.  In addition, it 
 * ORs together the start timer outputs of the farm road and highway 
 * controllers.  Note that only a single timer is used for both the farm 
 * road and highway controllers. In theory, this could lead to conflicts; as 
 * implemented, such conflicts are avoided. 
 */
class Main extends Module {
  val io = IO(new Bundle {
    val farm_light = Output(Color())
    val hwy_light = Output(Color())
    val car_present = Output(BooleanEnum())
    val short_timer = Output(Bool())
    val long_timer = Output(Bool())
    // Additional outputs to preserve internal signals
    val start_timer = Output(Bool())
    val enable_farm = Output(Bool())
    val enable_hwy = Output(Bool())
    val farm_start_timer = Output(Bool())
    val hwy_start_timer = Output(Bool())
  })
  
  // Instantiate submodules
  val timer = Module(new Timer())
  val sensor = Module(new Sensor())
  val farm_control = Module(new FarmControl())
  val hwy_control = Module(new HwyControl())
  
  // Connect signals
  val start_timer = farm_control.io.farm_start_timer || hwy_control.io.hwy_start_timer
  
  timer.io.start := start_timer
  
  farm_control.io.car_present := sensor.io.car_present
  farm_control.io.enable_farm := hwy_control.io.enable_farm
  farm_control.io.short_timer := timer.io.short
  farm_control.io.long_timer := timer.io.long
  
  hwy_control.io.car_present := sensor.io.car_present
  hwy_control.io.enable_hwy := farm_control.io.enable_hwy
  hwy_control.io.short_timer := timer.io.short
  hwy_control.io.long_timer := timer.io.long
  
  // Connect outputs
  io.farm_light := farm_control.io.farm_light
  io.hwy_light := hwy_control.io.hwy_light
  io.car_present := sensor.io.car_present
  io.short_timer := timer.io.short
  io.long_timer := timer.io.long
  io.start_timer := start_timer
  io.enable_farm := farm_control.io.enable_farm
  io.enable_hwy := hwy_control.io.enable_hwy
  io.farm_start_timer := farm_control.io.farm_start_timer
  io.hwy_start_timer := hwy_control.io.hwy_start_timer
}

object VerilogGenerator extends App {
  emitVerilog(new Main(), args)
}