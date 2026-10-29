# Generates AceArcade's RGSS engine self-test game (Game.ini + Data/Scripts.rxdata), our own script only.
require "zlib"; require "fileutils"
out = ARGV[0] or abort("usage: make_selftest_rgss.rb <dir>")
FileUtils.mkdir_p(File.join(out, "Data"))
script = <<~'RUBY'
  # Declares itself an Essentials v21 game so the engine's timing layer (seconds) is exercised as in real games.
  module Essentials; VERSION = "21.1"; MKXPZ_VERSION = "2.4.2/c9378cf"; end
  begin
    bg = Sprite.new; bg.bitmap = Bitmap.new(640, 480); bg.bitmap.fill_rect(0, 0, 640, 480, Color.new(11, 8, 32))
    text = Sprite.new; text.bitmap = Bitmap.new(640, 120); text.z = 5
    draw = lambda do |line2|
      text.bitmap.clear
      text.bitmap.font.size = 26
      text.bitmap.draw_text(0, 10, 640, 34, "AceArcade engine test · RGSS (RPG Maker XP/VX/Ace)", 1) rescue nil
      text.bitmap.draw_text(0, 50, 640, 34, line2, 1) rescue nil
    end
    draw.call("Hold DOWN on the D-pad")
    hero = Sprite.new; hero.bitmap = Bitmap.new(32, 32); hero.bitmap.fill_rect(0, 0, 32, 32, Color.new(240, 58, 214))
    hero.x = 304; hero.y = 140; hero.z = 10
    steps = 0; held = 0.0; last = System.uptime; frames = 0
    loop do
      Graphics.update; Input.update; frames += 1
      now = System.uptime; dt = now - last; last = now
      next if dt > 0.25
      if Input.dir4 == 2
        held += dt
        while held >= 0.25
          held -= 0.25; steps += 1; hero.y += 32; hero.y = 140 if hero.y > 420
        end
      else
        held = 0.0
      end
      break if steps >= 6
    end
    draw.call("Passed: the engine runs and the D-pad moves the character.")
    File.open("selftest_ok", "wb") { |f| f.write(frames.to_s) }
    90.times { Graphics.update }
  rescue Exception => e
    File.open("selftest_error", "wb") { |f| f.write("#{e.class}: #{e.message}") } rescue nil
  end
  exit
RUBY
File.write(File.join(out, "Game.ini"), "[Game]\r\nLibrary=RGSS104E.dll\r\nScripts=Data\\Scripts.rxdata\r\nTitle=AceArcade engine test\r\nRTP1=\r\nRTP2=\r\nRTP3=\r\n")
File.binwrite(File.join(out, "Data", "Scripts.rxdata"), Marshal.dump([[1, "Main", Zlib::Deflate.deflate(script)]]))
