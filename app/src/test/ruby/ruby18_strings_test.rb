# Tests Ruby 1.8 string behaviour for RPG Maker XP games (acecore_compat.rb › Ruby18Strings).
# Run: ruby app/src/test/ruby/ruby18_strings_test.rb
require 'stringio'
require 'tmpdir'
require_relative 'engine_ruby18'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
module AceCoreCompat; DISPLAY_W = 1; DISPLAY_H = 1; end
module RPG; module Cache; end; end # RPG Maker XP
# Game scripts as the engine leaves them for preload scripts: [id, name, compressed, source].
audio = <<~'S'
  def getOggPage(file)
    hdrbodysize = (file.read(1)[0] rescue 0)
    bodysize = 0
    hdrbodysize.times { bodysize += (file.read(1)[0] rescue 0) }
    [hdrbodysize, bodysize]
  end
  def pbComment?(line) = line[0] == ?#
  def pbNewline?(c) = c == ?\n
  def pbTernary(x) = x ? 1 : 2
  def pbQuestion = "what?a"
  def pbSetByte(s) = (s[0] = 66; s)
  def pbLines(s) = (r = []; s.each { |l| r << l }; r)
S
# Pokémon Z, PField_RoamingPokemon: a top-level proc with "return" (Ruby 1.8: Kernel#proc was a lambda).
roaming = <<~'S'
  $roam_log = []
  class EventHandler
    def initialize = (@cb = [])
    def +(p) = (@cb << p; self)
    def trigger(*arg) = @cb.each { |c| c.call(arg[0], arg[1, arg.length]) }
  end
  $on_map_change = EventHandler.new
  $on_map_change += proc { |sender, e|
    return if e[0] > 5
    $roam_log << e[0]
  }
  $on_map_change += proc do |sender, e|
    [1, 2].each { |x| return if x == e[0] }
    $roam_log << :inner_kept
  end
  $value_proc = proc { |a| return a * 2 }
  $plain = proc { |a, b| [a, b] }
  def pbMethodProc = proc { return 1 }.call + 1
S
$RGSS_SCRIPTS = [[1, 'AudioUtilities', '', audio.dup], [2, 'PField_RoamingPokemon', '', roaming.dup]]
$stderr = StringIO.new
load COMPAT
$fail = false
def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect[0, 120]} != #{want.inspect[0, 120]}"); $fail = true))
eval($RGSS_SCRIPTS[0][3], TOPLEVEL_BINDING, '001:AudioUtilities')
eval($RGSS_SCRIPTS[1][3], TOPLEVEL_BINDING, '002:PField_RoamingPokemon')
$on_map_change.trigger(nil, 9)
$on_map_change.trigger(nil, 3)
$on_map_change.trigger(nil, 1)
check 'return in a top-level proc leaves only the proc (no LocalJumpError)', $roam_log, [:inner_kept, 3, :inner_kept, 1]
check 'return value of the proc', $value_proc.call(4), 8
check 'proc argument handling unchanged (lenient)', $plain.call(1), [1, nil]
check 'proc in a method: return leaves only the proc (Ruby 1.8)', pbMethodProc, 2
check 'line numbers unchanged', $RGSS_SCRIPTS[1][3].lines.size, roaming.lines.size
# Pokémon Xenoverse 172_Ombra.rb:75 (Overworld Shadows by KleinStudio): the player's shadow has @map = nil.
eval(<<~'S', TOPLEVEL_BINDING, '172_Ombra.rb')
  class ShadowProbe
    def initialize(map) = (@map = map)
    def same_map?(game_map) = (@map.id == game_map.id)
  end
  class MapWithId; def id = 7; end
S
check 'nil.id is an id, not an error (Ruby 1.8 Object#id)', ShadowProbe.new(nil).same_map?(Object.new), false
check "a class's own id is kept", MapWithId.new.id, 7
check 'Object#type is the class (Ruby 1.8)', "x".type, String
# $~ after str[/regexp/] in game code (Xenoverse Game_Player:151, Pokémon Z "Event Size":69, Reminiscencia DrawText).
eval(<<~'S', TOPLEVEL_BINDING, '020_Game_Player.rb')
  def pbTrainerDistance(name) = (name[/^Trainer\((\d+)\)$/] ? $~[1].to_i : nil)
  def pbEventSize(name) = (name[/size\((\d+),(\d+)\)/i] ? [$~[1].to_i, $~[2].to_i] : nil)
  def pbPreMatch(text) = (text[/<b>/] ; $~ && $~.pre_match)
S
check 'str[/re/] sets $~ for the game (trainer distance)', [pbTrainerDistance('Trainer(4)'), pbTrainerDistance('Bob')], [4, nil]
check 'str[/re/] sets $~ for the game (event size)', pbEventSize('Door size(2,3)'), [2, 3]
check '$~.pre_match after str[/re/]', pbPreMatch('ab<b>c'), 'ab'
# A game made for mkxp-z (its own mkxp.json; Pokémon Flux) keeps Ruby 3 strings even without "mkxp-z" in Scripts.rxdata.
Dir.mktmpdir do |d|
  File.write(File.join(d, 'mkxp.json'), '{}')
  probe = "module AceCoreCompat; DISPLAY_W = 1; DISPLAY_H = 1; end; module RPG; module Cache; end; end\n" \
          "$RGSS_SCRIPTS = [[1, 'Main', '', 'load_scripts_from_file']]; $stderr = File.open(File::NULL, 'w')\n" \
          "Dir.chdir(#{d.inspect}) { load #{COMPAT.inspect} }\nprint AceCoreCompat::Ruby18Strings.active.inspect, ' ', 'AB'[0].inspect"
  require 'rbconfig'
  out = IO.popen([RbConfig.ruby, '-e', probe], err: File::NULL, &:read)
  check 'mkxp.json game: no Ruby 1.8 strings', out, 'false "A"'
end
check 'proc adaptation logged', $stderr.string.include?('(4 "return" in proc)'), true
check 'file.read(1)[0] is the byte (Ruby 1.8), so .times works', getOggPage(StringIO.new("\x02\x05\x07".b)), [2, 12]
check '?# literal is a byte and matches str[0]', [pbComment?("# x"), pbComment?("x")], [true, false]
check '?\n literal', pbNewline?(10), true
check 'ternary untouched', [pbTernary(true), pbTernary(nil)], [1, 2]
check '"?" in strings untouched', pbQuestion, "what?a"
check 'str[i] = byte (Ruby 1.8)', pbSetByte(+"Ax"), "Bx"
check 'String#each iterates lines', pbLines("a\nb\n"), ["a\n", "b\n"]
check 'logged', $stderr.string.include?('Ruby 1.8 string behaviour for game scripts'), true
# AceArcade's own code (acecore_ files) keeps Ruby 3 behaviour.
eval("def acecore_probe(s) = s[0]", TOPLEVEL_BINDING, '/data/x/acecore_compat.rb')
check "AceArcade's code keeps Ruby 3 str[i]", acecore_probe("hi"), "h"
check 'two-argument and range forms unchanged', ["hello"[1, 2], "hello"[1..2], "hello"["ell"]], ["el", "el", "ell"]
# Essentials File readers (fgetb via getc), as in Pokémon Xenoverse's Pokédex data.
eval("def pbFgetb(f) = f.getc", TOPLEVEL_BINDING, '102_PokeBattle_Pokemon.rb')
Dir.mktmpdir do |d|
  f = File.join(d, 'dexdata.dat'); File.binwrite(f, "\x7F\x01".b)
  check 'File#getc gives the byte for game code', File.open(f, 'rb') { |io| [pbFgetb(io), pbFgetb(io)] }, [127, 1]
  check "AceArcade's code still gets a String from getc", File.open(f, 'rb') { |io| eval("io.getc", binding, '/x/acecore_runtime.rb') }, "\x7F".b
end
check 'byte reads counted and logged', AceCoreCompat::Ruby18Strings.conversions > 0 && $stderr.string.include?('Ruby 1.8 strings used for calls from'), true
# A game patching String the Ruby 1.8 way after AceArcade (seen in Pokémon Z / Xenoverse: String#getbyte answered
# through []): AceArcade's byte reads must still give Integers, and so must the game's own reads.
eval(<<~'S', TOPLEVEL_BINDING, '003:Win32_Compat')
  module Win32; end
  class String
    include Win32
    alias getbyte []
  end
  class StringInput
    def initialize(s) = (@string = s; @pos = 0)
    def getc = (@pos += 1; @string[@pos - 1])
    def each_byte = (yield getc while @pos < @string.size)
    def fgetb = (ret = 0; each_byte { |i| ret = i || 0; break }; ret)
  end
S
check 'after a game aliases getbyte to []: file.read(1)[0] still a byte', getOggPage(StringIO.new("\x02\x05\x07".b)), [2, 12]
check "after the alias: the game's own getbyte gives the byte", "AB".getbyte(1), 66
check 'Essentials StringInput fgetb (Xenoverse gender byte) gives an Integer', StringInput.new("\x7F\x01".b).fgetb, 127
# Script files a game's Main loads from a folder get the same adaptations (Xenoverse, Reminiscencia).
Dir.mktmpdir do |d|
  path = File.join(d, '0240 Folder_Script.rb')
  File.write(path, "def pbFolderA(s) = s[0] == ?A\ndef pbFolderW(x)\n  case x\n  when 1: :one\n  end\nend\n")
  eval(File.read(path), TOPLEVEL_BINDING, '0240 Folder_Script.rb')
  check 'folder script via File.read: ?x and "when x:" adapted', [pbFolderA("Abc"), pbFolderW(1)], [true, :one]
  File.write(path, "def pbFolderB(s) = s[1] == ?b\n")
  eval(File.open(path, 'r') { |f| f.read }, TOPLEVEL_BINDING, '0240 Folder_Script.rb')
  check 'folder script via File#read', pbFolderB("ab"), true
  check 'file on disk unchanged', File.read(path, mode: 'rb'), "def pbFolderB(s) = s[1] == ?b\n"
end
puts($fail ? 'SOME FAILED' : 'ALL PASSED')
exit($fail ? 1 : 0)
