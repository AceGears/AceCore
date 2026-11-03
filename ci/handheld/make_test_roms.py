#!/usr/bin/env python3
"""
Writes AceArcade's own tiny test ROMs (no Nintendo code or data; the header logo areas are left empty, which the
emulators accept without a boot ROM/BIOS) for the handheld core tests:

  acetest.gba  ARM code: backdrop magenta (0x7C1F); green (0x03E0) while the byte at 0x02000100 (EWRAM) is 0x42.
               Counts loop iterations in the 32-bit word at 0x02000000. Writes "ACE!" to SRAM 0x0E000000 at start
               (the "SRAM_V113" tag makes the emulator give it SRAM).
  acetest.gb   LR35902 code, MBC1+RAM+battery: screen black (BGP=FF over blank tiles); white while the byte at
               0xC100 (WRAM) is 0x42. Counts in 0xC000. Writes "ACE!" to cartridge RAM 0xA000 at start.
  acetest.gbc  The same with the Game Boy Color flag (0xC0, colour only).

A cheat that writes 0x42 to the marker byte therefore changes the picture as well as the memory, so tests can
check that the emulator applied it every frame. Usage: make_test_roms.py OUT_DIR
"""
import os
import sys

# Assembled from ci/handheld/test-rom-src/acetest-gba.s (arm-none-eabi-as; bytes from 0xC0).
GBA_CODE = bytes.fromhex(
    "0103a0e30010a0e3b010c0e10544a0e358509fe53e6ea0e30224a0e350709fe50010a0e30010c7e5001082e50e84a0e3"
    "4190a0e30090c8e54390a0e30190c8e54590a0e30290c8e52190a0e30390c8e5003092e5013083e2003082e50030d7e5"
    "420053e3b050c411b060c401f7ffffea1f7c0000000100025352414d5f56313133000000")


def gba_rom():
    rom = bytearray(0x10000)
    rom[0:4] = bytes.fromhex("2e0000ea")            # b 0xC0
    rom[0xA0:0xAC] = b"ACETEST\0\0\0\0\0"          # title
    rom[0xAC:0xB0] = b"ZACE"                       # game code (not a real one)
    rom[0xB0:0xB2] = b"00"                          # maker
    rom[0xB2] = 0x96                                # fixed value
    rom[0xC0:0xC0 + len(GBA_CODE)] = GBA_CODE
    rom[0xBD] = (-(sum(rom[0xA0:0xBD]) + 0x19)) & 0xFF   # header complement check
    return bytes(rom)


def gb_code():
    """LR35902 machine code at 0x150 (see the module docstring)."""
    c = bytearray()
    c += bytes([0x3E, 0x0A, 0xEA, 0x00, 0x00])                  # ld a,0Ah ; ld (0000h),a   enable cartridge RAM
    for i, ch in enumerate(b"ACE!"):
        c += bytes([0x3E, ch, 0xEA, i, 0xA0])                   # ld a,ch ; ld (A00ih),a
    c += bytes([0xAF, 0xEA, 0x00, 0xC1, 0xEA, 0x00, 0xC0])      # xor a ; ld (C100h),a ; ld (C000h),a
    c += bytes([0x3E, 0x91, 0xE0, 0x40])                        # ld a,91h ; ldh (40h),a     LCD on, BG on
    loop = len(c)
    c += bytes([0x21, 0x00, 0xC0, 0x34])                        # ld hl,C000h ; inc (hl)
    c += bytes([0xFA, 0x00, 0xC1, 0xFE, 0x42])                  # ld a,(C100h) ; cp 42h
    c += bytes([0x28, 0x06])                                    # jr z,white (+6)
    c += bytes([0x3E, 0xFF, 0xE0, 0x47])                        # ld a,FFh ; ldh (47h),a     black
    c += bytes([0x18, (loop - (len(c) + 2)) & 0xFF])            # jr loop
    c += bytes([0x3E, 0x00, 0xE0, 0x47])                        # white: ld a,00h ; ldh (47h),a
    c += bytes([0x18, (loop - (len(c) + 2)) & 0xFF])            # jr loop
    return bytes(c)


def gb_rom(cgb):
    rom = bytearray(0x8000)
    rom[0x100:0x104] = bytes([0x00, 0xC3, 0x50, 0x01])          # nop ; jp 0150h
    rom[0x134:0x13B] = b"ACETEST"
    rom[0x143] = 0xC0 if cgb else 0x00
    rom[0x147] = 0x03                                           # MBC1 + RAM + battery
    rom[0x148] = 0x00                                           # 32 KB
    rom[0x149] = 0x02                                           # 8 KB RAM
    code = gb_code()
    rom[0x150:0x150 + len(code)] = code
    x = 0
    for b in rom[0x134:0x14D]:
        x = (x - b - 1) & 0xFF
    rom[0x14D] = x
    g = sum(rom) - rom[0x14E] - rom[0x14F]
    rom[0x14E] = (g >> 8) & 0xFF
    rom[0x14F] = g & 0xFF
    return bytes(rom)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    for name, data in (("acetest.gba", gba_rom()), ("acetest.gb", gb_rom(False)), ("acetest.gbc", gb_rom(True))):
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)
        print(name, len(data))
