package llmverify
import chisel3._
import chisel3.util._

object VerilogGenerator extends App {
  emitVerilog(new b12(), args)
}

class b12 extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val k = Input(UInt(4.W))
    val nloss = Output(Bool())
    val nl = Output(UInt(4.W))
    val speaker = Output(Bool())
  })
  
  // Constants
  val RED = 0.U(2.W)
  val GREEN = 1.U(2.W)
  val YELLOW = 2.U(2.W)
  val BLUE = 3.U(2.W)
  
  val LED_OFF = false.B
  val LED_ON = true.B
  
  val PLAY_OFF = false.B
  val PLAY_ON = true.B
  
  val KEY_ON = true.B
  
  val NUM_KEY = 4
  val COD_COLOR = 2
  val COD_SOUND = 3
  
  val S_WIN = 4.U(3.W)
  val S_LOSS = 5.U(3.W)
  
  val SIZE_ADDRESS = 5
  val SIZE_MEM = 32
  
  val COUNT_KEY = 33.U(6.W)
  val COUNT_SEQ = 33.U(6.W)
  val DEC_SEQ = 1.U(6.W)
  val COUNT_FIN = 8.U(6.W)
  
  val ERROR_TONE = 1.U(3.W)
  val RED_TONE = 2.U(3.W)
  val GREEN_TONE = 3.U(3.W)
  val YELLOW_TONE = 4.U(3.W)
  val BLUE_TONE = 5.U(3.W)
  val WIN_TONE = 6.U(3.W)
  
  // State enumeration
  object GammaState extends ChiselEnum {
    val G0, G1, G2, G3, G4, G5, G6, G7, G8, G9, G10, G10a, G11,
        G12, Ea, E0, E1, K0, K1, K2, K3, K4, K5, K6, W0, W1 = Value
  }
  
  // Registers
  val speaker = RegInit(false.B)
  val nloss = RegInit(LED_OFF)
  val nl = RegInit(0.U(4.W))
  val wr = RegInit(false.B)
  val address = RegInit(0.U(5.W))
  val data_in = RegInit(0.U(2.W))
  val data_out = RegInit(0.U(2.W))
  val num = RegInit(0.U(2.W))
  val sound = RegInit(0.U(3.W))
  val play = RegInit(PLAY_OFF)
  val s = RegInit(false.B)
  val counter = RegInit(0.U(3.W))
  val gamma = RegInit(GammaState.G0)
  val ind = RegInit(0.U(2.W))
  val scan = RegInit(0.U(5.W))
  val max = RegInit(0.U(5.W))
  val timebase = RegInit(0.U(6.W))
  val count = RegInit(0.U(6.W))
  
  // Memory
  val memory = RegInit(VecInit(Seq.fill(32)(0.U(2.W))))
  
  // Combinational logic
  val counterp1 = counter + 1.U
  val countm1 = Mux(count === 0.U, 0.U, count - 1.U)
  
  // Sound generation logic
  when (play) {
    switch (sound) {
      is (0.U) {
        when (counter > RED_TONE) {
          s := ~s
          speaker := s
          counter := 0.U
        } .otherwise {
          counter := counterp1
        }
      }
      is (1.U) {
        when (counter > GREEN_TONE) {
          s := ~s
          speaker := s
          counter := 0.U
        } .otherwise {
          counter := counterp1
        }
      }
      is (2.U) {
        when (counter > YELLOW_TONE) {
          s := ~s
          speaker := s
          counter := 0.U
        } .otherwise {
          counter := counterp1
        }
      }
      is (3.U) {
        when (counter > BLUE_TONE) {
          s := ~s
          speaker := s
          counter := 0.U
        } .otherwise {
          counter := counterp1
        }
      }
      is (S_WIN) {
        when (counter > WIN_TONE) {
          s := ~s
          speaker := s
          counter := 0.U
        } .otherwise {
          counter := counterp1
        }
      }
      is (S_LOSS) {
        when (counter > ERROR_TONE) {
          s := ~s
          speaker := s
          counter := 0.U
        } .otherwise {
          counter := counterp1
        }
      }
    }
  } .otherwise {
    counter := 0.U
    speaker := false.B
  }
  
  // Number generator
  num := num + 1.U
  
  // Memory read/write logic
  data_out := memory(address)
  when (wr) {
    memory(address) := data_in
  }
  
  // Main state machine
  when (io.start) {
    gamma := GammaState.G1
  }
  
  switch (gamma) {
    is (GammaState.G0) {
      gamma := GammaState.G0
    }
    is (GammaState.G1) {
      nloss := LED_OFF
      nl := 0.U
      play := PLAY_OFF
      wr := false.B
      max := 0.U
      timebase := COUNT_SEQ
      gamma := GammaState.G2
    }
    is (GammaState.G2) {
      scan := 0.U
      wr := true.B
      address := max
      data_in := num
      gamma := GammaState.G3
    }
    is (GammaState.G3) {
      wr := false.B
      address := scan
      gamma := GammaState.G4
    }
    is (GammaState.G4) {
      gamma := GammaState.G5
    }
    is (GammaState.G5) {
      nl := 0.U
      switch (data_out) {
        is (0.U) { nl := nl | (1.U << 0.U) }
        is (1.U) { nl := nl | (1.U << 1.U) }
        is (2.U) { nl := nl | (1.U << 2.U) }
        is (3.U) { nl := nl | (1.U << 3.U) }
      }
      count := timebase
      play := PLAY_ON
      sound := Cat(0.U(1.W), data_out)
      gamma := GammaState.G6
    }
    is (GammaState.G6) {
      when (count === 0.U) {
        nl := 0.U
        play := PLAY_OFF
        count := timebase
        gamma := GammaState.G7
      } .otherwise {
        count := countm1
        gamma := GammaState.G6
      }
    }
    is (GammaState.G7) {
      when (count === 0.U) {
        when (scan =/= max) {
          scan := scan + 1.U
          gamma := GammaState.G3
        } .otherwise {
          scan := 0.U
          gamma := GammaState.G8
        }
      } .otherwise {
        count := countm1
        gamma := GammaState.G7
      }
    }
    is (GammaState.G8) {
      count := COUNT_KEY
      address := scan
      gamma := GammaState.G9
    }
    is (GammaState.G9) {
      gamma := GammaState.G10
    }
    is (GammaState.G10) {
      when (count === 0.U) {
        nloss := LED_ON
        max := 0.U
        gamma := GammaState.K0
      } .otherwise {
        count := countm1
        when (io.k(0) === KEY_ON) {
          ind := 0.U
          sound := 0.U
          play := PLAY_ON
          count := timebase
          when (data_out === 0.U) {
            gamma := GammaState.G10a
          } .otherwise {
            nloss := LED_ON
            gamma := GammaState.Ea
          }
        } .elsewhen (io.k(1) === KEY_ON) {
          ind := 1.U
          sound := 1.U
          play := PLAY_ON
          count := timebase
          when (data_out === 1.U) {
            gamma := GammaState.G10a
          } .otherwise {
            nloss := LED_ON
            gamma := GammaState.Ea
          }
        } .elsewhen (io.k(2) === KEY_ON) {
          ind := 2.U
          sound := 2.U
          play := PLAY_ON
          count := timebase
          when (data_out === 2.U) {
            gamma := GammaState.G10a
          } .otherwise {
            nloss := LED_ON
            gamma := GammaState.Ea
          }
        } .elsewhen (io.k(3) === KEY_ON) {
          ind := 3.U
          sound := 3.U
          play := PLAY_ON
          count := timebase
          when (data_out === 3.U) {
            gamma := GammaState.G10a
          } .otherwise {
            nloss := LED_ON
            gamma := GammaState.Ea
          }
        } .otherwise {
          gamma := GammaState.G10
        }
      }
    }
    is (GammaState.G10a) {
      nl := 0.U
      switch (ind) {
        is (0.U) { nl := nl | (1.U << 0.U) }
        is (1.U) { nl := nl | (1.U << 1.U) }
        is (2.U) { nl := nl | (1.U << 2.U) }
        is (3.U) { nl := nl | (1.U << 3.U) }
      }
      gamma := GammaState.G11
    }
    is (GammaState.G11) {
      when (count === 0.U) {
        nl := 0.U
        play := PLAY_OFF
        count := timebase
        gamma := GammaState.G12
      } .otherwise {
        count := countm1
        gamma := GammaState.G11
      }
    }
    is (GammaState.G12) {
      when (count === 0.U) {
        when (scan =/= max) {
          scan := scan + 1.U
          gamma := GammaState.G8
        } .elsewhen (max =/= (SIZE_MEM - 1).U) {
          max := max + 1.U
          timebase := timebase - DEC_SEQ
          gamma := GammaState.G2
        } .otherwise {
          play := PLAY_ON
          sound := S_WIN
          count := COUNT_FIN
          gamma := GammaState.W0
        }
      } .otherwise {
        count := countm1
        gamma := GammaState.G12
      }
    }
    is (GammaState.Ea) {
      nl := 0.U
      switch (ind) {
        is (0.U) { nl := nl | (1.U << 0.U) }
        is (1.U) { nl := nl | (1.U << 1.U) }
        is (2.U) { nl := nl | (1.U << 2.U) }
        is (3.U) { nl := nl | (1.U << 3.U) }
      }
      gamma := GammaState.E0
    }
    is (GammaState.E0) {
      when (count === 0.U) {
        nl := 0.U
        play := PLAY_OFF
        count := timebase
        gamma := GammaState.E1
      } .otherwise {
        count := countm1
        gamma := GammaState.E0
      }
    }
    is (GammaState.E1) {
      when (count === 0.U) {
        max := 0.U
        gamma := GammaState.K0
      } .otherwise {
        count := countm1
        gamma := GammaState.E1
      }
    }
    is (GammaState.K0) {
      address := max
      gamma := GammaState.K1
    }
    is (GammaState.K1) {
      gamma := GammaState.K2
    }
    is (GammaState.K2) {
      nl := 0.U
      switch (data_out) {
        is (0.U) { nl := nl | (1.U << 0.U) }
        is (1.U) { nl := nl | (1.U << 1.U) }
        is (2.U) { nl := nl | (1.U << 2.U) }
        is (3.U) { nl := nl | (1.U << 3.U) }
      }
      play := PLAY_ON
      sound := Cat(0.U(1.W), data_out)
      count := timebase
      gamma := GammaState.K3
    }
    is (GammaState.K3) {
      when (count === 0.U) {
        nl := 0.U
        play := PLAY_OFF
        count := timebase
        gamma := GammaState.K4
      } .otherwise {
        count := countm1
        gamma := GammaState.K3
      }
    }
    is (GammaState.K4) {
      when (count === 0.U) {
        when (max =/= scan) {
          max := max + 1.U
          gamma := GammaState.K0
        } .otherwise {
          nl := 0.U
          switch (data_out) {
            is (0.U) { nl := nl | (1.U << 0.U) }
            is (1.U) { nl := nl | (1.U << 1.U) }
            is (2.U) { nl := nl | (1.U << 2.U) }
            is (3.U) { nl := nl | (1.U << 3.U) }
          }
          play := PLAY_ON
          sound := S_LOSS
          count := COUNT_FIN
          gamma := GammaState.K5
        }
      } .otherwise {
        count := countm1
        gamma := GammaState.K4
      }
    }
    is (GammaState.K5) {
      when (count === 0.U) {
        nl := 0.U
        play := PLAY_OFF
        count := COUNT_FIN
        gamma := GammaState.K6
      } .otherwise {
        count := countm1
        gamma := GammaState.K5
      }
    }
    is (GammaState.K6) {
      when (count === 0.U) {
        nl := 0.U
        switch (data_out) {
          is (0.U) { nl := nl | (1.U << 0.U) }
          is (1.U) { nl := nl | (1.U << 1.U) }
          is (2.U) { nl := nl | (1.U << 2.U) }
          is (3.U) { nl := nl | (1.U << 3.U) }
        }
        play := PLAY_ON
        sound := S_LOSS
        count := COUNT_FIN
        gamma := GammaState.K5
      } .otherwise {
        count := countm1
        gamma := GammaState.K6
      }
    }
    is (GammaState.W0) {
      when (count === 0.U) {
        nl := "b1111".U
        play := PLAY_OFF
        count := COUNT_FIN
        gamma := GammaState.W1
      } .otherwise {
        count := countm1
        gamma := GammaState.W0
      }
    }
    is (GammaState.W1) {
      when (count === 0.U) {
        nl := 0.U
        play := PLAY_ON
        sound := S_WIN
        count := COUNT_FIN
        gamma := GammaState.W0
      } .otherwise {
        count := countm1
        gamma := GammaState.W1
      }
    }
  }
  
  // Output assignments
  io.nloss := nloss
  io.nl := nl
  io.speaker := speaker
}