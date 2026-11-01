# Tests quick save / quick load for Pokémon Essentials up to v18 (no SaveData module: Pokémon Z, Xenoverse,
# Reminiscencia), where the list of saved objects is read from the game's own pbSave.
# Run: ruby app/src/test/ruby/tools_old_essentials_test.rb
require 'tmpdir'
require 'zlib'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
GAME = Dir.mktmpdir
HELPER = Dir.mktmpdir
Dir.mkdir(File.join(GAME, 'Data'))
File.write(File.join(GAME, 'Data', 'Scripts.rxdata'), 'x' * 99)
module System; def self.uptime = 1.0; def self.delta = 1.0; def self.platform = "Android"; end
module Graphics
  @frame_count = 0
  class << self
    attr_accessor :frame_count
    def update = (@frame_count += 1)
    def width = 512
    def height = 384
  end
end
module Input; def self.time?(b) = 0; end
module Kernel; def load_data(n) = n; private :load_data; end
module AceCoreCompat
  DISPLAY_W = 1920; DISPLAY_H = 1080; DISPLAY_FILE = File.join(HELPER, 'settings.json'); FIT = 'fit'; SMOOTH = true; SIZE = 100
end
# The game's save script as Essentials v16 writes it (abridged), loaded as a game script.
save_script = <<~'S'
  def pbSave(safesave=false)
    $Trainer.metaID=$PokemonGlobal.playerID
    begin
      File.open("Game.rxdata","wb"){|f|
         Marshal.dump($Trainer,f)
         Marshal.dump(Graphics.frame_count,f)
         $game_system.save_count+=1
         Marshal.dump($game_system,f)
         Marshal.dump($game_map.map_id,f)
         Marshal.dump($game_variables,f)
         if $PokemonBag
           Marshal.dump($PokemonBag, f)
         end
         Marshal.dump($MapFactory,f)
         Marshal.dump($PokemonGlobal,f)
      }
    rescue
      return false
    end
    return true
  end
S
$RGSS_SCRIPTS = [[1, 'PScreen_Save', '', save_script.dup]]
load COMPAT
Dir.chdir(GAME)
eval($RGSS_SCRIPTS[0][3], TOPLEVEL_BINDING, '000:PScreen_Save')

class Game_Temp; attr_accessor :message_window_showing, :player_transferring; end
class Game_System; attr_accessor :save_count; def initialize = (@save_count = 0); end
class Game_Map
  attr_reader :map_id, :updated
  attr_accessor :need_refresh
  def initialize(id) = (@map_id = id)
  def update = (@updated = true)
end
class MapFactory; attr_reader :map; def initialize(id) = (@map = Game_Map.new(id)); end
class PokemonGlobalMetadata; attr_accessor :playerID, :steps; end
class PokeBattle_Trainer; attr_accessor :metaID, :money; end
class PokemonBag; attr_accessor :items; end
class PokemonTemp; def dependentEvents = (@dependentEvents ||= Object.new); end
class PokemonEncounters; attr_reader :map; def setup(id) = (@map = id); end
class PokemonMapMetadata; attr_reader :updated; def updateMap = (@updated = true); end
class Player; def moving? = false; end
class Scene_Map; def update; end; end
def pbMapInterpreterRunning? = false
def pbAutoplayOnSave = ($autoplay = true)

$game_temp = Game_Temp.new; $game_system = Game_System.new; $game_player = Player.new
$MapFactory = MapFactory.new(5); $game_map = $MapFactory.map
$Trainer = PokeBattle_Trainer.new.tap { |t| t.money = 3000 }
$PokemonGlobal = PokemonGlobalMetadata.new.tap { |g| g.steps = 10 }
$PokemonBag = PokemonBag.new.tap { |b| b.items = [[1, 5]] }
$PokemonTemp = PokemonTemp.new; $PokemonMap = PokemonMapMetadata.new
$game_variables = [nil, 1]
Graphics.frame_count = 600
$scene = Scene_Map.new

$fail = false
def check(name, got, want) = (got == want ? puts("ok   #{name}") : (puts("FAIL #{name}: #{got.inspect} != #{want.inspect}"); $fail = true))
def request(id, action)
  File.write(File.join(HELPER, "tools_cmd"), "#{id}\t#{action}")
  6.times { AceCoreCompat::Tools.poll }
  $scene.update
  File.exist?(File.join(HELPER, "tools_result")) ? File.read(File.join(HELPER, "tools_result")).split("\t", 3) : nil
end

check "old Essentials save system detected", AceCoreCompat::Tools.flavor, :essentials_old
check "saved objects read from the game's pbSave",
      AceCoreCompat::Tools.old_essentials_saves,
      ["$Trainer", "Graphics.frame_count", "$game_system", "$game_map.map_id", "$game_variables", "$PokemonBag", "$MapFactory", "$PokemonGlobal"]
check "quick save", request(1, "quicksave"), ["1", "ok", "Quick save created"]
check "the game's real save file is not written", File.exist?(File.join(GAME, "Game.rxdata")), false
check "pbSave side effects not run (save count)", $game_system.save_count, 0

old_dep = $PokemonTemp.dependentEvents
$Trainer.money = 1; $PokemonGlobal.steps = 999; $PokemonBag.items = []; $game_variables[1] = 42
$MapFactory = MapFactory.new(9); $game_map = $MapFactory.map; Graphics.frame_count = 9000
old_scene = $scene
check "quick load", request(2, "quickload"), ["2", "ok", "Quick save loaded"]
check "trainer, bag, variables, global data restored",
      [$Trainer.money, $PokemonBag.items, $game_variables[1], $PokemonGlobal.steps], [3000, [[1, 5]], 1, 10]
check "map restored from the saved map factory", [$game_map.map_id, $game_map.equal?($MapFactory.map)], [5, true]
check "play time restored", Graphics.frame_count, 600
check "encounters set up for the loaded map", $PokemonEncounters.map, 5
check "followers rebuilt from the loaded data", $PokemonTemp.dependentEvents.equal?(old_dep), false
check "map refreshed, autoplay, map metadata updated", [$game_map.updated, $autoplay, $PokemonMap.updated], [true, true, true]
check "fresh map scene", $scene.is_a?(Scene_Map) && !$scene.equal?(old_scene), true

# A game whose save system isn't recognised gets a clear message, not "busy".
AceCoreCompat::Tools.instance_variable_set(:@old_saves, nil)
check "unrecognised save system", request(3, "quicksave")[2], "Quick save isn't available for this game: its save system isn't one AceArcade recognises."
puts($fail ? "SOME FAILED" : "ALL PASSED")
exit($fail ? 1 : 0)
