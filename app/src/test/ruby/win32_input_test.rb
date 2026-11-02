# Essentials v16/v17 games (Pokémon Axon, Pokémon Ópalo) replace the engine's Input module with one that reads every
# key through user32!GetAsyncKeyState and never calls the engine's Input.update. The engine's raw_key_states snapshot
# is refreshed only by that Input.update, so those games must be answered from the live key state.
# Run: ruby app/src/test/ruby/win32_input_test.rb
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
class MiniFFI
  def initialize(dll, func, *a) = raise(RuntimeError, "Failed loading #{dll}: dlopen failed: library \"#{dll}\" not found")
end
Win32API = MiniFFI
module Graphics; @fc = 0; def self.width = 512; def self.height = 384; def self.update = (@fc += 1); def self.frame_count = @fc; end
module Input
  @live = Array.new(512, false)
  class << self; attr_reader :live; end
  def self.raw_key_states = Array.new(512, false)   # snapshot: never refreshed, the game never calls the engine's update
  def self.acearcade_key_states = @live.dup         # event-thread state (engine)
end
require 'stringio'; $stderr = StringIO.new
load COMPAT
$fail = false
def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect} != #{want.inspect}"); $fail = true))

# The Essentials v16/v17 PSystem_Controls pattern (as in Axon and Ópalo), condensed.
def pbSameThread(wnd)
  return false if wnd == 0
  processid = [0].pack('l')
  t = Win32API.new('kernel32', 'GetCurrentThreadId', '%w()', 'l').call
  Win32API.new('user32', 'GetWindowThreadProcessId', '%w(l p)', 'l').call(wnd, processid) == t
end
module Input
  C = 13; B = 12; DOWN = 2
  @GetKeyState = Win32API.new('user32', 'GetAsyncKeyState', 'i', 'i')
  @GetForegroundWindow = Win32API.new('user32', 'GetForegroundWindow', '', 'i')
  def self.getstate(key) = (@GetKeyState.call(key) & 0x8000) > 0
  def self.updateKeyState(i)
    gfw = pbSameThread(@GetForegroundWindow.call())
    unless @stateUpdated[i]
      newstate = getstate(i) && gfw
      @triggerstate[i] = (newstate && @keystate[i] == 0)
      @releasestate[i] = (!newstate && @keystate[i] > 0)
      @keystate[i] = newstate ? @keystate[i] + 1 : 0
      @stateUpdated[i] = true
    end
  end
  def self.update
    if @keystate
      256.times { |i| @stateUpdated[i] = false; updateKeyState(i) if @keystate[i] > 0 }
    else
      @stateUpdated = []; @keystate = []; @triggerstate = []; @releasestate = []
      256.times { |i| @stateUpdated[i] = true; @keystate[i] = getstate(i) ? 1 : 0; @triggerstate[i] = false; @releasestate[i] = false }
    end
  end
  def self.buttonToKey(b) = { C => [0x43, 0x0D, 0x20], B => [0x58, 0x1B], DOWN => [0x28] }[b] || []
  def self.trigger?(b) = buttonToKey(b).any? { |k| updateKeyState(k); @triggerstate[k] }
  def self.press?(b) = buttonToKey(b).any? { |k| updateKeyState(k); @keystate[k] > 0 }
  def self.release?(b) = buttonToKey(b).any? { |k| updateKeyState(k); @releasestate[k] }
end
frame = -> { sleep 0.003; Graphics.update; Input.update }
frame.call
check 'nothing held', [Input.press?(Input::C), Input.trigger?(Input::C)], [false, false]
Input.live[0x28] = true # on-screen A -> Enter (SDL_SCANCODE_RETURN)
frame.call
check 'Enter pressed: C triggered', [Input.trigger?(Input::C), Input.press?(Input::C)], [true, true]
frame.call
check 'Enter held: C pressed, not triggered again', [Input.trigger?(Input::C), Input.press?(Input::C)], [false, true]
Input.live[0x28] = false
frame.call
check 'Enter released', [Input.press?(Input::C), Input.release?(Input::C)], [false, true]
Input.live[0x51] = true # Down arrow
frame.call
check 'Down arrow held', Input.press?(Input::DOWN), true
check 'other buttons unaffected', Input.press?(Input::B), false
puts($fail ? 'SOME FAILED' : 'ALL PASSED')
exit($fail ? 1 : 0)
