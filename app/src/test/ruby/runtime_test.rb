# Tests AceCore's mkxp-z runtime helper (app/src/main/assets/acecore/rgss/acecore_runtime.rb) against mock
# game environments that follow the real APIs of Pokémon Essentials v20/v21, Essentials v17/v18 and plain
# RPG Maker XP / VX Ace. Each environment runs in its own Ruby process.
#
#   ruby app/src/test/ruby/runtime_test.rb          (exit status 0 = all passed)
require "tmpdir"
require "rbconfig"

RUNTIME = File.expand_path("../../main/assets/acecore/rgss/acecore_runtime.rb", __dir__)

ENVS = {}

# --------------------------------------------------------------------------------------- shared mocks
COMMON = <<~'RUBY'
  module Kernel
    def load_data(f) "data:#{f}" end
    private :load_data
  end
  module Graphics
    @frames = 0
    def self.update; @frames += 1; end
    def self.frames; @frames; end
  end
  class Game_Character
    attr_reader :move_speed
    def initialize; @move_speed = 4; end
    def update; update_move; end
    def update_move; @used_speed = @move_speed; end
    attr_reader :used_speed
  end
  class Game_Player < Game_Character
    def update; @move_speed = 4; super; end   # games reset the speed every frame
  end
  def frames(n) n.times { Graphics.update } end
  def cmd(dir, *lines)
    File.write(File.join(dir, "cmd"), lines.join("\n") + "\n")
    frames(AceCore::POLL_FRAMES)
    res = File.read(File.join(dir, "result"), encoding: "UTF-8")
    File.delete(File.join(dir, "result"))
    res.lines.map { |l| l.chomp.split("\t", 3) }
  end
  def state(dir)
    File.read(File.join(dir, "state"), encoding: "UTF-8").lines.map(&:chomp).map { |l| l.split("=", 2) }.to_h
  end
  $failures = 0
  def check(name, cond)
    if cond then puts "  ok   #{name}" else puts "  FAIL #{name}"; $failures += 1 end
  end
RUBY

ENVS["essentials_v20"] = <<~'RUBY'
  module Essentials; VERSION = "20.1"; end
  module Settings; MAX_MONEY = 999_999; MAXIMUM_LEVEL = 100; end
  module GameData
    class Item
      attr_reader :id, :name, :pocket
      DATA = {}
      def initialize(id, name, pocket, ball) @id, @name, @pocket, @ball = id, name, pocket, ball end
      def is_poke_ball?; @ball; end
      def is_key_item?; @pocket == 8; end
      def self.add(*a) DATA[a[0]] = new(*a) end
      def self.exists?(id) DATA.key?(id) end
      def self.get(id) DATA.fetch(id) end
      def self.each(&b) DATA.values.each(&b) end
    end
    Item.add(:POTION, "Potion", 2, false)
    Item.add(:POKEBALL, "Poké Ball", 3, true)
    Item.add(:MASTERBALL, "Master Ball", 3, true)
    Item.add(:BICYCLE, "Bicycle", 8, false)
    class Nature
      attr_reader :id, :name
      DATA = {}
      def initialize(id, name) @id, @name = id, name end
      def self.exists?(id) DATA.key?(id) end
      def self.get(id) DATA.fetch(id) end
      def self.each(&b) DATA.values.each(&b) end
    end
    { HARDY: "Hardy", ADAMANT: "Adamant", TIMID: "Timid" }.each { |k, v| Nature::DATA[k] = Nature.new(k, v) }
    class Ability
      attr_reader :id, :name
      def initialize(id, name) @id, @name = id, name end
    end
    class Species
      attr_reader :id, :name, :form
      DATA = {}
      def initialize(id, name) @id, @name, @form = id, name, 0 end
      def self.exists?(id) DATA.key?(id) end
      def self.each_species(&b) DATA.values.each(&b) end
      def self.each(&b) DATA.values.each(&b) end
    end
    { PIKACHU: "Pikachu", EEVEE: "Eevee" }.each { |k, v| Species::DATA[k] = Species.new(k, v) }
    module GrowthRate; def self.max_level; 100; end; end
  end
  class Pokemon
    attr_accessor :name, :happiness, :exp, :iv, :ev, :ability_index, :shiny
    attr_reader :level, :species, :calc_count
    def initialize(species, level)
      @species, @name, @level, @happiness, @exp = species, species.to_s.capitalize, level, 70, level ** 3
      @iv = { HP: 1, ATTACK: 2, DEFENSE: 3, SPECIAL_ATTACK: 4, SPECIAL_DEFENSE: 5, SPEED: 6 }
      @ev = { HP: 0, ATTACK: 0, DEFENSE: 0, SPECIAL_ATTACK: 0, SPECIAL_DEFENSE: 0, SPEED: 0 }
      @nature = :HARDY; @ability_index = 0; @ability = :FORCED; @hp = 1; @calc_count = 0; @shiny = false
    end
    def speciesName; GameData::Species::DATA[@species].name; end
    def level=(v) @level = v; @exp = v ** 3 end
    def nature; GameData::Nature.get(@nature); end
    def nature=(v) @nature = v end
    def ability; GameData::Ability.new(@ability || :"ABILITY#{@ability_index}", (@ability || "Ability #{@ability_index}").to_s) end
    def shiny?; @shiny; end
    def egg?; false; end
    def heal; @hp = 99; end
    def hp; @hp; end
    def calc_stats; @calc_count += 1; end
  end
  class Player
    attr_accessor :money, :party
    def initialize; @money = 3000; @party = [Pokemon.new(:PIKACHU, 12), Pokemon.new(:EEVEE, 8)]; end
  end
  class Bag
    attr_reader :items
    def initialize; @items = Hash.new(0); end
    def add(item, qty) @items[item] += qty; true end
  end
  class PokemonEncounters
    def encounter_triggered?(*args) true end
  end
  class PokemonStorage
    attr_reader :stored
    def initialize; @stored = []; end
    def pbStoreCaught(pk) @stored << pk; 0 end
  end
  def pbAddPokemonSilent(sp, level) $player.party << Pokemon.new(sp, level); true end
  PokemonGlobalMetadata = Class.new
  # Essentials v21: speed is set through move_speed= by set_movement_type at the start of each step.
  class Game_Character
    def move_speed=(val) @move_speed = val; @move_time = 2.0 / (2**val) end
  end
  class Game_Player
    def update; super; end
    def update_move
      set_movement_type(:walking)
      super
    end
    def set_movement_type(t) self.move_speed = (t == :running ? 4 : 3) end
  end
RUBY

ENVS["essentials_v18"] = <<~'RUBY'
  ESSENTIALS_VERSION = "18.1"
  MAX_MONEY = 999_999
  module PBItems; POTION = 1; POKEBALL = 4; BICYCLE = 9; def self.getName(i) { 1 => "Potion", 4 => "Poké Ball", 9 => "Bicycle" }[i] end end
  module PBSpecies; PIKACHU = 25; def self.getName(i) { 25 => "Pikachu" }[i] end; def self.maxValue; 25; end end
  module PBNatures; HARDY = 0; ADAMANT = 3; def self.getName(i) %w[Hardy Lonely Brave Adamant][i] end; def self.getCount; 4; end end
  module PBAbilities; def self.getName(i) "Ability#{i}" end end
  def pbGetPocket(id) id == 4 ? 3 : (id == 9 ? 8 : 2) end
  def pbIsKeyItem?(id) id == 9 end
  def pbIsPokeBall?(id) id == 4 end
  class PokeBattle_Pokemon
    attr_accessor :name, :happiness, :exp, :iv, :ev
    attr_reader :level, :species, :nature, :ability
    def initialize(species, level, player = nil, with_moves = true)
      @species, @name, @level, @happiness, @exp = species, "Pika", level, 70, level ** 3
      @iv = [10, 11, 12, 13, 14, 15]; @ev = [0, 0, 0, 0, 0, 0]; @nature = 0; @ability = 0
    end
    def level=(v) @level = v end
    def setNature(n) @nature = n end
    def setAbility(i) @ability = i end
    def makeShiny; @shiny = true end
    def isShiny?; !!@shiny end
    def isEgg?; false end
    def heal; @healed = true end
    def calcStats; end
  end
  class PokeBattle_Trainer
    attr_accessor :money, :party
    def initialize; @money = 100; @party = [PokeBattle_Pokemon.new(25, 5)]; end
  end
  class PokemonBag
    attr_reader :stored
    def initialize; @stored = {}; end
    def pbStoreItem(item, qty) @stored[item] = qty; true end
  end
  class PokemonEncounters
    def isEncounterPossibleHere?; true end
  end
RUBY

ENVS["rpgmaker_xp"] = <<~'RUBY'
  module RPG; class Item; attr_reader :name; def initialize(n) @name = n end end end
  class Game_Actor
    attr_reader :name, :level
    def initialize(n) @name = n; @level = 3 end
    def level=(v) @level = v end
    def recover_all; @recovered = true end
    def recovered; @recovered end
  end
  class Game_Party
    attr_reader :gold, :actors, :items
    def initialize; @gold = 50; @actors = [Game_Actor.new("Aluxes")]; @items = Hash.new(0) end
    def gain_gold(n) @gold += n end
    def gain_item(id, n) @items[id] += n end
  end
  class Game_System; attr_accessor :encounter_disabled; end
  class Game_Player
    def initialize; super; @encounter_count = 20; end
    def step; @encounter_count -= 1; end
    attr_reader :encounter_count
  end
RUBY

# --------------------------------------------------------------------------------------- scenarios
SCENARIOS = {}

SCENARIOS["essentials_v20"] = <<~'RUBY'
  check("boot marker written when the helper loads", File.exist?(File.join(DIR, "boot")))
  check("load_data still works", load_data("Data/System.rxdata") == "data:Data/System.rxdata")
  check("stage shows what the game loads", File.read(File.join(DIR, "stage")).include?("Data/System.rxdata"))
  frames(1)
  check("ready marker written on first frame", File.exist?(File.join(DIR, "ready")))
  check("ready marker holds a millisecond timestamp", File.read(File.join(DIR, "ready")).to_i > 1_600_000_000_000)
  # Title screen: no player yet.
  r = cmd(DIR, "1\tmoney\tset\t5000")
  check("money refused before a save is loaded", r[0][1] == "error")
  s = state(DIR)
  check("no money capability on the title screen", !s["caps"].split(",").include?("money"))

  $player = Player.new; $bag = Bag.new; $PokemonStorage = PokemonStorage.new
  frames(1)
  r = cmd(DIR, "2\tprobe")
  s = state(DIR)
  caps = s["caps"].split(",")
  check("detects Essentials version 20.1", s["essentials_version"] == "20.1")
  check("capabilities after loading", (%w[money items balls party_heal pkmn_edit pkmn_add pkmn_to_pc walk_speed no_encounters] - caps).empty?)
  check("party exported", s["pkmn0"].split("\t")[1] == "Pikachu" && s["pkmn1"].split("\t")[2] == "8")

  r = cmd(DIR, "3\tmoney\tset\t5000000")
  check("money clamped to MAX_MONEY", $player.money == 999_999 && r[0][1] == "ok")
  r = cmd(DIR, "4\tmoney\tadd\t-1000000")
  check("money never negative", $player.money == 0)
  cmd(DIR, "5\titem\tMASTERBALL\t5")
  check("item added through Bag#add", $bag.items[:MASTERBALL] == 5)
  r = cmd(DIR, "6\titem\tNOPE\t1")
  check("unknown item reported as error", r[0][1] == "error")
  cmd(DIR, "7\titems")
  items = File.read(File.join(DIR, "items"), encoding: "UTF-8").lines.map { |l| l.chomp.split("\t") }
  check("item list includes balls flag", items.find { |i| i[0] == "POKEBALL" }[3] == "1")
  $bag.items.clear; $bag.items[:POTION] = 3
  r = cmd(DIR, "8\titems_all\t10")
  check("add all items: every regular item, existing quantity kept and added to", $bag.items[:POTION] == 13 && $bag.items[:MASTERBALL] == 10 && $bag.items[:POKEBALL] == 10)
  check("add all items: key items left out", !$bag.items.key?(:BICYCLE) && r[0][1] == "ok" && r[0][2].include?("key items left out"))

  pk = $player.party[0]
  cmd(DIR, "8\tpkmn\t0\tlevel\t55")
  check("level set and stats recalculated", pk.level == 55 && pk.calc_count > 0)
  cmd(DIR, "9\tpkmn\t0\tiv\tall\t31")
  check("all IVs set (hash API)", pk.iv.values.all? { |v| v == 31 })
  cmd(DIR, "10\tpkmn\t0\tev\t5\t252")
  check("Speed EV set (index 5 = Speed)", pk.ev[:SPEED] == 252)
  r = cmd(DIR, "11\tpkmn\t0\tev\tall\t252")
  check("EV total over 510 refused", r[0][1] == "error")
  cmd(DIR, "12\tpkmn\t0\tnature\tADAMANT")
  check("nature set", pk.nature.name == "Adamant")
  cmd(DIR, "13\tpkmn\t0\tability\t2")
  check("ability index set and forced ability cleared", pk.ability_index == 2 && pk.ability.name == "Ability 2")
  cmd(DIR, "14\tpkmn\t0\thappiness\t300")
  check("friendship clamped to 255", pk.happiness == 255)
  cmd(DIR, "15\tpkmn\t0\tshiny\t1")
  check("shiny set", pk.shiny? == true)
  cmd(DIR, "16\tparty_heal")
  check("party healed", $player.party.all? { |p| p.hp == 99 })
  orig = $player.party.dup
  cmd(DIR, "17\tpkmn_add\tEEVEE\t30")
  check("Pokémon added to the next slot (3rd)", $player.party.size == 3 && $player.party.last.level == 30)
  check("existing members are the same objects in the same slots", orig.each_with_index.all? { |x, k| $player.party[k].equal?(x) })
  cmd(DIR, "18\tpkmn_to_pc\t2")
  check("Pokémon sent to PC", $player.party.size == 2 && $PokemonStorage.stored.size == 1)
  $player.party.push(Pokemon.new(:PIKACHU, 3), Pokemon.new(:PIKACHU, 4))
  four = $player.party.dup
  cmd(DIR, "40\tpkmn_add\tPIKACHU\t5\tparty")
  check("4 → 5th slot", $player.party.size == 5 && $player.party[4].level == 5 && four.each_with_index.all? { |x, k| $player.party[k].equal?(x) })
  cmd(DIR, "41\tpkmn_add\tEEVEE\t6\tparty")
  check("5 → 6th slot", $player.party.size == 6 && $player.party[5].level == 6)
  six = $player.party.dup; stored = $PokemonStorage.stored.size
  probe = state(DIR) rescue nil
  r = cmd(DIR, "42\tpkmn_add\tEEVEE\t7\tparty")
  check("full party + Party: refused, nothing replaced", r[0][1] == "error" && $player.party.size == 6 && six.each_with_index.all? { |x, k| $player.party[k].equal?(x) } && $PokemonStorage.stored.size == stored)
  r = cmd(DIR, "43\tpkmn_add\tEEVEE\t7\tpc")
  check("full party + PC Storage: stored, party unchanged", r[0][1] == "ok" && $PokemonStorage.stored.size == stored + 1 && $PokemonStorage.stored.last.level == 7 && $player.party.size == 6)
  def ($PokemonStorage).pbStoreCaught(pk) -1 end
  r = cmd(DIR, "44\tpkmn_add\tEEVEE\t8\tpc")
  check("storage full: safe error, no data lost", r[0][1] == "error" && $player.party.size == 6 && $PokemonStorage.stored.size == stored + 1)
  r = cmd(DIR, "45\tpkmn_add\tNOT_A_SPECIES\t8\tpc")
  check("unsupported species: validation error", r[0][1] == "error")
  $player.party.pop(4)
  r = cmd(DIR, "19\tpkmn\t9\tlevel\t5")
  check("missing slot reported", r[0][1] == "error")

  gp = Game_Player.new
  frames(1)
  gp.update
  check("walk speed untouched when off", gp.used_speed == 3)
  cmd(DIR, "20\tset\twalk_speed\t6")
  gp.update
  check("walk speed hook applies every frame", gp.used_speed == 6)
  cmd(DIR, "21\tset\twalk_speed\t0")
  gp.update
  check("walk speed back to normal", gp.used_speed == 3)
  enc = PokemonEncounters.new
  check("encounters normal by default", enc.encounter_triggered?(:land) == true)
  cmd(DIR, "22\tset\tno_encounters\t1")
  check("wild encounters disabled", enc.encounter_triggered?(:land) == false)
  r = cmd(DIR, "23\tbogus")
  check("unknown command is an error, not a crash", r[0][1] == "error")
  File.write(File.join(DIR, "cmd"), "24\tpkmn\t0\tlevel\tabc\n25\tmoney\tset\t10\n")
  frames(AceCore::POLL_FRAMES)
  res = File.read(File.join(DIR, "result"), encoding: "UTF-8").lines
  check("one bad command doesn't stop the next", res[0].include?("error") && res[1].include?("ok") && $player.money == 10)
RUBY

SCENARIOS["essentials_v18"] = <<~'RUBY'
  $Trainer = PokeBattle_Trainer.new; $PokemonBag = PokemonBag.new
  frames(1)
  cmd(DIR, "1\tprobe")
  s = state(DIR)
  caps = s["caps"].split(",")
  check("detects Essentials 18.1", s["essentials_version"] == "18.1")
  check("v18 capabilities", (%w[money items pkmn_edit no_encounters walk_speed] - caps).empty?)
  check("v18 offers pkmn_add (PokeBattle_Pokemon)", caps.include?("pkmn_add"))
  first = $Trainer.party[0]
  r = cmd(DIR, "50\tpkmn_add\t25\t7\tparty")
  check("v18 add appends, first member untouched", $Trainer.party.size == 2 && $Trainer.party[0].equal?(first) && $Trainer.party[1].level == 7 && r[0][1] == "ok")
  r = cmd(DIR, "51\tpkmn_add\tMISSINGNO\t5\tparty")
  check("v18 unknown species: safe error, party unchanged", r[0][1] == "error" && $Trainer.party.size == 2)
  $Trainer.party.pop
  cmd(DIR, "2\tmoney\tset\t1234")
  check("v18 money", $Trainer.money == 1234)
  cmd(DIR, "3\titem\tPOKEBALL\t7")
  check("v18 item by constant name via pbStoreItem", $PokemonBag.stored[4] == 7)
  r = cmd(DIR, "3b\titems_all\t99")
  check("v18 add all items without key items", $PokemonBag.stored[1] == 99 && $PokemonBag.stored[4] == 99 && !$PokemonBag.stored.key?(9) && r[0][1] == "ok")
  pk = $Trainer.party[0]
  cmd(DIR, "4\tpkmn\t0\tiv\t3\t31")
  check("v18 Sp. Atk IV maps to PBStats index 4", pk.iv[4] == 31 && pk.iv[3] == 13)
  cmd(DIR, "5\tpkmn\t0\tiv\t5\t30")
  check("v18 Speed IV maps to PBStats index 3", pk.iv[3] == 30)
  cmd(DIR, "6\tpkmn\t0\tnature\tADAMANT")
  check("v18 nature via setNature", pk.nature == 3)
  cmd(DIR, "7\tpkmn\t0\tshiny\t1")
  check("v18 shiny via makeShiny", pk.isShiny? == true)
  cmd(DIR, "8\tpkmn\t0\tability\t1")
  check("v18 ability via setAbility", pk.ability == 1)
  cmd(DIR, "9\tnatures")
  check("v18 nature list", File.read(File.join(DIR, "natures"), encoding: "UTF-8").include?("3\tAdamant"))
  enc = PokemonEncounters.new
  cmd(DIR, "10\tset\tno_encounters\t1")
  check("v18 encounters disabled", enc.isEncounterPossibleHere? == false)
RUBY

SCENARIOS["rpgmaker_xp"] = <<~'RUBY'
  $game_party = Game_Party.new; $game_system = Game_System.new; $data_items = [nil, RPG::Item.new("Potion")]
  $game_player = Game_Player.new
  frames(1)
  cmd(DIR, "1\tprobe")
  s = state(DIR)
  caps = s["caps"].split(",")
  check("RPG Maker capabilities", (%w[gold rpg_items rpg_heal rpg_level no_encounters walk_speed] - caps).empty?)
  check("no Pokémon capabilities", !caps.include?("pkmn_edit") && s["kind"] == "rpgmaker")
  cmd(DIR, "2\tgold\tset\t9000")
  check("gold set via gain_gold", $game_party.gold == 9000)
  # Game switches and variables, with the names from the game's database.
  sysdata = Struct.new(:switches, :variables).new([nil, "Met rival", "", "Gym 1"], [nil, "Steps", "Name"])
  $data_system = sysdata
  $game_switches = [nil, false, true, false]; $game_variables = [nil, 12, "Ash"]
  map = Struct.new(:need_refresh).new(false); $game_map = map
  cmd(DIR, "20\tprobe")
  check("switches/variables capability", (%w[switches variables] - state(DIR)["caps"].split(",")).empty?)
  r = cmd(DIR, "21\tswitches")
  rows = File.read(File.join(DIR, "switches")).lines.map { |l| l.chomp.split("\t") }
  check("switch list with names and states", r[0][1] == "ok" && rows == [["1", "1: Met rival  [OFF]"], ["2", "2: (unnamed)  [ON]"], ["3", "3: Gym 1  [OFF]"]])
  cmd(DIR, "22\tswitch\t3\ttoggle")
  check("switch toggled, map refreshed", $game_switches[3] == true && map.need_refresh)
  cmd(DIR, "23\tvariables")
  vrows = File.read(File.join(DIR, "variables")).lines.map { |l| l.chomp.split("\t") }
  check("variable list shows values", vrows[0] == ["1", "1: Steps = 12", "12"] && vrows[1][1] == "2: Name = (String)")
  cmd(DIR, "24\tvariable\t1\t500")
  check("variable set", $game_variables[1] == 500)
  r = cmd(DIR, "25\tvariable\t2\t5")
  check("text variable left unchanged", r[0][1] == "error" && $game_variables[2] == "Ash")
  cmd(DIR, "3\trpg_item\t1\t5")
  check("XP gain_item uses item id", $game_party.items[1] == 5)
  cmd(DIR, "4\trpg_heal")
  check("actors recovered", $game_party.actors.all?(&:recovered))
  cmd(DIR, "5\trpg_level\t0\t20")
  check("actor level", $game_party.actors[0].level == 20)
  cmd(DIR, "6\tset\tno_encounters\t1")
  15.times { $game_player.step }
  frames(1)
  check("encounter counter kept from running out", $game_player.encounter_count >= 100)
  check("saved switch untouched", $game_system.encounter_disabled.nil?)
  cmd(DIR, "7\tset\tno_encounters\t0")
  150.times { $game_player.step }
  frames(1)
  check("counter runs down again when off", $game_player.encounter_count < 0)
  gp = $game_player
  cmd(DIR, "8\tset\twalk_speed\t6")
  gp.update
  check("walk speed on plain RMXP (no move_speed=) doesn't crash", gp.used_speed == 6)
RUBY

SCENARIOS["error_capture"] = <<~'RUBY'
  frames(1)
  raise NameError, "uninitialized constant Win32API"
RUBY
ENVS["error_capture"] = ""

# Pokémon Xenoverse, 172_Ombra.rb:75 (undefined method `id' for nil): the report names the nil variable.
SCENARIOS["nil_state"] = <<~'RUBY'
  frames(1)
  eval(<<~'S', TOPLEVEL_BINDING, "172_Ombra.rb")
    class Sprite_Shadow
      def initialize(event) = (@event = event; @map = nil; @name = "shadow"; update)
      def update
        visible = true
        visible = !(@map.id == 1)
      end
    end
  S
  begin; Integer("x"); rescue ArgumentError; end # an earlier, rescued exception isn't reported
  begin
    Sprite_Shadow.new([1, 2])
  rescue NoMethodError => e
    raise e # games' main loops re-raise (Xenoverse 000:Main:16): the report keeps the original line
  end
RUBY
ENVS["nil_state"] = ""

SCENARIOS["cheats_off"] = <<~'RUBY'
  $player = Player.new; $bag = Bag.new
  frames(1)
  File.write(File.join(DIR, "cmd"), "1\tmoney\tset\t5\n")
  frames(AceCore::POLL_FRAMES * 2)
  check("commands ignored when cheats are off", $player.money == 3000 && File.exist?(File.join(DIR, "cmd")))
  check("ready marker still written", File.exist?(File.join(DIR, "ready")))
  gp = Game_Player.new
  gp.update
  check("no gameplay hooks when cheats are off", !Game_Player.ancestors.include?(AceCore::Cheats::PlayerSpeed))
RUBY
ENVS["cheats_off"] = ENVS["essentials_v20"]

total_fail = 0
SCENARIOS.each do |name, body|
  Dir.mktmpdir do |dir|
    cheats = name != "cheats_off"
    rgss = name == "rpgmaker_xp" || name.start_with?("essentials") ? 1 : 3
    header = "module AceCoreBoot; DIR = #{dir.inspect}; LAUNCH_AT = 0; RGSS = #{rgss}; CHEATS = #{cheats}; " \
             "FLAGS = { \"walk_speed\" => \"0\", \"no_encounters\" => \"0\" }; end\n"
    script = COMMON + ENVS[name] + header + File.read(RUNTIME, encoding: "UTF-8") + "\nDIR = #{dir.inspect}\n" + body +
             "\n$stdout.flush; exit!($failures == 0 ? 0 : 1) unless #{%w[error_capture nil_state].include?(name)}\n"
    path = File.join(dir, "test.rb")
    File.write(path, script, encoding: "UTF-8")
    puts "== #{name}"
    out = `#{RbConfig.ruby} #{path} 2>&1`
    ok = $?.success?
    if name == "error_capture"
      err = File.exist?(File.join(dir, "error")) ? File.read(File.join(dir, "error")) : ""
      good = !ok && err.start_with?("NameError: uninitialized constant Win32API")
      puts(good ? "  ok   game exception recorded in 'error'" : "  FAIL error capture (#{err.inspect})")
      total_fail += 1 unless good
    elsif name == "nil_state"
      err = File.exist?(File.join(dir, "error")) ? File.read(File.join(dir, "error")) : ""
      good = !ok && err.include?("undefined method `id' for nil") &&
             err.include?("State at 172_Ombra.rb:5 (self: #<Sprite_Shadow>)") && err.include?("  @map = nil\n") &&
             err.include?("  @event = Array (2 items)\n") && err.include?('  @name = "shadow"') && err.include?("  visible = true\n")
      puts(good ? "  ok   the report shows the variables at the failing line (@map = nil)" : "  FAIL raise state (#{err.inspect})")
      total_fail += 1 unless good
    else
      puts out
      total_fail += 1 unless ok
    end
  end
end
puts(total_fail == 0 ? "ALL PASSED" : "#{total_fail} SCENARIO(S) FAILED")
exit(total_fail == 0 ? 0 : 1)
