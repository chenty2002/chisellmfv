package llmverify

import chisel3._
import chisel3.util._

// Enum definitions for coins and states using ChiselEnum
object Coin extends ChiselEnum {
  val none, nickel, dime, quarter = Value
}

object State extends ChiselEnum {
  val accepting, change, refund, beverage = Value
}

// Monitor module that tracks the balance
class Monitor extends Module {
  val io = IO(new Bundle {
    val deposit = Input(Coin())
    val beverage = Input(Bool())
    val change = Input(Coin())
    val balance = Output(SInt(6.W)) // Signed to handle negative values
  })
  
  val balanceReg = RegInit(0.S(6.W))
  
  // Calculate values for deposit, change, and beverage
  val valD = Mux(io.deposit === Coin.quarter, 5.S,
             Mux(io.deposit === Coin.dime, 2.S,
             Mux(io.deposit === Coin.nickel, 1.S, 0.S)))
  
  val valC = Mux(io.change === Coin.quarter, 5.S,
             Mux(io.change === Coin.dime, 2.S,
             Mux(io.change === Coin.nickel, 1.S, 0.S)))
  
  val valB = Mux(io.beverage, 5.S, 0.S)
  
  // Update balance
  balanceReg := balanceReg + valD - (valC + valB)
  
  io.balance := balanceReg
}

// Main vending machine module
class Vending(BITS: Int = 8) extends Module {
  val io = IO(new Bundle {
    val deposit = Input(Coin())
    val change = Output(Coin())
    val beverage = Output(Bool())
    val enable = Output(Bool())
    // Debug outputs to preserve internal state
    val debugState = Output(State())
    val debugT5 = Output(UInt(BITS.W))
    val debugT10 = Output(UInt(BITS.W))
    val debugT25 = Output(UInt(BITS.W))
    val debugL5 = Output(UInt(3.W))
    val debugL10 = Output(UInt(2.W))
    val debugL25 = Output(UInt(1.W))
  })
  
  // State registers
  val stateReg = RegInit(State.accepting)
  val changeReg = RegInit(Coin.none)
  
  // Total counters (since reset)
  val t5Reg = RegInit(0.U(BITS.W))
  val t10Reg = RegInit(0.U(BITS.W))
  val t25Reg = RegInit(0.U(BITS.W))
  
  // Local transaction counters
  val l5Reg = RegInit(0.U(3.W))
  val l10Reg = RegInit(0.U(2.W))
  val l25Reg = RegInit(0.U(1.W))
  
  // Calculate total nickel-equivalents
  // #(nickels) + 2 #(dimes) + 5 #(quarters)
  val total = l5Reg + (l10Reg << 1) + (l25Reg << 2) + l25Reg
  
  // Outputs
  io.beverage := (stateReg === State.beverage)
  io.enable := (stateReg === State.accepting && total < 5.U)
  io.change := changeReg
  
  // Debug outputs
  io.debugState := stateReg
  io.debugT5 := t5Reg
  io.debugT10 := t10Reg
  io.debugT25 := t25Reg
  io.debugL5 := l5Reg
  io.debugL10 := l10Reg
  io.debugL25 := l25Reg
  
  // State machine logic
  switch(stateReg) {
    is(State.accepting) {
      when(total >= 5.U) {
        changeReg := io.deposit
        stateReg := State.change
      }.otherwise {
        switch(io.deposit) {
          is(Coin.nickel) {
            when(t5Reg === Fill(BITS, 1.U)) {
              changeReg := Coin.nickel
            }.otherwise {
              changeReg := Coin.none
              t5Reg := t5Reg + 1.U
              l5Reg := l5Reg + 1.U
            }
          }
          is(Coin.dime) {
            when(t10Reg === Fill(BITS, 1.U)) {
              changeReg := Coin.dime
            }.otherwise {
              changeReg := Coin.none
              t10Reg := t10Reg + 1.U
              l10Reg := l10Reg + 1.U
            }
          }
          is(Coin.quarter) {
            when(t25Reg === Fill(BITS, 1.U)) {
              changeReg := Coin.quarter
            }.otherwise {
              changeReg := Coin.none
              t25Reg := t25Reg + 1.U
              l25Reg := l25Reg + 1.U
            }
          }
          is(Coin.none) {
            changeReg := Coin.none
          }
        }
      }
    }
    is(State.change) {
      when(total === 5.U) {
        changeReg := Coin.none
        stateReg := State.beverage
      }.elsewhen(total === 6.U) {
        when(t5Reg > 0.U) {
          changeReg := Coin.nickel
          t5Reg := t5Reg - 1.U
          stateReg := State.beverage
        }.otherwise {
          changeReg := Coin.none
          stateReg := State.refund
        }
      }.otherwise { // at least 35c
        when(l10Reg > 0.U) {
          changeReg := Coin.dime
          t10Reg := t10Reg - 1.U
          l10Reg := l10Reg - 1.U
        }.otherwise {
          changeReg := Coin.nickel
          t5Reg := t5Reg - 1.U
          l5Reg := l5Reg - 1.U
        }
      }
    }
    is(State.beverage) {
      changeReg := Coin.none
      l5Reg := 0.U
      l10Reg := 0.U
      l25Reg := 0.U
      stateReg := State.accepting
    }
    is(State.refund) {
      when(l10Reg > 0.U) {
        l10Reg := l10Reg - 1.U
        t10Reg := t10Reg - 1.U
        changeReg := Coin.dime
      }.otherwise {
        stateReg := State.accepting
        changeReg := Coin.none
      }
    }
  }
}

// Environment module (testbench)
class Environment(BITS: Int = 8) extends Module {
  val io = IO(new Bundle {
    val balance = Output(SInt(6.W))
    val deposit = Output(Coin())
    val beverage = Output(Bool())
    val change = Output(Coin())
    val enable = Output(Bool())
    // Debug outputs
    val debugState = Output(State())
    val debugT5 = Output(UInt(BITS.W))
    val debugT10 = Output(UInt(BITS.W))
    val debugT25 = Output(UInt(BITS.W))
  })
  
  // Instantiate vending machine
  val vending = Module(new Vending(BITS))
  
  // Instantiate monitor
  val monitor = Module(new Monitor())
  
  // Random deposit generation (simplified - using counter instead of $ND)
  val randomCounter = RegInit(0.U(8.W))
  randomCounter := randomCounter + 1.U
  
  val nd = Mux(randomCounter(2,0) === 0.U, Coin.none,
           Mux(randomCounter(2,0) === 1.U, Coin.nickel,
           Mux(randomCounter(2,0) === 2.U, Coin.dime,
           Coin.quarter)))
  
  val depositReg = RegInit(Coin.none)
  
  // Connect vending machine
  vending.io.deposit := depositReg
  
  // Connect monitor
  monitor.io.deposit := depositReg
  monitor.io.beverage := vending.io.beverage
  monitor.io.change := vending.io.change
  
  // Generate deposit based on enable
  when(vending.io.enable) {
    depositReg := nd
  }.otherwise {
    depositReg := Coin.none
  }
  
  // Connect outputs
  io.balance := monitor.io.balance
  io.deposit := depositReg
  io.beverage := vending.io.beverage
  io.change := vending.io.change
  io.enable := vending.io.enable
  
  // Debug outputs
  io.debugState := vending.io.debugState
  io.debugT5 := vending.io.debugT5
  io.debugT10 := vending.io.debugT10
  io.debugT25 := vending.io.debugT25
}

object VerilogGenerator extends App {
  emitVerilog(new Environment(), Array("--target-dir", "generated"))
}