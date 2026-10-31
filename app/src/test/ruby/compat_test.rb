# Tests acecore_compat.rb (engine compatibility preload) against mock mkxp-z modules.
# Run: ruby app/src/test/ruby/compat_test.rb
require 'tmpdir'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
game = Dir.mktmpdir
Dir.mkdir(File.join(game, 'Data')); File.write(File.join(game, 'Data', 'Scripts.rxdata'), '')
module Graphics
  @fixed = true; @int = false; @last = true; @updates = 0
  class << self
    attr_accessor :fixed_aspect_ratio, :integer_scaling, :last_mile_scaling, :smooth_scaling, :acearcade_area, :updates
    def width = 512
    def height = 384
    def update = (@updates += 1)
  end
end
DISPLAY_TMP = File.join(Dir.mktmpdir, 'display.json')
module Kernel
  def load_data(name) = "data:#{name}"
  private :load_data
end
$scene = nil
# Like the Android engine: times in whole microseconds (upstream mkxp-z >= 2.4.2 uses Float seconds).
$fake_us = 5_000_000
module System
  def self.platform; "Android"; end
  def self.delta; $fake_us; end
  def self.uptime; $fake_us; end
  # Like PhysFS 3.2: a mount point of "." or one with "\\" is an illegal filename.
  def self.mount(path, mp = nil, reload = true)
    raise "PHYSFSError: Failed to mount #{path} (filename is illegal or insecure)" if mp.is_a?(String) && (mp == "." || mp.include?("\\"))
    ($mounts ||= []) << [path, mp, reload]; path
  end
end
module Input
  def self.time?(button); 30_000; end
end
class << Graphics
  def delta = 16_667
end
module Audio; def self.bgm_play(*a); end; end
module AceCoreCompat; DISPLAY_W = 3120; DISPLAY_H = 1440; DISPLAY_FILE = DISPLAY_TMP; FIT = 'fit'; SMOOTH = true; SIZE = 100; end
load COMPAT
Dir.chdir(game)
def check(name, got, want) = (got == want ? puts("ok   #{name}") : (puts("FAIL #{name}: #{got.inspect} != #{want.inspect}"); $fail = true))
# Time units (Essentials v21 skips every character update when a frame takes "> 0.25" — i.e. always, in µs)
check "uptime in seconds", System.uptime, 5.0
$fake_us += 16_667
check "frame step below Essentials' 0.25 s limit", (System.uptime - 5.0) < 0.25, true
check "Graphics.delta unchanged before the game's version is known", Graphics.delta, 16_667
module Essentials; VERSION = "20.1"; end
check "Essentials v20: microseconds kept", Input.time?(2), 30_000
AceCoreCompat::Timing.reset!
module Essentials; MKXPZ_VERSION = "2.4.2/c9378cf"; end
check "Essentials for mkxp-z 2.4.2: Graphics.delta in seconds", Graphics.delta, 16_667 / 1_000_000.0
check "Essentials for mkxp-z 2.4.2: Input.time? in seconds", Input.time?(2), 0.03
check "Essentials for mkxp-z 2.4.2: System.delta in seconds", System.delta, $fake_us / 1_000_000.0
check "zlib", Zlib::Inflate.inflate(Zlib::Deflate.deflate("x")), "x"
check "display_width", Graphics.display_width, 3120
check "display_height", Graphics.display_height, 1440
check "file_exist? exact", System.file_exist?("Data/Scripts.rxdata"), true
check "file_exist? case", System.file_exist?("data\\SCRIPTS.rxdata"), true
check "file_exist? missing", System.file_exist?("Data/Nope.rxdata"), false
check "csv", System.parse_csv(%Q{a,"b,c",d\r\n1,"he said ""hi""","multi\nline"\n2\n}), [["a","b,c","d"],["1",'he said "hi"',"multi\nline"],["2","",""]]
check "is_bsd?", System.is_bsd?, false
check "bgm_volume default", Audio.bgm_volume, 100
Audio.bgm_set_volume(40, 1); check "bgm_set_volume", [Audio.bgm_volume(1), Audio.bgm_volume], [40, 100]
File.write(DISPLAY_TMP, '{"screenFit":"integer","screenSize":"80"}')
30.times { Graphics.update }
check "reports game resolution", File.read(File.join(File.dirname(DISPLAY_TMP), 'screen')), "512 384\n"
check "live fit: pixel-perfect, 80% area", [Graphics.fixed_aspect_ratio, Graphics.integer_scaling, Graphics.last_mile_scaling, Graphics.smooth_scaling, Graphics.acearcade_area], [true, true, false, false, 0.8]
check "engine update still runs", Graphics.updates, 30
sleep 1.1; File.write(DISPLAY_TMP, '{"screenFit":"fill","screenSize":"100"}')
30.times { Graphics.update }
check "live fit: stretch (sharp: integer step + smoothed last step), full area", [Graphics.fixed_aspect_ratio, Graphics.integer_scaling, Graphics.last_mile_scaling, Graphics.smooth_scaling, Graphics.acearcade_area], [false, true, true, true, 1.0]
Graphics.fixed_aspect_ratio = true # what the engine does after a window resize
30.times { Graphics.update }
check "stretch re-asserted after the engine re-locks the aspect ratio", Graphics.fixed_aspect_ratio, false
module Input
  UP = 8; DOWN = 2; LEFT = 4; RIGHT = 6; A = 11; B = 12; C = 13; X = 14; Y = 15; Z = 16; L = 17; R = 18
  @down = [UP, C]
  def self.press?(c) = @down.include?(c)
  def self.dir4 = 8
end
File.write(File.join(File.dirname(DISPLAY_TMP), 'input_test'), '')
6.times { Graphics.update }
check "controls test reports engine state", File.read(File.join(File.dirname(DISPLAY_TMP), 'input_state')), "UP C\ndir4=8\n"
class Scene_Intro; end
class Scene_Map; end
$game_map = Struct.new(:map_id).new(0)
check "load_data still returns the game's data", load_data("Data/PluginScripts.rxdata"), "data:Data/PluginScripts.rxdata"
$scene = Scene_Intro.new; Graphics.update
$scene = Scene_Map.new; $game_map.map_id = 12; 60.times { Graphics.update }
dd = File.dirname(DISPLAY_TMP)
tl = File.read(File.join(dd, 'timeline'))
check "timeline: engine ready, first data, plugins, first frame, scenes, first map",
  ["Engine ready", "first data file: Data/PluginScripts", "Loading plugins", "First frame drawn", "Scene: Scene_Intro", "Scene: Scene_Map", "First map shown (map 12)"].all? { |t| tl.include?(t) }, true
check "timeline uses one monotonic clock", tl.lines.map { |l| l.split("\t").first.to_i }.each_cons(2).all? { |a, b| b >= a }, true
check "scene log", File.read(File.join(dd, 'scenes')).include?("scene=Scene_Map map=12"), true
check "heartbeat", File.read(File.join(dd, 'heartbeat')).split[1, 2], ["Scene_Map", "12"]
# System.mount: Pokémon Flux mounts its archives at "." (the root)
System.mount("Data/Data_0.fpk", ".", false)
check "mount '.' mounts at the root", $mounts.last, ["Data/Data_0.fpk", nil, false]
System.mount("Data/b.fpk", ".\\Gfx\\")
check "mount point backslashes and ./ normalised", $mounts.last, ["Data/b.fpk", "Gfx", true]
System.mount("Data/c.fpk")
check "plain mount unchanged", $mounts.last, ["Data/c.fpk", nil, true]
# existing engine methods are not replaced
module Graphics; def self.display_width; 999; end; end
load COMPAT; check "keeps native", Graphics.display_width, 999
exit($fail ? 1 : 0)
