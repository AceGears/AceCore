require 'socket'; require 'openssl'
ctx = OpenSSL::SSL::SSLContext.new; ctx.cert = OpenSSL::X509::Certificate.new(File.read('srv.pem')); ctx.key = OpenSSL::PKey::RSA.new(File.read('srv.key'))
srv = OpenSSL::SSL::SSLServer.new(TCPServer.new('127.0.0.1', 8443), ctx)
big = (0...5000).map { |i| "line #{i}\n" }.join
loop do
  s = srv.accept rescue next
  Thread.new(s) do |c|
    req = c.gets.to_s; hdr = {}
    while (l = c.gets) && l != "\r\n"; k, v = l.split(':', 2); hdr[k.strip.downcase] = v.to_s.strip; end
    path = req.split[1]
    body = hdr['content-length'] ? c.read(hdr['content-length'].to_i) : ''
    case path
    when '/echo'
      out = "#{req.split[0]} #{hdr['x-game']} #{body}"
      c.write "HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\nContent-Length: #{out.bytesize}\r\nConnection: close\r\n\r\n#{out}"
    when '/version' then c.write "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\n1.0.1"
    when '/redir' then c.write "HTTP/1.1 302 Found\r\nLocation: /version\r\nContent-Length: 0\r\n\r\n"
    when '/chunked' then c.write "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + big.scan(/.{1,3000}/m).map { |p| p.bytesize.to_s(16) + "\r\n" + p + "\r\n" }.join + "0\r\n\r\n"
    else c.write "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
    end
    c.close
  end
end
