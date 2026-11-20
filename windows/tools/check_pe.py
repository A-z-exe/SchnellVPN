"""Print (and optionally assert) the PE machine and Windows version of an exe.

Used to keep the Windows 7 build honest: x86 (0x14c) and subsystem 6.1.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

MACHINES = {0x14c: "x86", 0x8664: "x64", 0xaa64: "arm64"}


def pe_info(path: Path) -> tuple[int, tuple[int, int], tuple[int, int]]:
    data = path.read_bytes()
    if data[:2] != b"MZ":
        raise SystemExit(f"{path}: not a PE file")
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe_offset : pe_offset + 4] != b"PE\0\0":
        raise SystemExit(f"{path}: bad PE signature")
    machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
    optional = pe_offset + 24
    os_version = struct.unpack_from("<HH", data, optional + 40)
    subsystem_version = struct.unpack_from("<HH", data, optional + 48)
    return machine, os_version, subsystem_version


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: check_pe.py <exe> [x86|amd64] [major.minor]")
        return 2
    path = Path(argv[0])
    machine, os_version, subsystem = pe_info(path)
    print(f"{path.name}: {MACHINES.get(machine, hex(machine))}, "
          f"os {os_version[0]}.{os_version[1]}, "
          f"subsystem {subsystem[0]}.{subsystem[1]}")
    ok = True
    if len(argv) > 1:
        want = {"x86": 0x14C, "amd64": 0x8664}[argv[1]]
        if machine != want:
            print(f"expected machine {argv[1]}, got {hex(machine)}")
            ok = False
    if len(argv) > 2:
        # A lower subsystem version is more compatible, not less.
        max_sub = tuple(int(part) for part in argv[2].split("."))
        if tuple(subsystem) > max_sub:
            print(f"expected subsystem <= {argv[2]}, got {subsystem[0]}.{subsystem[1]}")
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
