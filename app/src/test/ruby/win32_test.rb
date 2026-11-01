# Tests the Windows API functions acecore_compat.rb implements for games written for Windows (Essentials "Win32API"
# sections, RGSS1 games). Run: ruby app/src/test/ruby/win32_test.rb
require 'tmpdir'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
class MiniFFI
  def initialize(dll, func, *a) = raise(RuntimeError, "Failed loading #{dll}: dlopen failed: library \"#{dll}\" not found")
end
Win32API = MiniFFI
module AceCoreCompat; DISPLAY_W = 2400; DISPLAY_H = 1080; end
module Graphics; @w = 640; @h = 480; def self.width = @w; def self.height = @h; def self.update; end; def self.resize_screen(w, h) = (@w, @h = w, h); end
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
check 'GetClientRect = game size', r.unpack('l4'), [0, 0, 640, 480]
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
# An unimplemented function bound without "rescue" (module-level Win32API.new): the binding succeeds, as on Windows,
# and only calling it raises, naming the function. Nothing returns a made-up value.
unbound = Win32API.new('shell32', 'SHGetKnownFolderPath', 'pllp', 'i')
check 'unguarded binding of an unknown function succeeds', unbound.class.name, 'AceCoreCompat::MissingFunction::Binding'
err = (unbound.call(0, 0, 0, "\0" * 8) rescue $!)
check 'calling it raises clearly', [err.class, err.message.include?('shell32!SHGetKnownFolderPath is not available on Android')], [RuntimeError, true]
check 'unknown function listed in the log', $stderr.string.include?('Windows function shell32!SHGetKnownFolderPath is not available on Android'), true
begin
  begin
    Win32API.new('gdi32', 'CreateDIBSection', 'lpllpl', 'l')
  rescue RuntimeError => e
    check 'binding inside begin/rescue fails like a missing DLL', e.message.include?('gdi32!CreateDIBSection is not available on Android'), true
  end
end
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
# Essentials "MiniRegistry" (SpriteWindow / FileTests sections of Pokémon Z, Infinity, Xenoverse, Reminiscencia):
# all functions are bound when the module loads; reads fall back to defaults when a key isn't there.
module MiniRegistry
  HKEY_CURRENT_USER = 0x80000001
  HKEY_LOCAL_MACHINE = 0x80000002
  FormatMessageA = Win32API.new("kernel32", "FormatMessageA", "LPLLPLP", "L")
  RegOpenKeyExA = Win32API.new("advapi32", "RegOpenKeyExA", "LPLLP", "L")
  RegCloseKey = Win32API.new("advapi32", "RegCloseKey", "L", "L")
  RegQueryValueExA = Win32API.new("advapi32", "RegQueryValueExA", "LPLPPP", "L")
  def self.open(hkey, subkey, bit64 = false)
    key = 0.chr * 4
    rg = RegOpenKeyExA.call(hkey, subkey, 0, bit64 ? 0x20119 : 0x20019, key)
    return nil if rg != 0
    key = key.unpack("V")[0]
    if block_given?
      begin yield(key) ensure check(RegCloseKey.call(key)) end
    else
      key
    end
  end
  def self.get(hkey, subkey, name, default = nil, bit64 = false)
    self.open(hkey, subkey, bit64) { |key| return (self.read(key, name) rescue default) }
    default
  end
  def self.read(hkey, name)
    type = 0.chr * 4; size = 0.chr * 4
    check(RegQueryValueExA.call(hkey, name, 0, type, 0, size))
    data = " " * size.unpack("V")[0]
    check(RegQueryValueExA.call(hkey, name, 0, type, data, size))
    type = type.unpack("V")[0]
    data = data[0, size.unpack("V")[0]]
    case type
    when 1 then data.chop
    when 4 then data.unpack("V")[0]
    else raise "Type #{type} not supported."
    end
  end
  def self.check(code)
    if code != 0
      msg = "\0" * 1024
      len = FormatMessageA.call(0x1200, 0, code, 0, msg, 1024, 0)
      raise msg[0, len].tr("\r", '').chomp
    end
  end
end
check 'MiniRegistry.get on a missing key returns the default',
      MiniRegistry.get(MiniRegistry::HKEY_LOCAL_MACHINE, "SOFTWARE\\Enterbrain\\RGSS\\RTP", "Standard", "none"), "none"
check 'MiniRegistry.open on a missing key returns nil', MiniRegistry.open(MiniRegistry::HKEY_CURRENT_USER, "Software\\X"), nil
err = (MiniRegistry.read(MiniRegistry::HKEY_CURRENT_USER, "Personal") rescue $!.message)
check 'MiniRegistry.read on a root: Windows message for ERROR_FILE_NOT_FOUND', err, 'The system cannot find the file specified.'
err = (MiniRegistry.check(6) rescue $!.message)
check 'MiniRegistry.check(6): invalid handle message', err, 'The handle is invalid.'
fm = Win32API.new('kernel32', 'FormatMessageA', 'LPLLPLP', 'L')
check 'FormatMessageA unknown message id -> 0, ERROR_MR_MID_NOT_FOUND',
      [fm.call(0x1200, 0, 424242, 0, "\0" * 64, 64, 0), Win32API.new('kernel32', 'GetLastError', '', 'l').call], [0, 317]
check 'FormatMessageA too-small buffer -> 0, ERROR_INSUFFICIENT_BUFFER',
      [fm.call(0x1200, 0, 2, 0, "\0" * 8, 8, 0), Win32API.new('kernel32', 'GetLastError', '', 'l').call], [0, 122]
check 'FormatMessageA ALLOCATE_BUFFER is refused', fm.call(0x1300, 0, 2, 0, "\0" * 8, 0, 0), 0
b = "\0" * 64
check 'FormatMessageA FROM_STRING', [fm.call(0x400, "Hello", 0, 0, b, 64, 0), b[0, 5]], [5, 'Hello']
w = "\0" * 200
n = Win32API.new('kernel32', 'FormatMessageW', 'LPLLPLP', 'L').call(0x1200, 0, 5, 0, w, 100, 0)
check 'FormatMessageW UTF-16 text', w.byteslice(0, n * 2).force_encoding('UTF-16LE').encode('UTF-8'), "Access is denied.\r\n"
check 'RegCloseKey on a root succeeds, on another handle fails', [Win32API.new('advapi32', 'RegCloseKey', 'L', 'L').call(0x80000001), Win32API.new('advapi32', 'RegCloseKey', 'L', 'L').call(1234)], [0, 6]
# Essentials FontInstaller (PScreen_Load in Infinity / Xenoverse), shortened: copies the game's fonts into the
# Windows font folder, registers them, writes win.ini and broadcasts WM_FONTCHANGE.
Dir.mktmpdir do |d|
  Dir.chdir(d) do
    Dir.mkdir('Fonts'); File.binwrite('Fonts/pkmnem.ttf', 'TTF'); Dir.mkdir('Windows'); Dir.mkdir('Windows/Fonts')
    ENV['SystemRoot'] = File.join(d, 'Windows')
    def safeExists?(f) = (ret = false; File.open(f, 'rb') { ret = true } rescue nil; ret)
    afr = Win32API.new('gdi32', 'AddFontResource', ['P'], 'L')
    wps = Win32API.new('kernel32', 'WriteProfileString', ['P'] * 3, 'L')
    sm = Win32API.new('user32', 'PostMessage', ['L'] * 4, 'L')
    dest = ENV['SystemRoot'] + '\\Fonts\\'
    check 'fonts not yet in the Windows font folder', safeExists?(dest + 'pkmnem.ttf'), false
    File.open('Fonts/' + 'pkmnem.ttf', 'rb') { |s| File.open(dest + 'pkmnem.ttf', 'wb') { |o| o.write(s.read) } }
    check 'backslash path written as a real file in the font folder', File.binread(File.join(d, 'Windows', 'Fonts', 'pkmnem.ttf')), 'TTF'
    check 'safeExists? with backslashes finds it', safeExists?(dest + 'pkmnem.ttf'), true
    check 'AddFontResource: the game font is available', afr.call(dest + 'pkmnem.ttf'), 1
    check 'AddFontResource: another font is not added', afr.call(dest + 'other.ttf'), 0
    check 'WriteProfileString', wps.call('Fonts', 'Power Green (TrueType)', 'pkmnem.ttf'), 1
    wps.call('Fonts', 'Power Green (TrueType)', 'pkmnem2.ttf')
    check 'win.ini written and updated in place', File.binread(File.join(d, 'Windows', 'win.ini')), "[Fonts]\r\nPower Green (TrueType)=pkmnem2.ttf\r\n"
    check 'PostMessage broadcast accepted', sm.call(0xFFFF, 0x1D, 0, 0), 1
    check 'PostMessage to an unknown window fails', sm.call(777, 0x1D, 0, 0), 0
    check 'Dir.exist? with backslashes', Dir.exist?(d + '\\Windows\\Fonts'), true
  end
end
# Essentials "Mouse" module (Reminiscencia PSystem_Controls): capture functions bound at load.
sc = Win32API.new('user32', 'SetCapture', 'p', 'i'); rc = Win32API.new('user32', 'ReleaseCapture', '', 'i')
check 'SetCapture returns the previous capture (none), then the game window', [sc.call(42), sc.call(42)], [0, 42]
check 'ReleaseCapture', [rc.call, Win32API.new('user32', 'GetCapture', '', 'i').call], [1, 0]
# Registry: the user's shell folders exist (Essentials getSaveFolder / FontInstaller read them); other keys don't.
Dir.mktmpdir do |d|
  ENV['USERPROFILE'] = File.join(d, 'User'); Dir.mkdir(ENV['USERPROFILE'])
  personal = MiniRegistry.get(MiniRegistry::HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "Personal")
  check 'Shell Folders\\Personal is the game\'s Documents folder', personal.tr('\\', '/'), File.join(d, 'User', 'Documents')
  check 'File.join on it works and the folder exists', File.directory?(File.join(personal, 'My Games').sub(%r{/My Games\z}, '')), true
  saved = MiniRegistry.get(MiniRegistry::HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "{4C5C32FF-BB9D-43b0-B5B4-2D72E54EAAA4}")
  check 'Saved Games folder', saved.tr('\\', '/'), File.join(d, 'User', 'Saved Games')
  check 'unknown value -> default', MiniRegistry.get(MiniRegistry::HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "Nope", :dflt), :dflt
  check 'RTP key absent', MiniRegistry.get(MiniRegistry::HKEY_LOCAL_MACHINE, "SOFTWARE\\Enterbrain\\RGSS\\RTP", "Standard", nil), nil
  q = Win32API.new('advapi32', 'RegQueryValueExA', 'LPLPPP', 'L'); o = Win32API.new('advapi32', 'RegOpenKeyExA', 'LPLLP', 'L')
  k = "\0" * 4; o.call(0x80000001, "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", 0, 0x20019, k)
  h = k.unpack1('L'); size = [4].pack('L'); type = "\0" * 4
  check 'RegQueryValueEx small buffer -> ERROR_MORE_DATA with the size', [q.call(h, 'Personal', 0, type, "\0" * 4, size), size.unpack1('L') > 4, type.unpack1('L')], [234, true, 1]
end
check 'GetCurrentProcess pseudo-handle', Win32API.new('kernel32.dll', 'GetCurrentProcess', '', 'l').call, -1
check 'GetDC fails (no GDI surface)', Win32API.new('user32', 'GetDC', 'l', 'l').call(42), 0
# Essentials (XP) screen size: Win32API.SetWindowPos(w, h) resizes the window to the client size it wants.
swp = Win32API.new('user32', 'SetWindowPos', %w(l i i i i i i), 'i')
swp.call(42, 0, 0, 0, 512, 384, 0)
check 'SetWindowPos 512x384 -> engine screen 512x384', [Graphics.width, Graphics.height], [512, 384]
swp.call(42, 0, 0, 0, 1024, 768, 0)
check 'SetWindowPos 2x (1024x768) -> same visible area', [Graphics.width, Graphics.height], [512, 384]
swp.call(42, 0, 100, 100, 9999, 9999, 0x1)
check 'SWP_NOSIZE leaves the size', [Graphics.width, Graphics.height], [512, 384]
Win32API.new('user32', 'MoveWindow', %w(l i i i i i), 'i').call(42, 0, 0, 640, 480, 1)
check 'MoveWindow back to 640x480', [Graphics.width, Graphics.height], [640, 480]
check 'GetClientRect follows', (r = [0, 0, 0, 0].pack('l4'); Win32API.new('user32', 'GetClientRect', %w(l p), 'i').call(42, r); r.unpack('l4')), [0, 0, 640, 480]
# Essentials (XP) defines its own Graphics.width/height = 512x384; the engine is still at 640x480 and must resize.
module Graphics; class << self; alias_method :__engine_w, :width; alias_method :__engine_h, :height; def width = 512; def height = 384; end; end
swp.call(42, 0, 0, 0, 512, 384, 0)
check "SetWindowPos resizes although the game's Graphics.width already says 512", [Graphics.__engine_w, Graphics.__engine_h], [512, 384]
module Graphics; class << self; alias_method :width, :__engine_w; alias_method :height, :__engine_h; end; end
# Pokémon Z / Xenoverse: the game only declares 512x384 (its own Graphics.width/height) and never resizes the window.
Graphics.resize_screen(640, 480)
module Graphics; class << self; alias_method :__engine_w2, :width; alias_method :__engine_h2, :height; def width = 512; def height = 384; end; end
AceCoreCompat::Display.match_game_screen
check 'engine screen matched to the size the game declares', [Graphics.__engine_w2, Graphics.__engine_h2], [512, 384]
module Graphics; class << self; alias_method :width, :__engine_w2; alias_method :height, :__engine_h2; end; end
# A game restarting itself to apply settings (starts its own exe, then exits).
Dir.mktmpdir do |d|
  helper = File.join(d, 'helper'); Dir.mkdir(helper)
  AceCoreCompat.const_set(:DISPLAY_FILE, File.join(helper, 'settings.json')) unless defined?(AceCoreCompat::DISPLAY_FILE)
  Dir.chdir(d) do
    File.binwrite('Game.exe', 'MZ')
    se = Win32API.new('shell32', 'ShellExecute', %w(l p p p p i), 'l')
    check 'ShellExecute of Game.exe -> success, restart requested', [se.call(42, 'open', 'Game.exe', nil, nil, 1), File.exist?(File.join(helper, 'restart'))], [42, true]
    File.delete(File.join(helper, 'restart'))
    check 'system("Game.exe") -> true, restart requested', [system('"Game.exe"'), File.exist?(File.join(helper, 'restart'))], [true, true]
    check 'ShellExecute of another program fails', se.call(42, 'open', 'notepad.exe', nil, nil, 1), 2
    check 'system of another program: Ruby\'s normal nil', system('notepad.exe'), nil
  end
end
# Language (Xenoverse picks its text language from these): the device locale as Windows reports it.
AceCoreCompat.const_set(:LOCALE, 'es-MX')
check 'GetUserDefaultLangID = device language', Win32API.new('kernel32', 'GetUserDefaultLangID', '', 'i').call, 0x080A
k = "\0" * 4
check 'Control Panel\\International exists', Win32API.new('advapi32', 'RegOpenKeyExA', 'LPLLP', 'L').call(0x80000001, "Control Panel\\International", 0, 0x20019, k), 0
check 'LocaleName', MiniRegistry.get(MiniRegistry::HKEY_CURRENT_USER, "Control Panel\\International", "LocaleName"), 'es-MX'
check 'ResourceLocale default value', MiniRegistry.get(MiniRegistry::HKEY_CURRENT_USER, "Control Panel\\Desktop\\ResourceLocale", ""), '0000080A'
puts($fail ? 'SOME FAILED' : 'ALL PASSED')
exit($fail ? 1 : 0)
