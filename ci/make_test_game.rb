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
    reached = 0
    probe = lambda { |n, max| reached = n if n > reached; n >= max ? n : [0].each { return probe.call(n + 1, max) } }
    depth = begin; probe.call(0, 3000); rescue SystemStackError; -reached; end
    nested = (1..2000).inject(0) { |acc, _| [acc] }
    marshal_ok = begin; Marshal.load(Marshal.dump(nested)).flatten == [0]; rescue SystemStackError; false; end
    mark.call("stack_probe", "#{depth} #{marshal_ok} env=#{ENV["SDL_THREAD_STACK_SIZE"]}")
    # Ruby 1.8 (RGSS1) behaviours real XP games rely on: a class variable used from a top-level method, and the
    # Windows API calls the Essentials "Win32API" section makes while loading.
    cvar = begin
      eval("def acearcade_tl_cvar; @@acearcade_tl = 7; @@acearcade_tl; end; acearcade_tl_cvar", TOPLEVEL_BINDING)
    rescue Exception => e
      "#{e.class}: #{e.message}"
    end
    win = begin
      tid = Win32API.new('kernel32', 'GetCurrentThreadId', '', 'l').call
      hwnd = Win32API.new('user32', 'FindWindowEx', 'llpp', 'l').call(0, 0, 'RGSS Player', 0)
      "#{tid > 0 && hwnd != 0}"
    rescue Exception => e
      "#{e.class}: #{e.message}"
    end
    # Ruby 1.8 strings (used for RPG Maker XP games not written for mkxp-z; this test game declares mkxp-z, so the
    # mechanism is switched on here directly): with the engine's Ruby, "AB"[0] must then be the byte 65.
    s18 = if defined?(AceCoreCompat::Ruby18Strings)
            AceCoreCompat::Ruby18Strings.install
            # and str[/re/] must still set $~ for the game (Xenoverse "Trainer(4)", Pokémon Z "size(2,3)").
            m = ("Trainer(4)"[/\((\d)\)/] && $~ && $~[1])
            ("AB"[0] == 65 && "AB"[1] == 66 && "AB"[0, 1] == "A" && m == "4") ? "ok" : ["AB"[0], m].inspect
          else
            "missing"
          end
    # Marshal.load from a reader whose read(n) returns more than n bytes (Essentials StringInput on UTF-8 text,
    # Pokémon Flux): the engine's Ruby must load it intact instead of overrunning its buffer
    # (ci/patches/ruby/marshal_read_overflow.py).
    marshal = begin
      greedy = Class.new do
        def initialize(s) = (@s = s.b; @pos = 0)
        def getbyte = (c = @s.getbyte(@pos); @pos += 1; c)
        def read(n) = (r = @s.byteslice(@pos, n * 4); @pos += r.bytesize; r)
      end
      data = Array.new(20000) { |i| "Pok\u00e9mon Center #{i}" }
      ok = (1..5).all? { Marshal.load(greedy.new(Marshal.dump(data))) == data }
      # A port whose to_str returns a new String (Flux's StringInput: "alias to_str read"): that String must stay
      # alive for the whole load, also when the garbage collector runs during it (GC.stress: on every allocation).
      tostr = Class.new do
        def initialize(s) = (@s = s)
        def getbyte = nil
        def read(*) = nil
        def to_str = (+"").concat(@s)
      end
      small = Array.new(300) { |i| "Pok\u00e9mon Center #{i}" }
      GC.stress = true
      ok &&= Marshal.load(tostr.new(Marshal.dump(small))) == small
      GC.stress = false
      GC.start
      ok ? "ok" : "wrong"
    rescue Exception => e
      "#{e.class}"
    end
    # Display layout modes (Fill / Zoom / Original + picture position) from the engine patch.
    layout = begin
      if Graphics.respond_to?(:acearcade_layout)
        # (Frames are drawn with these modes at the start of the main loop below; no Graphics.update here, the test
        # reads these markers when the first frame appears.)
        Graphics.acearcade_layout(1, 1.0, 0.0, 0.0); Graphics.acearcade_layout(0, 1.0, 0.0, 0.0)
        "ok"
      else
        "missing"
      end
    rescue Exception => e
      e.class.to_s
    end
    mark.call("rgss1", "r18=#{$acearcade_r18} s18=#{s18} cvar=#{cvar} win32=#{win} marshal=#{marshal} layout=#{layout}")
    # Encodings Ruby uses for file names (must be UTF-8 on Android, whose file systems store UTF-8 names).
    mark.call("encodings", "fs=#{Encoding.find('filesystem')} locale=#{Encoding.find('locale')} lp=#{$LOAD_PATH.size}")
    mark.call("test_started", "1")
    frames = 0
    layout_modes = [[1, 1.0, 0.0, 0.0], [2, 1.5, 0.5, -0.5], [3, 1.0, -1.0, 1.0], [0, 1.0, 0.0, 0.0]]
    while frames < 60 * 90
      # The first frames are drawn in each display layout mode (Fill, Zoom + offset, Original + offset), then back to
      # the default: a crash in those engine paths would end the test here.
      if frames < layout_modes.size && Graphics.respond_to?(:acearcade_layout)
        Graphics.acearcade_layout(*layout_modes[frames])
      end
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
# A Ruby 1.8-style script (RGSS1): "when x:" instead of "when x then"; AceArcade adapts it in memory.
ruby18 = "case 1\nwhen 1: $acearcade_r18 = :ok\nend\n"
File.binwrite(File.join(out, "Data", "Scripts.rxdata"), Marshal.dump([[2, "Ruby18", Zlib::Deflate.deflate(ruby18)], [1, "Main", Zlib::Deflate.deflate(script)]]))
# Like many released Essentials games (e.g. Pokémon Xenoverse), the test game ships mkxp.json with "debugMode": true,
# which makes mkxp-z set Ruby's $DEBUG for RGSS1 games (Ruby then reports every exception, even rescued ones).
File.write(File.join(out, "mkxp.json"), "{\n  \"debugMode\": true\n}\n")
puts "Test game written to #{out}"
