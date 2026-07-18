package withw.gigamax

import chisel3._

/** Encodings for the nine commands in the public single-bus SMV models. */
private object CommandCode {
  val width = 4

  def idle: UInt = 0.U(width.W)
  def readShared: UInt = 1.U(width.W)
  def readOwned: UInt = 2.U(width.W)
  def writeInvalid: UInt = 3.U(width.W)
  def writeShared: UInt = 4.U(width.W)
  def writeRespInvalid: UInt = 5.U(width.W)
  def writeRespShared: UInt = 6.U(width.W)
  def invalidate: UInt = 7.U(width.W)
  def response: UInt = 8.U(width.W)

  /** Map every hardware input encoding into the exact SMV command domain.
    *
    * The SMV fallback set contains all nine commands. Codes 9--15 are mapped
    * to idle, so every Chisel input selects a legal command and every SMV set
    * member remains selectable. The duplicate mapping does not add behavior.
    */
  def legalize(raw: UInt): UInt = Mux(raw <= response, raw, idle)
}

/** Encodings for the cache state and snoop enumerations. */
private object CacheCode {
  val width = 2

  def invalid: UInt = 0.U(width.W)
  def shared: UInt = 1.U(width.W)
  def owned: UInt = 2.U(width.W)
}

/** Exact design deltas between the two public single-bus SMV versions. */
private final case class PublicVariant(
  sourceStem: String,
  selectorReturnsP0ForP2: Boolean,
  duplicateReadSharedSnoopGuard: Boolean
)

private object PublicVariant {
  val original: PublicVariant = PublicVariant(
    sourceStem = "gigamax",
    selectorReturnsP0ForP2 = true,
    duplicateReadSharedSnoopGuard = false
  )

  val fixed: PublicVariant = PublicVariant(
    sourceStem = "gigamax_fixed",
    selectorReturnsP0ForP2 = false,
    duplicateReadSharedSnoopGuard = true
  )
}

/** One processor instance from the public single-bus Gigamax SMV model.
  *
  * All right-hand sides use the old register values, matching simultaneous SMV
  * `next(...)` evaluation. Set-valued assignments and the unassigned
  * `reply-stall` variable are explicit primary inputs.
  */
private final class PublicProcessor(variant: PublicVariant) extends Module {
  val CMD = IO(Input(UInt(CommandCode.width.W)))
  val master = IO(Input(Bool()))
  val REPLY_WAITING = IO(Input(Bool()))
  val REPLY_STALL = IO(Input(Bool()))

  // {read-shared, read-owned} when a master has an invalid cache line.
  val chooseReadOwned = IO(Input(Bool()))
  // {shared, invalid} in the spontaneous shared-state transition.
  val chooseInvalidFromShared = IO(Input(Bool()))
  // bus-device.reply-stall has neither init nor next in the source SMV.
  val unconstrainedReplyStall = IO(Input(Bool()))

  val cmd = IO(Output(UInt(CommandCode.width.W)))
  val state = IO(Output(UInt(CacheCode.width.W)))
  val snoop = IO(Output(UInt(CacheCode.width.W)))
  val waiting = IO(Output(Bool()))
  val replyOwned = IO(Output(Bool()))
  val replyWaiting = IO(Output(Bool()))
  val replyStall = IO(Output(Bool()))
  val abort = IO(Output(Bool()))
  val readable = IO(Output(Bool()))
  val writable = IO(Output(Bool()))

  // The reset edge represents the SMV initial-state predicate. There is no
  // additional functional reset in the source model.
  private val stateReg = RegInit(CacheCode.invalid)
  private val snoopReg = RegInit(CacheCode.invalid)
  private val waitingReg = RegInit(false.B)

  private val isRead = CMD === CommandCode.readShared || CMD === CommandCode.readOwned
  private val abortNow = REPLY_STALL || (isRead && REPLY_WAITING)

  abort := abortNow
  state := stateReg
  snoop := snoopReg
  waiting := waitingReg
  replyOwned := !master && stateReg === CacheCode.owned
  replyWaiting := !master && waitingReg
  replyStall := unconstrainedReplyStall
  readable := (stateReg === CacheCode.shared || stateReg === CacheCode.owned) && !waitingReg
  writable := stateReg === CacheCode.owned && !waitingReg

  cmd := Mux(
    master && stateReg === CacheCode.invalid,
    Mux(chooseReadOwned, CommandCode.readOwned, CommandCode.readShared),
    Mux(
      master && stateReg === CacheCode.shared,
      CommandCode.readOwned,
      Mux(
        master && stateReg === CacheCode.owned && snoopReg === CacheCode.owned,
        CommandCode.writeRespInvalid,
        Mux(
          master && stateReg === CacheCode.owned && snoopReg === CacheCode.shared,
          CommandCode.writeRespShared,
          Mux(
            master && stateReg === CacheCode.owned && snoopReg === CacheCode.invalid,
            CommandCode.writeInvalid,
            CommandCode.idle
          )
        )
      )
    )
  )

  private val stateNext = WireDefault(stateReg)
  when(!abortNow) {
    when(master) {
      when(CMD === CommandCode.readShared) {
        stateNext := CacheCode.shared
      }.elsewhen(CMD === CommandCode.readOwned) {
        stateNext := CacheCode.owned
      }.elsewhen(
        CMD === CommandCode.writeInvalid || CMD === CommandCode.writeRespInvalid
      ) {
        stateNext := CacheCode.invalid
      }.elsewhen(
        CMD === CommandCode.writeShared || CMD === CommandCode.writeRespShared
      ) {
        stateNext := CacheCode.shared
      }
    }.elsewhen(
      stateReg === CacheCode.shared &&
        (CMD === CommandCode.readOwned || CMD === CommandCode.invalidate)
    ) {
      stateNext := CacheCode.invalid
    }.elsewhen(stateReg === CacheCode.shared) {
      stateNext := Mux(chooseInvalidFromShared, CacheCode.invalid, CacheCode.shared)
    }
  }

  private val secondOwnedSnoopCommand =
    if (variant.duplicateReadSharedSnoopGuard) CommandCode.readShared
    else CommandCode.readOwned
  private val snoopNext = WireDefault(snoopReg)
  when(!abortNow) {
    when(!master && stateReg === CacheCode.owned && CMD === CommandCode.readShared) {
      snoopNext := CacheCode.shared
    }.elsewhen(
      !master && stateReg === CacheCode.owned && CMD === secondOwnedSnoopCommand
    ) {
      // In gigamax_fixed.smv this guard deliberately duplicates the preceding
      // read-shared guard. First-match case semantics make this arm unreachable.
      snoopNext := CacheCode.owned
    }.elsewhen(
      master &&
        (CMD === CommandCode.writeRespInvalid || CMD === CommandCode.writeRespShared)
    ) {
      snoopNext := CacheCode.invalid
    }
  }

  private val waitingNext = WireDefault(waitingReg)
  when(!abortNow) {
    when(master && isRead) {
      waitingNext := true.B
    }.elsewhen(
      !master &&
        (CMD === CommandCode.response ||
          CMD === CommandCode.writeRespInvalid ||
          CMD === CommandCode.writeRespShared)
    ) {
      waitingNext := false.B
    }
  }

  stateReg := stateNext
  snoopReg := snoopNext
  waitingReg := waitingNext
}

/** Memory instance from the public single-bus Gigamax SMV model. */
private final class PublicMemory extends Module {
  val CMD = IO(Input(UInt(CommandCode.width.W)))
  val master = IO(Input(Bool()))
  val REPLY_OWNED = IO(Input(Bool()))
  val REPLY_WAITING = IO(Input(Bool()))
  val REPLY_STALL = IO(Input(Bool()))

  // {response, idle} when memory is the active master.
  val chooseResponse = IO(Input(Bool()))
  // The default {FALSE, TRUE} reply-stall choice.
  val chooseReplyStall = IO(Input(Bool()))

  val cmd = IO(Output(UInt(CommandCode.width.W)))
  val busy = IO(Output(Bool()))
  val replyStall = IO(Output(Bool()))
  val abort = IO(Output(Bool()))

  private val busyReg = RegInit(false.B)
  private val isRead = CMD === CommandCode.readShared || CMD === CommandCode.readOwned
  private val abortNow = REPLY_STALL || (isRead && REPLY_WAITING) || (isRead && REPLY_OWNED)
  private val commandNeedsBusyStall =
    isRead ||
      CMD === CommandCode.writeInvalid ||
      CMD === CommandCode.writeShared ||
      CMD === CommandCode.writeRespInvalid ||
      CMD === CommandCode.writeRespShared

  busy := busyReg
  abort := abortNow
  cmd := Mux(
    master && busyReg,
    Mux(chooseResponse, CommandCode.response, CommandCode.idle),
    CommandCode.idle
  )
  replyStall := Mux(busyReg && commandNeedsBusyStall, true.B, chooseReplyStall)

  private val busyNext = WireDefault(busyReg)
  when(!abortNow) {
    when(master && CMD === CommandCode.response) {
      busyNext := false.B
    }.elsewhen(!master && isRead) {
      busyNext := true.B
    }
  }
  busyReg := busyNext
}

/** Translation of either public single-bus Gigamax SMV version.
  *
  * The top has explicit inputs for every SMV nondeterministic choice. The SMV
  * `SPEC` clauses are not hardware state; all proposition atoms used by them
  * are exposed as outputs so a property layer can bind them without changing
  * the transition system.
  */
private final class PublicGigamax(variant: PublicVariant) extends Module {
  override def desiredName: String = "main"

  val ndP0Master = IO(Input(Bool()))
  val ndP1Master = IO(Input(Bool()))
  val ndP2Master = IO(Input(Bool()))
  val ndMMaster = IO(Input(Bool()))
  val ndCmdFallbackRaw = IO(Input(UInt(CommandCode.width.W)))

  val ndP0ReadOwned = IO(Input(Bool()))
  val ndP1ReadOwned = IO(Input(Bool()))
  val ndP2ReadOwned = IO(Input(Bool()))
  val ndP0SharedToInvalid = IO(Input(Bool()))
  val ndP1SharedToInvalid = IO(Input(Bool()))
  val ndP2SharedToInvalid = IO(Input(Bool()))
  val ndP0ReplyStall = IO(Input(Bool()))
  val ndP1ReplyStall = IO(Input(Bool()))
  val ndP2ReplyStall = IO(Input(Bool()))
  val ndMResponse = IO(Input(Bool()))
  val ndMReplyStall = IO(Input(Bool()))

  val CMD = IO(Output(UInt(CommandCode.width.W)))
  val REPLY_OWNED = IO(Output(Bool()))
  val REPLY_WAITING = IO(Output(Bool()))
  val REPLY_STALL = IO(Output(Bool()))

  val p0Master = IO(Output(Bool()))
  val p1Master = IO(Output(Bool()))
  val p2Master = IO(Output(Bool()))
  val mMaster = IO(Output(Bool()))
  val p0Cmd = IO(Output(UInt(CommandCode.width.W)))
  val p1Cmd = IO(Output(UInt(CommandCode.width.W)))
  val p2Cmd = IO(Output(UInt(CommandCode.width.W)))
  val mCmd = IO(Output(UInt(CommandCode.width.W)))

  val p0State = IO(Output(UInt(CacheCode.width.W)))
  val p1State = IO(Output(UInt(CacheCode.width.W)))
  val p2State = IO(Output(UInt(CacheCode.width.W)))
  val p0Snoop = IO(Output(UInt(CacheCode.width.W)))
  val p1Snoop = IO(Output(UInt(CacheCode.width.W)))
  val p2Snoop = IO(Output(UInt(CacheCode.width.W)))
  val p0Waiting = IO(Output(Bool()))
  val p1Waiting = IO(Output(Bool()))
  val p2Waiting = IO(Output(Bool()))
  val mBusy = IO(Output(Bool()))

  val p0ReplyStall = IO(Output(Bool()))
  val p1ReplyStall = IO(Output(Bool()))
  val p2ReplyStall = IO(Output(Bool()))
  val mReplyStall = IO(Output(Bool()))
  val p0Abort = IO(Output(Bool()))
  val p1Abort = IO(Output(Bool()))
  val p2Abort = IO(Output(Bool()))
  val mAbort = IO(Output(Bool()))
  val p0Readable = IO(Output(Bool()))
  val p1Readable = IO(Output(Bool()))
  val p2Readable = IO(Output(Bool()))
  val p0Writable = IO(Output(Bool()))
  val p1Writable = IO(Output(Bool()))
  val p2Writable = IO(Output(Bool()))

  private val p0 = Module(new PublicProcessor(variant))
  private val p1 = Module(new PublicProcessor(variant))
  private val p2 = Module(new PublicProcessor(variant))
  private val memory = Module(new PublicMemory)

  private val p0MasterNow = ndP0Master
  private val p1MasterNow = !p0MasterNow && ndP1Master
  private val p2MasterNow = !p0MasterNow && !p1MasterNow && ndP2Master
  private val mMasterNow = !p0MasterNow && !p1MasterNow && !p2MasterNow && ndMMaster

  private val selectedCMD = Wire(UInt(CommandCode.width.W))
  when(p1.cmd === CommandCode.idle && p2.cmd === CommandCode.idle && memory.cmd === CommandCode.idle) {
    selectedCMD := p0.cmd
  }.elsewhen(
    p0.cmd === CommandCode.idle && p2.cmd === CommandCode.idle && memory.cmd === CommandCode.idle
  ) {
    selectedCMD := p1.cmd
  }.elsewhen(
    p0.cmd === CommandCode.idle && p1.cmd === CommandCode.idle && memory.cmd === CommandCode.idle
  ) {
    // gigamax.smv intentionally returns p0.cmd here; gigamax_fixed.smv
    // returns p2.cmd. This is the only command-selector design delta.
    selectedCMD := (if (variant.selectorReturnsP0ForP2) p0.cmd else p2.cmd)
  }.elsewhen(
    p0.cmd === CommandCode.idle && p1.cmd === CommandCode.idle && p2.cmd === CommandCode.idle
  ) {
    selectedCMD := memory.cmd
  }.otherwise {
    selectedCMD := CommandCode.legalize(ndCmdFallbackRaw)
  }

  private val replyOwnedNow = p0.replyOwned || p1.replyOwned || p2.replyOwned
  private val replyWaitingNow = p0.replyWaiting || p1.replyWaiting || p2.replyWaiting
  private val replyStallNow = p0.replyStall || p1.replyStall || p2.replyStall || memory.replyStall

  private def connectProcessor(
    processor: PublicProcessor,
    masterNow: Bool,
    readOwnedChoice: Bool,
    sharedToInvalidChoice: Bool,
    replyStallChoice: Bool
  ): Unit = {
    processor.CMD := selectedCMD
    processor.master := masterNow
    processor.REPLY_WAITING := replyWaitingNow
    processor.REPLY_STALL := replyStallNow
    processor.chooseReadOwned := readOwnedChoice
    processor.chooseInvalidFromShared := sharedToInvalidChoice
    processor.unconstrainedReplyStall := replyStallChoice
  }

  connectProcessor(p0, p0MasterNow, ndP0ReadOwned, ndP0SharedToInvalid, ndP0ReplyStall)
  connectProcessor(p1, p1MasterNow, ndP1ReadOwned, ndP1SharedToInvalid, ndP1ReplyStall)
  connectProcessor(p2, p2MasterNow, ndP2ReadOwned, ndP2SharedToInvalid, ndP2ReplyStall)

  memory.CMD := selectedCMD
  memory.master := mMasterNow
  memory.REPLY_OWNED := replyOwnedNow
  memory.REPLY_WAITING := replyWaitingNow
  memory.REPLY_STALL := replyStallNow
  memory.chooseResponse := ndMResponse
  memory.chooseReplyStall := ndMReplyStall

  CMD := selectedCMD
  REPLY_OWNED := replyOwnedNow
  REPLY_WAITING := replyWaitingNow
  REPLY_STALL := replyStallNow

  p0Master := p0MasterNow
  p1Master := p1MasterNow
  p2Master := p2MasterNow
  mMaster := mMasterNow
  p0Cmd := p0.cmd
  p1Cmd := p1.cmd
  p2Cmd := p2.cmd
  mCmd := memory.cmd

  p0State := p0.state
  p1State := p1.state
  p2State := p2.state
  p0Snoop := p0.snoop
  p1Snoop := p1.snoop
  p2Snoop := p2.snoop
  p0Waiting := p0.waiting
  p1Waiting := p1.waiting
  p2Waiting := p2.waiting
  mBusy := memory.busy

  p0ReplyStall := p0.replyStall
  p1ReplyStall := p1.replyStall
  p2ReplyStall := p2.replyStall
  mReplyStall := memory.replyStall
  p0Abort := p0.abort
  p1Abort := p1.abort
  p2Abort := p2.abort
  mAbort := memory.abort
  p0Readable := p0.readable
  p1Readable := p1.readable
  p2Readable := p2.readable
  p0Writable := p0.writable
  p1Writable := p1.writable
  p2Writable := p2.writable
}

/** Encodings used by the paper-derived deadlock reconstruction. */
private object PaperCode {
  object Action {
    val width = 4
    def idle: UInt = 0.U(width.W)
    def c1ReadMiss: UInt = 1.U(width.W)
    def c3ReadMiss: UInt = 2.U(width.W)
    def c2WriteResponse: UInt = 3.U(width.W)
    def c1ReceiveResponse: UInt = 4.U(width.W)
    def c2ReplaceReadMiss: UInt = 5.U(width.W)
    def completeC1Request: UInt = 6.U(width.W)
    def completeC2Request: UInt = 7.U(width.W)
    def recover: UInt = 8.U(width.W)
    def grantC1Shared: UInt = 9.U(width.W)
    def grantC1Owned: UInt = 10.U(width.W)
    def grantC2Shared: UInt = 11.U(width.W)
    def grantC2Owned: UInt = 12.U(width.W)
    def grantC3Shared: UInt = 13.U(width.W)
    def grantC3Owned: UInt = 14.U(width.W)

    // Raw code 15 duplicates idle; all 15 SMV action values remain selectable.
    def legalize(raw: UInt): UInt = Mux(raw <= grantC3Owned, raw, idle)
  }

  object Owner {
    val width = 2
    def mem: UInt = 0.U(width.W)
    def c1: UInt = 1.U(width.W)
    def c2: UInt = 2.U(width.W)
    def c3: UInt = 3.U(width.W)
  }
}

/** Translation of gigamax_paper_deadlock.smv.
  *
  * The five-action counterexample and its closed circular wait are preserved;
  * no recovery action can escape once deadlockCycle is true because that guard
  * remains first in every source `case` expression.
  */
private final class PaperDeadlockGigamax extends Module {
  override def desiredName: String = "main"

  val actionRaw = IO(Input(UInt(PaperCode.Action.width.W)))

  val action = IO(Output(UInt(PaperCode.Action.width.W)))
  val c1Watcher = IO(Output(UInt(CacheCode.width.W)))
  val c2Watcher = IO(Output(UInt(CacheCode.width.W)))
  val c3Watcher = IO(Output(UInt(CacheCode.width.W)))
  val owner = IO(Output(UInt(PaperCode.Owner.width.W)))
  val c1LocalInterlock = IO(Output(Bool()))
  val c2GlobalInterlock = IO(Output(Bool()))
  val globalQueueC1ReadPublic = IO(Output(Bool()))
  val cluster1QueueC2ReadPublic = IO(Output(Bool()))
  val flushC2Pending = IO(Output(Bool()))
  val c3ReadPending = IO(Output(Bool()))
  val responseToC1Pending = IO(Output(Bool()))
  val c1DataInMemory = IO(Output(Bool()))
  val c1RequestBlocked = IO(Output(Bool()))
  val c2RequestBlocked = IO(Output(Bool()))
  val deadlockCycle = IO(Output(Bool()))
  val normalReady = IO(Output(Bool()))

  private val c1WatcherReg = RegInit(CacheCode.invalid)
  private val c2WatcherReg = RegInit(CacheCode.owned)
  private val c3WatcherReg = RegInit(CacheCode.invalid)
  private val ownerReg = RegInit(PaperCode.Owner.c2)
  private val c1LocalInterlockReg = RegInit(false.B)
  private val c2GlobalInterlockReg = RegInit(false.B)
  private val globalQueueReg = RegInit(false.B)
  private val cluster1QueueReg = RegInit(false.B)
  private val flushC2PendingReg = RegInit(false.B)
  private val c3ReadPendingReg = RegInit(false.B)
  private val responseToC1PendingReg = RegInit(false.B)
  private val c1DataInMemoryReg = RegInit(false.B)

  private val actionNow = PaperCode.Action.legalize(actionRaw)
  private val c1RequestBlockedNow = globalQueueReg && c2GlobalInterlockReg
  private val c2RequestBlockedNow = cluster1QueueReg && c1LocalInterlockReg
  private val deadlockCycleNow =
    c1LocalInterlockReg && c2GlobalInterlockReg &&
      c1RequestBlockedNow && c2RequestBlockedNow
  private val normalReadyNow =
    !c1LocalInterlockReg && !c2GlobalInterlockReg &&
      !globalQueueReg && !cluster1QueueReg &&
      !flushC2PendingReg && !c3ReadPendingReg &&
      !responseToC1PendingReg

  private val startC1 =
    actionNow === PaperCode.Action.c1ReadMiss && normalReadyNow && ownerReg === PaperCode.Owner.c2
  private val startC3 =
    actionNow === PaperCode.Action.c3ReadMiss &&
      c1LocalInterlockReg && globalQueueReg && ownerReg === PaperCode.Owner.c2
  private val c2Responds =
    actionNow === PaperCode.Action.c2WriteResponse && flushC2PendingReg && c3ReadPendingReg
  private val c1Receives =
    actionNow === PaperCode.Action.c1ReceiveResponse && responseToC1PendingReg
  private val c2ReplacesAndMisses =
    actionNow === PaperCode.Action.c2ReplaceReadMiss &&
      c1LocalInterlockReg && globalQueueReg && c1DataInMemoryReg &&
      c2WatcherReg === CacheCode.shared && !cluster1QueueReg
  private val completesC1 =
    actionNow === PaperCode.Action.completeC1Request && globalQueueReg && !c2GlobalInterlockReg
  private val completesC2 =
    actionNow === PaperCode.Action.completeC2Request && cluster1QueueReg && !c1LocalInterlockReg

  private val c1LocalInterlockNext = WireDefault(c1LocalInterlockReg)
  when(deadlockCycleNow) {
    c1LocalInterlockNext := true.B
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    c1LocalInterlockNext := false.B
  }.elsewhen(startC1) {
    c1LocalInterlockNext := true.B
  }.elsewhen(completesC1) {
    c1LocalInterlockNext := false.B
  }

  private val c2GlobalInterlockNext = WireDefault(c2GlobalInterlockReg)
  when(deadlockCycleNow) {
    c2GlobalInterlockNext := true.B
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    c2GlobalInterlockNext := false.B
  }.elsewhen(c2ReplacesAndMisses) {
    c2GlobalInterlockNext := true.B
  }.elsewhen(completesC2) {
    c2GlobalInterlockNext := false.B
  }

  private val globalQueueNext = WireDefault(globalQueueReg)
  when(deadlockCycleNow) {
    globalQueueNext := true.B
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    globalQueueNext := false.B
  }.elsewhen(startC1) {
    globalQueueNext := true.B
  }.elsewhen(completesC1) {
    globalQueueNext := false.B
  }

  private val cluster1QueueNext = WireDefault(cluster1QueueReg)
  when(deadlockCycleNow) {
    cluster1QueueNext := true.B
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    cluster1QueueNext := false.B
  }.elsewhen(c2ReplacesAndMisses) {
    cluster1QueueNext := true.B
  }.elsewhen(completesC2) {
    cluster1QueueNext := false.B
  }

  private val flushC2PendingNext = WireDefault(flushC2PendingReg)
  when(deadlockCycleNow) {
    flushC2PendingNext := flushC2PendingReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    flushC2PendingNext := false.B
  }.elsewhen(startC3) {
    flushC2PendingNext := true.B
  }.elsewhen(c2Responds) {
    flushC2PendingNext := false.B
  }

  private val c3ReadPendingNext = WireDefault(c3ReadPendingReg)
  when(deadlockCycleNow) {
    c3ReadPendingNext := c3ReadPendingReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    c3ReadPendingNext := false.B
  }.elsewhen(startC3) {
    c3ReadPendingNext := true.B
  }.elsewhen(c2Responds) {
    c3ReadPendingNext := false.B
  }

  private val responseToC1PendingNext = WireDefault(responseToC1PendingReg)
  when(deadlockCycleNow) {
    responseToC1PendingNext := responseToC1PendingReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    responseToC1PendingNext := false.B
  }.elsewhen(c2Responds) {
    responseToC1PendingNext := true.B
  }.elsewhen(c1Receives) {
    responseToC1PendingNext := false.B
  }

  private val c1DataInMemoryNext = WireDefault(c1DataInMemoryReg)
  when(deadlockCycleNow) {
    c1DataInMemoryNext := c1DataInMemoryReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    c1DataInMemoryNext := false.B
  }.elsewhen(c1Receives) {
    c1DataInMemoryNext := true.B
  }

  private val ownerNext = WireDefault(ownerReg)
  when(deadlockCycleNow) {
    ownerNext := ownerReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    ownerNext := PaperCode.Owner.c2
  }.elsewhen(c2Responds || completesC1 || completesC2) {
    ownerNext := PaperCode.Owner.mem
  }.elsewhen(
    normalReadyNow &&
      (actionNow === PaperCode.Action.grantC1Shared ||
        actionNow === PaperCode.Action.grantC2Shared ||
        actionNow === PaperCode.Action.grantC3Shared)
  ) {
    ownerNext := PaperCode.Owner.mem
  }.elsewhen(actionNow === PaperCode.Action.grantC1Owned && normalReadyNow) {
    ownerNext := PaperCode.Owner.c1
  }.elsewhen(actionNow === PaperCode.Action.grantC2Owned && normalReadyNow) {
    ownerNext := PaperCode.Owner.c2
  }.elsewhen(actionNow === PaperCode.Action.grantC3Owned && normalReadyNow) {
    ownerNext := PaperCode.Owner.c3
  }

  private val c1WatcherNext = WireDefault(c1WatcherReg)
  when(deadlockCycleNow) {
    c1WatcherNext := c1WatcherReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    c1WatcherNext := CacheCode.invalid
  }.elsewhen(completesC1) {
    c1WatcherNext := CacheCode.shared
  }.elsewhen(actionNow === PaperCode.Action.grantC1Shared && normalReadyNow) {
    c1WatcherNext := CacheCode.shared
  }.elsewhen(
    actionNow === PaperCode.Action.grantC2Shared && normalReadyNow && c1WatcherReg === CacheCode.owned
  ) {
    c1WatcherNext := CacheCode.shared
  }.elsewhen(
    actionNow === PaperCode.Action.grantC3Shared && normalReadyNow && c1WatcherReg === CacheCode.owned
  ) {
    c1WatcherNext := CacheCode.shared
  }.elsewhen(actionNow === PaperCode.Action.grantC1Owned && normalReadyNow) {
    c1WatcherNext := CacheCode.owned
  }.elsewhen(
    normalReadyNow &&
      (actionNow === PaperCode.Action.grantC2Owned || actionNow === PaperCode.Action.grantC3Owned)
  ) {
    c1WatcherNext := CacheCode.invalid
  }

  private val c2WatcherNext = WireDefault(c2WatcherReg)
  when(deadlockCycleNow) {
    c2WatcherNext := c2WatcherReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    c2WatcherNext := CacheCode.owned
  }.elsewhen(c2Responds) {
    c2WatcherNext := CacheCode.shared
  }.elsewhen(c2ReplacesAndMisses) {
    c2WatcherNext := CacheCode.invalid
  }.elsewhen(completesC1 && c2WatcherReg === CacheCode.owned) {
    c2WatcherNext := CacheCode.shared
  }.elsewhen(completesC2) {
    c2WatcherNext := CacheCode.shared
  }.elsewhen(
    actionNow === PaperCode.Action.grantC1Shared && normalReadyNow && c2WatcherReg === CacheCode.owned
  ) {
    c2WatcherNext := CacheCode.shared
  }.elsewhen(actionNow === PaperCode.Action.grantC2Shared && normalReadyNow) {
    c2WatcherNext := CacheCode.shared
  }.elsewhen(
    actionNow === PaperCode.Action.grantC3Shared && normalReadyNow && c2WatcherReg === CacheCode.owned
  ) {
    c2WatcherNext := CacheCode.shared
  }.elsewhen(actionNow === PaperCode.Action.grantC2Owned && normalReadyNow) {
    c2WatcherNext := CacheCode.owned
  }.elsewhen(
    normalReadyNow &&
      (actionNow === PaperCode.Action.grantC1Owned || actionNow === PaperCode.Action.grantC3Owned)
  ) {
    c2WatcherNext := CacheCode.invalid
  }

  private val c3WatcherNext = WireDefault(c3WatcherReg)
  when(deadlockCycleNow) {
    c3WatcherNext := c3WatcherReg
  }.elsewhen(actionNow === PaperCode.Action.recover) {
    c3WatcherNext := CacheCode.invalid
  }.elsewhen(c2Responds) {
    c3WatcherNext := CacheCode.shared
  }.elsewhen(
    actionNow === PaperCode.Action.grantC1Shared && normalReadyNow && c3WatcherReg === CacheCode.owned
  ) {
    c3WatcherNext := CacheCode.shared
  }.elsewhen(
    actionNow === PaperCode.Action.grantC2Shared && normalReadyNow && c3WatcherReg === CacheCode.owned
  ) {
    c3WatcherNext := CacheCode.shared
  }.elsewhen(actionNow === PaperCode.Action.grantC3Shared && normalReadyNow) {
    c3WatcherNext := CacheCode.shared
  }.elsewhen(actionNow === PaperCode.Action.grantC3Owned && normalReadyNow) {
    c3WatcherNext := CacheCode.owned
  }.elsewhen(
    normalReadyNow &&
      (actionNow === PaperCode.Action.grantC1Owned || actionNow === PaperCode.Action.grantC2Owned)
  ) {
    c3WatcherNext := CacheCode.invalid
  }

  c1LocalInterlockReg := c1LocalInterlockNext
  c2GlobalInterlockReg := c2GlobalInterlockNext
  globalQueueReg := globalQueueNext
  cluster1QueueReg := cluster1QueueNext
  flushC2PendingReg := flushC2PendingNext
  c3ReadPendingReg := c3ReadPendingNext
  responseToC1PendingReg := responseToC1PendingNext
  c1DataInMemoryReg := c1DataInMemoryNext
  ownerReg := ownerNext
  c1WatcherReg := c1WatcherNext
  c2WatcherReg := c2WatcherNext
  c3WatcherReg := c3WatcherNext

  action := actionNow
  c1Watcher := c1WatcherReg
  c2Watcher := c2WatcherReg
  c3Watcher := c3WatcherReg
  owner := ownerReg
  c1LocalInterlock := c1LocalInterlockReg
  c2GlobalInterlock := c2GlobalInterlockReg
  globalQueueC1ReadPublic := globalQueueReg
  cluster1QueueC2ReadPublic := cluster1QueueReg
  flushC2Pending := flushC2PendingReg
  c3ReadPending := c3ReadPendingReg
  responseToC1Pending := responseToC1PendingReg
  c1DataInMemory := c1DataInMemoryReg
  c1RequestBlocked := c1RequestBlockedNow
  c2RequestBlocked := c2RequestBlockedNow
  deadlockCycle := deadlockCycleNow
  normalReady := normalReadyNow
}

private object GigamaxGenerators {
  val all: Seq[(String, () => RawModule)] = Seq(
    PublicVariant.original.sourceStem -> (() => new PublicGigamax(PublicVariant.original)),
    PublicVariant.fixed.sourceStem -> (() => new PublicGigamax(PublicVariant.fixed)),
    "gigamax_paper_deadlock" -> (() => new PaperDeadlockGigamax)
  )
}
