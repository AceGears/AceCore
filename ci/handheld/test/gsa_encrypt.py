#!/usr/bin/env python3
"""Encrypts a raw GameShark Advance (v1/v2) code pair the way GameShark devices store them (TEA, 32 rounds, the
devices' published key), so the test can check that the emulator detects and decrypts real-world encrypted codes.
Usage: gsa_encrypt.py ADDRESS_WORD VALUE_WORD   (hex) -> prints "XXXXXXXX+YYYYYYYY"."""
import sys
KEY = (0x09F4FBBD, 0x9681884A, 0x352027E9, 0xF3DEE5A7)
def encrypt(v0, v1):
    s = 0
    for _ in range(32):
        s = (s + 0x9E3779B9) & 0xFFFFFFFF
        v0 = (v0 + ((((v1 << 4) & 0xFFFFFFFF) + KEY[0]) ^ ((v1 + s) & 0xFFFFFFFF) ^ ((v1 >> 5) + KEY[1]))) & 0xFFFFFFFF
        v1 = (v1 + ((((v0 << 4) & 0xFFFFFFFF) + KEY[2]) ^ ((v0 + s) & 0xFFFFFFFF) ^ ((v0 >> 5) + KEY[3]))) & 0xFFFFFFFF
    return v0, v1
a, b = encrypt(int(sys.argv[1], 16), int(sys.argv[2], 16))
print(f"{a:08X}+{b:08X}")
