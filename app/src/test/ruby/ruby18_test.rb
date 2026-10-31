# Tests the in-memory Ruby 1.8 syntax adaptation of RPG Maker XP scripts in acecore_compat.rb.
# Run: ruby app/src/test/ruby/ruby18_test.rb
require 'stringio'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
module AceCoreCompat; DISPLAY_W = 1; DISPLAY_H = 1; end
# Script list as mkxp-z leaves it for preload scripts: [id, name, compressed, decoded].
audio = <<~'S'
  def pbToneKind(x)
    case x
    when 0: # dialtone
      "dial"
    when 1: "busy"
    when Foo::Bar then "const"
    when :sym then "sym"
    when "a:b" then "str"
    end
  end
S
broken = "case 1\nwhen 1: foo(\nend\n"
modern = "x = 1 ? :a : :b\n"
$RGSS_SCRIPTS = [[1, 'AudioUtilities', '', audio.dup], [2, 'Broken', '', broken.dup], [3, 'Modern', '', modern.dup]]
$stderr = StringIO.new
load COMPAT
$fail = false
def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect[0, 120]} != #{want.inspect[0, 120]}"); $fail = true))
module Foo; Bar = 99; end
eval($RGSS_SCRIPTS[0][3])
check '"when 0:" adapted and runs', [pbToneKind(0), pbToneKind(1), pbToneKind(99), pbToneKind(:sym), pbToneKind('a:b')], %w[dial busy const sym str]
check 'a script that still does not compile is left unchanged', $RGSS_SCRIPTS[1][3], broken
check 'modern scripts are untouched', $RGSS_SCRIPTS[2][3], modern
check 'adaptation is logged', $stderr.string.include?('001:AudioUtilities') || $stderr.string.include?('000:AudioUtilities'), true
puts($fail ? 'SOME FAILED' : 'ALL PASSED')
exit($fail ? 1 : 0)
