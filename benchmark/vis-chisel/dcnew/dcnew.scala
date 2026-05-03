package llmverify

import chisel3._
import chisel3.util._

// Enum definitions
object WR extends ChiselEnum {
  val conduct, open = Value
}

object CH extends ChiselEnum {
  val discharged, charged = Value
}

object RL extends ChiselEnum {
  val pulling, neutral = Value
}

object LP extends ChiselEnum {
  val lit, unlit = Value
}

object CT extends ChiselEnum {
  val B, middle, C = Value
}

object TR extends ChiselEnum {
  val present1, present2, absent = Value
}

object TS extends ChiselEnum {
  val on, off = Value
}

object FT extends ChiselEnum {
  val good, faulty1, faulty2 = Value
}

object IN extends ChiselEnum {
  val go, stop = Value
}

object ST extends ChiselEnum {
  val stop1, stop2, go1, go2, go3, go4 = Value
}

object TRS extends ChiselEnum {
  val t1, t2, t3, t4, t5, t6, t7, t8, t9, t10 = Value
}

object STT extends ChiselEnum {
  val good, bad = Value
}

class dchek extends Module {
  val io = IO(new Bundle {
    val track1 = Output(WR())
    val track2 = Output(WR())
    val track3 = Output(WR())
    val charger = Output(CH())
    val relay = Output(RL())
    val lamp = Output(LP())
    val contact = Output(CT())
    val train = Output(TR())
    val ts_power = Output(TS())
    val interpretation = Output(IN())
    val property_state = Output(STT())
  })
  
  // Instantiate modules
  val trk1 = Module(new track_mod())
  val trk2 = Module(new track_mod())
  val trk3 = Module(new track_mod())
  val chg = Module(new charger_mod())
  val rly = Module(new relay_mod())
  val lmp = Module(new lamp_mod())
  val cnt = Module(new contact_mod())
  val trn = Module(new train_mod())
  val tsm = Module(new track_system_mod())
  val int_mod = Module(new interpretation_mod())
  val prop = Module(new property_mod())
  
  // Connect modules
  io.track1 := trk1.io.track
  io.track2 := trk2.io.track
  io.track3 := trk3.io.track
  io.charger := chg.io.out
  io.relay := rly.io.relay
  io.lamp := lmp.io.lamp
  io.contact := cnt.io.contact
  io.train := trn.io.out
  io.ts_power := tsm.io.ts_power
  io.interpretation := int_mod.io.out
  io.property_state := prop.io.state
  
  // Connect internal signals
  chg.io.ts_power := tsm.io.ts_power
  chg.io.lamp := lmp.io.lamp
  rly.io.charger := chg.io.out
  lmp.io.contact := cnt.io.contact
  lmp.io.wire3 := trk3.io.track
  lmp.io.ts_power := tsm.io.ts_power
  lmp.io.charger := chg.io.out
  cnt.io.relay := rly.io.relay
  tsm.io.wire1 := trk1.io.track
  tsm.io.wire2 := trk2.io.track
  tsm.io.train := trn.io.out
  tsm.io.contact := cnt.io.contact
  int_mod.io.lamp := lmp.io.lamp
  prop.io.train := trn.io.out
  prop.io.interpretation := int_mod.io.out
}

class track_mod extends Module {
  val io = IO(new Bundle {
    val track = Output(WR())
  })
  
  val state = RegInit(FT.good)
  val r_state = Wire(FT())
  val counter = RegInit(0.U(16.W))
  
  // Simple pseudo-random counter for nondeterministic behavior
  counter := counter + 1.U
  r_state := Mux(counter(0), FT.good, FT.faulty1)
  
  io.track := Mux(state === FT.good, WR.conduct, WR.open)
  
  when(state === FT.faulty1) {
    state := FT.faulty1
  }.elsewhen(state === FT.good) {
    state := r_state
  }
}

class charger_mod extends Module {
  val io = IO(new Bundle {
    val ts_power = Input(TS())
    val lamp = Input(LP())
    val out = Output(CH())
  })
  
  val state = RegInit(FT.good)
  val charger = RegInit(CH.discharged)
  val r_state = Wire(FT())
  val counter = RegInit(0.U(16.W))
  
  // Simple pseudo-random counter for nondeterministic behavior
  counter := counter + 1.U
  when(counter(1,0) === 0.U) { r_state := FT.good }
  .elsewhen(counter(1,0) === 1.U) { r_state := FT.faulty1 }
  .otherwise { r_state := FT.faulty2 }
  
  io.out := Mux(state === FT.faulty1, CH.discharged,
           Mux(state === FT.faulty2, CH.charged, charger))
  
  when(state === FT.faulty1) {
    state := FT.faulty1
  }.elsewhen(state === FT.faulty2) {
    state := FT.faulty2
  }.elsewhen(state === FT.good) {
    state := r_state
    when(io.ts_power === TS.on) {
      charger := CH.charged
    }.elsewhen(io.lamp === LP.lit) {
      charger := CH.discharged
    }.otherwise {
      charger := charger
    }
  }
}

class relay_mod extends Module {
  val io = IO(new Bundle {
    val charger = Input(CH())
    val relay = Output(RL())
  })
  
  val state = RegInit(FT.good)
  val r_state = Wire(FT())
  val counter = RegInit(0.U(16.W))
  
  // Simple pseudo-random counter for nondeterministic behavior
  counter := counter + 1.U
  when(counter(1,0) === 0.U) { r_state := FT.good }
  .elsewhen(counter(1,0) === 1.U) { r_state := FT.faulty1 }
  .otherwise { r_state := FT.faulty2 }
  
  io.relay := Mux(state === FT.faulty1, RL.neutral,
             Mux(state === FT.faulty2, RL.pulling,
             Mux(state === FT.good && io.charger === CH.charged, RL.pulling, RL.neutral)))
  
  when(state === FT.faulty1) {
    state := FT.faulty1
  }.elsewhen(state === FT.faulty2) {
    state := FT.faulty2
  }.elsewhen(state === FT.good) {
    state := r_state
  }
}

class contact_mod extends Module {
  val io = IO(new Bundle {
    val relay = Input(RL())
    val contact = Output(CT())
  })
  
  val state = RegInit(FT.good)
  val r_state = Wire(FT())
  val counter = RegInit(0.U(16.W))
  
  // Simple pseudo-random counter for nondeterministic behavior
  counter := counter + 1.U
  when(counter(1,0) === 0.U) { r_state := FT.good }
  .elsewhen(counter(1,0) === 1.U) { r_state := FT.faulty1 }
  .otherwise { r_state := FT.faulty2 }
  
  io.contact := Mux(state === FT.faulty1, CT.B,
               Mux(state === FT.faulty2, CT.C,
               Mux(state === FT.good && io.relay === RL.pulling, CT.C, CT.B)))
  
  when(state === FT.faulty1) {
    state := FT.faulty1
  }.elsewhen(state === FT.faulty2) {
    state := FT.faulty2
  }.elsewhen(state === FT.good) {
    state := r_state
  }
}

class train_mod extends Module {
  val io = IO(new Bundle {
    val out = Output(TR())
  })
  
  val train = RegInit(TRS.t1)
  val counter = RegInit(0.U(16.W))
  
  val r1_train = Wire(TRS())
  val r2_train = Wire(TRS())
  val r3_train = Wire(TRS())
  val r4_train = Wire(TRS())
  val r5_train = Wire(TRS())
  val r6_train = Wire(TRS())
  val r7_train = Wire(TRS())
  val r8_train = Wire(TRS())
  val r9_train = Wire(TRS())
  val r10_train = Wire(TRS())
  
  // Simple pseudo-random counter for nondeterministic behavior
  counter := counter + 1.U
  
  r1_train := Mux(counter(0), TRS.t1, TRS.t2)
  r2_train := Mux(counter(1), TRS.t2, TRS.t3)
  r3_train := Mux(counter(2), TRS.t3, TRS.t4)
  r4_train := Mux(counter(3), TRS.t4, TRS.t5)
  r5_train := Mux(counter(4), TRS.t5, TRS.t6)
  r6_train := Mux(counter(5), TRS.t6, TRS.t7)
  r7_train := Mux(counter(6), TRS.t7, TRS.t8)
  r8_train := Mux(counter(7), TRS.t8, TRS.t9)
  r9_train := Mux(counter(8), TRS.t9, TRS.t10)
  r10_train := Mux(counter(9), TRS.t10, TRS.t1)
  
  io.out := Mux(train === TRS.t1, TR.absent, TR.present1)
  
  switch(train) {
    is(TRS.t1) { train := r1_train }
    is(TRS.t2) { train := r2_train }
    is(TRS.t3) { train := r3_train }
    is(TRS.t4) { train := r4_train }
    is(TRS.t5) { train := r5_train }
    is(TRS.t6) { train := r6_train }
    is(TRS.t7) { train := r7_train }
    is(TRS.t8) { train := r8_train }
    is(TRS.t9) { train := r9_train }
    is(TRS.t10) { train := r10_train }
  }
}

class lamp_mod extends Module {
  val io = IO(new Bundle {
    val contact = Input(CT())
    val wire3 = Input(WR())
    val ts_power = Input(TS())
    val charger = Input(CH())
    val lamp = Output(LP())
  })
  
  val state = RegInit(FT.good)
  val r_state = Wire(FT())
  val counter = RegInit(0.U(16.W))
  
  // Simple pseudo-random counter for nondeterministic behavior
  counter := counter + 1.U
  when(counter(1,0) === 0.U) { r_state := FT.good }
  .elsewhen(counter(1,0) === 1.U) { r_state := FT.faulty1 }
  .otherwise { r_state := FT.faulty2 }
  
  val lamp_condition = (io.contact === CT.C) && (io.wire3 === WR.conduct) &&
                       ((io.ts_power === TS.on) || (io.charger === CH.charged))
  
  io.lamp := Mux(state === FT.faulty1, LP.unlit,
            Mux(state === FT.faulty2, LP.lit,
            Mux(state === FT.good && lamp_condition, LP.lit, LP.unlit)))
  
  when(state === FT.faulty1) {
    state := FT.faulty1
  }.elsewhen(state === FT.faulty2) {
    state := FT.faulty2
  }.elsewhen(state === FT.good) {
    state := r_state
  }
}

class track_system_mod extends Module {
  val io = IO(new Bundle {
    val wire1 = Input(WR())
    val wire2 = Input(WR())
    val train = Input(TR())
    val contact = Input(CT())
    val ts_power = Output(TS())
  })
  
  val state = RegInit(FT.good)
  val r_state = Wire(FT())
  val counter = RegInit(0.U(16.W))
  
  // Simple pseudo-random counter for nondeterministic behavior
  counter := counter + 1.U
  when(counter(1,0) === 0.U) { r_state := FT.good }
  .elsewhen(counter(1,0) === 1.U) { r_state := FT.faulty1 }
  .otherwise { r_state := FT.faulty2 }
  
  val power_condition = (io.wire1 === WR.conduct) && (io.wire2 === WR.conduct) &&
                        ((io.train =/= TR.absent) || (io.contact === CT.B))
  
  io.ts_power := Mux(state === FT.faulty1, TS.on,
                Mux(state === FT.faulty2, TS.off,
                Mux(state === FT.good && power_condition, TS.on, TS.off)))
  
  when(state === FT.faulty1) {
    state := FT.faulty1
  }.elsewhen(state === FT.faulty2) {
    state := FT.faulty2
  }.elsewhen(state === FT.good) {
    state := r_state
  }
}

class interpretation_mod extends Module {
  val io = IO(new Bundle {
    val lamp = Input(LP())
    val out = Output(IN())
  })
  
  val state = RegInit(ST.stop1)
  
  io.out := Mux(state === ST.go3 || state === ST.go4, IN.go, IN.stop)
  
  switch(state) {
    is(ST.stop1) {
      when(io.lamp === LP.lit) {
        state := ST.stop2
      }.otherwise {
        state := ST.stop1
      }
    }
    is(ST.stop2) {
      when(io.lamp === LP.unlit) {
        state := ST.go1
      }.otherwise {
        state := ST.stop1
      }
    }
    is(ST.go1) {
      when(io.lamp === LP.lit) {
        state := ST.go2
      }.otherwise {
        state := ST.stop1
      }
    }
    is(ST.go2) {
      when(io.lamp === LP.unlit) {
        state := ST.go3
      }.otherwise {
        state := ST.stop1
      }
    }
    is(ST.go3) {
      when(io.lamp === LP.lit) {
        state := ST.go4
      }.otherwise {
        state := ST.stop1
      }
    }
    is(ST.go4) {
      when(io.lamp === LP.unlit) {
        state := ST.go3
      }.otherwise {
        state := ST.stop1
      }
    }
  }
}

class property_mod extends Module {
  val io = IO(new Bundle {
    val train = Input(TR())
    val interpretation = Input(IN())
    val state = Output(STT())
  })
  
  val state = RegInit(STT.good)
  io.state := state
  
  when((io.train === TR.present2 && io.interpretation === IN.go) || state === STT.bad) {
    state := STT.bad
  }.otherwise {
    state := STT.good
  }
}

object VerilogGenerator extends App {
  emitVerilog(new dchek(), args)
}