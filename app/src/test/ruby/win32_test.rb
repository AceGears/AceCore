# Tests the Windows API functions acecore_compat.rb implements for games written for Windows (Essentials "Win32API"
# sections, RGSS1 games). Run: ruby app/src/test/ruby/win32_test.rb
require 'tmpdir'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
class MiniFFI
  def initialize(dll, func, *a) = raise(RuntimeError, "Failed loading #{dll}: dlopen failed: library \"#{dll}\" not found")
end
Win32API = MiniFFI
module AceCoreCompat; DISPLAY_W = 2400; DISPLAY_H = 1080; end
module Graphics; def self.width = 512; def self.height = 384; def self.update; end; end
module Input
  def self.raw_key_states = (s = Array.new(256, false); s[0x04] = true; s[0x51] = true; s) # A and Down held
  def self.mouse_x = 10
  def self.mouse_y = 20
end
$stderr = StringIO.new rescue (require 'stringio'; $stderr = StringIO.new)
load COMPAT
$fail = false
def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect[0, 120]} != #{want.inspect[0, 120]}"); $fail = true))
api = ->(dll, fn, *spec) { Win32API.new(dll, fn, *spec) }

# The Essentials "Win32API" section (v16-v19), verbatim pattern: class variables created at load time.
class Win32API
  @@GetCurrentThreadId = Win32API.new('kernel32', 'GetCurrentThreadId', '%w()', 'l')
  @@GetWindowThreadProcessId = Win32API.new('user32', 'GetWindowThreadProcessId', '%w(l p)', 'l')
  @@FindWindowEx = Win32API.new('user32', 'FindWindowEx', '%w(l l p p)', 'l')
  def self.pbRgssWindow
    thread = @@GetCurrentThreadId.call
    hwnd = 0
    while (hwnd = @@FindWindowEx.call(0, hwnd, 'RGSS Player', 0)) != 0
      return hwnd if @@GetWindowThreadProcessId.call(hwnd, [0].pack('l')) == thread
      break
    end
    0
  end
end
check 'Essentials pbRgssWindow finds the game window', Win32API.pbRgssWindow, 42

check 'GetSystemMetrics screen width', api['user32', 'GetSystemMetrics', 'i', 'i'].call(0), 2400
check 'GetSystemMetrics screen height', api['user32', 'GetSystemMetrics', 'i', 'i'].call(1), 1080
r = [0, 0, 0, 0].pack('l4'); api['user32', 'GetClientRect', 'lp', 'i'].call(42, r)
check 'GetClientRect = game size', r.unpack('l4'), [0, 0, 512, 384]
check 'SetWindowPos succeeds (no desktop window on Android)', api['user32', 'SetWindowPos', 'liiiiip', 'i'].call(42, 0, 0, 0, 640, 480, 0), 1
check 'GetAsyncKeyState(Down) pressed', api['user32', 'GetAsyncKeyState', 'i', 'i'].call(0x28) & 0x8000, 0x8000
check 'GetKeyState(Up) released', api['user32', 'GetKeyState', 'i', 'i'].call(0x26), 0
ks = "\0" * 256; api['user32', 'GetKeyboardState', 'p', 'i'].call(ks)
check 'GetKeyboardState A + Down', [ks.getbyte(0x41), ks.getbyte(0x28), ks.getbyte(0x42)], [0x80, 0x80, 0]
c = [0, 0].pack('l2'); api['user32', 'GetCursorPos', 'p', 'i'].call(c)
check 'GetCursorPos', c.unpack('l2'), [10, 20]
f = [0].pack('q'); api['kernel32', 'QueryPerformanceFrequency', 'p', 'i'].call(f)
check 'QueryPerformanceFrequency', f.unpack1('q'), 1_000_000_000
t1 = [0].pack('q'); t2 = [0].pack('q'); qpc = api['kernel32', 'QueryPerformanceCounter', 'p', 'i']
qpc.call(t1); sleep 0.01; qpc.call(t2)
check 'QueryPerformanceCounter advances', t2.unpack1('q') > t1.unpack1('q'), true

Dir.mktmpdir do |d|
  Dir.chdir(d) do
    File.write('Game.ini', "[Game]\r\nLibrary=RGSS104E.dll\r\nTitle=Pokémon Z\r\n")
    gpps = api['kernel32', 'GetPrivateProfileString', 'pppplp', 'l']
    buf = "\0" * 256
    n = gpps.call('Game', 'Title', '', buf, buf.size, '.\\Game.ini')
    check 'GetPrivateProfileString reads Game.ini', buf[0, n].force_encoding('UTF-8'), 'Pokémon Z'
    buf = "\0" * 256
    n = gpps.call('Game', 'Missing', 'dflt', buf, buf.size, '.\\Game.ini')
    check 'GetPrivateProfileString default', buf[0, n], 'dflt'
  end
end

dst = "\0" * 8; api['kernel32', 'RtlMoveMemory', 'ppl', ''].call(dst, "ABCDEFGHIJ", 4)
check 'RtlMoveMemory copies between buffers', dst, "ABCD\0\0\0\0"
dst = "xxxx"; api['kernel32', 'RtlMoveMemory', 'pll', ''].call(dst, 123456, 4)
check 'RtlMoveMemory from a raw address copies nothing', dst, 'xxxx'
mb = api['kernel32', 'MultiByteToWideChar', 'ilpipi', 'i']
need = mb.call(65001, 0, "Pokémon", -1, nil, 0)
w = "\0" * (need * 2); mb.call(65001, 0, "Pokémon", -1, w, need)
check 'MultiByteToWideChar UTF-8 -> UTF-16 with NUL', [need, w.force_encoding('UTF-16LE').encode('UTF-8')], [8, "Pokémon\0"]
wc = api['kernel32', 'WideCharToMultiByte', 'ilpipipp', 'i']
u16 = "Pokémon".encode('UTF-16LE').b + "\0\0".b
need = wc.call(65001, 0, u16, -1, nil, 0, nil, nil)
o = "\0" * need; wc.call(65001, 0, u16, -1, o, need, nil, nil)
check 'WideCharToMultiByte UTF-16 -> UTF-8 with NUL', o.force_encoding('UTF-8'), "Pokémon\0"
2.times do
  begin
    api['shell32', 'SHGetKnownFolderPath', 'pllp', 'i']; check 'unknown function raises', :created, :raised
  rescue RuntimeError => e
    check 'unknown function raises clearly', e.message.include?('shell32!SHGetKnownFolderPath is not available on Android'), true
  end
end
check 'unknown function logged once', $stderr.string.scan('shell32!SHGetKnownFolderPath').size, 1
# Essentials pattern: optional calls guarded with "rescue nil" fall back to their non-Windows path.
skgfp = (Win32API.new('shell32.dll', 'SHGetKnownFolderPath', 'pllp', 'i') rescue nil)
check 'Essentials getKnownFolder falls back', skgfp, nil
# Essentials "Audio" section (Pokémon Infinity / Z, 057:Audio:74): LoadLibrary/FreeLibrary bound at load time,
# audio.dll used only if safeExists? (which opens the file) finds it.
Dir.mktmpdir do |d|
  Dir.chdir(d) do
    File.binwrite('audio.dll', "MZ\x90\0")
    File.binwrite('Game.ini', "[Game]\n")
    def safeExists?(f) = (ret = false; File.open(f, 'rb') { ret = true } rescue nil; ret)
    w32_LL = Win32API.new('kernel32.dll', 'LoadLibrary', 'p', 'l')
    w32_FL = Win32API.new('kernel32.dll', 'FreeLibrary', 'p', 'l')
    check 'Essentials AudioState: audio.dll reported absent', safeExists?('audio.dll'), false
    check 'File.exist? on a Windows DLL', [File.exist?('audio.dll'), FileTest.exist?('./AUDIO.DLL')], [false, false]
    check 'other files unaffected', [safeExists?('Game.ini'), File.exist?('Game.ini'), File.read('Game.ini')], [true, true, "[Game]\n"]
    check 'LoadLibrary -> NULL, ERROR_MOD_NOT_FOUND',
          [w32_LL.call('audio.dll'), Win32API.new('kernel32', 'GetLastError', '', 'l').call], [0, 126]
    check 'LoadLibraryW -> NULL', Win32API.new('kernel32', 'LoadLibraryW', 'p', 'l').call("x.dll".encode('UTF-16LE').b + "\0\0"), 0
    check 'GetProcAddress -> NULL', Win32API.new('kernel32', 'GetProcAddress', 'lp', 'l').call(0, 'AudioContextNew'), 0
    check 'FreeLibrary(NULL) fails', w32_FL.call(0), 0
    check 'DLL note logged', $stderr.string.include?('audio.dll is a Windows DLL'), true
  end
end
# Sprite_Resizer (Reminiscencia): hotkeys are bound at load time and registration fails cleanly.
rh = Win32API.new('user32', 'RegisterHotKey', 'liii', 'i')
check 'RegisterHotKey fails (no global hotkeys on Android)', [rh.call(42, 1, 0, 0x74), Win32API.new('kernel32', 'GetLastError', '', 'l').call], [0, 120]
check 'UnregisterHotKey fails', Win32API.new('user32', 'UnregisterHotKey', 'li', 'i').call(42, 1), 0
check 'PeekMessage: no message', Win32API.new('user32', 'PeekMessage', 'plllll', 'i').call("\0" * 48, 0, 0, 0, 0, 1), 0
check 'WinHttpOpen binds at load time (Xenoverse Sockets)', Win32API.new('winhttp', 'WinHttpOpen', 'plppl', 'l').call("G\0\0\0".b, 0, nil, nil, 0) > 0, true
puts($fail ? 'SOME FAILED' : 'ALL PASSED')
exit($fail ? 1 : 0)
