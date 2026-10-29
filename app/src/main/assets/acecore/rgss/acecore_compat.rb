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

begin
  require 'zlib'
rescue LoadError => e
  ($stderr.puts("AceArcade: Ruby zlib extension unavailable: #{e.message}") rescue nil)
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
  end
end

if defined?(System) && System.respond_to?(:uptime) && System.uptime.is_a?(Integer)
  class << System
    alias_method :__acearcade_uptime_us, :uptime
    def uptime; __acearcade_uptime_us / AceCoreCompat::Timing::US_PER_S; end
    if method_defined?(:delta)
      alias_method :__acearcade_delta_us, :delta
      def delta; AceCoreCompat::Timing.convert(__acearcade_delta_us); end
    end
  end
end
if defined?(Graphics) && Graphics.respond_to?(:delta) && !Graphics.respond_to?(:__acearcade_delta_us)
  class << Graphics
    alias_method :__acearcade_delta_us, :delta
    def delta; AceCoreCompat::Timing.convert(__acearcade_delta_us); end
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
# they do on Windows; any other Windows call still fails with a clear message (nothing is faked).
#
#   kernel32  SetPriorityClass                     -> success (Android manages process priority itself)
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

      def request(scheme, host, port, path)
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
        req = "GET #{path} HTTP/1.1\r\nHost: #{host}\r\nUser-Agent: Mozilla/5.0 (Android) AceArcade\r\n" \
              "Accept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
        write_all(io, req)
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

  WIN_FUNCTIONS = {
    'kernel32' => {
      'setpriorityclass' => proc { |*_| 1 },
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
        AceCoreCompat.win_function(dll, func) or
          raise(e.class, "#{e.message} (Windows function #{dll}!#{func} is not available on Android)")
      end
    end
  end
  MiniFFI.singleton_class.prepend(AceCoreCompat::MiniFFINew)
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
      Graphics.last_mile_scaling = !integer if Graphics.respond_to?(:last_mile_scaling=)
      Graphics.fixed_aspect_ratio = (fit != 'fill') if Graphics.respond_to?(:fixed_aspect_ratio=)
      Graphics.integer_scaling = integer if Graphics.respond_to?(:integer_scaling=)
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

if defined?(Graphics) && Graphics.respond_to?(:update) && !Graphics.respond_to?(:__acearcade_update)
  class << Graphics
    alias_method :__acearcade_update, :update
    def update(*args)
      AceCoreCompat::Display.tick
      AceCoreCompat::InputTest.tick
      AceCoreCompat::Diag.tick
      __acearcade_update(*args)
    end
  end
end
