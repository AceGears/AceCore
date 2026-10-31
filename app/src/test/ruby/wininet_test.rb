# Tests the Win32API/WinInet emulation in acecore_compat.rb under device conditions: only the C parts of Ruby's
# socket and openssl extensions are loaded (the Ruby-side helper libraries aren't shipped on Android).
# Starts a local HTTPS test server (own CA) in a child process. Run: ruby app/src/test/ruby/wininet_test.rb
require 'tmpdir'
require 'rbconfig'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
SERVER = File.expand_path('wininet/server.rb', __dir__)
dir = Dir.mktmpdir
Dir.chdir(dir) do
  system(RbConfig.ruby, '-ropenssl', '-e', <<~'RUBY') or abort 'cert generation failed'
    ca_key = OpenSSL::PKey::RSA.new(2048); ca = OpenSSL::X509::Certificate.new; ca.version = 2; ca.serial = 1
    ca.subject = ca.issuer = OpenSSL::X509::Name.parse('/CN=Test CA'); ca.public_key = ca_key.public_key
    ca.not_before = Time.now - 60; ca.not_after = Time.now + 3600
    ef = OpenSSL::X509::ExtensionFactory.new(ca, ca)
    ca.add_extension(ef.create_extension('basicConstraints', 'CA:TRUE', true))
    ca.add_extension(ef.create_extension('keyUsage', 'keyCertSign,cRLSign', true)); ca.sign(ca_key, 'SHA256')
    k = OpenSSL::PKey::RSA.new(2048); c = OpenSSL::X509::Certificate.new; c.version = 2; c.serial = 2
    c.subject = OpenSSL::X509::Name.parse('/CN=localhost'); c.issuer = ca.subject; c.public_key = k.public_key
    c.not_before = Time.now - 60; c.not_after = Time.now + 3600
    c.add_extension(OpenSSL::X509::ExtensionFactory.new(ca, c).create_extension('subjectAltName', 'DNS:localhost'))
    c.sign(ca_key, 'SHA256')
    Dir.mkdir('game'); File.write('game/cacert.pem', ca.to_pem); File.write('srv.pem', c.to_pem); File.write('srv.key', k.to_pem)
  RUBY
end
pid = spawn(RbConfig.ruby, SERVER, chdir: dir, err: File::NULL)
sleep 1.5
client = <<~'RUBY'
  require 'socket.so'; require 'openssl.so'
  abort 'openssl Ruby lib loaded' if OpenSSL::SSL::SSLContext.method_defined?(:set_params)
  class MiniFFI
    def initialize(dll, func, *a) = raise(RuntimeError, "Failed loading #{dll}: dlopen failed: library \"#{dll}\" not found")
  end
  Win32API = MiniFFI
  module AceCoreCompat; DISPLAY_W = 1; DISPLAY_H = 1; end
  $stderr.reopen(File::NULL)
  load ARGV[0]
  Dir.chdir('game')
  # Same call pattern as common RGSS WinInet downloaders.
  IOA = Win32API.new('wininet', 'InternetOpenA', 'plppl', 'l').call('', 0, '', '', 0)
  IOU = Win32API.new('wininet', 'InternetOpenUrl', 'lppllp', 'l')
  IRF = Win32API.new('wininet', 'InternetReadFile', 'lpip', 'l')
  HQI = Win32API.new('wininet', 'HttpQueryInfo', 'llppp', 'i')
  ICH = Win32API.new('wininet', 'InternetCloseHandle', 'l', 'l')
  def fetch(url)
    h = IOU.call(IOA, url, nil, 0, 0, 0)
    out = +''
    loop do
      buf = ' ' * 1024; o = [0].pack('i!')
      r = IRF.call(h, buf, 1024, o); n = o.unpack1('i!')
      break if r && n == 0
      out << buf[0, n]
    end
    ICH.call(h)
    [h, out]
  end
  def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect[0, 80]} != #{want.inspect[0, 80]}"); $fail = true))
  check 'SetPriorityClass', Win32API.new('kernel32', 'SetPriorityClass', 'pi', 'i').call(-1, 128), 1
  check 'InternetOpenA handle', IOA > 0, true
  check 'GET', fetch('https://localhost:8443/version')[1], '1.0.1'
  h = IOU.call(IOA, 'https://localhost:8443/version', nil, 0, 0, 0); k = "\0" * 32
  HQI.call(h, 5, k, [k.size - 1].pack('l'), nil); check 'HttpQueryInfo content length', k.delete("\0"), '5'
  check 'redirect', fetch('https://localhost:8443/redir')[1], '1.0.1'
  big = (0...5000).map { |i| "line #{i}\n" }.join
  check 'chunked body in 1 KB reads', fetch('https://localhost:8443/chunked')[1], big
  check 'unreachable host -> handle 0, empty body', fetch('https://localhost:1/x'), [0, '']
  File.rename('cacert.pem', 'other.pem'); ENV.delete('SSL_CERT_FILE'); AceCoreCompat::WinInet.instance_variable_set(:@store, nil)
  check 'untrusted certificate rejected', fetch('https://localhost:8443/version'), [0, '']
  # WinHTTP, as RGSS online scripts call it (Unicode strings, DWORD out-parameters).
  W = ->(t) { t.encode('UTF-16LE').b + "\0\0".b }
  wh = ->(f, spec, ret = 'l') { Win32API.new('winhttp', f, spec, ret) }
  # (back in the right folder for the trusted CA)
  File.rename('other.pem', 'cacert.pem'); AceCoreCompat::WinInet.instance_variable_set(:@store, nil)
  ses = wh['WinHttpOpen', 'plppl'].call(W['Game'], 0, nil, nil, 0)
  check 'WinHttpOpen session', ses > 0, true
  con = wh['WinHttpConnect', 'lpll'].call(ses, W['localhost'], 8443, 0)
  req = wh['WinHttpOpenRequest', 'lppppll'].call(con, W['POST'], W['/echo'], nil, nil, nil, 0x00800000)
  hdr = "X-Game: Xenoverse\r\nContent-Type: text/plain"
  sent = wh['WinHttpSendRequest', 'lplplll', 'i'].call(req, W[hdr], -1, 'trainer=1', 9, 9, 0)
  check 'WinHttpSendRequest', sent, 1
  check 'WinHttpReceiveResponse', wh['WinHttpReceiveResponse', 'll', 'i'].call(req, 0), 1
  st = [0].pack('L'); len = [4].pack('L')
  wh['WinHttpQueryHeaders', 'llpppp', 'i'].call(req, 19 | 0x20000000, nil, st, len, nil)
  check 'WinHttpQueryHeaders status (number)', st.unpack1('L'), 201
  small = "\0" * 4; len = [4].pack('L')
  r = wh['WinHttpQueryHeaders', 'llpppp', 'i'].call(req, 1, nil, small, len, nil)
  check 'WinHttpQueryHeaders too-small buffer', [r, Win32API.new('kernel32', 'GetLastError', '', 'l').call, len.unpack1('L')], [0, 122, 22]
  ct = "\0" * 22; wh['WinHttpQueryHeaders', 'llpppp', 'i'].call(req, 1, nil, ct, len, nil)
  check 'WinHttpQueryHeaders content type (UTF-16)', ct.force_encoding('UTF-16LE').encode('UTF-8').delete("\0"), 'text/plain'
  out = +''
  loop do
    avail = [0].pack('L'); wh['WinHttpQueryDataAvailable', 'lp', 'i'].call(req, avail)
    n = avail.unpack1('L'); break if n.zero?
    buf = "\0" * [n, 4].min; got = [0].pack('L')
    wh['WinHttpReadData', 'lplp', 'i'].call(req, buf, buf.bytesize, got)
    out << buf[0, got.unpack1('L')]
  end
  check 'WinHttp POST body and headers reach the server', out, 'POST Xenoverse trainer=1'
  check 'WinHttpCloseHandle', [req, con, ses].map { |h| wh['WinHttpCloseHandle', 'l', 'i'].call(h) }, [1, 1, 1]
  bad = wh['WinHttpOpenRequest', 'lppppll'].call(wh['WinHttpConnect', 'lpll'].call(ses2 = wh['WinHttpOpen', 'plppl'].call(W['G'], 0, nil, nil, 0), W['localhost'], 1, 0), W['GET'], W['/'], nil, nil, nil, 0)
  wh['WinHttpSendRequest', 'lplplll', 'i'].call(bad, nil, 0, nil, 0, 0, 0)
  check 'WinHttp unreachable server -> FALSE + ERROR_WINHTTP_CANNOT_CONNECT',
        [wh['WinHttpReceiveResponse', 'll', 'i'].call(bad, 0), Win32API.new('kernel32', 'GetLastError', '', 'l').call], [0, 12029]
  begin
    Win32API.new('gdi32', 'CreateFontA', 'iiiiiiiiiiiiip', 'l'); check 'unknown Windows call', :none, :error
  rescue RuntimeError => e
    check 'unknown Windows call fails clearly', e.message.include?('gdi32!CreateFontA is not available on Android'), true
  end
  exit($fail ? 1 : 0)
RUBY
ok = Dir.chdir(dir) { system(RbConfig.ruby, '-W0', '-e', client, COMPAT) }
Process.kill('TERM', pid) rescue nil
exit(ok ? 0 : 1)
