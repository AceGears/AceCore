#!/usr/bin/env python3
"""Smoke test helper: once the test program's window is up (DIR/smoke-window.txt), connects to the runtime's VNC
display the way the app does (RFB 3.8, no authentication), moves the mouse pointer outside the window and presses
Enter every few seconds until the program reports (DIR/smoke-ok.txt) or the time runs out.
Usage: send-keys.py DIR [PORT]"""
import os, socket, struct, sys, time

d = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5900
deadline = time.time() + 3600
while not os.path.exists(os.path.join(d, "smoke-window.txt")):
    if time.time() > deadline or os.path.exists(os.path.join(d, "smoke-ok.txt")):
        print("send-keys: the window never appeared"); sys.exit(0)
    time.sleep(2)
time.sleep(5)  # let the window watcher (checks every second) see the new window

def recv_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise IOError("display closed")
        b += c
    return b

s = socket.create_connection(("127.0.0.1", port), timeout=30)
recv_exact(s, 12); s.sendall(b"RFB 003.008\n")
n = recv_exact(s, 1)[0]; recv_exact(s, n); s.sendall(b"\x01")
if struct.unpack(">I", recv_exact(s, 4))[0] != 0: raise IOError("security handshake failed")
s.sendall(b"\x01")
w, h = struct.unpack(">HH", recv_exact(s, 4)); recv_exact(s, 16)
recv_exact(s, struct.unpack(">I", recv_exact(s, 4))[0])
# The window is 320x240 at the top left; the pointer goes to the opposite corner.
s.sendall(struct.pack(">BBHH", 5, 0, w - 5, h - 5))
sent = 0
end = time.time() + 600
while time.time() < end and not os.path.exists(os.path.join(d, "smoke-ok.txt")):
    s.sendall(struct.pack(">BBHI", 4, 1, 0, 0xFF0D)); time.sleep(0.3)
    s.sendall(struct.pack(">BBHI", 4, 0, 0, 0xFF0D)); sent += 1
    time.sleep(3)
print(f"send-keys: pressed Enter {sent} time(s) with the pointer at {w - 5},{h - 5} (display {w}x{h})")
