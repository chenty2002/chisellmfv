from deadlock_parser import *
import sys

def opcode_str(chn, opcode):
    allops = {
        "a": ["PutFullData", "PutPartialData", "ArithmeticData", "LogicalData", "Get", "Hint", "AcquireBlock", "AcquirePerm"],
        "b": ["PutFullData", "PutPartialData", "ArithmeticData", "LogicalData", "Get", "Hint", "Probe"],
        "c": ["AccessAck", "AccessAckData", "HintAck", "Invalid Opcode", "ProbeAck", "ProbeAckData", "Release", "ReleaseData"],
        "d": ["AccessAck", "AccessAckData", "HintAck", "Invalid Opcode", "Grant", "GrantData", "ReleaseAck"],
        "e": ["GrantAck"]
    }
    return allops[chn][opcode] if opcode < len(allops[chn]) else "Unknown"

def param_str(chn, param):
    cap = ["toT", "toB", "toN"]
    grow = ["NtoB", "NtoT", "BtoT"]
    report = ["TtoB", "TtoN", "BtoN", "TtoT", "BtoB", "NtoN"]
    allparams = {
        "a": grow, "b": cap, "c": report, "d": cap, "e": [""]
    }
    return allparams[chn][param] if param < len(allparams[chn]) else "Unknown"

def tllog_site(l2):
    return {
        "VerifyTop.coupledL2": "L3_L2[0]",
        "VerifyTop.coupledL2_1": "L3_L2[1]",
        "VerifyTop.coupledL2AsL1": "L2_L1[0].C[0]",
        "VerifyTop.coupledL2AsL1_1": "L2_L1[1].C[0]"
    }[l2]

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python tllog_parser.py <log_file> <target_addr>")
        sys.exit(1)

    filename = sys.argv[1]
    target_addr = int(sys.argv[2], base = 16)
    

    fst = pylibfst.lib.fstReaderOpen(filename.encode("UTF-8"))
    if fst == pylibfst.ffi.NULL:
        print("Unable to open file '" + filename + "'!")
        sys.exit(1)

    # dumpInfo(fst)
    # print()

    (scopes, signals) = pylibfst.get_scopes_signals2(fst)
    # print("Scopes:  " + str(scopes))

    pylibfst.lib.fstReaderSetFacProcessMaskAll(fst)
    timestamps = pylibfst.lib.fstReaderGetTimestamps(fst)

    if timestamps.nvals == 0:
        print("没有时间戳数据")
        sys.exit(-1)

    def get_signal_by_name(name):
        for signal in signals.by_name.values():
            if re.match(rf"{name}", signal.name):
                return signal
        return None

    buf = pylibfst.ffi.new("char[256]")
    def get_value(signal, base = 2):
        return int(pylibfst.helpers.string(
                pylibfst.lib.fstReaderGetValueFromHandleAtTime(
                fst, time, signal.handle, buf)), base = base)
    # ===========================================
    Top = "VerifyTop"
    caches = [Top + "." + cc for cc in ["coupledL2", "coupledL2_1", "coupledL2AsL1", "coupledL2AsL1_1"]]

    for cc in caches:
        a_addr_current_source = -1 # used to pair Acquire - Grant
        c_addr_current_source = -1 # used to pair Release - ReleaseAck
        chn_all_signals = {}

        for chn in ['a', 'b', 'c', 'd']:
            chn_all_signals[chn] = {
                "valid": get_signal_by_name(f"{cc}.auto_out_{chn}_valid"),
                "ready": get_signal_by_name(f"{cc}.auto_out_{chn}_ready"),
                "opcode": get_signal_by_name(f"{cc}.auto_out_{chn}_bits_opcode"),
                "address": get_signal_by_name(f"{cc}.auto_out_{chn}_bits_address"),
                "param": get_signal_by_name(f"{cc}.auto_out_{chn}_bits_param"),
                "source": get_signal_by_name(f"{cc}.auto_out_{chn}_bits_source"),
                "data": get_signal_by_name(f"{cc}.auto_out_{chn}_bits_data")
            }

        for ts in range(0, timestamps.nvals, 2): # step 2 to skip negedge
            time = timestamps.val[ts]

            for chn in ['a', 'b', 'c', 'd']:
                valid = get_value(chn_all_signals[chn]["valid"])
                ready = get_value(chn_all_signals[chn]["ready"])
                source = get_value(chn_all_signals[chn]["source"])
                
                if valid == 1 and ready == 1:
                    if chn != 'd':
                        address = get_value(chn_all_signals[chn]["address"])

                        if address == target_addr:
                            opcode = get_value(chn_all_signals[chn]["opcode"])

                            if chn == 'a':
                                a_addr_current_source = source
                            if chn == 'c' and (opcode == 6 or opcode == 7): # Release or ReleaseData
                                c_addr_current_source = source
                            
                            param = get_value(chn_all_signals[chn]["param"])
                            data = get_value(chn_all_signals[chn]["data"])

                            # print(f"Time: {time:5}, {cc:26}: "
                            #       f"{chn.upper()} {opcode_str(chn, opcode):12}, "
                            #       f"{param_str(chn, param):4}, "
                            #       f"{hex(address)}"
                            #      )

                            print(f"{time:5} {tllog_site(cc):16} "
                                f"{chn.upper()} {opcode_str(chn, opcode):12} "
                                f"X {param_str(chn, param):4} {data:x}"
                                )


                    else: # chn == 'd'
                        opcode = get_value(chn_all_signals[chn]["opcode"])
                        d_match_a = (opcode == 4 or opcode == 5) and source == a_addr_current_source
                        d_match_c = opcode == 6 and source == c_addr_current_source

                        if d_match_a:
                            a_addr_current_source = -1 # avoid redundant match across different reqs, and this will reduce two Data beats to one
                        if d_match_c:
                            c_addr_current_source = -1
                        if d_match_a or d_match_c:
                            param = get_value(chn_all_signals[chn]["param"])
                            data = get_value(chn_all_signals[chn]["data"])

                            print(f"{time:5} {tllog_site(cc):16} "
                                f"{chn.upper()} {opcode_str(chn, opcode):12} "
                                f"X {param_str(chn, param):4} {data:x}"
                                )


    # ===========================================
    pylibfst.lib.fstReaderFreeTimestamps(timestamps)


'''
class TLBundle:
    def __init__(self, name):
        self._name = name

    def __getattr__(self, name):
        """
        当访问一个不存在的属性时，此方法会被调用。
        它根据属性名和当前路径来构建新的路径。
        """
        # l2 = "VerifyTop.coupledL2"
        # l2.a = "VerifyTop.coupledL2.auto_out_a"
        # l2.a.valid = "VerifyTop.coupledL2.auto_out_a_valid"
        # l2.a.bits.opcode = "VerifyTop.coupledL2.auto_out_a_bits_opcode"
        if name in ["a", "b", "c", "d", "e"]:
            segment = f"auto_out_{name}"
            separator = "."
        else:
            segment = name
            separator = "_"

        # 构建新的完整路径
        if self._name:
            new_name = f"{self._name}{separator}{segment}"
        else:
            # 如果是初始路径（即第一次创建 TLBundle），则直接使用 segment
            new_name = segment
        
        # 返回一个新的 TLBundle 实例，其路径为新构建的路径
        return TLBundle(new_name)

    def __str__(self):
        """
        返回当前实例的字符串表示，即其内部存储的路径。
        这使得打印 TLBundle 实例时能得到期望的字符串。
        """
        return self._name

    def __repr__(self):
        """
        返回当前实例的官方字符串表示，便于调试。
        """
        return f"TLBundle('{self._name}')"

# 验证结果
# l2 应该等于 "VerifyTop.coupledL2"
print(f'l2 = "{l2}"')

# l2.a 应该等于 "VerifyTop.coupledL2.auto_out_a"
l2_a = l2.a
print(f'l2.a = "{l2_a}"')

# l2.a.valid 应该等于 "VerifyTop.coupledL2.auto_out_a_valid"
l2_a_valid = l2.a.valid
print(f'l2.a.valid = "{l2_a_valid}"')

# l2.a.bits.opcode 应该等于 "VerifyTop.coupledL2.auto_out_a.bits_opcode"
l2_a_bits_opcode = l2.a.bits.opcode
print(f'l2.a.bits.opcode = "{l2.a.bits.opcode}"')

# 您也可以继续添加其他层级，例如 l2.b 或 l2.a.some_other_field
l2_b = l2.b
print(f'l2.b = "{l2_b}"')

l2_a_some_field = l2.a.some_other_field
print(f'l2.a.some_other_field = "{l2_a_some_field}"')
'''
