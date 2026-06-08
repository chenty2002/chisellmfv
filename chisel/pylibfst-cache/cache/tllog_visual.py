# log format: (but we only use the params before address)
# 71839 L2_L1[1].C[0]  A AcquireBlock Grow NtoB      0   1 16000 0000000000000000    user: 0 echo: 0
# 72143 L3_L2[0]       A AcquireBlock Grow NtoB      0   3 16000 0000000000000000    user: 0 echo: 1
# 72275 L3_L2[1]       A AcquireBlock Grow NtoB      0   1 16000 0000000000000000    user: 0 echo: 1
# 72401 L3_L2[0]       D GrantData    Cap toT        0   3 16000 ab20fc0dd3b8ad63    user: 0 echo: 0
# 72409 L2_L1[0].C[0]  D GrantData    Cap toT        0   9 16000 ab20fc0dd3b8ad63    user: 0 echo: 0
# 72485 L3_L2[0]       B Probe        Cap toB        0   0 16000 0000000000000000    user: 0 echo: 0
# 72491 L2_L1[0].C[0]  B Probe        Cap toB        0   0 16000 0000000000000000    user: 0 echo: 0
# 72493 L2_L1[0].C[0]  C ProbeAckData Shrink TtoB    0  16 16000 f90a5bdebaa06116    user: 0 echo: 0
# 72500 L3_L2[0]       C ProbeAckData Shrink TtoB    0   4 16000 f90a5bdebaa06116    user: 0 echo: 1
# 72511 L3_L2[1]       D GrantData    Cap toB        0   1 16000 f90a5bdebaa06116    user: 0 echo: 1
# 72519 L2_L1[1].C[0]  D GrantData    Cap toB        0   1 16000 f90a5bdebaa06116    user: 0 echo: 0
# 72615 L2_L1[1].C[0]  C Release      Shrink BtoN    0   7 16000 89095418240adfb0    user: 0 echo: 0
# 72619 L2_L1[1].C[0]  D ReleaseAck   Cap toT        0   7 16000 6001394d042dbb87    user: 0 echo: 0
# 72827 L2_L1[0].C[0]  A AcquireBlock Grow NtoT      0   0 16000 0000000000000000    user: 3 echo: 0
# 73074 L2_L1[1].C[0]  A AcquireBlock Grow NtoB      0   3 16000 0000000000000000    user: 1 echo: 0
# 73172 L3_L2[0]       B Probe        Cap toN        0   0 16000 0000000000000000    user: 0 echo: 0
# 73173 L3_L2[1]       B Probe        Cap toN        0   0 16000 0000000000000000    user: 0 echo: 0
# 73178 L3_L2[0]       C ProbeAck     Report NtoN    0 128 16000 1e7133c530c3f068    user: 0 echo: 0
# 73179 L3_L2[1]       C ProbeAck     Shrink BtoN    0 128 16000 7e1134b65d90bc38    user: 0 echo: 0
# 73307 L3_L2[0]       A AcquireBlock Grow NtoT      0   0 16000 0000000000000000    user: 0 echo: 1
# 73537 L3_L2[1]       A AcquireBlock Grow NtoB      0   0 16000 0000000000000000    user: 0 echo: 1
# 73565 L3_L2[0]       D GrantData    Cap toT        0   0 16000 f90a5bdebaa06116    user: 0 echo: 1
# 73573 L2_L1[0].C[0]  D GrantData    Cap toT        0   0 16000 f90a5bdebaa06116    user: 0 echo: 0
# 73595 L2_L1[0].C[0]  C Release      Shrink BtoN    0  12 16000 f8a93a14db00f737    user: 0 echo: 0
# 73599 L2_L1[0].C[0]  D ReleaseAck   Cap toT        0  12 16000 f90a5bdebaa06116    user: 0 echo: 0
# 73765 L3_L2[0]       B Probe        Cap toB        0   0 16000 0000000000000000    user: 0 echo: 0
# """

import re

def parse_log(log):
    previous_line = ""
    blank = " " * 21
    time_width = 6
    print(f"{'time':>6} [L1|0] {blank}[L2|0] {blank}[ L3 ] {blank}[L2|1] {blank}[L1|1]")
    print("-" * 125)

    states = ['  ' for _ in range(5)]

    for line in log.strip().split('\n'):
        parts = line.split()
        if len(parts) < 6:
            continue

        time = parts[0]
        site = parts[1]
        channel = parts[2]
        opcode = parts[3]
        param = parts[5]

        node_lower = 0
        node_upper = 0
        column_id = 0
        direction = ""

        if (site == "L2_L1[0].C[0]"):
            column_id = 0
            node_upper = 0
            node_lower = 1
            direction = "->"
        elif (site == "L3_L2[0]"):
            column_id = 1
            node_upper = 1
            node_lower = 2
            direction = "->"
        elif (site == "L3_L2[1]"):
            column_id = 2
            node_upper = 3
            node_lower = 2
            direction = "<-"
        elif (site == "L2_L1[1].C[0]"):
            column_id = 3
            node_upper = 4
            node_lower = 3
            direction = "<-"

        if channel == "B" or channel == "D":
            direction = "<-" if direction == "->" else "->"
        
        if channel == "A":
            states[node_upper] = ' ' + param[0]
        elif channel == "B":
            pass
        elif channel == "C":
            # add state check
            line = f"{site} {opcode} {param}"
            if line == previous_line:
                continue # skip the second beat of ReleaseData/ProbeAckData
            original_state = param[0]
            if original_state != states[node_upper].strip():
                print(f"\033[36m↓↓↓ State mismatch at {time} for {site}: expected {states[node_upper]}, got {original_state} ↓↓↓\033[0m")
            previous_line = line

            states[node_upper] = ' ' + param[-1]

        elif channel == "D" and opcode != "ReleaseAck":
            states[node_upper] = ' ' + param[-1]

        columns = [f"{'':>22}" for _ in range(4)]
        direction = f"\033[33m{direction}\033[0m"

        if column_id == 0 or column_id == 1:
            columns[column_id] = f" {opcode} {param}".ljust(20) + direction
        else:
            columns[column_id] = direction + f" {opcode} {param}".ljust(20)

        print(f"{time:>6} ", end="")
        for i in range(5):
            color_dict = {
                '  ': "\033[0m",  # Reset color for empty state
                ' N': "\033[0m",  # White for Invalid
                ' B': "\033[32m",  # Blue for Branch
                ' T': "\033[31m"   # Red for Tip/Trunk
            }
            print(f"[ {color_dict[states[i]]}{states[i]}{color_dict['  ']} ]", end="")
            if i != 4:
                print(f"{columns[i]}", end="")
            else:
                print()


import sys
if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python tllog_visual.py <log_file>")
        sys.exit(1)

    filename = sys.argv[1]

    with open(filename, "r") as f:
        log = f.read()
        parse_log(log)