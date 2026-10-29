# Tests AceArcade's gameplay tools in acecore_compat.rb (fast-forward, quick save / quick load) against mock
# mkxp-z modules and mock RPG Maker XP / Pokémon Essentials save systems.
# Run: ruby app/src/test/ruby/tools_test.rb
require 'tmpdir'
require 'zlib'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
GAME = Dir.mktmpdir
HELPER = Dir.mktmpdir
Dir.mkdir(File.join(GAME, 'Data'))
File.write(File.join(GAME, 'Data', 'Scripts.rxdata'), 'x' * 1234)

$us = 1_000_000
module System
  def self.uptime; $us; end
  def self.delta; $us; end
  def self.platform; "Android"; end
end
module Graphics
  @frame_count = 0; @drawn = 0
  class << self
    attr_accessor :frame_count, :drawn, :fixed_aspect_ratio, :integer_scaling, :last_mile_scaling, :smooth_scaling, :acearcade_area
    def update; @drawn += 1; @frame_count += 1; end
    def width = 640
    def height = 480
    def delta = 16_667
  end
end
module Input; def self.time?(b) = 0; end
$se = []
module Audio
  def self.se_play(*a) = ($se << a.first)
  def self.me_play(*a) = ($se << a.first)
end
module Kernel; def load_data(n) = n; private :load_data; end
module AceCoreCompat
  DISPLAY_W = 1920; DISPLAY_H = 1080; DISPLAY_FILE = File.join(HELPER, 'settings.json'); FIT = 'fit'; SMOOTH = true; SIZE = 100
end
load COMPAT
Dir.chdir(GAME)
module Essentials; VERSION = "21.1"; MKXPZ_VERSION = "2.4.2/c9378cf"; end

$fail = false
def check(name, got, want) = (got == want ? puts("ok   #{name}") : (puts("FAIL #{name}: #{got.inspect} != #{want.inspect}"); $fail = true))
def poll! = 6.times { AceCoreCompat::Tools.poll }
def speed!(s, audio = "normal")
  File.write(File.join(HELPER, "speed"), "speed=#{s}\naudio=#{audio}\n")
  File.utime(Time.now, Time.now + rand(1000), File.join(HELPER, "speed"))
  poll!
end

# ---- fast-forward
speed!(3, "quiet")
Graphics.drawn = 0; Graphics.frame_count = 0
30.times { Graphics.update }
check "3x: draws one of three frames", Graphics.drawn, 10
check "3x: every call still counts as a game frame", Graphics.frame_count, 30
t0 = System.uptime; $us += 1_000_000; t1 = System.uptime
check "3x: game clock runs 3 times faster", (t1 - t0).round(3), 3.0
Audio.se_play("Cursor"); Audio.me_play("Fanfare")
check "quiet mode skips sound effects while fast", $se, []
speed!(1.5)
Graphics.drawn = 0; 30.times { Graphics.update }
check "1.5x: draws two of three frames", Graphics.drawn, 20
Audio.se_play("Cursor")
check "normal audio keeps sound effects", $se, ["Cursor"]
speed!(1)
Graphics.drawn = 0; 10.times { Graphics.update }
check "1x: every frame drawn", Graphics.drawn, 10
t0 = System.uptime; $us += 500_000; t1 = System.uptime
check "1x: game clock back to real time (no jump)", (t1 - t0).round(3), 0.5

# ---- quick save / load with an RPG Maker XP style save system
class Game_Temp; attr_accessor :message_window_showing, :player_transferring; end
class Interp; attr_accessor :run; def running? = @run; end
class Game_System
  attr_accessor :map_interpreter, :playing_bgm, :playing_bgs
  def initialize; @map_interpreter = Interp.new; end
  def bgm_play(*); end
  def bgs_play(*); end
end
class Game_Map; def update; end; end
class Player; attr_accessor :moving; def moving? = @moving; end
class Scene_Map; attr_reader :updates; def update = (@updates = (@updates || 0) + 1); end
class Scene_Save
  def write_save_data(f) = Marshal.dump($game_variables, f)
end
class Scene_Load
  def read_save_data(f) = ($game_variables = Marshal.load(f))
end
$game_temp = Game_Temp.new; $game_system = Game_System.new; $game_map = Game_Map.new; $game_player = Player.new
$game_variables = [nil, 10, 20]
$scene = Scene_Map.new
poll!
check "scene hook installed", Scene_Map.method_defined?(:__acearcade_map_update), true

def request(id, action)
  File.write(File.join(HELPER, "tools_cmd"), "#{id}\t#{action}")
  poll!
  $scene.update
  File.exist?(File.join(HELPER, "tools_result")) ? File.read(File.join(HELPER, "tools_result")).split("\t", 3) : nil
end

check "load without a quick save", request(1, "quickload"), ["1", "error", "No quick save for this game yet."]
check "first quick save", request(2, "quicksave"), ["2", "ok", "Quick save created"]
slot = File.join(HELPER, "quicksave.aas")
check "slot written in AceArcade's folder, not the game's", [File.exist?(slot), Dir.glob(File.join(GAME, "**", "*.aas")).empty?], [true, true]
$game_variables[1] = 99
check "second quick save replaces the slot", request(3, "quicksave"), ["3", "ok", "Quick save replaced"]
check "only one slot file", Dir.glob(File.join(HELPER, "quicksave*")).map { |f| File.basename(f) }, ["quicksave.aas"]
$game_variables[1] = 5
old_scene = $scene
check "quick load", request(4, "quickload"), ["4", "ok", "Quick save loaded"]
check "quick load restores the latest quick save", $game_variables[1], 99
check "quick load starts a fresh map scene", $scene.is_a?(Scene_Map) && !$scene.equal?(old_scene), true

$game_system.map_interpreter.run = true
File.write(File.join(HELPER, "tools_cmd"), "5\tquicksave"); poll!; $scene.update
check "busy: waits instead of saving mid-event", File.read(File.join(HELPER, "tools_result")).start_with?("4\t"), true
AceCoreCompat::Tools.instance_variable_set(:@pending_since, Time.now - 5); $scene.update
check "busy for long: explains", File.read(File.join(HELPER, "tools_result")).split("\t")[1], "error"
$game_system.map_interpreter.run = false

class Scene_Menu; def update; end; end
$scene = Scene_Menu.new
r = request(6, "quicksave")
check "not on the map: refused with a reason", r[0..1], ["6", "error"]
$scene = Scene_Map.new

data = File.binread(slot); File.binwrite(slot, data.sub("crc=", "crc=1"))
check "damaged slot is refused", request(7, "quickload")[2].include?("damaged"), true
File.binwrite(slot, data)
File.write(File.join(GAME, 'Data', 'Scripts.rxdata'), 'y' * 999)
check "different game version is refused", request(8, "quickload")[2].include?("different version"), true
File.write(File.join(GAME, 'Data', 'Scripts.rxdata'), 'x' * 1234)

# failed save keeps the previous slot
class Scene_Save; def write_save_data(f) = raise(IOError, "disk full"); end
before = File.binread(slot)
check "failed quick save reports the error", request(9, "quicksave")[1], "error"
check "failed quick save keeps the previous slot", File.binread(slot), before

# ---- Essentials flavour
module SaveData
  def self.compile_save_hash = { variables: $game_variables.dup, player: [3, 4] }
  def self.load_all_values(h) = ($game_variables = h[:variables]; $loaded_player = h[:player])
end
module Game; def self.load_map = ($map_loaded = true); end
def pbMapInterpreterRunning? = false
def pbAutoplayOnSave = ($autoplay = true)
$game_temp.message_window_showing = false
check "Essentials save system detected", AceCoreCompat::Tools.flavor, :essentials
File.delete(slot)
$game_variables = [nil, 7]
check "Essentials quick save", request(10, "quicksave")[1], "ok"
$game_variables = [nil, 0]
check "Essentials quick load", request(11, "quickload")[1], "ok"
check "Essentials values restored", [$game_variables[1], $loaded_player, $map_loaded, $autoplay], [7, [3, 4], true, true]
File.delete(slot)
module SaveData; def self.compile_save_hash = { v: 1 }; end
request(12, "quicksave")
Object.send(:remove_const, :SaveData)
check "quick save from another engine kind is refused", request(13, "quickload")[2].include?("different kind"), true

exit($fail ? 1 : 0)
