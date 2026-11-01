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
# Ruby 1.8 "retry" in iterators: restarts the whole iterator call.
retry_src = <<~'S'
  def pbLoadTries(list)
    $tries = 0
    out = []
    list.each do |x|   # comment with retry in it
      $tries += 1
      out << x
      retry if $tries == 2 # starts the each over: 1, 2, then 1, 2, 3
    end
    out
  end
  def pbLoopRetry
    n = 0
    loop { n += 1; retry if n < 3; break }
    n
  end
  def pbRescueRetry
    t = 0
    begin
      t += 1
      raise "x" if t < 2
    rescue
      retry
    end
    t
  end
  def pbForRetry
    seen = []; k = 0
    for i in 0...3
      k += 1; seen << i
      retry if k == 2
    end
    seen
  end
  def pbNoCatch
    r = []
    [1].each { begin; r << :in; retry if r.size < 2; rescue => e; r << :caught; end }
    r
  end
S
# Ruby 1.8 byte-range regexp (Pokémon Z, PSystem_Utilities: JSON strings without control characters).
bytes_src = <<~'S'
  def pbJsonString?(s)
    !!(s =~ /\A"[^"\\\n\r\x00-\x1f\x7f-\x9f]*"\z/)
  end
S
$RGSS_SCRIPTS = [[1, 'AudioUtilities', '', audio.dup], [2, 'Broken', '', broken.dup], [3, 'Modern', '', modern.dup],
                 [4, 'PScreen_Load', '', retry_src.dup], [5, 'PSystem_Utilities', '', bytes_src.dup]]
$stderr = StringIO.new
load COMPAT
$fail = false
def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect[0, 120]} != #{want.inspect[0, 120]}"); $fail = true))
module Foo; Bar = 99; end
eval($RGSS_SCRIPTS[0][3])
check '"when 0:" adapted and runs', [pbToneKind(0), pbToneKind(1), pbToneKind(99), pbToneKind(:sym), pbToneKind('a:b')], %w[dial busy const sym str]
check 'a script that still does not compile is left unchanged', $RGSS_SCRIPTS[1][3], broken
check 'modern scripts are untouched', $RGSS_SCRIPTS[2][3], modern
eval($RGSS_SCRIPTS[3][3], binding, '004:PScreen_Load')
check 'retry in each restarts the iteration (Ruby 1.8)', pbLoadTries([1, 2, 3]), [1, 2, 1, 2, 3]
check 'retry in loop', pbLoopRetry, 3
check 'retry in rescue unchanged', pbRescueRetry, 2
check 'retry in for restarts the loop', pbForRetry, [0, 1, 0, 1, 2]
check "game's rescue => e does not catch the restart", pbNoCatch, [:in, :in]
check 'line numbers kept', $RGSS_SCRIPTS[3][3].lines.size, retry_src.lines.size
eval($RGSS_SCRIPTS[4][3])
check 'byte-range regexp compiles and keeps its meaning', [pbJsonString?('"abc é"'), pbJsonString?("\"a\u0085b\""), pbJsonString?("\"a\nb\"")], [true, false, false]
# Ruby 1.8 weakref.rb pattern (Pokémon Z BitmapCache): Thread.critical around shared tables.
class OldWeakRef
  @@id_map = {}
  def initialize(obj) = __setobj__(obj)
  def __setobj__(obj)
    @__id = obj.__id__
    __old_status = Thread.critical
    begin
      Thread.critical = true
      @@id_map[@__id] = [] unless @@id_map[@__id]
    ensure
      Thread.critical = __old_status
    end
    @@id_map[@__id].push self.__id__
  end
  def __getobj__ = ObjectSpace._id2ref(@__id)
end
bmp = +"bitmap"
check 'Thread.critical starts false', Thread.critical, false
check '1.8 weakref with Thread.critical works', OldWeakRef.new(bmp).__getobj__.equal?(bmp), true
check 'Thread.critical released after the ensure', Thread.critical, false
Thread.critical = true; Thread.critical = true
check 'nested set keeps it', Thread.critical, true
other = Thread.new { Thread.critical = true; r = :got; Thread.critical = false; r }
sleep 0.05
check 'another thread waits while it is held', other.alive?, true
Thread.critical = false
check 'and gets it once released', other.value, :got
check 'logged', $stderr.string.include?('003:PScreen_Load (4 "retry" in an iterator)'), true
check 'adaptation is logged', $stderr.string.include?('001:AudioUtilities') || $stderr.string.include?('000:AudioUtilities'), true
# A finalizer (Essentials' BitmapCache WeakRef) runs in trap context, where Mutex#lock isn't allowed (Pokémon Z:
# "lock: can't be called from trap context").
trap_result = nil
Signal.trap('USR2') do
  trap_result = begin
    old = Thread.critical
    Thread.critical = true
    Thread.critical = old
    :ok
  rescue ThreadError => e
    e.message
  end
end
Process.kill('USR2', Process.pid)
sleep 0.2
check 'Thread.critical in trap context (finalizer)', trap_result, :ok
check 'lock free after the trap', Thread.critical, false
puts($fail ? 'SOME FAILED' : 'ALL PASSED')
exit($fail ? 1 : 0)
