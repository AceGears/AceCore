# AceArcade engine compatibility layer for mkxp-z-android-reworked (loaded through mkxp-z's "preloadScript",
# before the game's own scripts, also when the game helper is off).
#
# The Android port of mkxp-z is older than the mkxp-z that PC games ship with (e.g. Pokémon Essentials v21.1).
# This file makes the Android engine match upstream mkxp-z where the port is behind, so the same game scripts run:
#   * loads Ruby's zlib extension at start-up, as upstream mkxp-z does (Essentials' PluginManager needs Zlib);
#   * adds the upstream mkxp-z functions the port lacks, each only if the engine doesn't define it already;
#   * reports time in seconds where the game expects upstream's units (see "Time units").
# AceArcade prepends a header defining AceCoreCompat::DISPLAY_W / DISPLAY_H (the device screen in landscape pixels).
# Never touches game files.

# Start-up notes for crash reports (stderr is captured into the report; nothing is shown to the player).
($stderr.puts("AceArcade: compat start ruby=#{RUBY_VERSION} cwd=#{(Dir.pwd rescue '?')} load_path=#{$LOAD_PATH.inspect} " \
  "features=#{$LOADED_FEATURES.size} zlib_defined=#{defined?(Zlib) ? 1 : 0}"); $stderr.flush) rescue nil
begin
  require 'zlib'
  ($stderr.puts("AceArcade: zlib loaded #{Zlib::ZLIB_VERSION rescue '?'}"); $stderr.flush) rescue nil
rescue LoadError => e
  ($stderr.puts("AceArcade: Ruby zlib extension unavailable: #{e.message}") rescue nil)
end

# Windows folders games use for saves and settings. Essentials up to v18 saves to %APPDATA%\<game> (or the folder
# Windows reports for "Saved Games"); on Android these variables don't exist, and File.join(nil, ...) stopped such
# games while their scripts loaded. They point into AceArcade's private folder for this game, where AceArcade's save
# backups look for saves. Never overrides a value that is already set.
if defined?(AceCoreCompat::DATA_DIR) && AceCoreCompat::DATA_DIR
  begin
    home = File.join(AceCoreCompat::DATA_DIR, 'User')
    { 'USERPROFILE' => home, 'APPDATA' => File.join(home, 'AppData', 'Roaming'),
      'LOCALAPPDATA' => File.join(home, 'AppData', 'Local') }.each do |k, v|
      next if ENV[k] && !ENV[k].empty?
      Dir.mkdir(v) rescue nil
      [File.dirname(File.dirname(v)), File.dirname(v), v].each { |d| Dir.mkdir(d) rescue nil }
      ENV[k] = v
    end
    # The rest of a Windows user environment that scripts combine into paths (%HOMEDRIVE%%HOMEPATH% = the profile).
    { 'HOMEDRIVE' => '', 'HOMEPATH' => home, 'TEMP' => File.join(home, 'AppData', 'Local', 'Temp'),
      'TMP' => File.join(home, 'AppData', 'Local', 'Temp'), 'USERNAME' => 'Player',
      'ALLUSERSPROFILE' => File.join(AceCoreCompat::DATA_DIR, 'ProgramData'),
      'PROGRAMDATA' => File.join(AceCoreCompat::DATA_DIR, 'ProgramData'),
      'PUBLIC' => File.join(AceCoreCompat::DATA_DIR, 'Public') }.each do |k, v|
      next if ENV.key?(k)
      (Dir.mkdir(v) rescue nil) unless v.empty? || k == 'USERNAME' || k == 'HOMEPATH'
      ENV[k] = v
    end
    # %SystemRoot% / %windir%: a Windows folder of this game's own, so scripts that look for system fonts
    # (Essentials FontInstaller) find a real folder. The engine already loads the game's Fonts folder itself.
    win = File.join(AceCoreCompat::DATA_DIR, 'Windows')
    [win, File.join(win, 'Fonts')].each { |d| Dir.mkdir(d) rescue nil }
    %w[SystemRoot windir].each { |k| ENV[k] = win if ENV[k].nil? || ENV[k].empty? }
  rescue StandardError => e
    ($stderr.puts("AceArcade: could not prepare Windows folders: #{e.message}") rescue nil)
  end
end

module AceCoreCompat
  module_function

  # Case-insensitive file lookup relative to the game folder (upstream checks through its asset file system, which
  # ignores case like Windows does).
  def exist?(path)
    path = path.to_s.tr('\\', '/')
    return true if File.exist?(path)
    dir = path.start_with?('/') ? '/' : '.'
    path.split('/').reject(&:empty?).each do |part|
      next if part == '.'
      entries = (Dir.entries(dir) rescue nil)
      return false unless entries
      match = entries.find { |e| e.casecmp?(part) }
      return false unless match
      dir = (dir == '/' ? "/#{match}" : File.join(dir, match))
    end
    File.exist?(dir)
  end

  # Same output shape as upstream's rapidcsv call: array of rows, each an array of strings; "," separator, quoted
  # fields with "" escapes and line breaks inside quotes; CR/LF line endings; rows padded to the widest row.
  def parse_csv(str)
    rows = []
    row = []
    field = +''
    quoted = false
    i = 0
    s = str.to_s
    n = s.length
    while i < n
      c = s[i]
      if quoted
        if c == '"'
          if s[i + 1] == '"'
            field << '"'
            i += 1
          else
            quoted = false
          end
        else
          field << c
        end
      else
        case c
        when '"' then quoted = true
        when ',' then row << field; field = +''
        when "\r" then nil
        when "\n" then row << field; rows << row; row = []; field = +''
        else field << c
        end
      end
      i += 1
    end
    row << field
    rows << row unless row.length == 1 && row[0].empty?
    width = rows.map(&:length).max || 0
    rows.each { |r| r.concat(Array.new(width - r.length, '')) }
    rows
  end
end

if defined?(Graphics)
  unless Graphics.respond_to?(:display_width)
    def Graphics.display_width; AceCoreCompat::DISPLAY_W; end
  end
  unless Graphics.respond_to?(:display_height)
    def Graphics.display_height; AceCoreCompat::DISPLAY_H; end
  end
end

if defined?(System)
  unless System.respond_to?(:file_exist?)
    def System.file_exist?(path); AceCoreCompat.exist?(path); end
  end
  unless System.respond_to?(:parse_csv)
    def System.parse_csv(str); AceCoreCompat.parse_csv(str); end
  end
  def System.is_bsd?; false; end unless System.respond_to?(:is_bsd?)
  def System.is_really_bsd?; false; end unless System.respond_to?(:is_really_bsd?)
end

# System.mount(archive, mountpoint): games built on newer/custom mkxp-z pass "." (or "./", "", Windows "\") to mean
# "the root of the game's file tree" (Pokémon Flux: System.mount("Data/Data_0.fpk", ".", false)). PhysFS rejects "." as
# an insecure filename, so the same call fails here. Normalise the mount point; the archive path is unchanged.
module AceCoreCompat
  def self.mount_point(mp)
    return mp unless mp.is_a?(String)
    m = mp.tr("\\", "/").sub(%r{\A(?:\./)+}, "").sub(%r{/+\z}, "")
    (m.empty? || m == ".") ? nil : m
  end
end
if defined?(System) && System.respond_to?(:mount) && !System.respond_to?(:__acearcade_mount)
  class << System
    alias_method :__acearcade_mount, :mount
    def mount(path, mountpoint = nil, *rest)
      __acearcade_mount(path, AceCoreCompat.mount_point(mountpoint), *rest)
    end
  end
end

if defined?(Audio)
  # Upstream keeps a separate volume per BGM track. The port has a single BGM channel and no such control, so the
  # value is remembered and reported back but does not change loudness (the volume passed to bgm_play still does).
  unless Audio.respond_to?(:bgm_volume)
    module AceCoreCompat
      @bgm_volumes = Hash.new(100)
      class << self; attr_reader :bgm_volumes; end
    end
    def Audio.bgm_volume(track = nil); AceCoreCompat.bgm_volumes[track]; end
    def Audio.bgm_set_volume(volume, track = nil); AceCoreCompat.bgm_volumes[track] = volume.to_i; nil; end
  end
end

#===============================================================================
# Time units
#
# mkxp-z changed its timing functions from whole microseconds to seconds (Float) in 2.4.2. The Android port is
# older: System.uptime, System.delta, Graphics.delta and Input.time? still return microseconds (e.g. 16667 for one
# frame instead of 0.0167). Pokémon Essentials v21 is written for 2.4.2: Game_Character#update starts with
# "return if @delta_t > 0.25" (seconds), so with microseconds EVERY character update was skipped — the player
# never turned or walked, events never moved, and event scripts waiting for a move route never finished (intros
# stalled). Menus still worked because they read Input.trigger?/repeat? directly.
#
#   System.uptime  -> always seconds. It is the 2.4.2 API; games call it only when written for seconds.
#   System.delta, Graphics.delta, Input.time?, Input.timeex?
#                  -> seconds for games written for mkxp-z 2.4.2 or later (detected from Essentials::MKXPZ_VERSION /
#                     Essentials::VERSION >= 21), unchanged microseconds for older games that expect them.
# Only applied when the engine really returns integers, so a fixed engine is left alone.
#===============================================================================
module AceCoreCompat
  module Timing
    US_PER_S = 1_000_000.0

    module_function

    # true: the game expects seconds; false: microseconds. Decided once the game's own version constants exist.
    def seconds_api?
      return @seconds unless @seconds.nil?
      ess = defined?(::Essentials) ? ::Essentials : nil
      if ess && ess.const_defined?(:MKXPZ_VERSION, false)
        v = ess.const_get(:MKXPZ_VERSION).to_s[/\d+(\.\d+)*/].to_s.split('.').map(&:to_i)
        @seconds = (v <=> [2, 4, 2]) >= 0
      elsif ess && ess.const_defined?(:VERSION, false)
        @seconds = ess.const_get(:VERSION).to_s.to_f >= 21
      else
        false # not known yet (or not Essentials): keep the engine's own units, decide again next call
      end
    end

    def convert(value)
      value.is_a?(Integer) && seconds_api? ? value / US_PER_S : value
    end

    def reset!
      @seconds = nil
    end

    # ---- Fast-forward clock. The game sees a clock that runs [speed] times faster than real time, continuous
    # across speed changes (never jumps backwards). Essentials v21 and other time-based scripts follow it.
    def speed
      @speed || 1.0
    end

    def speed=(value)
      value = value.to_f.clamp(1.0, 8.0)
      @last_draw = @frame_us = nil if value != speed # measure the frame length afresh at the new speed
      @speed = value
    end

    # [real_us]: the engine's own microsecond clock. Returns the game clock in microseconds.
    # At normal speed the game clock follows real time. While fast-forwarding, the game runs [speed] updates per
    # drawn frame (Tools.skip_frame?), and the clock advances in equal steps, one per Graphics.update call, each one
    # real frame long (frame_tick). The game then sees [speed] times as much time pass, in the same per-update steps
    # it sees at normal speed. A clock running continuously [speed] times faster instead gave the drawn update the
    # whole speed x frame time (0.45 s at 3x and 7 fps) and the skipped ones none: Essentials ignores updates longer
    # than 0.25 s, so the player stopped walking while fast-forwarding on slower devices.
    def virtual_us(real_us)
      @last_real ||= real_us
      @virtual ||= real_us
      @virtual += real_us - @last_real if real_us > @last_real && speed == 1.0
      @last_real = real_us
      @virtual
    end

    # Called by Graphics.update while fast-forwarding: [drawn] is true for the call that draws the frame.
    def now_us = Process.clock_gettime(Process::CLOCK_MONOTONIC, :microsecond)

    def frame_tick(drawn)
      now = now_us
      if drawn
        @frame_us = (now - @last_draw).clamp(1, 1_000_000) if @last_draw && now > @last_draw
        @last_draw = now
      end
      @step_us = @last_draw && @frame_us ? @frame_us : 16_667
      @virtual += @step_us if @virtual
    end

    # Durations (Graphics.delta, System.delta): while fast-forwarding, one clock step (the length of a real frame)
    # per update, matching the game clock above.
    def scaled(value)
      return value if speed == 1.0 || !value.is_a?(Numeric)
      step = @step_us || 16_667
      value.is_a?(Integer) ? step : step / 1_000_000.0
    end
  end
end

if defined?(System) && System.respond_to?(:uptime) && System.uptime.is_a?(Integer)
  class << System
    alias_method :__acearcade_uptime_us, :uptime
    def uptime; AceCoreCompat::Timing.virtual_us(__acearcade_uptime_us) / AceCoreCompat::Timing::US_PER_S; end
    if method_defined?(:delta)
      alias_method :__acearcade_delta_us, :delta
      def delta; AceCoreCompat::Timing.convert(AceCoreCompat::Timing.scaled(__acearcade_delta_us)); end
    end
  end
end
if defined?(Graphics) && Graphics.respond_to?(:delta) && !Graphics.respond_to?(:__acearcade_delta_us)
  class << Graphics
    alias_method :__acearcade_delta_us, :delta
    def delta; AceCoreCompat::Timing.convert(AceCoreCompat::Timing.scaled(__acearcade_delta_us)); end
  end
end
if defined?(Input) && Input.respond_to?(:time?) && !Input.respond_to?(:__acearcade_time_us)
  class << Input
    alias_method :__acearcade_time_us, :time?
    def time?(*args); AceCoreCompat::Timing.convert(__acearcade_time_us(*args)); end
    if method_defined?(:timeex?)
      alias_method :__acearcade_timeex_us, :timeex?
      def timeex?(*args); AceCoreCompat::Timing.convert(__acearcade_timeex_us(*args)); end
    end
  end
end

#===============================================================================
# Windows API (Win32API / MiniFFI) on Android
#
# Games built for Windows sometimes call Windows libraries directly. On Android those libraries don't exist, so the
# engine raised "Failed loading kernel32". AceArcade implements the specific calls below in Ruby so they behave as
# they do on Windows (see the list for what each one really does).
#
#   kernel32  SetPriorityClass                     -> success (Android manages process priority itself)
#             GetCurrentThreadId / GetCurrentProcessId, GetTickCount, QueryPerformanceCounter/Frequency, Sleep
#                                                  -> real values from the running engine
#             GetPrivateProfileString(A)           -> reads the game's .ini file (as Windows does)
#             LoadLibrary(A/W/Ex), GetProcAddress, FreeLibrary -> fail as for a DLL that isn't there (NULL,
#                                                     GetLastError 126/127): Windows DLLs can't run on Android
#             GetLastError / SetLastError          -> the error code the functions here set
#             FormatMessage(A/W)                   -> Windows' text for system error codes (FROM_SYSTEM) or the given
#                                                     format string (FROM_STRING); 0 + ERROR_MR_MID_NOT_FOUND otherwise
#             WriteProfileString(A)                -> win.ini in the game's own Windows folder (%SystemRoot%)
#   gdi32     AddFontResource(A/W/Ex)              -> 1 for a font the engine has loaded (the game's Fonts folder)
#   user32    PostMessage to the game window / HWND_BROADCAST -> accepted; SetCapture/ReleaseCapture/GetCapture
#   advapi32  RegOpenKey(Ex)(A/W), RegQueryValueEx(A/W), RegEnum*, RegCloseKey -> read-only registry with the user's
#                                                     shell folders (Documents, Saved Games, AppData, Fonts...) in
#                                                     this game's Windows folders; any other key: not found (2)
#   game DLLs (audio.dll, rubyscreen.dll...) -> reported absent to File.exist?/File.open, so Essentials' own
#                                                     "no DLL" code path runs
#   user32    FindWindow(Ex)(A), GetForegroundWindow/GetActiveWindow -> the game window (there is exactly one)
#             GetWindowThreadProcessId             -> the engine's thread/process (matches GetCurrentThreadId)
#             GetSystemMetrics(0/1)                -> the device screen size; GetClientRect/GetWindowRect -> game size
#             GetKeyState/GetAsyncKeyState/GetKeyboardState, GetCursorPos -> the engine's real input state
#             SetWindowPos/MoveWindow/ShowWindow/SetWindowText/SetWindowLong... -> success, no effect: the game always
#                                                     fills the Android screen, there is no desktop window to move
#             MessageBox(A/W)                      -> shown as the engine's message box
#             RegisterHotKey/UnregisterHotKey      -> fail (no global hotkeys); PeekMessage -> no message
#   winhttp   WinHttpOpen/Connect/OpenRequest/SendRequest/WriteData/ReceiveResponse/QueryHeaders/
#             QueryDataAvailable/ReadData/CloseHandle -> real HTTP(S) requests (any verb, headers, body), same TLS
#                                                     checks as wininet; FALSE + GetLastError on failure
#   any other Windows function (see AceCoreCompat::MissingFunction): listed once in the engine log. If the game binds
#   it inside "rescue", Win32API.new raises like a missing DLL on Windows and the game's fallback runs; otherwise the
#   binding succeeds and only an actual call raises, naming the function. Nothing returns a made-up value: a fake 0
#   would read as success for HRESULT functions (SHGetKnownFolderPath) and send games down a wrong path.
#   wininet   InternetOpenA / InternetConnectA     -> session handles
#             InternetOpenUrl(A)                   -> real HTTP(S) GET (redirects followed, TLS verified against the
#                                                     game's cacert.pem or Android's CA store); 0 on failure, like Windows
#             HttpQueryInfo(A)                     -> status code / content length / headers
#             InternetReadFile                     -> reads the response body into the caller's buffer
#             InternetCloseHandle                  -> releases the handle
#             DeleteUrlCacheEntry(A)               -> success (there is no WinINet cache)
#
# Uses only the C parts of Ruby's socket and openssl extensions (compiled into the engine; the Ruby-side helper
# libraries are not shipped on Android).
#===============================================================================
module AceCoreCompat
  module WinInet
    MAX_REDIRECTS = 5
    TIMEOUT = 20
    @handles = {}
    @next = 0x100
    @lock = Thread::Mutex.new

    class Response
      attr_accessor :status, :headers, :body, :pos
      def initialize(status, headers, body)
        @status, @headers, @body, @pos = status, headers, body, 0
      end
    end

    class << self
      def new_handle(obj)
        @lock.synchronize { h = (@next += 1); @handles[h] = obj; h }
      end

      def handle(h) = @lock.synchronize { @handles[h] }
      def close(h) = @lock.synchronize { !@handles.delete(h).nil? }

      def open_url(url)
        res = get(url.to_s)
        res ? new_handle(res) : 0
      rescue StandardError, ScriptError => e
        log("WinInet: #{url}: #{e.class}: #{e.message}")
        0
      end

      def log(msg)
        ($stderr.puts("AceArcade #{msg}") rescue nil)
      end

      def get(url, redirects = 0)
        m = url.match(%r{\A(https?)://([^/:?#]+)(?::(\d+))?([^#]*)}i) or raise ArgumentError, "unsupported URL"
        scheme, host, port, path = m[1].downcase, m[2], m[3], m[4]
        path = '/' if path.nil? || path.empty?
        port = (port || (scheme == 'https' ? 443 : 80)).to_i
        status, headers, body = request(scheme, host, port, path)
        if [301, 302, 303, 307, 308].include?(status) && headers['location'] && redirects < MAX_REDIRECTS
          loc = headers['location']
          loc = "#{scheme}://#{host}#{port == (scheme == 'https' ? 443 : 80) ? '' : ":#{port}"}#{loc}" if loc.start_with?('/')
          return get(loc, redirects + 1)
        end
        Response.new(status, headers, body)
      end

      def request(scheme, host, port, path, verb = 'GET', extra_headers = '', body = nil)
        require 'socket.so'
        tcp = TCPSocket.new(host, port, connect_timeout: TIMEOUT)
        io = tcp
        if scheme == 'https'
          require 'openssl.so'
          ctx = OpenSSL::SSL::SSLContext.new
          ctx.cert_store = cert_store
          ctx.verify_mode = OpenSSL::SSL::VERIFY_PEER
          io = OpenSSL::SSL::SSLSocket.new(tcp, ctx)
          io.hostname = host
          io.connect
          raise "certificate verification failed (#{io.verify_result})" unless io.verify_result == OpenSSL::X509::V_OK
          raise "certificate does not match #{host}" unless host_matches?(io.peer_cert, host)
        end
        given = extra_headers.to_s.split(/\r?\n/).reject(&:empty?)
        names = given.map { |l| l.split(':', 2)[0].to_s.strip.downcase }
        default_port = scheme == 'https' ? 443 : 80
        req = +"#{verb} #{path} HTTP/1.1\r\nHost: #{host}#{port == default_port ? '' : ":#{port}"}\r\n"
        req << "User-Agent: Mozilla/5.0 (Android) AceArcade\r\n" unless names.include?('user-agent')
        req << "Accept: */*\r\n" unless names.include?('accept')
        given.each { |l| req << l << "\r\n" unless %w[host connection accept-encoding content-length].include?(l.split(':', 2)[0].to_s.strip.downcase) }
        req << "Accept-Encoding: identity\r\nConnection: close\r\n"
        req << "Content-Length: #{body.bytesize}\r\n" if body && (!body.empty? || verb != 'GET')
        req << "\r\n"
        write_all(io, req.b + (body || '').b)
        raw = read_all(io, tcp)
        parse(raw)
      ensure
        (io.sysclose if io && io != tcp) rescue nil
        (tcp.close if tcp) rescue nil
      end

      def write_all(io, data)
        data = data.b
        until data.empty?
          n = io.syswrite(data)
          data = data.byteslice(n..-1)
        end
      end

      # Waits on the TCP socket itself (SSLSocket#to_io is Ruby-side and not available on Android); data TLS has
      # already decrypted and buffered is picked up through #pending without waiting.
      def read_all(io, tcp)
        out = String.new(encoding: Encoding::BINARY)
        loop do
          buffered = io.respond_to?(:pending) && io.pending.to_i > 0
          raise "timed out" unless buffered || IO.select([tcp], nil, nil, TIMEOUT)
          out << io.sysread(16_384)
        end
      rescue EOFError
        out
      end

      def parse(raw)
        head, body = raw.split("\r\n\r\n", 2)
        raise "malformed HTTP response" if head.nil? || body.nil?
        lines = head.split("\r\n")
        status = lines.shift.to_s[/\AHTTP\/\d(?:\.\d)?\s+(\d{3})/, 1].to_i
        headers = {}
        lines.each { |l| k, v = l.split(':', 2); headers[k.strip.downcase] = v.to_s.strip if k }
        body = dechunk(body) if headers['transfer-encoding'].to_s.downcase.include?('chunked')
        if (len = headers['content-length']) && !headers.key?('transfer-encoding')
          body = body.byteslice(0, len.to_i)
        end
        [status, headers, body]
      end

      def dechunk(data)
        out = String.new(encoding: Encoding::BINARY)
        pos = 0
        loop do
          eol = data.index("\r\n", pos) or break
          size = data[pos...eol].split(';', 2)[0].to_i(16)
          break if size.zero?
          out << data.byteslice(eol + 2, size)
          pos = eol + 2 + size + 2
        end
        out
      end

      def cert_store
        @store ||= begin
          s = OpenSSL::X509::Store.new
          added = 0
          [ENV['SSL_CERT_FILE'], 'cacert.pem'].compact.uniq.each do |f|
            next unless File.file?(f)
            s.add_file(f) rescue next
            added += 1
          end
          if added.zero?
            # Android's system CA store (Android 14+ keeps the updatable copy in the Conscrypt APEX).
            %w[/apex/com.android.conscrypt/cacerts /system/etc/security/cacerts].each do |dir|
              next unless File.directory?(dir)
              Dir.children(dir).each do |name|
                pem = File.read(File.join(dir, name)) rescue next
                pem.scan(/-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----/m).each do |c|
                  (s.add_cert(OpenSSL::X509::Certificate.new(c)); added += 1) rescue nil
                end
              end
              break if added > 0
            end
          end
          s
        end
      end

      # RFC 6125 matching on subjectAltName DNS entries (falls back to the subject CN when there are none).
      def host_matches?(cert, host)
        return false unless cert
        host = host.downcase
        names = []
        cert.extensions.each do |ext|
          next unless ext.oid == 'subjectAltName'
          ext.value.split(/,\s*/).each { |e| names << e.sub(/\ADNS:/, '').downcase if e.start_with?('DNS:') }
        end
        if names.empty?
          cn = cert.subject.to_a.find { |n| n[0] == 'CN' }
          names << cn[1].downcase if cn
        end
        names.any? do |n|
          if n.start_with?('*.')
            suffix = n[1..]
            host.end_with?(suffix) && !host[0...-suffix.length].include?('.') && host.length > suffix.length
          else
            n == host
          end
        end
      end
    end
  end

  # WinHTTP (winhttp.dll) on top of the same HTTP(S) client: Open -> Connect(host, port) -> OpenRequest(verb, path,
  # WINHTTP_FLAG_SECURE) -> SendRequest(headers, body) [+ WriteData] -> ReceiveResponse -> QueryHeaders /
  # QueryDataAvailable / ReadData -> CloseHandle. WinHTTP is Unicode-only, so text arguments are UTF-16LE strings.
  # Failures return 0 (FALSE / NULL) and set GetLastError, as WinHTTP does; nothing pretends to have succeeded.
  module WinHttp
    FLAG_SECURE = 0x00800000
    QUERY_FLAG_NUMBER = 0x20000000
    ERROR_INVALID_HANDLE = 6
    ERROR_INSUFFICIENT_BUFFER = 122
    ERROR_CANNOT_CONNECT = 12029     # ERROR_WINHTTP_CANNOT_CONNECT
    ERROR_INCORRECT_HANDLE_STATE = 12019
    ERROR_HEADER_NOT_FOUND = 12150

    Session = Struct.new(:agent)
    Connection = Struct.new(:host, :port)
    Request = Struct.new(:conn, :verb, :path, :secure, :headers, :body, :response, :pos)

    module_function

    # A wide (UTF-16LE) string argument up to its terminating NUL; nil for a NULL pointer.
    def wide(arg, chars = -1)
      return nil unless arg.is_a?(String)
      bytes = arg.b
      if chars.to_i >= 0 && chars.to_i < 0xFFFFFFFF
        bytes = bytes.byteslice(0, chars.to_i * 2) || ''.b
      else
        stop = (0...bytes.bytesize).step(2).find { |i| bytes.getbyte(i).zero? && bytes.getbyte(i + 1).to_i.zero? }
        bytes = bytes.byteslice(0, stop) if stop
      end
      bytes.force_encoding(Encoding::UTF_16LE).encode(Encoding::UTF_8, invalid: :replace, undef: :replace)
    end

    def failed(code)
      Win32.last_error = code
      0
    end

    def ok
      Win32.last_error = 0
      1
    end

    def put_dword(buf, value)
      Win32.fill(buf, [value].pack('L')) if buf.is_a?(String) && buf.bytesize >= 4
    end

    def open_session(agent)
      Win32.last_error = 0
      WinInet.new_handle(Session.new(wide(agent)))
    end

    def connect(session, host, port)
      return failed(ERROR_INVALID_HANDLE) unless WinInet.handle(session).is_a?(Session)
      name = wide(host).to_s
      return failed(87) if name.empty? # ERROR_INVALID_PARAMETER
      Win32.last_error = 0
      WinInet.new_handle(Connection.new(name, port.to_i))
    end

    def open_request(conn, verb, path, flags)
      c = WinInet.handle(conn)
      return failed(ERROR_INVALID_HANDLE) unless c.is_a?(Connection)
      v = wide(verb).to_s
      v = 'GET' if v.empty?
      pth = wide(path).to_s
      pth = "/#{pth}" unless pth.start_with?('/')
      Win32.last_error = 0
      WinInet.new_handle(Request.new(c, v.upcase, pth, (flags.to_i & FLAG_SECURE) != 0, +'', ''.b, nil, 0))
    end

    def request(h) = (r = WinInet.handle(h)).is_a?(Request) ? r : nil

    def add_headers(h, headers, len)
      r = request(h) or return failed(ERROR_INVALID_HANDLE)
      text = wide(headers, len).to_s
      r.headers << text.sub(/\r?\n?\z/, "\r\n") unless text.empty?
      ok
    end

    def send_request(h, headers, len, optional, optional_len)
      r = request(h) or return failed(ERROR_INVALID_HANDLE)
      add_headers(h, headers, len) if headers.is_a?(String)
      if optional.is_a?(String) && optional_len.to_i > 0
        r.body << (optional.b.byteslice(0, optional_len.to_i) || ''.b)
      end
      ok
    end

    def write(h, data, len, written)
      r = request(h) or return failed(ERROR_INVALID_HANDLE)
      chunk = data.is_a?(String) ? (data.b.byteslice(0, len.to_i) || ''.b) : ''.b
      r.body << chunk
      put_dword(written, chunk.bytesize)
      ok
    end

    def receive(h)
      r = request(h) or return failed(ERROR_INVALID_HANDLE)
      c = r.conn
      scheme = r.secure ? 'https' : 'http'
      port = c.port.zero? ? (r.secure ? 443 : 80) : c.port
      status, headers, body = WinInet.request(scheme, c.host, port, r.path, r.verb, r.headers,
                                              r.verb == 'GET' && r.body.empty? ? nil : r.body)
      r.response = WinInet::Response.new(status, headers, body)
      r.pos = 0
      ok
    rescue StandardError, ScriptError => e
      WinInet.log("WinHTTP: #{r&.verb} #{scheme}://#{c&.host}:#{port}#{r&.path}: #{e.class}: #{e.message}")
      failed(ERROR_CANNOT_CONNECT)
    end

    def query_headers(h, level, buf, len_buf)
      r = request(h) or return failed(ERROR_INVALID_HANDLE)
      res = r.response or return failed(ERROR_INCORRECT_HANDLE_STATE)
      value = case level.to_i & 0xFFFF
              when 19 then res.status.to_s                                   # WINHTTP_QUERY_STATUS_CODE
              when 5 then res.headers['content-length'] || res.body.bytesize.to_s # WINHTTP_QUERY_CONTENT_LENGTH
              when 1 then res.headers['content-type']                        # WINHTTP_QUERY_CONTENT_TYPE
              when 22 then "HTTP/1.1 #{res.status}\r\n" + res.headers.map { |k, v| "#{k}: #{v}\r\n" }.join + "\r\n"
              end
      return failed(ERROR_HEADER_NOT_FOUND) if value.nil?
      if (level.to_i & QUERY_FLAG_NUMBER) != 0
        return failed(ERROR_INSUFFICIENT_BUFFER) unless buf.is_a?(String) && buf.bytesize >= 4
        put_dword(buf, value.to_i)
        put_dword(len_buf, 4)
        return ok
      end
      out = value.encode(Encoding::UTF_16LE).b
      cap = len_buf.is_a?(String) && len_buf.bytesize >= 4 ? len_buf.b.unpack1('L') : (buf.is_a?(String) ? buf.bytesize : 0)
      cap = [cap, buf.is_a?(String) ? buf.bytesize : 0].min
      if out.bytesize + 2 > cap
        put_dword(len_buf, out.bytesize + 2)
        return failed(ERROR_INSUFFICIENT_BUFFER)
      end
      Win32.fill(buf, out + "\0\0".b)
      put_dword(len_buf, out.bytesize)
      ok
    end

    def available(h, out)
      r = request(h) or return failed(ERROR_INVALID_HANDLE)
      res = r.response or return failed(ERROR_INCORRECT_HANDLE_STATE)
      put_dword(out, [res.body.bytesize - r.pos, 0].max)
      ok
    end

    def read(h, buf, size, read_out)
      r = request(h) or return failed(ERROR_INVALID_HANDLE)
      res = r.response or return failed(ERROR_INCORRECT_HANDLE_STATE)
      want = [size.to_i, buf.is_a?(String) ? buf.bytesize : 0].min
      chunk = res.body.byteslice(r.pos, [want, 0].max) || ''.b
      r.pos += chunk.bytesize
      Win32.fill(buf, chunk) unless chunk.empty?
      put_dword(read_out, chunk.bytesize)
      ok
    end

    def close(h) = WinInet.close(h) ? ok : failed(ERROR_INVALID_HANDLE)
  end

  # Windows paths in game scripts ("Graphics\\Titles\\x.png", ENV['SystemRoot'] + "\\Fonts\\"): Windows accepts "\\"
  # as a folder separator, Android only "/". Paths given to File / FileTest / Dir have "\\" turned into "/" (a "\\"
  # can't be part of a file name on Windows, so no real name changes meaning).
  #
  # Windows DLLs that ship with a game (audio.dll, rubyscreen.dll, ...) are Windows machine code and can't be loaded
  # on Android. Pokémon Essentials checks for them by opening the file (safeExists?) and only then binds their
  # functions; reporting such a DLL as absent sends the game down its own documented no-DLL path (RGSS Audio etc.).
  # Only paths ending in ".dll" are affected; every other file behaves exactly as before.
  module HideWindowsDlls
    def self.fix(path)
      path.is_a?(String) && path.include?('\\') ? path.tr('\\', '/') : path
    end

    def self.dll?(path)
      path.is_a?(String) && path.match?(/\.dll\z/i)
    end

    def self.hide(path)
      Win32.note_once("#{File.basename(path.to_s)} is a Windows DLL: reported absent so the game uses its non-DLL code path")
    end

    def self.writing?(mode)
      (mode.is_a?(String) && mode.match?(/[wa+]/)) || (mode.is_a?(Integer) && (mode & (File::WRONLY | File::RDWR | File::CREAT)) != 0)
    end

    module FileClass
      %i[exist? file? readable? size?].each do |m|
        define_method(m) do |path, *rest|
          path = HideWindowsDlls.fix(path)
          HideWindowsDlls.dll?(path) ? (HideWindowsDlls.hide(path); m == :size? ? nil : false) : super(path, *rest)
        end
      end

      %i[directory? writable? size mtime atime ctime stat lstat read binread readlines foreach write binwrite
         expand_path truncate].each do |m|
        define_method(m) { |path, *rest, **kw, &blk| super(HideWindowsDlls.fix(path), *rest, **kw, &blk) }
      end

      def open(path, *rest, **kw, &blk)
        path = HideWindowsDlls.fix(path)
        if HideWindowsDlls.dll?(path) && !HideWindowsDlls.writing?(rest[0] || kw[:mode])
          HideWindowsDlls.hide(path)
          raise Errno::ENOENT, path
        end
        super(path, *rest, **kw, &blk)
      end

      def new(path, *rest, **kw, &blk)
        path = HideWindowsDlls.fix(path)
        if HideWindowsDlls.dll?(path) && !HideWindowsDlls.writing?(rest[0] || kw[:mode])
          HideWindowsDlls.hide(path)
          raise Errno::ENOENT, path
        end
        super(path, *rest, **kw, &blk)
      end

      def delete(*paths) = super(*paths.map { |x| HideWindowsDlls.fix(x) })
      def unlink(*paths) = super(*paths.map { |x| HideWindowsDlls.fix(x) })
      def rename(a, b) = super(HideWindowsDlls.fix(a), HideWindowsDlls.fix(b))
    end

    module FileTestModule
      %i[exist? file? readable? size?].each do |m|
        define_method(m) do |path, *rest|
          path = HideWindowsDlls.fix(path)
          HideWindowsDlls.dll?(path) ? (HideWindowsDlls.hide(path); m == :size? ? nil : false) : super(path, *rest)
        end
      end
      define_method(:directory?) { |path| super(HideWindowsDlls.fix(path)) }
    end

    module DirClass
      %i[mkdir exist? entries children each_child rmdir delete chdir].each do |m|
        define_method(m) { |path = nil, *rest, **kw, &blk| path.nil? ? super(*rest, **kw, &blk) : super(HideWindowsDlls.fix(path), *rest, **kw, &blk) }
      end
    end
  end
  File.singleton_class.prepend(HideWindowsDlls::FileClass)
  FileTest.singleton_class.prepend(HideWindowsDlls::FileTestModule)
  Dir.singleton_class.prepend(HideWindowsDlls::DirClass)

  # Programs a game starts. Android can't run Windows programs, except one: the game itself. Games restart
  # themselves to apply settings (screen size, language) by starting Game.exe and exiting; AceArcade then starts the
  # game again once it has closed ("restart" in the helper folder, read by the launcher). Every command is logged.
  module Commands
    module_function

    def dir
      defined?(AceCoreCompat::DISPLAY_FILE) && AceCoreCompat::DISPLAY_FILE ? File.dirname(AceCoreCompat::DISPLAY_FILE) : nil
    end

    def program(cmd)
      cmd = cmd.to_s.strip
      first = cmd.start_with?('"') ? cmd[1..].split('"', 2)[0].to_s : cmd.split(/\s+/, 2)[0].to_s
      File.basename(first.tr('\\', '/'))
    end

    # The game's own executable: an .exe in the game folder whose name matches (Game.exe or the game's renamed exe).
    def own_exe?(cmd)
      name = program(cmd)
      return false unless name.match?(/\.exe\z/i) || name.casecmp?('game')
      name = "#{name}.exe" unless name.match?(/\.exe\z/i)
      (Dir.children('.') rescue []).any? { |f| f.casecmp?(name) }
    end

    def request_restart(cmd)
      d = dir or return false
      File.binwrite(File.join(d, 'restart'), "#{Time.now.to_i}\n#{cmd}\n")
      Win32.note_once("game restarts itself (#{program(cmd)}): AceArcade starts it again when it closes")
      true
    rescue StandardError
      false
    end

    def note(cmd)
      Win32.note_once("game ran a program: #{cmd.to_s[0, 120]} (Windows programs can't run on Android)")
    end

    def shell_execute(file)
      file = file.is_a?(String) ? Win32.c_string(file, -1).force_encoding(Encoding::UTF_8).scrub : ''
      return 42 if own_exe?(file) && request_restart(file) # > 32: success
      note(file)
      2
    end

    def command(args)
      args = args.dup
      args.shift if args.first.is_a?(Hash) # environment
      args.reject { |a| a.is_a?(Hash) }.flatten.map(&:to_s).join(' ')
    end
  end

  # A Windows function implemented by AceArcade. Arguments arrive as Ruby values: 'p' buffers are the caller's own
  # String objects and are filled in place, as Windows fills the memory behind the pointer.
  class WinFunction
    def initialize(name, &impl)
      @name = name
      @impl = impl
    end

    def call(*args) = @impl.call(*args)
    alias Call call
  end

  module Win32
    WINDOW = 42 # the game's window handle (one window, like "RGSS Player")
    THREAD = 1
    module_function

    def fill(buf, bytes)
      return unless buf.is_a?(String)
      bytes = bytes.b
      n = [bytes.bytesize, buf.bytesize].min
      buf.force_encoding(Encoding::BINARY) if buf.encoding != Encoding::BINARY && !buf.frozen?
      buf[0, n] = bytes.byteslice(0, n)
    end

    def game_size
      [(Graphics.width rescue 640), (Graphics.height rescue 480)]
    end

    # The game's screen as the engine started it (640x480 for RPG Maker XP): the largest area a game can show.
    def base_size
      @base_size ||= game_size
    end

    # SetWindowPos / MoveWindow with a size. RPG Maker XP always renders 640x480; Pokémon Essentials for XP shows a
    # smaller screen (512x384) by resizing the game window, which on Windows shows only the top-left part of the
    # 640x480 image, at 1x, 2x or 0.5x zoom. Here the zoom is AceArcade's display setting, so the requested window
    # size is brought back to 1x (halved or doubled until it fits the engine's screen) and becomes the engine's
    # screen size (Graphics.resize_screen): the same visible area as on Windows, centred and scaled on the phone.
    def resize_client(w, h)
      w, h = w.to_i, h.to_i
      return 1 if w <= 0 || h <= 0 || !Graphics.respond_to?(:resize_screen)
      bw, bh = base_size
      while (w > bw || h > bh) && w.even? && h.even? && w > 1
        w /= 2; h /= 2
      end
      while w * 2 <= bw && h * 2 <= bh
        w *= 2; h *= 2
      end
      return 1 if w > bw || h > bh || [w, h] == game_size
      Graphics.resize_screen(w, h)
      note_once("window resized by the game: screen #{w}x#{h} (was #{bw}x#{bh})")
      1
    rescue StandardError => e
      note_once("window resize to #{w}x#{h} not applied: #{e.message}")
      1
    end

    def ini_value(file, section, key)
      path = file.to_s.tr('\\', '/').sub(%r{\A\./}, '')
      path = File.basename(path) unless File.exist?(path)
      return nil unless File.exist?(path)
      current = nil
      File.foreach(path, mode: 'rb') do |line|
        line = line.strip
        if line =~ /\A\[(.+)\]\z/ then current = $1
        elsif current && current.casecmp?(section.to_s) && line.include?('=')
          k, v = line.split('=', 2)
          return v.strip if k.strip.casecmp?(key.to_s)
        end
      end
      nil
    rescue StandardError
      nil
    end

    def key_state(vk)
      states = (Input.raw_key_states rescue nil) or return false
      # Windows virtual-key -> SDL scancode for the keys games query most (letters, digits, arrows, common keys)
      vk = vk.to_i
      sc = case vk
           when 0x41..0x5A then 0x04 + (vk - 0x41)
           when 0x31..0x39 then 0x1E + (vk - 0x31)
           when 0x30 then 0x27
           when 0x0D then 0x28 when 0x1B then 0x29 when 0x08 then 0x2A when 0x09 then 0x2B when 0x20 then 0x2C
           when 0x25 then 0x50 when 0x26 then 0x52 when 0x27 then 0x4F when 0x28 then 0x51
           when 0x70..0x7B then 0x3A + (vk - 0x70)
           when 0x10, 0xA0 then 0xE1 when 0xA1 then 0xE5 when 0x11, 0xA2 then 0xE0 when 0xA3 then 0xE4
           when 0x12, 0xA4 then 0xE2 when 0xA5 then 0xE6
           end
      sc ? !!states[sc] : false
    end

    # Text up to the first NUL (or [len] bytes when len >= 0).
    def c_string(src, len)
      str = src.is_a?(String) ? src.b : ''.b
      return str.byteslice(0, len) || ''.b if len >= 0
      i = str.index("\0".b)
      i ? str.byteslice(0, i) : str
    end

    @last_error = 0
    @captured = false
    class << self
      attr_accessor :last_error, :captured
    end
    ERROR_MOD_NOT_FOUND = 126
    ERROR_PROC_NOT_FOUND = 127
    ERROR_CALL_NOT_IMPLEMENTED = 120

    # LoadLibrary: Windows DLLs can't be loaded on Android, so the call fails exactly as Windows does for a DLL that
    # isn't there (NULL, GetLastError = ERROR_MOD_NOT_FOUND). The DLLs AceArcade implements are reached through
    # Win32API.new directly and need no handle.
    def load_library(name)
      note_once("LoadLibrary(#{c_string(name, -1).force_encoding(Encoding::UTF_8).scrub}) -> NULL (Windows DLLs can't be loaded on Android)") if name.is_a?(String)
      self.last_error = ERROR_MOD_NOT_FOUND
      0
    end

    def unsupported(what)
      note_once("#{what} -> fails (not available on Android)")
      self.last_error = ERROR_CALL_NOT_IMPLEMENTED
      0
    end

    # FormatMessage(FORMAT_MESSAGE_FROM_SYSTEM): the Windows text for the error codes the functions here produce
    # and the ones games report (registry, files). Each ends in CR LF, as on Windows.
    SYSTEM_MESSAGES = {
      0 => 'The operation completed successfully.',
      2 => 'The system cannot find the file specified.',
      3 => 'The system cannot find the path specified.',
      5 => 'Access is denied.',
      6 => 'The handle is invalid.',
      8 => 'Not enough memory resources are available to process this command.',
      50 => 'The request is not supported.',
      87 => 'The parameter is incorrect.',
      120 => 'This function is not supported on this system.',
      122 => 'The data area passed to a system call is too small.',
      126 => 'The specified module could not be found.',
      127 => 'The specified procedure could not be found.',
      234 => 'More data is available.',
      259 => 'No more data is available.',
      1419 => 'Hot key is not registered.',
      12029 => 'A connection with the server could not be established',
    }.freeze
    FORMAT_ALLOCATE_BUFFER = 0x100
    FORMAT_FROM_STRING = 0x400
    FORMAT_FROM_HMODULE = 0x800
    FORMAT_FROM_SYSTEM = 0x1000
    ERROR_MR_MID_NOT_FOUND = 317

    # FormatMessage(A/W)(flags, source, id, lang, buffer, size, args) -> characters written (without the NUL), or 0
    # with GetLastError set, exactly as Windows reports a message it doesn't have.
    def format_message(flags, source, id, buf, size, wide)
      flags = flags.to_i
      if (flags & FORMAT_ALLOCATE_BUFFER) != 0
        # Windows would allocate memory and return its address; there is no Windows heap to hand out.
        note_once('FormatMessage with FORMAT_MESSAGE_ALLOCATE_BUFFER -> fails (no Windows memory on Android)')
        self.last_error = 50
        return 0
      end
      text = if (flags & FORMAT_FROM_STRING) != 0 && source.is_a?(String)
               wide ? WinHttp.wide(source) : c_string(source, -1).force_encoding(Encoding::Windows_1252)
             elsif (flags & FORMAT_FROM_SYSTEM) != 0 && (msg = SYSTEM_MESSAGES[id.to_i & 0xFFFFFFFF])
               msg + "\r\n"
             end
      if text.nil?
        self.last_error = ERROR_MR_MID_NOT_FOUND
        return 0
      end
      out = wide ? text.encode(Encoding::UTF_16LE).b : text.encode(Encoding::Windows_1252, invalid: :replace, undef: :replace).b
      unit = wide ? 2 : 1
      chars = out.bytesize / unit
      cap = [size.to_i, buf.is_a?(String) ? buf.bytesize / unit : 0].min
      if chars + 1 > cap
        self.last_error = 122
        return 0
      end
      fill(buf, out + ("\0" * unit).b)
      self.last_error = 0
      chars
    end

    # ---- Language. The device language as Windows reports it (LANGID = primary language | sublanguage << 10).
    LANGIDS = {
      'en-us' => 0x0409, 'en-gb' => 0x0809, 'en-au' => 0x0C09, 'en-ca' => 0x1009, 'en' => 0x0409,
      'es-es' => 0x0C0A, 'es-mx' => 0x080A, 'es-us' => 0x540A, 'es-ar' => 0x2C0A, 'es' => 0x0C0A,
      'fr-fr' => 0x040C, 'fr-ca' => 0x0C0C, 'fr' => 0x040C, 'de-de' => 0x0407, 'de' => 0x0407,
      'it-it' => 0x0410, 'it' => 0x0410, 'pt-br' => 0x0416, 'pt-pt' => 0x0816, 'pt' => 0x0816,
      'ja-jp' => 0x0411, 'ja' => 0x0411, 'ko-kr' => 0x0412, 'ko' => 0x0412, 'zh-cn' => 0x0804, 'zh-tw' => 0x0404,
      'zh-hans' => 0x0804, 'zh-hant' => 0x0404, 'zh' => 0x0804, 'ru-ru' => 0x0419, 'ru' => 0x0419,
      'pl-pl' => 0x0415, 'pl' => 0x0415, 'nl-nl' => 0x0413, 'nl' => 0x0413, 'tr-tr' => 0x041F, 'tr' => 0x041F,
      'sv-se' => 0x041D, 'sv' => 0x041D, 'da-dk' => 0x0406, 'da' => 0x0406, 'nb-no' => 0x0414, 'fi-fi' => 0x040B,
      'cs-cz' => 0x0405, 'el-gr' => 0x0408, 'hu-hu' => 0x040E, 'ar-sa' => 0x0401, 'id-id' => 0x0421,
    }.freeze
    # Three-letter language codes Windows uses in Control Panel\International (sLanguage).
    ABBREVIATIONS = { 0x0409 => 'ENU', 0x0809 => 'ENG', 0x0C0A => 'ESN', 0x080A => 'ESM', 0x040C => 'FRA',
                      0x0407 => 'DEU', 0x0410 => 'ITA', 0x0416 => 'PTB', 0x0816 => 'PTG', 0x0411 => 'JPN',
                      0x0412 => 'KOR', 0x0804 => 'CHS', 0x0404 => 'CHT', 0x0419 => 'RUS' }.freeze

    def locale_tag
      tag = defined?(AceCoreCompat::LOCALE) ? AceCoreCompat::LOCALE.to_s : 'en-US'
      tag.empty? || tag == 'und' ? 'en-US' : tag
    end

    def langid
      parts = locale_tag.downcase.split('-')
      LANGIDS[parts.first(2).join('-')] || LANGIDS[parts.first] || 0x0409
    end

 AceArcade provides the part every Windows installation has and
    # games read: the user's shell folders (Documents, Saved Games, AppData, Fonts...), pointing at this game's own
    # Windows folders (see USERPROFILE / SystemRoot at the top). Every other key is absent, as on a Windows install
    # where nothing was written there: opening it fails with ERROR_FILE_NOT_FOUND and games use their defaults
    # (Essentials MiniRegistry; the RPG Maker RTP key, so no RTP is reported installed). Read-only: nothing is created.
    HKEY_ROOTS = (0x80000000..0x80000006).freeze
    HKCU = 0x80000001
    SHELL_FOLDERS = 'software\\microsoft\\windows\\currentversion\\explorer\\shell folders'
    USER_SHELL_FOLDERS = 'software\\microsoft\\windows\\currentversion\\explorer\\user shell folders'
    @reg_handles = {}
    @reg_next = 0x1000

    def reg_root?(h) = HKEY_ROOTS.cover?(h.to_i & 0xFFFFFFFF)

    def user_dir(*parts)
      home = ENV['USERPROFILE'] or return nil
      path = File.join(home, *parts)
      (Dir.mkdir(path) rescue nil) unless File.directory?(path)
      path.tr('/', '\\')
    end

    def shell_folders
      {
        'personal' => user_dir('Documents'), 'my documents' => user_dir('Documents'),
        'desktop' => user_dir('Desktop'), 'my pictures' => user_dir('Pictures'), 'my music' => user_dir('Music'),
        'my video' => user_dir('Videos'), 'appdata' => ENV['APPDATA']&.tr('/', '\\'),
        'local appdata' => ENV['LOCALAPPDATA']&.tr('/', '\\'),
        'fonts' => ENV['SystemRoot'] && File.join(ENV['SystemRoot'], 'Fonts').tr('/', '\\'),
        '{4c5c32ff-bb9d-43b0-b5b4-2d72e54eaaa4}' => user_dir('Saved Games'),   # FOLDERID_SavedGames
        '{374de290-123f-4565-9164-39c4925e467b}' => user_dir('Downloads'),     # FOLDERID_Downloads
      }.compact
    end

    # The values of a key, or nil when the key doesn't exist.
    def reg_key(root, path)
      root = root.to_i & 0xFFFFFFFF
      if (h = @reg_handles[root])
        root, path = h[:root], [h[:path], path].reject { |x| x.nil? || x.empty? }.join('\\')
      end
      key = path.to_s.downcase.tr('/', '\\').sub(/\A\\+/, '').sub(/\\+\z/, '')
      return nil unless root == HKCU
      values = case key
               when SHELL_FOLDERS, USER_SHELL_FOLDERS then shell_folders
               when 'control panel\\international'
                 { 'locale' => format('%08X', langid), 'localename' => locale_tag,
                   'slanguage' => ABBREVIATIONS[langid] || 'ENU' }
               when 'control panel\\desktop\\resourcelocale' then { '' => format('%08X', langid) }
               when 'control panel\\desktop' then {}
               end
      values && { root: root, path: key, values: values }
    end

    # RegOpenKey(Ex): writes the new key handle to [out] (a 4-byte buffer) and returns 0, or ERROR_FILE_NOT_FOUND.
    def reg_open(root, name, out)
      name = name.is_a?(String) ? c_string(name, -1).force_encoding(Encoding::UTF_8).scrub : ''
      key = reg_key(root, name)
      unless key
        note_once("registry key #{name} -> not found (no Windows registry on Android)")
        return 2
      end
      h = (@reg_next += 1)
      @reg_handles[h] = key
      fill(out, [h].pack('L'))
      0
    end

    def reg_close(h)
      return 0 if reg_root?(h)
      @reg_handles.delete(h.to_i & 0xFFFFFFFF) ? 0 : 6
    end

    # RegQueryValueEx(key, name, reserved, type*, data*, size*): REG_SZ text with its NUL. data NULL: only the size;
    # data too small: ERROR_MORE_DATA (234) with the size needed, as on Windows.
    def reg_query(h, name, type_buf, data_buf, size_buf, wide = false)
      key = @reg_handles[h.to_i & 0xFFFFFFFF]
      return(reg_root?(h) ? 2 : 6) unless key
      name = name.is_a?(String) ? (wide ? WinHttp.wide(name) : c_string(name, -1).force_encoding(Encoding::UTF_8)) : ''
      value = key[:values][name.to_s.downcase] or return 2
      bytes = wide ? (value + "\0").encode(Encoding::UTF_16LE).b : (value.encode(Encoding::Windows_1252, invalid: :replace, undef: :replace) + "\0").b
      fill(type_buf, [1].pack('L')) # REG_SZ
      cap = size_buf.is_a?(String) && size_buf.bytesize >= 4 ? size_buf.b.unpack1('L') : 0
      fill(size_buf, [bytes.bytesize].pack('L'))
      return 0 unless data_buf.is_a?(String)
      return 234 if bytes.bytesize > [cap, data_buf.bytesize].min
      fill(data_buf, bytes)
      0
    end

    def reg_enum(h) = @reg_handles[h.to_i & 0xFFFFFFFF] ? 259 : (reg_root?(h) ? 259 : 6) # ERROR_NO_MORE_ITEMS

    # AddFontResource: the engine loads every font in the game's "Fonts" folder at start-up, so a font file that is
    # there (or a copy of one, as Essentials' FontInstaller makes) is available to the game: 1 font added. Any other
    # font can't be added to the running engine: 0, as Windows returns for a font it couldn't add.
    def add_font(path)
      path = HideWindowsDlls.fix(path.is_a?(String) ? c_string(path, -1).force_encoding(Encoding::UTF_8) : '')
      name = File.basename(path)
      game_fonts = (Dir.children('Fonts') rescue [])
      if File.exist?(path) && game_fonts.any? { |f| f.casecmp?(name) }
        note_once("font #{name}: available (loaded by the engine from the game's Fonts folder)")
        1
      else
        note_once("font #{name}: not added (only fonts in the game's Fonts folder are loaded)")
        self.last_error = 2
        0
      end
    end

    # WriteProfileString (win.ini): written to win.ini in this game's own Windows folder (see SystemRoot above).
    def write_profile(section, key, value)
      dir = ENV['SystemRoot'] or return 0
      file = File.join(dir, 'win.ini')
      section, key = c_string(section, -1), (key.is_a?(String) ? c_string(key, -1) : nil)
      value = value.is_a?(String) ? c_string(value, -1) : nil
      lines = (File.binread(file).split(/\r?\n/) rescue [])
      start = lines.index { |l| l.strip.casecmp?("[#{section}]".b) }
      unless start
        lines << "[#{section}]".b
        start = lines.size - 1
      end
      stop = (start + 1...lines.size).find { |i| lines[i].start_with?('[') } || lines.size
      idx = key && (start + 1...stop).find { |i| lines[i].split('=', 2)[0].to_s.strip.casecmp?(key) }
      if key.nil?
        lines.slice!(start, stop - start)                   # NULL key: delete the section
      elsif value.nil?
        lines.delete_at(idx) if idx                          # NULL value: delete the key
      elsif idx
        lines[idx] = "#{key}=#{value}".b
      else
        lines.insert(stop, "#{key}=#{value}".b)
      end
      File.binwrite(file, lines.join("\r\n") + "\r\n")
      1
    rescue StandardError
      self.last_error = 5
      0
    end

    @notes = {}
    def note_once(text)
      return if @notes[text]
      @notes[text] = true
      ($stderr.puts("AceArcade: #{text}") rescue nil)
    end

    @missing = {}
    def missing(dll, func)
      key = "#{dll}!#{func}"
      unless @missing[key]
        @missing[key] = true
        ($stderr.puts("AceArcade: Windows function #{key} is not available on Android") rescue nil)
      end
      0
    end
  end

  WIN_FUNCTIONS = {
    'kernel32' => {
      'setpriorityclass' => proc { |*_| 1 },
      'getcurrentthreadid' => proc { |*_| Win32::THREAD },
      'getcurrentprocessid' => proc { |*_| Process.pid },
      # Pseudo-handles, the constant values Windows returns ("the current process / thread").
      'getcurrentprocess' => proc { |*_| -1 },
      # The device language (see Win32.langid); LCIDs use the default sort order, so they equal the LANGID.
      'getuserdefaultlangid' => (lang = proc { |*_| Win32.langid }), 'getsystemdefaultlangid' => lang,
      'getuserdefaultuilanguage' => lang, 'getsystemdefaultuilanguage' => lang,
      'getuserdefaultlcid' => lang, 'getsystemdefaultlcid' => lang, 'getthreadlocale' => lang,
      'getcurrentthread' => proc { |*_| -2 },
      'gettickcount' => proc { |*_| (Process.clock_gettime(Process::CLOCK_MONOTONIC, :millisecond) & 0xFFFFFFFF) },
      'queryperformancefrequency' => proc { |buf| Win32.fill(buf, [1_000_000_000].pack('q')); 1 },
      'queryperformancecounter' => proc { |buf| Win32.fill(buf, [Process.clock_gettime(Process::CLOCK_MONOTONIC, :nanosecond)].pack('q')); 1 },
      'sleep' => proc { |ms| sleep(ms.to_i / 1000.0); 0 },
      'getprivateprofilestring' => (gpps = proc { |section, key, default, buf, size, file|
        value = (Win32.ini_value(file, section, key) || default.to_s).b
        cap = [size.to_i, buf.is_a?(String) ? buf.bytesize : 0].min
        value = value.byteslice(0, [cap - 1, 0].max)
        Win32.fill(buf, value + "\0")
        value.bytesize
      }),
      'getprivateprofilestringa' => gpps,
      'loadlibrary' => (ll = proc { |name, *_| Win32.load_library(name) }),
      'loadlibrarya' => ll, 'loadlibraryex' => ll, 'loadlibraryexa' => ll,
      'loadlibraryw' => (llw = proc { |name, *_| Win32.load_library(name.is_a?(String) ? WinHttp.wide(name).b : name) }),
      'loadlibraryexw' => llw,
      # No library is ever loaded, so no handle is valid and no function address exists.
      'getprocaddress' => proc { |*_| Win32.last_error = Win32::ERROR_PROC_NOT_FOUND; 0 },
      'freelibrary' => proc { |*_| Win32.last_error = WinHttp::ERROR_INVALID_HANDLE; 0 },
      'formatmessage' => (fm = proc { |flags, source, id, _lang, buf, size, *_| Win32.format_message(flags, source, id, buf, size, false) }),
      'formatmessagea' => fm,
      'formatmessagew' => proc { |flags, source, id, _lang, buf, size, *_| Win32.format_message(flags, source, id, buf, size, true) },
      'writeprofilestring' => (wps = proc { |section, key, value| Win32.write_profile(section, key, value) }),
      'writeprofilestringa' => wps,
      'getlasterror' => proc { |*_| Win32.last_error },
      'setlasterror' => proc { |code| Win32.last_error = code.to_i; 0 },
      'outputdebugstring' => (ods = proc { |text| ($stderr.puts(text.to_s) rescue nil); 0 }),
      'outputdebugstringa' => ods,
      # RtlMoveMemory(dest, src, len): copies between buffers the game passed as Strings, within both buffers'
      # bounds. A raw address (an Integer from a native API) can't be read on Android: nothing is copied and the
      # engine log says so, instead of reading arbitrary memory.
      'rtlmovememory' => proc { |dest, src, len = nil|
        if dest.is_a?(String) && src.is_a?(String)
          n = [len ? len.to_i : src.bytesize, src.bytesize, dest.bytesize].min
          Win32.fill(dest, src.b.byteslice(0, [n, 0].max)) if n > 0
        else
          Win32.note_once('kernel32!RtlMoveMemory with a raw memory address: nothing copied (no Windows memory on Android)')
        end
        0
      },
      # Text conversion between the game's 8-bit text and UTF-16 (code page 65001 = UTF-8, others as Windows-1252).
      'multibytetowidechar' => proc { |cp, _flags, src, srclen, dst = nil, dstlen = 0|
        text = Win32.c_string(src, srclen.to_i).force_encoding(cp.to_i == 65001 ? Encoding::UTF_8 : Encoding::Windows_1252)
        wide = (text.encode(Encoding::UTF_16LE, invalid: :replace, undef: :replace) rescue ''.encode(Encoding::UTF_16LE)).b
        wide += "\0\0".b if srclen.to_i < 0
        chars = wide.bytesize / 2
        next chars if dstlen.to_i <= 0
        n = [chars, dstlen.to_i].min
        Win32.fill(dst, wide.byteslice(0, n * 2))
        n
      },
      'widechartomultibyte' => proc { |cp, _flags, src, srclen, dst = nil, dstlen = 0, *_|
        bytes = src.is_a?(String) ? src.b : ''.b
        bytes = bytes.byteslice(0, srclen.to_i * 2) if srclen.to_i >= 0
        if srclen.to_i < 0
          stop = (0...bytes.bytesize).step(2).find { |i| bytes.byteslice(i, 2) == "\0\0".b }
          bytes = bytes.byteslice(0, stop || bytes.bytesize)
        end
        target = cp.to_i == 65001 ? Encoding::UTF_8 : Encoding::Windows_1252
        out = (bytes.force_encoding(Encoding::UTF_16LE).encode(target, invalid: :replace, undef: :replace) rescue '').b
        out += "\0".b if srclen.to_i < 0
        next out.bytesize if dstlen.to_i <= 0
        n = [out.bytesize, dstlen.to_i].min
        Win32.fill(dst, out.byteslice(0, n))
        n
      },
    },
    'user32' => {
      'findwindow' => (fw = proc { |*_| Win32::WINDOW }),
      'findwindowa' => fw, 'findwindoww' => fw, 'findwindowex' => fw, 'findwindowexa' => fw, 'findwindowexw' => fw,
      'getforegroundwindow' => fw, 'getactivewindow' => fw, 'getfocus' => fw,
      'getwindowthreadprocessid' => proc { |_hwnd, pid_buf = nil| Win32.fill(pid_buf, [Process.pid].pack('l')); Win32::THREAD },
      'getsystemmetrics' => proc { |n|
        case n.to_i
        when 0 then defined?(AceCoreCompat::DISPLAY_W) ? AceCoreCompat::DISPLAY_W : Win32.game_size[0]
        when 1 then defined?(AceCoreCompat::DISPLAY_H) ? AceCoreCompat::DISPLAY_H : Win32.game_size[1]
        else 0
        end
      },
      'getclientrect' => (rect = proc { |_hwnd, buf| w, h = Win32.game_size; Win32.fill(buf, [0, 0, w, h].pack('l4')); 1 }),
      'getwindowrect' => rect,
      'setwindowpos' => proc { |_hwnd, _after = 0, _x = 0, _y = 0, cx = 0, cy = 0, flags = 0|
        (flags.to_i & 0x1) != 0 ? 1 : Win32.resize_client(cx, cy) # SWP_NOSIZE: position only
      },
      'movewindow' => proc { |_hwnd, _x = 0, _y = 0, w = 0, h = 0, *_| Win32.resize_client(w, h) },
      'showwindow' => (ok = proc { |*_| 1 }), 'updatewindow' => ok, 'setwindowtext' => ok, 'setwindowtexta' => ok,
      'setwindowtextw' => ok, 'setforegroundwindow' => ok, 'setfocus' => ok, 'setactivewindow' => ok,
      'setwindowlong' => ok, 'setwindowlonga' => ok, 'adjustwindowrect' => ok, 'adjustwindowrectex' => ok,
      'screentoclient' => ok, 'clienttoscreen' => ok,
      'getwindowlong' => (zero = proc { |*_| 0 }), 'getwindowlonga' => zero, 'showcursor' => zero, 'keybd_event' => zero,
      'getkeystate' => proc { |vk| Win32.key_state(vk) ? 0x8000 : 0 },
      'getasynckeystate' => proc { |vk| Win32.key_state(vk) ? 0x8000 : 0 },
      'getkeyboardstate' => proc { |buf|
        Win32.fill(buf, (0...256).map { |vk| Win32.key_state(vk) ? 0x80 : 0 }.pack('C*')); 1
      },
      'getcursorpos' => proc { |buf| Win32.fill(buf, [(Input.mouse_x rescue 0), (Input.mouse_y rescue 0)].pack('l2')); 1 },
      'messagebox' => (mb = proc { |_hwnd, text, caption = nil, *_| (print(text.to_s) rescue nil); 1 }),
      'messageboxa' => mb, 'messageboxw' => mb,
      # Global hotkeys are a Windows desktop feature; registration fails as it does when Windows refuses a hotkey,
      # and no WM_HOTKEY message is ever queued (PeekMessage: no message). Keys still reach the game through Input.
      'registerhotkey' => proc { |*_| Win32.unsupported('user32!RegisterHotKey') },
      'unregisterhotkey' => proc { |*_| Win32.last_error = 1419; 0 }, # ERROR_HOTKEY_NOT_REGISTERED
      'peekmessage' => (pm = proc { |*_| 0 }), 'peekmessagea' => pm, 'peekmessagew' => pm,
      # Posting to the game window or broadcasting (HWND_BROADCAST, e.g. WM_FONTCHANGE) is accepted like on Windows;
      # no other window exists to receive anything.
      'postmessage' => (post = proc { |hwnd, *_|
        if [Win32::WINDOW, 0xFFFF].include?(hwnd.to_i) then 1 else Win32.last_error = 1400; 0 end # ERROR_INVALID_WINDOW_HANDLE
      }),
      'postmessagea' => post, 'postmessagew' => post,
      # Mouse capture: the game window is the only window, so it always has the mouse. SetCapture returns the window
      # that had capture before (none); GetCapture the game window once captured.
      # Device contexts are Windows GDI drawing surfaces of a desktop window; the game draws through the engine and
      # there is no GDI surface to hand out. GetDC fails (NULL), as Windows does when it can't provide one.
      'getdc' => (gdc = proc { |*_| Win32.note_once('user32!GetDC -> NULL (no Windows drawing surface on Android)'); 0 }),
      'getwindowdc' => gdc, 'getdcex' => gdc,
      'releasedc' => proc { |*_| 0 },
      'setcapture' => proc { |_hwnd| prev = Win32.captured ? Win32::WINDOW : 0; Win32.captured = true; prev },
      'releasecapture' => proc { |*_| Win32.captured = false; 1 },
      'getcapture' => proc { |*_| Win32.captured ? Win32::WINDOW : 0 },
    },
    'shell32' => {
      # ShellExecute(hwnd, verb, file, params, dir, show): starting the game's own exe (a game restarting itself to
      # apply settings) restarts the game in AceArcade; anything else can't be run on Android (SE_ERR_FNF = 2).
      'shellexecute' => (se = proc { |_h, _verb, file, *_| Commands.shell_execute(file) }), 'shellexecutea' => se,
      'shellexecutew' => proc { |_h, _verb, file, *_| Commands.shell_execute(file.is_a?(String) ? WinHttp.wide(file) : file) },
    },
    'gdi32' => {
      'addfontresource' => (afr = proc { |path, *_| Win32.add_font(path) }),
      'addfontresourcea' => afr, 'addfontresourceex' => afr, 'addfontresourceexa' => afr,
      'addfontresourcew' => (afrw = proc { |path, *_| Win32.add_font(path.is_a?(String) ? WinHttp.wide(path).b : path) }),
      'addfontresourceexw' => afrw,
      'removefontresource' => afr, 'removefontresourcea' => afr,
    },
    # Registry functions return their error code directly (they don't use GetLastError).
    'advapi32' => {
      'regopenkeyex' => (rok = proc { |hkey, name, _opt = 0, _sam = 0, out = nil| Win32.reg_open(hkey, name, out) }),
      'regopenkeyexa' => rok,
      'regopenkey' => (ro = proc { |hkey, name, out = nil| Win32.reg_open(hkey, name, out) }), 'regopenkeya' => ro,
      'regopenkeyexw' => proc { |hkey, name, _opt = 0, _sam = 0, out = nil| Win32.reg_open(hkey, name.is_a?(String) ? WinHttp.wide(name).b : name, out) },
      'regopenkeyw' => proc { |hkey, name, out = nil| Win32.reg_open(hkey, name.is_a?(String) ? WinHttp.wide(name).b : name, out) },
      'regclosekey' => proc { |hkey| Win32.reg_close(hkey) },
      'regqueryvalueex' => (rqv = proc { |h, name, _r = 0, type = nil, data = nil, size = nil| Win32.reg_query(h, name, type, data, size) }),
      'regqueryvalueexa' => rqv,
      'regqueryvalueexw' => proc { |h, name, _r = 0, type = nil, data = nil, size = nil| Win32.reg_query(h, name, type, data, size, true) },
      'regenumkeyex' => (ren = proc { |h, *_| Win32.reg_enum(h) }), 'regenumkeyexa' => ren, 'regenumkeyexw' => ren,
      'regenumvalue' => ren, 'regenumvaluea' => ren, 'regenumvaluew' => ren,
    },
    'winhttp' => {
      'winhttpopen' => proc { |agent, *_| WinHttp.open_session(agent) },
      'winhttpconnect' => proc { |s, host, port, *_| WinHttp.connect(s, host, port) },
      'winhttpopenrequest' => proc { |c, verb, path, _ver = nil, _ref = nil, _acc = nil, flags = 0| WinHttp.open_request(c, verb, path, flags) },
      'winhttpaddrequestheaders' => proc { |h, headers, len, *_| WinHttp.add_headers(h, headers, len) },
      'winhttpsendrequest' => proc { |h, headers, len, opt = nil, opt_len = 0, *_| WinHttp.send_request(h, headers, len, opt, opt_len) },
      'winhttpwritedata' => proc { |h, data, len, written = nil| WinHttp.write(h, data, len, written) },
      'winhttpreceiveresponse' => proc { |h, *_| WinHttp.receive(h) },
      'winhttpqueryheaders' => proc { |h, level, _name, buf, len_buf, *_| WinHttp.query_headers(h, level, buf, len_buf) },
      'winhttpquerydataavailable' => proc { |h, out| WinHttp.available(h, out) },
      'winhttpreaddata' => proc { |h, buf, size, out = nil| WinHttp.read(h, buf, size, out) },
      'winhttpclosehandle' => proc { |h| WinHttp.close(h) },
      # Options and timeouts are accepted; the client uses its own fixed timeout (WinInet::TIMEOUT).
      'winhttpsetoption' => (wok = proc { |*_| WinHttp.ok }), 'winhttpsettimeouts' => wok,
    },
    'wininet' => {
      'internetopena' => proc { |*_| WinInet.new_handle(:session) },
      'internetopen' => proc { |*_| WinInet.new_handle(:session) },
      'internetconnecta' => proc { |*_| WinInet.new_handle(:connection) },
      'internetopenurl' => proc { |_session, url, *_| WinInet.open_url(url) },
      'internetopenurla' => proc { |_session, url, *_| WinInet.open_url(url) },
      'httpqueryinfo' => proc { |h, info, buf, len_buf, *_|
        res = WinInet.handle(h)
        next 0 unless res.is_a?(WinInet::Response)
        value = case info.to_i & 0xFFFF
                when 5 then res.body.bytesize.to_s                     # HTTP_QUERY_CONTENT_LENGTH
                when 19 then res.status.to_s                           # HTTP_QUERY_STATUS_CODE
                when 22 then res.headers.map { |k, v| "#{k}: #{v}" }.join("\r\n") # HTTP_QUERY_RAW_HEADERS_CRLF
                else nil
                end
        next 0 if value.nil?
        if buf.is_a?(String)
          bytes = value.b
          cap = buf.bytesize
          bytes = bytes.byteslice(0, [cap - 1, 0].max)
          buf.replace(bytes + "\0" + ("\0" * [cap - bytes.bytesize - 1, 0].max))
        end
        len_buf.replace([value.bytesize].pack('l')) if len_buf.is_a?(String) && len_buf.bytesize >= 4
        1
      },
      'internetreadfile' => proc { |h, buf, size, n_out|
        res = WinInet.handle(h)
        chunk = ''.b
        if res.is_a?(WinInet::Response)
          want = [size.to_i, buf.is_a?(String) ? buf.bytesize : size.to_i].min
          chunk = res.body.byteslice(res.pos, want) || ''.b
          res.pos += chunk.bytesize
        end
        if buf.is_a?(String) && !chunk.empty?
          rest = buf.byteslice(chunk.bytesize..-1) || ''.b
          buf.replace(chunk + rest.b)
        end
        n_out.replace([chunk.bytesize].pack(n_out.bytesize >= 8 ? 'q' : 'l')) if n_out.is_a?(String) && n_out.bytesize >= 4
        res.is_a?(WinInet::Response) ? 1 : 0
      },
      'internetclosehandle' => proc { |h| WinInet.close(h) ? 1 : 0 },
      'deleteurlcacheentry' => proc { |*_| 1 },
      'deleteurlcacheentrya' => proc { |*_| 1 },
    },
  }.freeze

  def self.win_function(dll, func)
    lib = dll.to_s.downcase.sub(/\.dll\z/, '')
    table = WIN_FUNCTIONS[lib] or return nil
    impl = table[func.to_s.downcase] or return nil
    WinFunction.new("#{lib}!#{func}", &impl)
  end
end

if defined?(MiniFFI)
  module AceCoreCompat
    module MiniFFINew
      def new(dll, func, *rest, &blk)
        super
      rescue RuntimeError => e
        raise unless e.message.start_with?('Failed loading', 'Failed to find')
        AceCoreCompat.win_function(dll, func) || AceCoreCompat::MissingFunction.bind(dll, func, e, caller_locations(1, 8)&.find { |l| l.path != __FILE__ })
      end
    end

    # A Windows function AceArcade doesn't implement.
    #
    # Games bind their Windows functions when a script loads (Win32API.new at module level), often many that are
    # used only by an optional feature (a debug map extractor, a mouse script, a font installer). On Windows the
    # binding always succeeds. Failing every such binding stopped games before their title screen even though the
    # function would never have been called; doing that game by game is what kept turning up "the next" missing
    # function.
    #
    # So the binding is decided by the game's own code:
    #   * guarded (the Win32API.new is inside "rescue", e.g. Win32API.new(...) rescue nil): it fails right away, the
    #     same as a missing DLL on Windows, so the game's fallback runs;
    #   * not guarded: the binding succeeds, and calling the function raises a clear error naming it. Nothing returns
    #     a made-up value; a game only stops if it really uses something Android can't do.
    # Either way the function is listed once in the engine log.
    module MissingFunction
      @asts = {}
      @sources = {}

      class Binding
        def initialize(dll, func, where)
          @dll, @func, @where = dll, func, where
        end

        def call(*_args)
          ($stderr.puts("AceArcade: Windows function #{@dll}!#{@func} called (bound at #{@where}): not available on Android") rescue nil)
          raise RuntimeError, "Windows function #{@dll}!#{@func} is not available on Android (called by the game; bound at #{@where})"
        end
        alias Call call
      end

      module_function

      def bind(dll, func, error, loc)
        AceCoreCompat::Win32.missing(dll, func)
        where = loc ? "#{loc.path}:#{loc.lineno}" : '?'
        if loc.nil? || guarded?(loc.path, loc.lineno)
          raise(error.class, "#{error.message} (Windows function #{dll}!#{func} is not available on Android)")
        end
        AceCoreCompat::Win32.note_once("#{dll}!#{func} bound at #{where} (unguarded): fails only if the game calls it")
        Binding.new(dll, func, where)
      end

      # The game's source for a backtrace path: "062:FileTests" (Scripts.rxdata, decoded by the engine) or a script
      # file the game's Main loads from a folder ("0240 Sprite_Resizer.rb").
      def source(path)
        return @sources[path] if @sources.key?(path)
        src = nil
        if (m = path.match(/\A(\d+):/)) && defined?($RGSS_SCRIPTS) && $RGSS_SCRIPTS.is_a?(Array)
          entry = $RGSS_SCRIPTS[m[1].to_i]
          src = entry[3] if entry.is_a?(Array) && entry[3].is_a?(String)
        end
        src ||= File.read(path) if File.file?(path)
        src ||= (Dir.glob("**/#{path.tr('[]{}*?', '')}").first&.then { |f| File.read(f) } rescue nil)
        @sources[path] = src && src.dup.force_encoding(Encoding::UTF_8)
      rescue StandardError
        @sources[path] = nil
      end

      # true when [line] is inside the protected part of a begin/rescue (or "x rescue y") in the game's code, or the
      # source can't be examined (then the call keeps the strict behaviour).
      def guarded?(path, line)
        src = source(path) or return true
        return true unless defined?(RubyVM::AbstractSyntaxTree)
        ast = (@asts[path] ||= RubyVM::AbstractSyntaxTree.parse(src))
        protected_line?(ast, line)
      rescue StandardError, SyntaxError
        true
      end

      def protected_line?(node, line)
        return false unless node.is_a?(RubyVM::AbstractSyntaxTree::Node)
        return false if node.first_lineno > line || node.last_lineno < line
        if node.type == :RESCUE
          body = node.children[0]
          return true if body.is_a?(RubyVM::AbstractSyntaxTree::Node) && body.first_lineno <= line && body.last_lineno >= line
        end
        node.children.any? { |c| protected_line?(c, line) }
      end
    end
  end
  MiniFFI.singleton_class.prepend(AceCoreCompat::MiniFFINew)
end
(AceCoreCompat::Win32.base_size rescue nil) # the engine's screen before any game resizes it

# Kernel#system / exec / ` (see AceCoreCompat::Commands): the game's own exe means "restart"; other programs keep
# Ruby's normal result for a program that can't be started (system -> nil, ` -> Errno::ENOENT) and are logged.
module Kernel
  alias_method :__acearcade_system, :system
  alias_method :__acearcade_exec, :exec
  alias_method :__acearcade_backtick, :`

  private

  def system(*args, **kw)
    cmd = AceCoreCompat::Commands.command(args)
    return true if AceCoreCompat::Commands.own_exe?(cmd) && AceCoreCompat::Commands.request_restart(cmd)
    AceCoreCompat::Commands.note(cmd)
    __acearcade_system(*args, **kw)
  end

  def exec(*args, **kw)
    cmd = AceCoreCompat::Commands.command(args)
    exit if AceCoreCompat::Commands.own_exe?(cmd) && AceCoreCompat::Commands.request_restart(cmd) # replaces this process
    AceCoreCompat::Commands.note(cmd)
    __acearcade_exec(*args, **kw)
  end

  def `(cmd)
    return '' if AceCoreCompat::Commands.own_exe?(cmd) && AceCoreCompat::Commands.request_restart(cmd)
    AceCoreCompat::Commands.note(cmd)
    __acearcade_backtick(cmd)
  end
end

#===============================================================================
# Display (☰ › Display › Screen fit / Game size)
#
# The Android surface stays full-screen; the engine lays the game out inside it:
#   Fit = aspect locked, Stretch = aspect unlocked, Pixel-perfect = whole-number scaling (nearest-neighbour),
#   Game size = Graphics.acearcade_area (share of the screen, centred; AceArcade's engine patch).
# This script applies the launch values (AceCoreCompat::FIT / SIZE / SMOOTH), applies changes the menu writes to
# DISPLAY_FILE, reports the game's resolution to "screen", and keeps Stretch in effect on engines without the
# aspect-ratio fix.
#===============================================================================
module AceCoreCompat
  module Display
    @mtime = nil
    @frame = 0
    @fit = defined?(AceCoreCompat::FIT) ? AceCoreCompat::FIT : nil
    @size = defined?(AceCoreCompat::SIZE) ? AceCoreCompat::SIZE : 100
    @reported = nil
    @applied = false

    def self.dir
      f = defined?(AceCoreCompat::DISPLAY_FILE) && AceCoreCompat::DISPLAY_FILE
      f && File.dirname(f)
    end

    def self.tick
      @frame += 1
      unless @applied
        @applied = true
        apply(@fit, @size) if @fit
      end
      return unless (@frame % 30).zero?
      report_resolution
      path = defined?(AceCoreCompat::DISPLAY_FILE) && AceCoreCompat::DISPLAY_FILE
      mtime = path && (File.mtime(path) rescue nil)
      if mtime && mtime != @mtime
        @mtime = mtime
        text = File.read(path)
        fit = text[/"screenFit"\s*:\s*"(\w+)"/, 1] || @fit
        size = (text[/"screenSize"\s*:\s*"(\d+)"/, 1] || @size).to_i
        apply(fit, size) if fit
      end
      if @fit == 'fill' && Graphics.respond_to?(:fixed_aspect_ratio) && Graphics.fixed_aspect_ratio
        Graphics.fixed_aspect_ratio = false
      end
    rescue StandardError
      nil
    end

    def self.report_resolution
      d = dir or return
      res = "#{Graphics.width} #{Graphics.height}"
      return if res == @reported
      @reported = res
      File.write(File.join(d, 'screen'), res + "\n")
    end

    def self.apply(fit, size = @size)
      @fit = fit
      @size = size.to_i.clamp(50, 100)
      integer = fit == 'integer'
      # Sharp scaling in every mode: a whole-number nearest-neighbour step, then (Fit/Stretch) a smoothed last step.
      Graphics.last_mile_scaling = !integer if Graphics.respond_to?(:last_mile_scaling=)
      Graphics.fixed_aspect_ratio = (fit != 'fill') if Graphics.respond_to?(:fixed_aspect_ratio=)
      Graphics.integer_scaling = true if Graphics.respond_to?(:integer_scaling=)
      if Graphics.respond_to?(:smooth_scaling=)
        smooth = defined?(AceCoreCompat::SMOOTH) ? AceCoreCompat::SMOOTH : true
        Graphics.smooth_scaling = integer ? false : smooth
      end
      Graphics.acearcade_area = @size / 100.0 if Graphics.respond_to?(:acearcade_area=)
    end
  end
end

#===============================================================================
# Controls test (☰ › Controls › Test controls)
#
# While AceArcade's test panel is open it creates "input_test" next to DISPLAY_FILE; this then writes the RGSS buttons
# the engine currently reports as pressed to "input_state", so every on-screen and controller button can be checked
# end to end (touch → key → engine binding → what the game reads).
#===============================================================================
module AceCoreCompat
  module InputTest
    BUTTONS = { 'UP' => :UP, 'DOWN' => :DOWN, 'LEFT' => :LEFT, 'RIGHT' => :RIGHT, 'A' => :A, 'B' => :B, 'C' => :C,
                'X' => :X, 'Y' => :Y, 'Z' => :Z, 'L' => :L, 'R' => :R }.freeze
    @frame = 0

    def self.tick
      @frame += 1
      return unless (@frame % 3).zero?
      dir = defined?(AceCoreCompat::DISPLAY_FILE) && AceCoreCompat::DISPLAY_FILE && File.dirname(AceCoreCompat::DISPLAY_FILE)
      return unless dir && File.exist?(File.join(dir, 'input_test'))
      pressed = BUTTONS.select { |_, c| Input.const_defined?(c) && Input.press?(Input.const_get(c)) }.keys
      dir4 = (Input.dir4 rescue 0)
      File.write(File.join(dir, 'input_state'), "#{pressed.join(' ')}\ndir4=#{dir4}\n")
    rescue StandardError
      nil
    end
  end
end

#===============================================================================
# Diagnostics: launch timeline, scene log and heartbeat (files next to DISPLAY_FILE)
#
#   timeline   "<CLOCK_BOOTTIME ms>\t<event>": engine ready, first data file, first frame, first map, first scenes
#              (same clock as the launcher, so the whole launch can be measured end to end)
#   scenes     scene and map changes with times, kept short (what the game was doing before a black screen)
#   heartbeat  "<frame> <scene> <map>" about once a second; if it stops changing the game loop is not running
#              (frozen, or busy loading), if it keeps changing the game is running even when the screen is black.
#===============================================================================
module AceCoreCompat
  module Diag
    @frame = 0
    @scene = nil
    @map = nil
    @scene_lines = 0
    @marks = {}

    def self.dir
      f = defined?(AceCoreCompat::DISPLAY_FILE) && AceCoreCompat::DISPLAY_FILE
      f && File.dirname(f)
    end

    def self.now
      Process.clock_gettime(Process::CLOCK_BOOTTIME, :millisecond)
    rescue StandardError
      Process.clock_gettime(Process::CLOCK_MONOTONIC, :millisecond)
    end

    def self.append(name, text)
      d = dir or return
      File.open(File.join(d, name), 'a') { |f| f.write(text) }
    rescue StandardError
      nil
    end

    # Records an event once per launch (key) on the shared timeline.
    def self.mark(key, event = key)
      return if @marks[key]
      @marks[key] = true
      append('timeline', "#{now}\t#{event}\n")
    end

    def self.scene_name
      sc = if defined?(SceneManager) && SceneManager.respond_to?(:scene) then SceneManager.scene else $scene end
      sc && sc.class.name
    rescue StandardError
      nil
    end

    def self.tick
      @frame += 1
      mark('first_frame', 'First frame drawn') if @frame == 1
      sc = scene_name
      map = (defined?($game_map) && $game_map && $game_map.respond_to?(:map_id)) ? $game_map.map_id : nil
      if sc != @scene || map != @map
        if @scene_lines < 300
          append('scenes', "#{now}\tscene=#{sc || '-'} map=#{map || '-'} frame=#{@frame}\n")
          @scene_lines += 1
        end
        mark("scene_#{sc}", "Scene: #{sc}") if sc && @marks.size < 30
        mark('first_map', "First map shown (map #{map})") if map && map > 0 && sc.to_s =~ /Map/
        @scene = sc
        @map = map
      end
      if (@frame % 60).zero?
        d = dir
        File.write(File.join(d, 'heartbeat'), "#{@frame} #{sc || '-'} #{map || '-'} #{now}\n") if d
      end
    rescue StandardError
      nil
    end
  end
end

AceCoreCompat::Diag.mark('ruby', 'Engine ready, Ruby running (before the game\'s scripts)')

module Kernel
  if (private_method_defined?(:load_data) || method_defined?(:load_data)) &&
     !(private_method_defined?(:__acearcade_load_data) || method_defined?(:__acearcade_load_data))
    alias_method :__acearcade_load_data, :load_data
    def load_data(*args)
      name = args[0].to_s
      AceCoreCompat::Diag.mark('first_data', "Game scripts running (first data file: #{name})")
      AceCoreCompat::Diag.mark('plugins', 'Loading plugins (PluginScripts)') if name =~ /PluginScripts/i
      AceCoreCompat::Diag.mark('first_mapdata', "Loading first map data (#{File.basename(name)})") if name =~ /Map\d+/i
      __acearcade_load_data(*args)
    end
    private :load_data, :__acearcade_load_data
  end
end

at_exit do
  e = $!
  if e && !e.is_a?(SystemExit)
    AceCoreCompat::Diag.append('scenes', "#{AceCoreCompat::Diag.now}\texception=#{e.class}: #{e.message.to_s[0, 300]} at #{(e.backtrace || []).first}\n")
  end
end

#===============================================================================
# Gameplay tools (☰ › Gameplay, and the FF / QS / QL buttons)
#
# Fast-forward
#   AceArcade writes "speed" (e.g. "speed=3\naudio=quiet") into the helper folder. While the speed is above 1:
#   * frame-based game logic (RPG Maker's frame counts, waits, animations) runs [speed] times per drawn frame:
#     Graphics.update only draws one of every [speed] calls and counts the others (Graphics.frame_count);
#   * time-based logic (Essentials v21: System.uptime, Graphics.delta) follows a game clock running [speed] times
#     faster (AceCoreCompat::Timing).
#   Audio "quiet" skips sound effects (Audio.se_play / me_play) while fast-forwarding; music keeps playing.
#
# Quick save / quick load (one slot per game, kept in AceArcade's own folder, never the game's save files)
#   Uses the game's own save routines, so it is a save made at a safe moment, not a snapshot of the whole running
#   program: it runs on the map when no event, message or movement is in progress.
#     Essentials v19+  SaveData.compile_save_hash / SaveData.load_all_values + Game.load_map
#     VX Ace           DataManager.make_save_contents / extract_save_contents
#     VX               Scene_File#write_save_data / read_save_data
#     XP               Scene_Save#write_save_data / Scene_Load#read_save_data
#   The slot is one file: a header (format, engine, game fingerprint, time, CRC32) and the saved data. It is written
#   to a temporary file and renamed over the previous slot, so a failed save keeps the previous quick save.
#   Requests come through "tools_cmd" ("<id>\t<quicksave|quickload>"); results go to "tools_result"
#   ("<id>\t<ok|error>\t<message>").
#===============================================================================
module AceCoreCompat
  module Tools
    FORMAT = 1
    MAGIC = "AceArcadeQuickSave"

    module_function

    def dir
      return @dir if defined?(@dir)
      @dir = defined?(AceCoreCompat::DISPLAY_FILE) ? File.dirname(AceCoreCompat::DISPLAY_FILE) : nil
    end

    def slot_path
      dir && File.join(dir, "quicksave.aas")
    end

    # ---- polling (from Graphics.update; one stat per file every few calls)
    def poll
      return unless dir
      @polls = (@polls || 0) + 1
      return unless @polls % 6 == 0
      check_speed
      check_command
      install_scene_hook
      expire_pending
    rescue StandardError
      nil
    end

    # Essentials runs messages, menus and battles in their own loops, so Scene_Map#update (where requests run) isn't
    # called then. Answer such requests after a moment instead of leaving them unanswered.
    def expire_pending
      return unless @pending
      @pending_at ||= Time.now
      waited = Time.now - @pending_at
      # A game script may have replaced Scene_Map#update after the hook was installed: install it again.
      if waited > 1.5 && map_scene? && !busy? && (@hook_seen.nil? || Time.now - @hook_seen > 1.5)
        @hooked = false
        install_scene_hook
      end
      return if waited < 4
      id, = @pending
      @pending = nil
      reply(id, "error", "Not now: the game is in a message, menu or event. Quick save and load work while you can walk on the map.")
    end

    def check_speed
      path = File.join(dir, "speed")
      mtime = File.exist?(path) ? File.mtime(path) : nil
      return if mtime == @speed_mtime
      @speed_mtime = mtime
      values = {}
      File.read(path).each_line { |l| k, v = l.strip.split('=', 2); values[k] = v if k && v } if mtime
      AceCoreCompat::Timing.speed = (values['speed'] || 1).to_f
      @quiet = values['audio'] == 'quiet'
      @acc = 0.0
    end

    def fast?
      AceCoreCompat::Timing.speed > 1.0
    end

    def quiet?
      @quiet && fast? ? true : false
    end

    # true: this Graphics.update call only advances the frame counter (fast-forward); false: draw it.
    def skip_frame?
      return false unless fast?
      @acc = (@acc || 0.0) + 1.0
      if @acc >= AceCoreCompat::Timing.speed
        @acc -= AceCoreCompat::Timing.speed
        false
      else
        true
      end
    end

    # ---- quick save / load requests
    def check_command
      path = File.join(dir, "tools_cmd")
      return unless File.exist?(path)
      text = File.read(path)
      File.delete(path) rescue nil
      id, action = text.strip.split("\t", 2)
      return unless id && %w[quicksave quickload].include?(action)
      unless map_scene?
        return reply(id, "error", "Quick save and quick load work on the map. Close menus or finish the battle first.")
      end
      @pending = [id, action]
      @pending_since = nil
      @pending_at = Time.now
    end

    def reply(id, status, message)
      tmp = File.join(dir, "tools_result.tmp")
      File.open(tmp, "wb") { |f| f.write("#{id}\t#{status}\t#{message}") }
      File.rename(tmp, File.join(dir, "tools_result"))
    rescue StandardError
      nil
    end

    def map_scene?
      return false unless defined?(::Scene_Map)
      if defined?(::SceneManager) && ::SceneManager.respond_to?(:scene)
        ::SceneManager.scene.is_a?(::Scene_Map)
      else
        $scene.is_a?(::Scene_Map)
      end
    end

    def install_scene_hook
      return if @hooked || !defined?(::Scene_Map) || !::Scene_Map.method_defined?(:update)
      @hook_gen = (@hook_gen || 0) + 1
      original = :"__acearcade_map_update_#{@hook_gen}"
      ::Scene_Map.send(:alias_method, original, :update)
      ::Scene_Map.send(:define_method, :update) do |*args|
        AceCoreCompat::Tools.hook_seen!
        next if AceCoreCompat::Tools.run_pending(self)
        send(original, *args)
      end
      @hooked = true
    end

    def hook_seen!
      @hook_seen = Time.now
    end

    def hooked?
      @hooked ? true : false
    end

    def pending
      @pending
    end

    # Runs a pending request at the start of Scene_Map#update. Returns true when the scene changed (load).
    def run_pending(scene = nil)
      return false unless @pending
      id, action = @pending
      if busy?
        @pending_since ||= Time.now
        return false if Time.now - @pending_since < 3     # give a step or message a moment to finish
        @pending = nil
        reply(id, "error", "The game is busy (an event, message or movement is running). Try again in a moment.")
        return false
      end
      @pending = nil
      if action == "quicksave"
        replaced = File.exist?(slot_path)
        save!
        reply(id, "ok", replaced ? "Quick save replaced" : "Quick save created")
        false
      else
        unless File.exist?(slot_path)
          reply(id, "error", "No quick save for this game yet.")
          return false
        end
        load!(scene)
        reply(id, "ok", "Quick save loaded")
        true
      end
    rescue StandardError => e
      reply(id, "error", "#{action == 'quicksave' ? 'Quick save' : 'Quick load'} failed: #{e.message.to_s[0, 160]}")
      false
    end

    def flavor
      if defined?(::SaveData) && ::SaveData.respond_to?(:compile_save_hash) && defined?(::Game) && ::Game.respond_to?(:load_map)
        :essentials
      elsif defined?(::DataManager) && ::DataManager.respond_to?(:make_save_contents)
        :ace
      elsif defined?(::Scene_File) && ::Scene_File.method_defined?(:write_save_data)
        :vx
      elsif defined?(::Scene_Save) && ::Scene_Save.method_defined?(:write_save_data)
        :xp
      end
    end

    def busy?
      return true if $game_player.respond_to?(:moving?) && $game_player.moving?
      return true if $game_temp.respond_to?(:player_transferring) && $game_temp.player_transferring
      case flavor
      when :essentials
        (respond_to?(:pbMapInterpreterRunning?, true) && pbMapInterpreterRunning?) || $game_temp.message_window_showing ? true : false
      when :ace
        $game_map.interpreter.running? || $game_message.busy?
      when :vx
        $game_map.interpreter.running? || $game_message.visible ? true : false
      when :xp
        $game_system.map_interpreter.running? || $game_temp.message_window_showing ? true : false
      else
        true
      end
    end

    # Identifies the game build (its scripts file) so a quick save is never loaded into a different game version.
    def fingerprint
      file = %w[Data/Scripts.rxdata Data/Scripts.rvdata Data/Scripts.rvdata2].find { |f| File.exist?(f) }
      file ? "#{File.basename(file)}:#{File.size(file)}" : "unknown"
    end

    def save!
      kind = flavor or raise "this game's save system isn't recognised"
      payload = case kind
                when :essentials then Marshal.dump(::SaveData.compile_save_hash)
                when :ace then Marshal.dump([::DataManager.make_save_header, ::DataManager.make_save_contents])
                else
                  body = File.join(dir, "quicksave.body.tmp")
                  begin
                    File.open(body, "wb") do |f|
                      kind == :vx ? ::Scene_File.allocate.write_save_data(f) : ::Scene_Save.allocate.write_save_data(f)
                    end
                    File.binread(body)
                  ensure
                    File.delete(body) if File.exist?(body)
                  end
                end
      header = { "format" => FORMAT, "engine" => kind, "game" => fingerprint, "time" => Time.now.to_i,
                 "crc" => Zlib.crc32(payload), "size" => payload.bytesize }
      tmp = slot_path + ".tmp"
      begin
        File.open(tmp, "wb") do |f|
          f.write(MAGIC + "\n")
          header.each { |k, v| f.write("#{k}=#{v}\n") }
          f.write("\n")
          f.write(payload)
        end
        File.rename(tmp, slot_path)   # atomic: the previous quick save stays until this succeeds
      ensure
        File.delete(tmp) if File.exist?(tmp)
      end
    end

    def read_slot
      data = File.binread(slot_path)
      head, payload = data.split("\n\n", 2)
      lines = head.to_s.split("\n")
      raise "the quick save file is damaged" unless lines.shift == MAGIC && payload
      header = {}
      lines.each { |l| k, v = l.split('=', 2); header[k] = v }
      raise "the quick save is from a newer AceArcade" if header["format"].to_i > FORMAT
      raise "the quick save is damaged (checksum)" unless header["crc"].to_i == Zlib.crc32(payload) && header["size"].to_i == payload.bytesize
      raise "the quick save was made with a different version of this game" unless header["game"] == fingerprint
      raise "the quick save belongs to a different kind of game" unless header["engine"] == flavor.to_s
      payload
    end

    def load!(scene = nil)
      payload = read_slot
      case flavor
      when :essentials
        data = Marshal.load(payload)
        # Reload inside the running map scene (as Essentials does after a map transfer): its sprites are disposed
        # while it is still $scene (Spriteset_Map#dispose uses $scene.map_renderer), then rebuilt for the loaded state.
        scene ||= $scene
        rebuild = scene.respond_to?(:disposeSpritesets) && scene.respond_to?(:createSpritesets)
        if rebuild
          scene.disposeSpritesets
          global = scene.instance_variable_get(:@spritesetGlobal)
          global.dispose if global.respond_to?(:dispose)
          scene.instance_variable_set(:@spritesetGlobal, nil)
          # The tilemap renderer keeps the old map's scroll position; a fresh one is made by createSpritesets (as when
          # a game is loaded from the title screen). Without this the map is drawn offset from the player.
          renderer = scene.instance_variable_get(:@map_renderer)
          renderer.dispose if renderer.respond_to?(:dispose) && !(renderer.respond_to?(:disposed?) && renderer.disposed?)
          scene.instance_variable_set(:@map_renderer, nil)
        end
        # SaveData.load_all_values skips values that are already loaded, which in a running game is all of them.
        # Mark them unloaded first, as Essentials' title screen does (start-up values such as options stay as they are).
        if ::SaveData.respond_to?(:mark_values_as_unloaded)
          ::SaveData.mark_values_as_unloaded
          ::SaveData.load_all_values(data)
        elsif ::SaveData.respond_to?(:load_values)
          ::SaveData.load_values(data)
        else
          ::SaveData.load_all_values(data)
        end
        # Followers are cached in $game_temp and rebuilt from the loaded $PokemonGlobal on next use.
        $game_temp.instance_variable_set(:@followers, nil) if $game_temp.instance_variable_defined?(:@followers)
        $game_temp.last_uptime_refreshed_play_time = System.uptime if $game_temp.respond_to?(:last_uptime_refreshed_play_time=)
        ::Game.load_map
        pbAutoplayOnSave if respond_to?(:pbAutoplayOnSave, true)
        $game_map.update
        $PokemonMap.updateMap if $PokemonMap.respond_to?(:updateMap)
        if rebuild
          scene.createSpritesets
        else
          $scene = ::Scene_Map.new
        end
      when :ace
        _header, contents = Marshal.load(payload)
        ::DataManager.extract_save_contents(contents)
        ::DataManager.reload_map_if_updated if ::DataManager.respond_to?(:reload_map_if_updated)
        $game_system.on_after_load if $game_system.respond_to?(:on_after_load)
        ::SceneManager.goto(::Scene_Map)
      else
        body = File.join(dir, "quicksave.body.tmp")
        File.binwrite(body, payload)
        begin
          File.open(body, "rb") do |f|
            if flavor == :vx
              loader = ::Scene_File.allocate
              loader.read_save_data(f)
              $scene = ::Scene_Map.new
              loader.instance_variable_get(:@last_bgm)&.play
              loader.instance_variable_get(:@last_bgs)&.play
            else
              ::Scene_Load.allocate.read_save_data(f)
              $game_system.bgm_play($game_system.playing_bgm)
              $game_system.bgs_play($game_system.playing_bgs)
              $game_map.update
              $scene = ::Scene_Map.new
            end
          end
        ensure
          File.delete(body) if File.exist?(body)
        end
      end
    end
  end
end

if defined?(Audio)
  class << Audio
    %i[se_play me_play].each do |m|
      next unless method_defined?(m) && !method_defined?(:"__acearcade_#{m}")
      alias_method :"__acearcade_#{m}", m
      define_method(m) { |*args| AceCoreCompat::Tools.quiet? ? nil : send(:"__acearcade_#{m}", *args) }
    end
  end
end

if defined?(Graphics) && Graphics.respond_to?(:update) && !Graphics.respond_to?(:__acearcade_update)
  class << Graphics
    alias_method :__acearcade_update, :update
    def update(*args)
      AceCoreCompat::Tools.poll
      skip = AceCoreCompat::Tools.skip_frame?
      AceCoreCompat::Timing.frame_tick(!skip) if AceCoreCompat::Tools.fast?
      if skip
        # Fast-forward: advance the game's frame without drawing it.
        self.frame_count += 1 if respond_to?(:frame_count=)
        return nil
      end
      AceCoreCompat::Display.tick
      AceCoreCompat::InputTest.tick
      AceCoreCompat::Diag.tick
      __acearcade_update(*args)
    end
  end
end

#===============================================================================
# Ruby 1.8 syntax in RPG Maker XP games
#
# RGSS1 (RPG Maker XP) ran Ruby 1.8, which accepted "when 0: ..." (a colon instead of "then"); Ruby 1.9 removed it,
# so older games stop with a SyntaxError before they start (Pokémon Z, 040:AudioUtilities:919). mkxp-z decodes the
# game's scripts into $RGSS_SCRIPTS before this file runs and evaluates them afterwards, so a script that does not
# compile is adapted in memory here: the colon after a "when" condition becomes "then". The change is kept only if
# the whole script then compiles; otherwise the original runs and reports its own error. Game files are not touched.
#===============================================================================
module AceCoreCompat
  class Ruby18Retry < Exception; end

  module Ruby18Syntax
    # "when <condition>:" where the colon ends the condition (not "::", not a symbol, not "a ? b : c").
    WHEN_COLON = /^(\s*when\s+[^#\n]*?[^\s:?])\s*:(?!:)(?=\s|$)/
    module_function

    def compiles?(src, name)
      RubyVM::InstructionSequence.compile(src, name)
      true
    rescue SyntaxError
      false
    end

    def compile_error(src, name)
      RubyVM::InstructionSequence.compile(src, name)
      nil
    rescue SyntaxError => e
      e
    end

    # Returns the adapted source, or nil when the script needs no change or can't be adapted safely.
    def adapt(src, name)
      return nil unless src.is_a?(String) && src.match?(WHEN_COLON)
      return nil if compiles?(src, name)
      fixed = src.gsub(WHEN_COLON) { "#{$1} then" }
      compiles?(fixed, name) ? fixed : nil
    end

    # ---- "retry" inside a block or for loop (Ruby 1.8: restarts that iterator call from the beginning, receiver and
    # arguments re-evaluated; since 1.9 retry is only allowed in rescue, so the script doesn't compile: "Invalid
    # retry"). The same behaviour in current Ruby: the iterator call is wrapped in begin/rescue/retry and the retry
    # becomes a raise of a private exception (not a StandardError, so the game's own "rescue" clauses can't catch it).
    # Everything is inserted on the existing lines, so line numbers in error messages stay the game's own.
    RETRY_RAISE = 'raise(::AceCoreCompat::Ruby18Retry)'
    RETRY_ONLY = /(?<![\w.:$@])retry(?![\w?!])/

    def adapt_retry(src, name)
      return nil unless src.is_a?(String) && src.include?('retry') && defined?(RubyVM::AbstractSyntaxTree)
      marked_lines = []
      # 1. Each line the compiler rejects: its retry becomes the raise (reported one line at a time).
      200.times do
        err = compile_error(src, name) or break
        line_no = err.message[/:(\d+): Invalid retry/, 1]&.to_i or return nil
        lines = src.lines
        code, comment = (lines[line_no - 1] or return nil).split('#', 2)
        return nil unless code.match?(RETRY_ONLY)
        lines[line_no - 1] = code.gsub(RETRY_ONLY, RETRY_RAISE) + (comment ? "##{comment}" : '')
        src = lines.join
        marked_lines << line_no
      end
      return nil if marked_lines.empty? || !compiles?(src, name)
      # 2. Wrap the innermost iterator (block call or for loop) around each of those lines.
      ast = RubyVM::AbstractSyntaxTree.parse(src)
      nodes = marked_lines.map { |l| innermost_iterator(ast, l) or return nil }.uniq { |n| [n.first_lineno, n.first_column, n.last_lineno, n.last_column] }
      fixed = wrap(src.lines, nodes)
      compiles?(fixed, name) ? [fixed, marked_lines.size] : nil
    rescue SyntaxError, StandardError
      nil
    end

    def innermost_iterator(node, line_no, best = nil)
      return best unless node.is_a?(RubyVM::AbstractSyntaxTree::Node)
      if %i[ITER FOR].include?(node.type) && node.first_lineno <= line_no && node.last_lineno >= line_no
        best = node
      end
      node.children.each { |c| best = innermost_iterator(c, line_no, best) if c.is_a?(RubyVM::AbstractSyntaxTree::Node) }
      best
    end

    # Inserts "begin;" before and ";rescue ...;retry;end" after each node, working from the end of the text back so
    # every position still refers to the original source.
    def wrap(lines, nodes)
      lines = lines.map(&:b)
      inserts = nodes.flat_map do |n|
        [[n.first_lineno - 1, n.first_column, 'begin;'], [n.last_lineno - 1, n.last_column, ';rescue ::AceCoreCompat::Ruby18Retry;retry;end']]
      end
      inserts.sort_by { |l, c, _| [-l, -c] }.each do |l, c, text|
        line = lines[l]
        lines[l] = line.byteslice(0, c) + text.b + line.byteslice(c..)
      end
      lines.join.force_encoding(Encoding::UTF_8)
    end

    # ---- Byte escapes above \x7F in a regexp ("[\x7f-\x9f]"): Ruby 1.8 regexps worked on bytes; since 1.9 a UTF-8
    # regexp can't contain a lone byte ("invalid multibyte escape"). Such ranges name Latin-1 characters (control
    # characters 0x80-0x9F, accented letters 0xA0-0xFF), so the escape becomes that character's code point (\u0080).
    BYTE_ESCAPE = /\\x([89a-fA-F][0-9a-fA-F])/

    def adapt_regexp_bytes(src, name)
      return nil unless src.is_a?(String) && src.match?(BYTE_ESCAPE)
      count = 0
      50.times do
        err = compile_error(src, name)
        return(count > 0 ? [src, count] : nil) if err.nil?
        line_no = err.message[/:(\d+): invalid multibyte escape/, 1]&.to_i or return nil
        lines = src.lines
        line = lines[line_no - 1] or return nil
        re = err.message[/invalid multibyte escape: (\/.*\/)/, 1]
        fixed = if re && line.include?(re)
                  line.sub(re) { |r| r.gsub(BYTE_ESCAPE) { "\\u00#{$1.downcase}" } }
                else
                  line.gsub(BYTE_ESCAPE) { "\\u00#{$1.downcase}" }
                end
        return nil if fixed == line
        lines[line_no - 1] = fixed
        src = lines.join
        count += 1
      end
      nil
    rescue StandardError
      nil
    end

    def apply(scripts)
      return unless scripts.is_a?(Array) && defined?(RubyVM::InstructionSequence)
      scripts.each_with_index do |entry, i|
        next unless entry.is_a?(Array) && entry[3].is_a?(String)
        name = format('%03d:%s', i, entry[1].to_s)
        if (fixed = adapt(entry[3], name))
          n = entry[3].scan(WHEN_COLON).size
          entry[3] = fixed
          ($stderr.puts("AceArcade: Ruby 1.8 syntax adapted in #{name} (#{n} \"when ...:\")") rescue nil)
        end
        if (r = adapt_regexp_bytes(entry[3], name))
          entry[3] = r[0]
          ($stderr.puts("AceArcade: Ruby 1.8 syntax adapted in #{name} (#{r[1]} byte escapes in a regexp)") rescue nil)
        end
        if (r = adapt_retry(entry[3], name))
          entry[3] = r[0]
          ($stderr.puts("AceArcade: Ruby 1.8 syntax adapted in #{name} (#{r[1]} \"retry\" in an iterator)") rescue nil)
        end
      end
    rescue StandardError => e
      ($stderr.puts("AceArcade: Ruby 1.8 syntax check skipped: #{e.class}: #{e.message}") rescue nil)
    end
  end
end
AceCoreCompat::Ruby18Syntax.apply($RGSS_SCRIPTS) if defined?($RGSS_SCRIPTS) && $RGSS_SCRIPTS

#===============================================================================
# Thread.critical (Ruby 1.8; removed in 1.9)
#
# In Ruby 1.8, "Thread.critical = true" stopped the scheduler from switching to another Ruby thread until it was
# set back to false; code used it as a global lock around shared data (Ruby 1.8's weakref.rb, copied into Pokémon
# Essentials' BitmapCache: old = Thread.critical; Thread.critical = true; ...; ensure Thread.critical = old).
# The same guarantee here is one process-wide lock: setting it true acquires the lock (a thread that already holds
# it keeps it; nesting doesn't count, as in 1.8), setting it false releases it. Threads that don't use
# Thread.critical were never stopped by it in a way games relied on beyond this mutual exclusion, and Ruby 3's own
# interpreter lock already keeps single Ruby operations atomic.
#===============================================================================
unless Thread.respond_to?(:critical)
  module AceCoreCompat
    module ThreadCritical
      @lock = Thread::Mutex.new
      @owner = nil
      class << self
        def held? = @owner.equal?(Thread.current)

        def set(on)
          if on
            unless held?
              @lock.lock
              @owner = Thread.current
            end
          elsif held?
            @owner = nil
            @lock.unlock
          end
          on
        end
      end
    end
  end
  class Thread
    def self.critical = AceCoreCompat::ThreadCritical.held?
    def self.critical=(value)
      AceCoreCompat::ThreadCritical.set(value ? true : false)
    end
  end
end

#===============================================================================
# Ruby 1.8 byte reading in Pokémon Essentials data files
#
# Essentials (up to about v16) reads its binary data (dexdata.dat, ...) through its own StringInput class, whose
# each_byte yields getc, and getc returns @string[@pos]. In Ruby 1.8 that was the byte as an Integer; since Ruby 1.9
# it is a one-character String, so fgetb/fgetw (built on each_byte) return Strings and the game stops with
# "comparison of Integer with String failed" (Pokémon Xenoverse, PokeBattle_Pokemon.isFemale, loading a save).
# IO#each_byte yields Integers in every Ruby version, so when a game defines StringInput, its each_byte is made to
# yield Integers too (a value that already is an Integer is passed through unchanged).
#===============================================================================
module AceCoreCompat
  module StringInputBytes
    def each_byte(*args)
      return enum_for(:each_byte, *args) unless block_given?
      super(*args) { |b| yield(b.is_a?(String) ? b.getbyte(0) : b) }
    end
  end

  # For readers whose fgetb returns getc directly.
  module StringInputFgetb
    def fgetb(*args)
      b = super
      b.is_a?(String) ? b.getbyte(0) : b
    end
  end

  module Ruby18Bytes
    @done = {}
    def self.patch(klass)
      return if @done[klass] || !klass.method_defined?(:each_byte)
      @done[klass] = true
      klass.prepend(StringInputBytes)
      klass.prepend(StringInputFgetb) if klass.method_defined?(:fgetb)
      ($stderr.puts("AceArcade: #{klass} reads bytes as Integers (Ruby 1.8 data readers)") rescue nil)
    end
  end
end
begin
  AceCoreCompat::Ruby18Bytes.instance_variable_set(:@trace, TracePoint.new(:end) do |tp|
    mod = tp.self
    AceCoreCompat::Ruby18Bytes.patch(mod) if mod.is_a?(Class) && mod.name == 'StringInput'
  end.tap(&:enable))
rescue StandardError => e
  ($stderr.puts("AceArcade: byte-reader check unavailable: #{e.message}") rescue nil)
end
