# Generates a tiny RPG Maker XP (RGSS1) test game used by AceCore's emulator test:
#   Game.ini + Data/Scripts.rxdata (Marshal + zlib, the format mkxp-z and RPG Maker XP read).
# The game draws a screen, reports through AceCore's runtime helper, waits for a cheat to set its gold to 777,
# records that it saw the change, then enters a walking phase that moves a character exactly the way Pokémon
# Essentials v21 does (time-based, "return if delta > 0.25", Input.dir4) while the instrumented test holds the
# on-screen D-pad; it reports its position to "walk". The script is AceArcade's own code (no RPG Maker or
# Essentials scripts are included).
#   ruby ci/make_test_game.rb <output dir>
require "zlib"
require "fileutils"

out = ARGV[0] or abort("usage: make_test_game.rb <dir>")
FileUtils.mkdir_p(File.join(out, "Data"))

script = <<~'RUBY'
  # Declares itself an Essentials v21 game for mkxp-z 2.4.2, like real v21 games do in their Settings script.
  module Essentials; VERSION = "21.1"; MKXPZ_VERSION = "2.4.2/c9378cf"; end
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
    # Deep-nesting probe (regression check for start-up crashes of large games such as Pokémon Essentials titles):
    # recursion through a C method (Array#each) and Marshal.load of deeply nested data both use the engine thread's
    # native stack, like big script sets and plugin loaders do. Reports the depth reached; a crash here ends the test.
    probe = lambda { |n, max| n >= max ? n : [0].each { return probe.call(n + 1, max) } }
    depth = begin; probe.call(0, 3000); rescue SystemStackError; -1; end
    nested = (1..2000).inject(0) { |acc, _| [acc] }
    marshal_ok = begin; Marshal.load(Marshal.dump(nested)).flatten == [0]; rescue SystemStackError; false; end
    mark.call("stack_probe", "#{depth} #{marshal_ok}")
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
    # ---- Walking, Essentials v21 style -------------------------------------------------------------------
    hero = Sprite.new
    hero.bitmap = Bitmap.new(32, 32)
    hero.bitmap.fill_rect(0, 0, 32, 32, Color.new(255, 230, 40))
    hero.x = 304; hero.y = 32; hero.z = 10
    steps = 0; facing = 8; frames = 0; skipped = 0; held = 0.0; down_frames = 0; started = System.uptime
    last = System.uptime
    mark.call("walk_ready", "1")
    while frames < 60 * 150
      Graphics.update
      Input.update
      frames += 1
      now = System.uptime
      dt = now - last
      last = now
      d = Input.dir4
      down_frames += 1 if d == 2 || Input.press?(Input::DOWN)
      if dt > 0.25
        skipped += 1          # Game_Character#update: "return if @delta_t > 0.25"
      elsif d > 0
        facing = d
        held += dt
        while held >= 0.25    # one tile per 0.25 s held
          held -= 0.25
          if d == 2 then steps += 1; hero.y += 8 end
        end
      else
        held = 0.0
      end
      mark.call("walk", "#{steps} #{d} #{facing} #{skipped} #{frames} #{now.class} #{down_frames} #{(now - started).round(2)}") if frames % 3 == 0
      break if File.exist?(File.join(dir, "walk_done"))
    end
    mark.call("walk", "#{steps} #{Input.dir4} #{facing} #{skipped} #{frames} #{System.uptime.class} #{down_frames} #{(System.uptime - started).round(2)}")
    30.times { Graphics.update }
  rescue Exception => e
    mark.call("test_error", "#{e.class}: #{e.message}")
  end
  exit
RUBY

File.write(File.join(out, "Game.ini"), "[Game]\r\nLibrary=RGSS104E.dll\r\nScripts=Data\\Scripts.rxdata\r\nTitle=AceCore Test\r\nRTP1=\r\nRTP2=\r\nRTP3=\r\n")
File.binwrite(File.join(out, "Data", "Scripts.rxdata"), Marshal.dump([[1, "Main", Zlib::Deflate.deflate(script)]]))
puts "Test game written to #{out}"
