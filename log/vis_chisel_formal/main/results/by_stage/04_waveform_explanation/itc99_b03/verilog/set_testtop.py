import sys
from pathlib import Path


def process(raw_path: Path, output_path: Path = Path("TestTop.sv")) -> None:
    print('starting SystemVerilog file post-processing for TestTop')
    with raw_path.open('r') as fin:
        lines = fin.readlines()

    filtered = lines

    cleaned = []
    i = 0
    while i < len(filtered):
        line = filtered[i]
        if '$error' not in line and 'assert(' in line:
            j = i - 1
            flag = False
            while j >= 0:
                prev = filtered[j]
                if 'match_tag' in prev or 'resetCounter_notChaos' in prev:
                    flag = True
                    break
                if 'if' in prev:
                    break
                j -= 1
            k = i + 1
            while k < len(filtered):
                if 'end' in filtered[k].split():
                    break
                k += 1
            if flag:
                cleaned.extend(filtered[j : k + 1])
            else:
                cleaned.extend(['// ' + ln if not ln.startswith('//') else ln for ln in filtered[j : k + 1]])
            i = k + 1
        else:
            cleaned.append(line)
            i += 1

    with output_path.open('w') as fout:
        for line in cleaned:
            if line.startswith('// ----- 8< ----- FILE "firrtl_black_box_resource_files.f" ----- 8< -----'):
                fout.write(line)
                break
            fout.write(line)


def main() -> None:
    if len(sys.argv) != 2:
        print('Usage: python3 set_testtop.py <input-sv>')
        sys.exit(1)

    raw = Path(sys.argv[1]).resolve()
    if not raw.exists():
        print(f'Input file {raw} does not exist')
        sys.exit(2)
    process(raw)

if __name__ == '__main__':
    main()
