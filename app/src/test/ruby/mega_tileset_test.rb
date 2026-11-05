# A tileset taller than the GPU allows, in a game whose "MKXP compatibility" tilemap reads @tilesets / @divided
# (Pokémon HGSS: Sevii Islands): acecore_compat.rb cuts it into pieces the GPU can show.
# Run: ruby app/src/test/ruby/mega_tileset_test.rb
require 'stringio'
COMPAT = File.expand_path('../../main/assets/acecore/rgss/acecore_compat.rb', __dir__)
module AceCoreCompat; DISPLAY_W = 1; DISPLAY_H = 1; end
$RGSS_SCRIPTS = [[1, 'Main', '', 'mainFunction']]

# Stand-ins for mkxp-z: a GPU limit of 16384 and bitmaps that remember which rows were copied into them.
Rect = Struct.new(:x, :y, :width, :height) do
  def set(x, y, w, h) = (self.x, self.y, self.width, self.height = x, y, w, h)
end
class Bitmap
  attr_reader :width, :height, :copied_from
  def self.max_size = 16384
  def initialize(w, h) = (@width, @height, @disposed = w, h, false)
  def blt(x, y, src, rect) = (@copied_from = [src, rect.y, rect.height])
  def dispose = (@disposed = true)
  def disposed? = @disposed
  def mega? = height > Bitmap.max_size
end
class Sprite
  attr_reader :bitmap
  attr_accessor :src_rect
  def initialize = (@src_rect = Rect.new(0, 0, 0, 0))
  def bitmap=(b)
    raise 'Operation not supported for mega surfaces' if b&.mega?
    @bitmap = b
  end
end

log = StringIO.new
$stderr = log
load COMPAT
$stderr = STDERR
$fail = false
def check(n, got, want) = (got == want ? puts("ok   #{n}") : (puts("FAIL #{n}: #{got.inspect} != #{want.inspect}"); $fail = true))

# The game's tilemap, defined after the compat layer: first the Essentials class, then the MKXP compatibility script
# reopening it (same logic as the game's getRegularTile / MKXP_Kyu).
eval(<<~'GAME', TOPLEVEL_BINDING)
  class CustomTilemap
    def initialize = (@tileset = nil; @divided = false; @tilesets = []; @disposed = false)
    def tileset=(value)
      @tileset = value; @tilesetChanged = true
    end
    def dispose = (@disposed = true)
  end
  class CustomTilemap
    def getRegularTile(sprite, id)
      y = ((id - 384) >> 3) * 32
      if @divided
        tileset_id = (y / 8000.0).floor
        tileset_id = @tilesets.length - 1 if tileset_id == @tilesets.length
        tileset = @tilesets[tileset_id]
        y -= 8000 * tileset_id
      else
        tileset = @tileset
      end
      sprite.bitmap = tileset if sprite.bitmap != tileset
      sprite.src_rect.set(((id - 384) & 7) * 32, y, 32, 32)
    end
    def MKXP_Kyu(rect, id) = nil
  end
GAME

tall = Bitmap.new(256, 40_000)
map = CustomTilemap.new
map.tileset = tall
pieces = map.instance_variable_get(:@tilesets)
check 'tall tileset cut into 8000-row pieces', pieces.map(&:height), [8000, 8000, 8000, 8000, 8000]
check 'each piece copies its rows of the tileset', pieces.map { |p| p.copied_from[1] }, [0, 8000, 16_000, 24_000, 32_000]
check 'divided is on', map.instance_variable_get(:@divided), true
s = Sprite.new
tile = 384 + (20_000 / 32) * 8 + 3 # a tile at row 20000 of the tileset, column 3
ok = begin
  map.getRegularTile(s, tile)
  true
rescue RuntimeError
  false
end
check 'a tile deep in the tileset is shown without the mega-surface error', ok, true
check 'from the third piece', s.bitmap.equal?(pieces[2]), true
check 'at the same place in that piece', [s.src_rect.x, s.src_rect.y], [96, 20_000 - 16_000]

small = Bitmap.new(256, 4096)
map.tileset = small
check 'a normal tileset is used as it is', map.instance_variable_get(:@divided), false
check 'the old pieces are released', pieces.all?(&:disposed?), true
map.getRegularTile(s, 384)
check 'normal tileset shown directly', s.bitmap.equal?(small), true

map.tileset = Bitmap.new(256, 20_000)
more = map.instance_variable_get(:@tilesets)
map.dispose
check 'pieces released with the tilemap', more.all?(&:disposed?), true
check 'patched once', CustomTilemap.ancestors.count(AceCoreCompat::MegaTilesets::Tilemap), 1

# A CustomTilemap without the MKXP compatibility script is left alone.
eval("class OtherTilemap; def tileset=(v); @t = v; end; end", TOPLEVEL_BINDING)
AceCoreCompat::MegaTilesets.patch(OtherTilemap)
check 'tilemaps without @tilesets support untouched', OtherTilemap.ancestors.include?(AceCoreCompat::MegaTilesets::Tilemap), false
exit(1) if $fail
