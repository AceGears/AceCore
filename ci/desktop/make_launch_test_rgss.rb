# Generates a tiny RPG Maker XP-format game (our own script only) that proves an RGSS engine really runs:
# it draws frames for ~3 seconds of engine time, then writes launch_ok (frames, seconds, engine info) and exits.
#   ruby make_launch_test_rgss.rb <dir>
require "zlib"; require "fileutils"
out = ARGV[0] or abort("usage: make_launch_test_rgss.rb <dir>")
FileUtils.mkdir_p(File.join(out, "Data"))
script = <<~'RUBY'
  File.open("launch_started", "wb") { |f| f.write(Dir.pwd) } rescue nil
  begin
    bg = Sprite.new; bg.bitmap = Bitmap.new(640, 480); bg.bitmap.fill_rect(0, 0, 640, 480, Color.new(11, 8, 32))
    t = Sprite.new; t.bitmap = Bitmap.new(640, 80); t.y = 200
    t.bitmap.font.size = 28
    t.bitmap.draw_text(0, 0, 640, 40, "AceArcade desktop launch test", 1) rescue nil
    hero = Sprite.new; hero.bitmap = Bitmap.new(32, 32); hero.bitmap.fill_rect(0, 0, 32, 32, Color.new(240, 58, 214))
    hero.y = 300
    start = (System.uptime rescue nil)
    frames = 0
    loop do
      Graphics.update; Input.update; frames += 1
      hero.x = (frames * 4) % 608
      File.open("launch_shot_now", "wb") { |f| f.write("1") } if frames == 90
      now = (System.uptime rescue nil)
      break if (start && now && now - start >= 3.0) || frames >= 600
    end
    secs = start ? (System.uptime - start) : -1
    info = ["frames=#{frames}", "seconds=#{'%.2f' % secs}", "ruby=#{RUBY_VERSION}",
            "mkxpz=#{defined?(MKXP_Z_VERSION) ? MKXP_Z_VERSION : (System.respond_to?(:version) ? System.version : '?')}"]
    File.open("launch_ok", "wb") { |f| f.write(info.join(" ")) }
  rescue Exception => e
    File.open("launch_error", "wb") { |f| f.write("#{e.class}: #{e.message}\n#{(e.backtrace || []).first(5).join("\n")}") } rescue nil
  end
  exit
RUBY
File.write(File.join(out, "Game.ini"), "[Game]\r\nLibrary=RGSS104E.dll\r\nScripts=Data\\Scripts.rxdata\r\nTitle=AceArcade launch test\r\nRTP1=\r\nRTP2=\r\nRTP3=\r\n")
File.binwrite(File.join(out, "Data", "Scripts.rxdata"), Marshal.dump([[1, "Main", Zlib::Deflate.deflate(script)]]))
puts "wrote #{out}"
