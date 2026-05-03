package llmverify

import chisel3._
import chisel3.util._

// Main MII Management Module
class miim extends Module {
  val io = IO(new Bundle {
    val Clk = Input(Clock())
    val Reset = Input(Bool())
    val Divider = Input(UInt(8.W))
    val NoPre = Input(Bool())
    val ScanIncr = Input(Bool())
    val CtrlData = Input(UInt(16.W))
    val Rgad = Input(UInt(5.W))
    val Fiad = Input(UInt(5.W))
    val WCtrlData = Input(Bool())
    val RStat = Input(Bool())
    val ScanStat = Input(Bool())
    val Mdi = Input(Bool())
    
    val Mdc = Output(Bool())
    val Mdo = Output(Bool())
    val MdoEn = Output(Bool())
    val Busy = Output(Bool())
    val Prsd = Output(UInt(16.W))
    val ScanPHYAddr = Output(UInt(5.W))
    val ScanPHYAddrValid = Output(Bool())
    val LinkOK = Output(Bool())
    val Nvalid = Output(Bool())
  })
  
  // Internal registers
  val Nvalid = RegInit(false.B)
  val EndBusy_d = RegInit(false.B)
  val EndBusy = RegInit(false.B)
  
  // Delay registers for edge detection
  val WCtrlData_q1 = RegInit(false.B)
  val WCtrlData_q2 = RegInit(false.B)
  val WCtrlData_q3 = RegInit(false.B)
  val WCtrlDataStart = RegInit(false.B)
  val WCtrlDataStart_q1 = RegInit(false.B)
  val WCtrlDataStart_q2 = RegInit(false.B)
  
  val RStat_q1 = RegInit(false.B)
  val RStat_q2 = RegInit(false.B)
  val RStat_q3 = RegInit(false.B)
  val RStatStart = RegInit(false.B)
  val RStatStart_q1 = RegInit(false.B)
  val RStatStart_q2 = RegInit(false.B)
  
  val ScanStat_q1 = RegInit(false.B)
  val ScanStat_q2 = RegInit(false.B)
  val SyncStatMdcEn = RegInit(false.B)
  
  val InProgress = RegInit(false.B)
  val InProgress_q1 = RegInit(false.B)
  val InProgress_q2 = RegInit(false.B)
  val InProgress_q3 = RegInit(false.B)
  
  val WriteOp = RegInit(false.B)
  val BitCounter = RegInit(0.U(6.W))
  
  val StatusSampledEnd = RegInit(false.B)
  val ScanPHYAddrValid = RegInit(false.B)
  
  val LatchByte1_d = RegInit(false.B)
  val LatchByte0_d = RegInit(false.B)
  val LatchByte = RegInit(0.U(2.W))
  
  // Clock Generator Module
  val clockGen = Module(new ClockGen)
  clockGen.io.Clk := io.Clk
  clockGen.io.Reset := io.Reset
  clockGen.io.Divider := io.Divider
  clockGen.io.MdoEn := io.MdoEn
  val MdcEn = clockGen.io.MdcEn
  val Mdc = clockGen.io.Mdc
  val MdcFrame = clockGen.io.MdcFrame
  
  // Shift Register Module
  val shiftReg = Module(new ShiftReg)
  shiftReg.io.Clk := io.Clk
  shiftReg.io.Reset := io.Reset
  shiftReg.io.MdcEn := MdcEn
  shiftReg.io.Mdi := io.Mdi
  shiftReg.io.Fiad := io.Fiad
  shiftReg.io.Rgad := io.Rgad
  shiftReg.io.CtrlData := io.CtrlData
  shiftReg.io.WriteOp := WriteOp
  shiftReg.io.ScanIncr := io.ScanIncr
  shiftReg.io.SyncStatMdcEn := SyncStatMdcEn
  shiftReg.io.ByteSelect := ByteSelect
  shiftReg.io.LatchByte := LatchByte
  val ShiftedBit = shiftReg.io.ShiftedBit
  
  // Output Control Module
  val outputControl = Module(new OutputControl)
  outputControl.io.Clk := io.Clk
  outputControl.io.Reset := io.Reset
  outputControl.io.InProgress := InProgress
  outputControl.io.ShiftedBit := ShiftedBit
  outputControl.io.BitCounter := BitCounter
  outputControl.io.WriteOp := WriteOp
  outputControl.io.NoPre := io.NoPre
  outputControl.io.MdcEn := MdcEn
  outputControl.io.MdcFrame := MdcFrame
  
  // Combinational logic
  val WriteDataOp = WCtrlDataStart_q1 & ~WCtrlDataStart_q2
  val ReadStatusOp = RStatStart_q1 & ~RStatStart_q2
  val ScanStatusOp = SyncStatMdcEn & ~InProgress & ~InProgress_q1 & ~InProgress_q2
  val StartOp = WriteDataOp | ReadStatusOp | ScanStatusOp
  val EndOp = BitCounter.andR
  
  val ByteSelect = Wire(UInt(4.W))
  ByteSelect(0) := InProgress & ((io.NoPre & (BitCounter === 0.U)) | (~io.NoPre & (BitCounter === 0x20.U)))
  ByteSelect(1) := InProgress & (BitCounter === 0x28.U)
  ByteSelect(2) := InProgress & WriteOp & (BitCounter === 0x30.U)
  ByteSelect(3) := InProgress & WriteOp & (BitCounter === 0x38.U)
  
  val LatchByte1_d2 = InProgress & ~WriteOp & (BitCounter === 0x37.U)
  val LatchByte0_d2 = InProgress & ~WriteOp & (BitCounter === 0x3F.U)
  
  val StatusSampled = ~StatusSampledEnd & LatchByte(0)
  
  // Sequential logic
  when(io.Reset) {
    EndBusy_d := false.B
    EndBusy := false.B
  }.otherwise {
    EndBusy_d := ~InProgress_q2 & InProgress_q3
    EndBusy := EndBusy_d
  }
  
  when(io.Reset) {
    WCtrlData_q1 := false.B
    WCtrlData_q2 := false.B
    WCtrlData_q3 := false.B
    RStat_q1 := false.B
    RStat_q2 := false.B
    RStat_q3 := false.B
    ScanStat_q1 := false.B
    ScanStat_q2 := false.B
    SyncStatMdcEn := false.B
  }.otherwise {
    WCtrlData_q1 := io.WCtrlData
    WCtrlData_q2 := WCtrlData_q1
    WCtrlData_q3 := WCtrlData_q2
    
    RStat_q1 := io.RStat
    RStat_q2 := RStat_q1
    RStat_q3 := RStat_q2
    
    ScanStat_q1 := io.ScanStat
    ScanStat_q2 := ScanStat_q1
    when(MdcEn) {
      SyncStatMdcEn := ScanStat_q2
    }
  }
  
  when(io.Reset) {
    WCtrlDataStart := false.B
    RStatStart := false.B
    Nvalid := false.B
  }.otherwise {
    when(EndBusy) {
      WCtrlDataStart := false.B
      RStatStart := false.B
      Nvalid := false.B
    }.otherwise {
      when(WCtrlData_q2 & ~WCtrlData_q3) {
        WCtrlDataStart := true.B
      }
      when(RStat_q2 & ~RStat_q3) {
        RStatStart := true.B
      }
      when(ScanStat_q2 & ~SyncStatMdcEn) {
        Nvalid := true.B
      }
    }
  }
  
  when(io.Reset) {
    WCtrlDataStart_q1 := false.B
    WCtrlDataStart_q2 := false.B
    RStatStart_q1 := false.B
    RStatStart_q2 := false.B
    LatchByte := 0.U
    LatchByte0_d := false.B
    LatchByte1_d := false.B
    InProgress_q1 := false.B
    InProgress_q2 := false.B
    InProgress_q3 := false.B
  }.otherwise {
    when(MdcEn) {
      WCtrlDataStart_q1 := WCtrlDataStart
      WCtrlDataStart_q2 := WCtrlDataStart_q1
      
      RStatStart_q1 := RStatStart
      RStatStart_q2 := RStatStart_q1
      
      LatchByte(0) := LatchByte0_d
      LatchByte(1) := LatchByte1_d
      
      LatchByte0_d := LatchByte0_d2
      LatchByte1_d := LatchByte1_d2
      
      InProgress_q1 := InProgress
      InProgress_q2 := InProgress_q1
      InProgress_q3 := InProgress_q2
    }
  }
  
  when(io.Reset) {
    InProgress := false.B
    WriteOp := false.B
  }.otherwise {
    when(MdcEn) {
      when(StartOp) {
        when(!InProgress) {
          WriteOp := WriteDataOp
        }
        InProgress := true.B
      }.otherwise {
        when(EndOp) {
          InProgress := false.B
          WriteOp := false.B
        }
      }
    }
  }
  
  when(io.Reset) {
    BitCounter := 0.U
  }.otherwise {
    when(MdcEn) {
      when(!InProgress_q1) {
        BitCounter := 0.U
      }.otherwise {
        when(io.NoPre & (BitCounter === 0.U)) {
          BitCounter := 0x21.U
        }.otherwise {
          BitCounter := BitCounter + 1.U
        }
      }
    }
  }
  
  when(io.Reset) {
    StatusSampledEnd := false.B
  }.otherwise {
    StatusSampledEnd := LatchByte(0)
  }
  
  when(io.Reset) {
    ScanPHYAddrValid := false.B
  }.otherwise {
    when(StatusSampled) {
      ScanPHYAddrValid := true.B
    }.otherwise {
      ScanPHYAddrValid := false.B
    }
  }
  
  // Output assignments
  io.Busy := WCtrlDataStart | RStatStart | SyncStatMdcEn | EndBusy | InProgress | InProgress_q3
  io.Mdc := Mdc
  io.Mdo := outputControl.io.Mdo
  io.MdoEn := outputControl.io.MdoEn
  io.Prsd := shiftReg.io.Prsd
  io.ScanPHYAddr := shiftReg.io.ScanPHYAddr
  io.ScanPHYAddrValid := ScanPHYAddrValid
  io.LinkOK := shiftReg.io.LinkOK
  io.Nvalid := Nvalid
}

// Clock Generator Module
class ClockGen extends Module {
  val io = IO(new Bundle {
    val Clk = Input(Clock())
    val Reset = Input(Bool())
    val Divider = Input(UInt(8.W))
    val MdoEn = Input(Bool())
    val MdcFrame = Input(Bool())
    val Mdc = Output(Bool())
    val MdcEn = Output(Bool())
  })
  
  val Mdc = RegInit(false.B)
  val Counter = RegInit(1.U(8.W))
  
  val TempDivider = Mux(io.Divider < 2.U, 2.U, io.Divider)
  val CounterPreset = (Cat(0.U(1.W), TempDivider(7, 1)) - 1.U)
  val CountEq0 = Counter === 0.U
  
  when(io.Reset) {
    Counter := 1.U
  }.otherwise {
    when(CountEq0) {
      Counter := CounterPreset
    }.otherwise {
      Counter := Counter - 1.U
    }
  }
  
  when(io.Reset) {
    Mdc := false.B
  }.otherwise {
    when(CountEq0 & io.MdcFrame) {
      Mdc := ~Mdc
    }
  }
  
  io.Mdc := Mdc
  io.MdcEn := CountEq0 & ~Mdc
}

// Output Control Module
class OutputControl extends Module {
  val io = IO(new Bundle {
    val Clk = Input(Clock())
    val Reset = Input(Bool())
    val InProgress = Input(Bool())
    val ShiftedBit = Input(Bool())
    val BitCounter = Input(UInt(6.W))
    val WriteOp = Input(Bool())
    val NoPre = Input(Bool())
    val MdcEn = Input(Bool())
    val MdcFrame = Input(Bool())
    val Mdo = Output(Bool())
    val MdoEn = Output(Bool())
  })
  
  val SerialEn_q = RegInit(false.B)
  val MdoEn_d2 = RegInit(false.B)
  val MdoEn_d = RegInit(false.B)
  val MdoEn_reg = RegInit(false.B)
  val Mdo_d = RegInit(false.B)
  val Mdo_reg = RegInit(false.B)
  val MdcFrame_d2 = RegInit(false.B)
  val MdcFrame_d1 = RegInit(false.B)
  
  val SerialEn = io.WriteOp & io.InProgress & (io.BitCounter(5) | ((io.BitCounter === 0.U) & io.NoPre)) |
                  ~io.WriteOp & io.InProgress & ((io.BitCounter(5) & ~io.BitCounter(4) & ~io.BitCounter(3, 1).andR) | ((io.BitCounter === 0.U) & io.NoPre))
  
  when(io.Reset) {
    SerialEn_q := false.B
    MdoEn_d2 := false.B
    MdcFrame_d2 := false.B
  }.otherwise {
    when(io.MdcEn) {
      SerialEn_q := SerialEn
      MdoEn_d2 := SerialEn | io.InProgress & ~io.BitCounter(5)
      MdcFrame_d2 := io.InProgress
    }
  }
  
  when(io.Reset) {
    MdoEn_d := false.B
    MdoEn_reg := false.B
    MdcFrame_d1 := false.B
  }.otherwise {
    MdoEn_d := MdoEn_d2
    MdoEn_reg := MdoEn_d
    MdcFrame_d1 := MdcFrame_d2
  }
  
  when(io.Reset) {
    Mdo_d := false.B
    Mdo_reg := false.B
  }.otherwise {
    Mdo_d := io.ShiftedBit | ~SerialEn_q
    Mdo_reg := Mdo_d
  }
  
  io.Mdo := Mdo_reg
  io.MdoEn := MdoEn_reg
}

// Shift Register Module
class ShiftReg extends Module {
  val io = IO(new Bundle {
    val Clk = Input(Clock())
    val Reset = Input(Bool())
    val MdcEn = Input(Bool())
    val Mdi = Input(Bool())
    val Fiad = Input(UInt(5.W))
    val Rgad = Input(UInt(5.W))
    val CtrlData = Input(UInt(16.W))
    val WriteOp = Input(Bool())
    val ScanIncr = Input(Bool())
    val SyncStatMdcEn = Input(Bool())
    val ByteSelect = Input(UInt(4.W))
    val LatchByte = Input(UInt(2.W))
    val ShiftedBit = Output(Bool())
    val ScanPHYAddr = Output(UInt(5.W))
    val Prsd = Output(UInt(16.W))
    val LinkOK = Output(Bool())
  })
  
  val shiftReg = RegInit(0.U(8.W))
  val FiadReg = RegInit(0.U(5.W))
  val Prsd = RegInit(0.U(16.W))
  val LinkOK_reg = RegInit(false.B)
  
  val shiftRegNext = Wire(UInt(8.W))
  
  val byteSelectValue = Wire(UInt(8.W))
  byteSelectValue := MuxLookup(io.ByteSelect, 0.U, Seq(
    "b0001".U -> Cat(1.U, 1.U, ~io.WriteOp, io.WriteOp, FiadReg(4, 1)),
    "b0010".U -> Cat(FiadReg(0), io.Rgad, 2.U),
    "b0100".U -> io.CtrlData(15, 8),
    "b1000".U -> io.CtrlData(7, 0)
  ))
  
  shiftRegNext := Mux(
    io.MdcEn,
    Mux(
      io.ByteSelect.orR,
      byteSelectValue,
      Cat(shiftReg(6, 0), io.Mdi)
    ),
    shiftReg
  )
  
  when(io.Reset) {
    shiftReg := 0.U
  }.otherwise {
    shiftReg := shiftRegNext
  }
  
  io.ShiftedBit := shiftReg(7)
  
  when(io.Reset) {
    Prsd := 0.U
    LinkOK_reg := false.B
  }.otherwise {
    when(io.LatchByte(0)) {
      Prsd(7, 0) := shiftReg
      when(io.Rgad === 1.U) {
        LinkOK_reg := shiftReg(2)
      }
    }.otherwise {
      when(io.LatchByte(1)) {
        Prsd(15, 8) := shiftReg
      }
    }
  }
  
  val IncrementAddr = io.SyncStatMdcEn & io.ByteSelect(1) & io.MdcEn
  
  when(io.Reset) {
    FiadReg := io.Fiad
  }.otherwise {
    when(!io.ScanIncr | (io.ScanIncr & IncrementAddr & (FiadReg === 0x1f.U))) {
      FiadReg := io.Fiad
    }.otherwise {
      when(io.ScanIncr & IncrementAddr) {
        FiadReg := FiadReg + 1.U
      }
    }
  }
  
  io.ScanPHYAddr := FiadReg
  io.Prsd := Prsd
  io.LinkOK := LinkOK_reg
}

object VerilogGenerator extends App {
  emitVerilog(new miim(), args)
}