package llmverify

import chisel3._
import chisel3.util._

// Coin type definitions
object Coin {
  val noneValue :: nickelValue :: dimeValue :: quarterValue :: Nil = Enum(4)
  val NONE = noneValue
  val NICKEL = nickelValue
  val DIME = dimeValue
  val QUARTER = quarterValue
}

// State definitions for the FSM
object VendingState {
  val acceptingValue :: changeValue :: refundValue :: beverageValue :: Nil = Enum(4)
  val ACCEPTING = acceptingValue
  val CHANGE = changeValue
  val REFUND = refundValue
  val BEVERAGE = beverageValue
}

// Vending machine module that dispenses one item in exchange for 25c.
// The machine accepts nickels, dimes, and quarters. It gives change if
// it can; otherwise, it returns the coins that were deposited.
class Vending(BITS: Int = 4) extends Module {
  val io = IO(new Bundle {
    val deposit = Input(UInt(2.W))  // Coin type
    val change = Output(UInt(2.W))  // Coin type
    val beverage = Output(Bool())   // causes beverage to be released
    val enable = Output(Bool())     // coins are only accepted when enable==1
    
    // Debug outputs to preserve internal state
    val debugState = Output(UInt(2.W))
    val debugT5 = Output(UInt(BITS.W))
    val debugT10 = Output(UInt(BITS.W))
    val debugT25 = Output(UInt(BITS.W))
    val debugL5 = Output(UInt(3.W))
    val debugL10 = Output(UInt(2.W))
    val debugL25 = Output(UInt(1.W))
    val debugTotal = Output(UInt(4.W))
  })
  
  // State registers
  val state = RegInit(VendingState.ACCEPTING)
  
  // Total numbers of nickels, dimes, and quarters deposited since reset
  val t5 = RegInit(0.U(BITS.W))
  val t10 = RegInit(0.U(BITS.W))
  val t25 = RegInit(0.U(BITS.W))
  
  // Numbers of nickels, dimes, and quarters deposited in this transaction
  val l5 = RegInit(0.U(3.W))
  val l10 = RegInit(0.U(2.W))
  val l25 = RegInit(0.U(1.W))
  
  // Change output register
  val change = RegInit(Coin.NONE)
  
  // Number of nickel-equivalents deposited so far during the current transaction
  // #(nickels) + 2 #(dimes) + 5 #(quarters)
  val total = (Cat(0.U(1.W), l5) + Cat(0.U(1.W), l10, 0.U(1.W)) + Cat(0.U(1.W), l25, l25, l25))
  
  // Default assignments
  io.change := change
  io.beverage := (state === VendingState.BEVERAGE)
  io.enable := (state === VendingState.ACCEPTING && total < 5.U)
  
  // Debug outputs
  io.debugState := state
  io.debugT5 := t5
  io.debugT10 := t10
  io.debugT25 := t25
  io.debugL5 := l5
  io.debugL10 := l10
  io.debugL25 := l25
  io.debugTotal := total
  
  // FSM logic
  switch(state) {
    is(VendingState.ACCEPTING) {
      when(total >= 5.U) {
        change := io.deposit
        state := VendingState.CHANGE
      }.otherwise {
        switch(io.deposit) {
          is(Coin.NICKEL) {
            when(t5 === Fill(BITS, 1.U)) {
              change := Coin.NICKEL
            }.otherwise {
              change := Coin.NONE
              t5 := t5 + 1.U
              l5 := l5 + 1.U
            }
          }
          is(Coin.DIME) {
            when(t10 === Fill(BITS, 1.U)) {
              change := Coin.DIME
            }.otherwise {
              change := Coin.NONE
              t10 := t10 + 1.U
              l10 := l10 + 1.U
            }
          }
          is(Coin.QUARTER) {
            when(t25 === Fill(BITS, 1.U)) {
              change := Coin.QUARTER
            }.otherwise {
              change := Coin.NONE
              t25 := t25 + 1.U
              l25 := l25 + 1.U
            }
          }
          is(Coin.NONE) {
            change := Coin.NONE
          }
        }
      }
    }
    
    is(VendingState.CHANGE) {
      when(total === 5.U) {
        change := Coin.NONE
        state := VendingState.BEVERAGE
      }.elsewhen(total === 6.U) {
        when(t5 > 0.U) {
          change := Coin.NICKEL
          t5 := t5 - 1.U
          state := VendingState.BEVERAGE
        }.otherwise {
          change := Coin.NONE
          state := VendingState.REFUND
        }
      }.otherwise { // at least 35c
        when(l10 > 0.U) {
          change := Coin.DIME
          t10 := t10 - 1.U
          l10 := l10 - 1.U
        }.otherwise {
          change := Coin.NICKEL
          t5 := t5 - 1.U
          l5 := l5 - 1.U
        }
      }
    }
    
    is(VendingState.BEVERAGE) {
      change := Coin.NONE
      l5 := 0.U
      l10 := 0.U
      l25 := 0.U
      state := VendingState.ACCEPTING
    }
    
    is(VendingState.REFUND) {
      when(l10 > 0.U) {
        l10 := l10 - 1.U
        t10 := t10 - 1.U
        change := Coin.DIME
      }.otherwise {
        state := VendingState.ACCEPTING
        change := Coin.NONE
      }
    }
  }
}

// Monitor module that tracks the balance
class Monitor extends Module {
  val io = IO(new Bundle {
    val deposit = Input(UInt(2.W))   // Coin type
    val beverage = Input(Bool())     // beverage signal
    val change = Input(UInt(2.W))    // Coin type
    val balance = Output(SInt(5.W))  // from -16 to 15
  })
  
  val balance = RegInit(0.S(5.W))
  
  // Value calculations - using when/otherwise chain instead of MuxLookup
  val valD = Wire(UInt(5.W))
  when(io.deposit === Coin.QUARTER) {
    valD := 5.U
  }.elsewhen(io.deposit === Coin.DIME) {
    valD := 2.U
  }.elsewhen(io.deposit === Coin.NICKEL) {
    valD := 1.U
  }.otherwise {
    valD := 0.U
  }
  
  val valC = Wire(UInt(5.W))
  when(io.change === Coin.QUARTER) {
    valC := 5.U
  }.elsewhen(io.change === Coin.DIME) {
    valC := 2.U
  }.elsewhen(io.change === Coin.NICKEL) {
    valC := 1.U
  }.otherwise {
    valC := 0.U
  }
  
  val valB = Mux(io.beverage, 5.U(5.W), 0.U(5.W))
  
  // Update balance
  balance := balance + valD.asSInt - (valC + valB).asSInt
  
  io.balance := balance
}

// Top-level module that combines vending machine and monitor
class VendingTop extends Module {
  val io = IO(new Bundle {
    val deposit = Input(UInt(2.W))
    val change = Output(UInt(2.W))
    val beverage = Output(Bool())
    val enable = Output(Bool())
    val balance = Output(SInt(5.W))
    
    // Debug outputs
    val debugState = Output(UInt(2.W))
    val debugT5 = Output(UInt(4.W))
    val debugT10 = Output(UInt(4.W))
    val debugT25 = Output(UInt(4.W))
    val debugL5 = Output(UInt(3.W))
    val debugL10 = Output(UInt(2.W))
    val debugL25 = Output(UInt(1.W))
    val debugTotal = Output(UInt(4.W))
  })
  
  val vending = Module(new Vending(4))
  val monitor = Module(new Monitor)
  
  // Connect vending machine
  vending.io.deposit := io.deposit
  io.change := vending.io.change
  io.beverage := vending.io.beverage
  io.enable := vending.io.enable
  
  // Connect monitor
  monitor.io.deposit := io.deposit
  monitor.io.beverage := vending.io.beverage
  monitor.io.change := vending.io.change
  io.balance := monitor.io.balance
  
  // Debug outputs
  io.debugState := vending.io.debugState
  io.debugT5 := vending.io.debugT5
  io.debugT10 := vending.io.debugT10
  io.debugT25 := vending.io.debugT25
  io.debugL5 := vending.io.debugL5
  io.debugL10 := vending.io.debugL10
  io.debugL25 := vending.io.debugL25
  io.debugTotal := vending.io.debugTotal
}

// Environment module for testing (simplified version)
class Environment extends Module {
  val io = IO(new Bundle {
    val balance = Output(SInt(5.W))
    val deposit = Output(UInt(2.W))
    val change = Output(UInt(2.W))
    val beverage = Output(Bool())
    val enable = Output(Bool())
  })
  
  val vending = Module(new Vending(4))
  val monitor = Module(new Monitor)
  
  // Simple test pattern generator
  val testCounter = RegInit(0.U(8.W))
  testCounter := testCounter + 1.U
  
  // Generate test coins in a pattern - using when/otherwise chain
  val testDeposit = Wire(UInt(2.W))
  val counterValue = testCounter(3, 0)
  when(counterValue === 0.U) {
    testDeposit := Coin.NICKEL
  }.elsewhen(counterValue === 1.U) {
    testDeposit := Coin.NICKEL
  }.elsewhen(counterValue === 2.U) {
    testDeposit := Coin.NICKEL
  }.elsewhen(counterValue === 3.U) {
    testDeposit := Coin.NICKEL
  }.elsewhen(counterValue === 4.U) {
    testDeposit := Coin.NICKEL
  }.elsewhen(counterValue === 5.U) {
    testDeposit := Coin.DIME
  }.elsewhen(counterValue === 6.U) {
    testDeposit := Coin.DIME
  }.elsewhen(counterValue === 7.U) {
    testDeposit := Coin.QUARTER
  }.elsewhen(counterValue === 8.U) {
    testDeposit := Coin.NONE
  }.elsewhen(counterValue === 9.U) {
    testDeposit := Coin.NONE
  }.elsewhen(counterValue === 10.U) {
    testDeposit := Coin.NICKEL
  }.elsewhen(counterValue === 11.U) {
    testDeposit := Coin.DIME
  }.elsewhen(counterValue === 12.U) {
    testDeposit := Coin.QUARTER
  }.elsewhen(counterValue === 13.U) {
    testDeposit := Coin.NONE
  }.elsewhen(counterValue === 14.U) {
    testDeposit := Coin.NONE
  }.otherwise {
    testDeposit := Coin.NONE
  }
  
  // Only deposit when enabled
  val deposit = Mux(vending.io.enable, testDeposit, Coin.NONE)
  
  // Connect vending machine
  vending.io.deposit := deposit
  
  // Connect monitor
  monitor.io.deposit := deposit
  monitor.io.beverage := vending.io.beverage
  monitor.io.change := vending.io.change
  
  // Outputs
  io.balance := monitor.io.balance
  io.deposit := deposit
  io.change := vending.io.change
  io.beverage := vending.io.beverage
  io.enable := vending.io.enable
}

object VerilogGenerator extends App {
  emitVerilog(new VendingTop(), args)
}