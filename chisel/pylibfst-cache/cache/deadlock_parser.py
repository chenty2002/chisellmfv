# Copyright (c) 2022 Manfred SCHLAEGL <manfred.schlaegl@gmx.at>
#
# SPDX-License-Identifier: BSD 3-clause "New" or "Revised" License
#

import sys
import pylibfst
import re

# 定义ANSI转义序列颜色
GREEN = '\033[92m'
CYAN = '\033[96m'
MAGENTA = '\033[95m'
YELLOW = '\033[93m'
RED = '\033[91m'
RED_UNDERLINE = '\033[91;4m'
RESET = '\033[0m'

# l1tagbits = 3
# l1setbits = 7
# l2tagbits = 3
# l2setbits = 7
# l3tagbits = 3
# l3setbits = 7
# offsetbits = 6

l1tagbits = 3
l1setbits = 1
l2tagbits = 2
l2setbits = 2
l3tagbits = 2
l3setbits = 2
offsetbits = 1


def printi(indent, *args):
    for i in range(indent):
        print("  ", end="")
    print("+ ", end="")
    print(*args)

def dumpInfo(fst):

    verStr = pylibfst.lib.fstReaderGetVersionString(fst)
    print("Version String:           " + pylibfst.helpers.string(verStr))

    date = pylibfst.lib.fstReaderGetDateString(fst)
    print("Date String:              " + pylibfst.helpers.string(date))

    fileType = pylibfst.lib.fstReaderGetFileType(fst)
    print("File Type:                " + str(fileType))

    varCount = pylibfst.lib.fstReaderGetVarCount(fst)
    print("Var Count:                " + str(varCount))

    scopeCount = pylibfst.lib.fstReaderGetScopeCount(fst)
    print("Scope Count:              " + str(scopeCount))

    aliasCount = pylibfst.lib.fstReaderGetAliasCount(fst)
    print("Alias Count:              " + str(aliasCount))

    startTime = pylibfst.lib.fstReaderGetStartTime(fst)
    endTime = pylibfst.lib.fstReaderGetEndTime(fst)
    print("Start Time:               " + str(startTime))
    print("End Time:                 " + str(endTime))

    timeScale = pylibfst.lib.fstReaderGetTimescale(fst)
    print("Time Scale:               " + str(timeScale))

    timeZero = pylibfst.lib.fstReaderGetTimezero(fst)
    print("Time Zero:                " + str(timeZero))

    valChSecCnt = pylibfst.lib.fstReaderGetValueChangeSectionCount(fst)
    print("Value Change Section Cnt: " + str(valChSecCnt))

def dump_signals(fst, signals):

    # get timestamps of all signal changes
    pylibfst.lib.fstReaderSetFacProcessMaskAll(fst)
    timestamps = pylibfst.lib.fstReaderGetTimestamps(fst)

    for signal in signals:
        print("'" + signal.name + "'; ", end="")
    print()

    buf = pylibfst.ffi.new("char[256]")

    for ts in range(timestamps.nvals):
        time = timestamps.val[ts]
        print("{: >5d}; ".format(time), end="")
        for signal in signals:
            val = pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    fst, time, signal.handle, buf
                )
            )
            print(str(val) + "; ", end="")
        print()

    pylibfst.lib.fstReaderFreeTimestamps(timestamps)

# 去掉信号长名字最后一个 . 后面的内容
def get_scope_name(signal):
    return '.'.join(signal.name.split('.')[:-1])
def get_sig_name(signal):
    return signal.name.split('.')[-1]


def get_signal(signals, name):
    for signal in signals.by_name.values():
        if signal.name == name:
            # print(f"找到信号: {signal.name}")
            return signal


def first_halt_mshrid(fst, signals, valids):
    # 获取所有信号变化的时间戳
    pylibfst.lib.fstReaderSetFacProcessMaskAll(fst)
    timestamps = pylibfst.lib.fstReaderGetTimestamps(fst)
    
    if timestamps.nvals == 0:
        print("没有时间戳数据")
        return
    
    buf = pylibfst.ffi.new("char[256]")
    last_time = timestamps.val[timestamps.nvals - 1]
    
    # 找到在最后时刻值为1的信号
    active_valids = {}
    remains = []

    for signal in valids:
        val = pylibfst.helpers.string(
            pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                fst, last_time, signal.handle, buf
            )
        )
        if val == "1":
            active_valids[signal] = last_time  # 初始假设都是最后一拍变为1的
            remains.append(signal)

    if not active_valids:
        print(f"{RED}没有找到在最后时刻值为1的信号{RESET}")
        pylibfst.lib.fstReaderFreeTimestamps(timestamps)
        return
    
    # print(f"{YELLOW}在最后时刻值为1的信号有 {len(active_signals)} 个{RESET}")
    
    # 从后往前遍历所有时间点
    for ts in range(timestamps.nvals - 2, -1, -1):  # 从倒数第二个时间点开始往前
        time = timestamps.val[ts]
        signals_to_remove = []

        for signal in remains:
            val = pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    fst, time, signal.handle, buf
                )
            )
            
            if val == "0":
                # 找到了信号从0变为1的时间点，更新信号变为1的时间
                active_valids[signal] = timestamps.val[ts + 1]
                signals_to_remove.append(signal)

        # 移除不符合条件的信号
        for signal in signals_to_remove:
            remains.remove(signal)
        
        if not remains:
            break
    
    # 输出结果，按变为1的时间排序
    if active_valids:
        print(f"{GREEN}找到 {len(active_valids)} 个请求没有完成的 MSHR:{RESET}")
        sorted_valids = sorted(active_valids.items(), key=lambda x: x[1])
        
        print(f"{CYAN}{'信号名称':<8} | {'起始时间':<6} | {'最后时间':<6} | {'地址信息':<19} | {'状态机 未完成任务'}{RESET}")
        print("-" * 85)
        
        highlight_first = RED
        for signal, change_time in sorted_valids:
            print(f"{highlight_first}", end="")
            print(f"{signal.name.split('.')[-2]:<12} | {change_time:<10} | {last_time:<10} |", end="")
            
            # --------------- 打印地址信息 ---------------
            scope = get_scope_name(signal)
            last_time = timestamps.val[timestamps.nvals - 1]

            # print([signal for signal in signals.by_name.values() if signal.name.startswith(scope)])

            if "L1" in scope:
                tagbits = l1tagbits
                setbits = l1setbits
            elif "L2" in scope:
                tagbits = l2tagbits
                setbits = l2setbits
            else:
                tagbits = l3tagbits
                setbits = l3setbits

            tag_str = scope + (f'.req_tag [{tagbits-1}:0]' if tagbits > 1 else '.req_tag')
            tag = int(pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    fst, last_time, get_signal(signals, tag_str).handle, buf
                )
            ), base = 2)
            sset_str = scope + (f'.req_set [{setbits-1}:0]' if setbits > 1 else '.req_set')
            sset = int(pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                    fst, last_time, get_signal(signals, sset_str).handle, buf
                )
            ), base = 2)

            addr = ((tag << setbits) + sset) << offsetbits
            print(f"{hex(tag):<6} {hex(sset):<6} {hex(addr):<10} |", end="")

            # --------------- 打印状态机 ---------------
            for signal in signals.by_name.values():
                # if re.search(rf'{scope}\.(state_)*[sw]_.*', signal.name):
                if re.search(rf'{scope}\.(state)*_*[sw]_.*', signal.name): # TODO! revert to firstline
                    if int(pylibfst.helpers.string(
                        pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                            fst, last_time, signal.handle, buf
                        )
                    ), base = 2) == 0:
                        print(f"{get_sig_name(signal).split('_')[-1]:<6}", end=" ")
                #TODO: add io.enable or probeHelperFinish for L3 mshr

            print(f"{RESET}")
            highlight_first = RESET  # 只在第一行使用突出显示

            
        # 突出显示最早变为1的信号
        # if sorted_valids:
        #     earliest_time = sorted_valids[0][1]
        #     earliest_valids = [s for s, t in sorted_valids if t == earliest_time]
        #     print(f"\n{MAGENTA}最早变为1并保持到结束的信号（时间点 {earliest_time}）:{RESET}")
        #     for signal in earliest_valids:
        #         print(f"{RED_UNDERLINE}{signal.name}{RESET}")

    pylibfst.lib.fstReaderFreeTimestamps(timestamps)



if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(
            "dumpfst (pylibfst example) (C) 2022 Manfred SCHLAEGL <manfred.schlaegl@gmx.at>\n"
        )
        print("Usage: " + sys.argv[0] + " <fstfile>\n")
        print("Example: " + sys.argv[0] + " counter.fst\n")
        sys.exit(1)
    filename = sys.argv[1]

    fst = pylibfst.lib.fstReaderOpen(filename.encode("UTF-8"))
    if fst == pylibfst.ffi.NULL:
        print("Unable to open file '" + filename + "'!")
        sys.exit(1)

    dumpInfo(fst)
    print()

    (scopes, signals) = pylibfst.get_scopes_signals2(fst)
    # print("Scopes:  " + str(scopes))
    print("Signals:")

    # === collect L2 mshr signals ===
    # Filter signals matching "mshrs_" followed by 1~9 and 10~15
    # TODO: distinguish slices? maybe not needed
    def l2_mshr_valid(scope, signal, digits):
        #TODO! use req_valid for io_status_valid temporarily
        return re.search(rf'{scope}\..*mshrs_\d{{{digits}}}\.req_valid', signal.name) is not None

    def find_l2_mshr_valids(scope):
        msv = []
        msv.extend([signal for signal in signals.by_name.values() if l2_mshr_valid(scope, signal, 1)])
        msv.extend([signal for signal in signals.by_name.values() if l2_mshr_valid(scope, signal, 2)])
        return msv
    
    def l3_mshr_valid(signal, digits):
        return re.search(rf'l3.*ms_\d{{{digits}}}\.req_valid', signal.name) is not None

    def find_l3_mshr_valids():
        msv = []
        msv.extend([signal for signal in signals.by_name.values() if l3_mshr_valid(signal, 1)])
        msv.extend([signal for signal in signals.by_name.values() if l3_mshr_valid(signal, 2)])
        return msv

    for scope in ['coupledL2AsL1', 'coupledL2', 'l3', 'coupledL2_1', 'coupledL2AsL1_1']:
        print(f"{YELLOW}============== Scope: {scope} =============={RESET}")
        mshr_valids = find_l2_mshr_valids(scope) if scope != 'l3' else find_l3_mshr_valids()
        if not mshr_valids:
            print(f"{RED}没有找到匹配的MSHR信号{RESET}")
            continue
        
        first_halt_mshrid(fst, signals, mshr_valids)
        print()


    pylibfst.lib.fstReaderClose(fst)
    print("done")
