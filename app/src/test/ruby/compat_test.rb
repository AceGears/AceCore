# Tests acecore_compat.rb (engine compatibility preload) against mock mkxp-z modules.
# Run: ruby app/src/test/ruby/compat_test.rb
require 'tmpdir'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
game = Dir.mktmpdir
Dir.mkdir(File.join(game, 'Data')); File.write(File.join(game, 'Data', 'Scripts.rxdata'), '')
module Graphics
  @fixed = true; @int = false; @last = true; @updates = 0
  class << self
    attr_accessor :fixed_aspect_ratio, :integer_scaling, :last_mile_scaling, :smooth_scaling, :updates
    def width = 512
    def height = 384
    def update = (@updates += 1)
  end
end
DISPLAY_TMP = File.join(Dir.mktmpdir, 'display.json')
module System; def self.platform; "Android"; end; end
module Audio; def self.bgm_play(*a); end; end
module AceCoreCompat; DISPLAY_W = 3120; DISPLAY_H = 1440; DISPLAY_FILE = DISPLAY_TMP; FIT = 'fit'; SMOOTH = true; end
load COMPAT
Dir.chdir(game)
def check(name, got, want) = (got == want ? puts("ok   #{name}") : (puts("FAIL #{name}: #{got.inspect} != #{want.inspect}"); $fail = true))
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
check "live fit: pixel-perfect (sharp, surface sized by AceArcade)", [Graphics.fixed_aspect_ratio, Graphics.integer_scaling, Graphics.smooth_scaling], [true, false, false]
check "engine update still runs", Graphics.updates, 30
sleep 1.1; File.write(DISPLAY_TMP, '{"screenFit":"fill","screenSize":"100"}')
30.times { Graphics.update }
check "live fit: stretch", [Graphics.fixed_aspect_ratio, Graphics.integer_scaling, Graphics.smooth_scaling], [false, false, true]
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
# existing engine methods are not replaced
module Graphics; def self.display_width; 999; end; end
load COMPAT; check "keeps native", Graphics.display_width, 999
exit($fail ? 1 : 0)
