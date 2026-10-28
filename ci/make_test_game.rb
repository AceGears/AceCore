# Generates a tiny RPG Maker XP (RGSS1) test game used by AceCore's emulator test:
#   Game.ini + Data/Scripts.rxdata (Marshal + zlib, the format mkxp-z and RPG Maker XP read).
# The game draws a screen, reports through AceCore's runtime helper, waits for a cheat to set its gold to 777,
# records that it saw the change, then exits.
#   ruby ci/make_test_game.rb <output dir>
require "zlib"
require "fileutils"

out = ARGV[0] or abort("usage: make_test_game.rb <dir>")
FileUtils.mkdir_p(File.join(out, "Data"))

script = <<~'RUBY'
  dir = defined?(AceCoreBoot) ? AceCoreBoot::DIR : "."
  mark = lambda { |name, text| File.open(File.join(dir, name), "wb") { |f| f.write(text.to_s) } }
  begin
    class Game_Party
      attr_reader :gold
      def initialize; @gold = 0; end
      def gain_gold(n); @gold += n; end
    end
    $game_party = Game_Party.new
    sprite = Sprite.new
    sprite.bitmap = Bitmap.new(640, 480)
    sprite.bitmap.fill_rect(0, 0, 640, 480, Color.new(140, 10, 30))
    sprite.bitmap.fill_rect(40, 180, 560, 120, Color.new(20, 20, 26))
    begin
      sprite.bitmap.font.size = 32
      sprite.bitmap.draw_text(40, 200, 560, 40, "AceCore RGSS test game", 1)
      sprite.bitmap.draw_text(40, 240, 560, 40, "Runs directly from shared storage", 1)
    rescue Exception
    end
    mark.call("test_started", "1")
    frames = 0
    while frames < 60 * 90
      Graphics.update
      Input.update
      frames += 1
      if $game_party.gold == 777
        mark.call("test_cheat_ok", $game_party.gold)
        break
      end
    end
    90.times { Graphics.update }
  rescue Exception => e
    mark.call("test_error", "#{e.class}: #{e.message}")
  end
  exit
RUBY

File.write(File.join(out, "Game.ini"), "[Game]\r\nLibrary=RGSS104E.dll\r\nScripts=Data\\Scripts.rxdata\r\nTitle=AceCore Test\r\nRTP1=\r\nRTP2=\r\nRTP3=\r\n")
File.binwrite(File.join(out, "Data", "Scripts.rxdata"), Marshal.dump([[1, "Main", Zlib::Deflate.deflate(script)]]))
puts "Test game written to #{out}"
