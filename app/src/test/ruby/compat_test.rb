# Tests acecore_compat.rb (engine compatibility preload) against mock mkxp-z modules.
# Run: ruby app/src/test/ruby/compat_test.rb
require 'tmpdir'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
game = Dir.mktmpdir
Dir.mkdir(File.join(game, 'Data')); File.write(File.join(game, 'Data', 'Scripts.rxdata'), '')
module Graphics; def self.width; 512; end; end
module System; def self.platform; "Android"; end; end
module Audio; def self.bgm_play(*a); end; end
module AceCoreCompat; DISPLAY_W = 3120; DISPLAY_H = 1440; end
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
# existing engine methods are not replaced
module Graphics; def self.display_width; 999; end; end
load COMPAT; check "keeps native", Graphics.display_width, 999
exit($fail ? 1 : 0)
