# Essentials v16-era StringInput + FileInputMixin (verbatim logic) read through acecore_compat.rb's fix.
# Run: ruby app/src/test/ruby/ruby18_bytes_test.rb
require 'stringio'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
module AceCoreCompat; DISPLAY_W = 1; DISPLAY_H = 1; end
module RPG; module Cache; end; end # RPG Maker XP game with Ruby 1.8 behaviour
$RGSS_SCRIPTS = [[1, 'Main', '', 'mainFunction']]
$stderr = StringIO.new
load COMPAT
$fail = false
def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect} != #{want.inspect}"); $fail = true))
# Defined the way the game does, after the compat layer has loaded.
eval(<<~'GAME', TOPLEVEL_BINDING)
  module FileInputMixin
    def fgetb
      x = 0; ret = 0
      each_byte do |i|
        ret = i || 0
        break
      end
      return ret
    end
    def fgetw
      x = 0; ret = 0
      each_byte do |i|
        break if !i
        ret |= (i << x)
        x += 8
        break if x == 16
      end
      return ret
    end
  end
  class StringInput
    include FileInputMixin
    def initialize(str) @string = str; @pos = 0 end
    def eof? = @pos >= @string.length
    def getc
      return nil if eof?
      ret = @string[@pos]
      @pos += 1
      return ret
    end
    def each_byte
      while !eof?
        yield getc
      end
    end
  end
GAME
s = StringInput.new([0, 31, 254, 0x34, 0x12].pack('C*').b)
s.instance_variable_set(:@pos, 1)
check 'fgetb returns an Integer (gender rate 31)', s.fgetb, 31
check 'fgetb 254', s.fgetb, 254
check 'fgetw little-endian word', s.fgetw, 0x1234
check 'isFemale comparison works', (5 <= StringInput.new([31].pack('C')).fgetb), true
check 'getc itself unchanged (game text readers)', StringInput.new('AB').getc, 'A'
eval("class StringInput2; def initialize(s) @s = s end; def each_byte; yield @s[0] end; def fgetb; @s[0] end; end; class StringInput < Object; end", TOPLEVEL_BINDING)
AceCoreCompat::Ruby18Bytes.patch(StringInput2)
check 'fgetb that returns getc directly', StringInput2.new([200].pack('C').b).fgetb, 200
check 'logged', $stderr.string.include?('StringInput reads bytes as Integers'), true
check 'wrapped in the class, not prepended', StringInput.ancestors.first, StringInput
# A game made for a current Ruby (Pokémon Flux: Main mounts its scripts with mkxp-z's System.mount) gets neither Ruby
# 1.8 strings nor the StringInput change.
require 'rbconfig'
probe = "module AceCoreCompat; DISPLAY_W = 1; DISPLAY_H = 1; end; module RPG; module Cache; end; end\n" \
        "$RGSS_SCRIPTS = [[1, 'Main', '', 'if File.safe?(\"Data/Data_0.fpk\")\n  System.mount(\"Data/Data_0.fpk\", \".\", false)\nend']]\n" \
        "$stderr = File.open(File::NULL, 'w'); load #{COMPAT.inspect}\n" \
        "class StringInput; def initialize(s) = (@s = s); def each_byte = @s.each_char { |c| yield c }; end\n" \
        "r = []; StringInput.new('A').each_byte { |b| r << b }; print AceCoreCompat::Ruby18Strings.active.inspect, ' ', r.inspect"
check 'mkxp-z game (System.mount in Main): Ruby 3 behaviour, StringInput untouched', IO.popen([RbConfig.ruby, '-e', probe], err: File::NULL, &:read), 'false ["A"]'
puts($fail ? 'SOME FAILED' : 'ALL PASSED')
exit($fail ? 1 : 0)
