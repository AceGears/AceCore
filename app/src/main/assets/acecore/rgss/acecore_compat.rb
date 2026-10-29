# AceArcade engine compatibility layer for mkxp-z-android-reworked (loaded through mkxp-z's "preloadScript",
# before the game's own scripts, also when the game helper is off).
#
# The Android port of mkxp-z is older than the mkxp-z that PC games ship with (e.g. Pokémon Essentials v21.1).
# This file makes the Android engine match upstream mkxp-z where the port is behind, so the same game scripts run:
#   * loads Ruby's zlib extension at start-up, as upstream mkxp-z does (Essentials' PluginManager needs Zlib);
#   * adds the upstream mkxp-z functions the port lacks, each only if the engine doesn't define it already.
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
# Live display changes from AceArcade's in-game menu (☰ › Display › Screen fit)
#
# The menu writes AceCoreCompat::DISPLAY_FILE ({"screenFit":"fit|fill|integer", ...}); this checks it about twice a
# second and switches the engine's own scaling. (The game-size setting is applied by Android's layout instead.)
#===============================================================================
module AceCoreCompat
  module Display
    @mtime = nil
    @frame = 0

    def self.tick
      @frame += 1
      return unless (@frame % 30).zero?
      path = defined?(AceCoreCompat::DISPLAY_FILE) && AceCoreCompat::DISPLAY_FILE
      return unless path
      mtime = (File.mtime(path) rescue nil)
      return if mtime.nil? || mtime == @mtime
      @mtime = mtime
      fit = File.read(path)[/"screenFit"\s*:\s*"(\w+)"/, 1]
      apply(fit) if fit
    rescue StandardError
      nil
    end

    def self.apply(fit)
      Graphics.fixed_aspect_ratio = (fit != 'fill') if Graphics.respond_to?(:fixed_aspect_ratio=)
      Graphics.integer_scaling = (fit == 'integer') if Graphics.respond_to?(:integer_scaling=)
      Graphics.last_mile_scaling = (fit != 'integer') if Graphics.respond_to?(:last_mile_scaling=)
    end
  end
end

if defined?(Graphics) && Graphics.respond_to?(:update) && !Graphics.respond_to?(:__acearcade_update)
  class << Graphics
    alias_method :__acearcade_update, :update
    def update(*args)
      AceCoreCompat::Display.tick
      __acearcade_update(*args)
    end
  end
end
