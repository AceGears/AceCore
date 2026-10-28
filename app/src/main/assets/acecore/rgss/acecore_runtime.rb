# AceCore runtime helper for mkxp-z (RPG Maker XP / VX / VX Ace, Pokémon Essentials).
#
# Loaded through mkxp-z's "preloadScript" option BEFORE the game's own scripts. AceCore prepends a header that
# defines AceCoreBoot::DIR (a private folder for this game), AceCoreBoot::LAUNCH_AT, AceCoreBoot::RGSS,
# AceCoreBoot::CHEATS and AceCoreBoot::FLAGS.
#
# What it does:
#   * writes "ready" when the game draws its first frame (launch statistics, launch-time measurement)
#   * writes "error" if the game stops with a Ruby exception
#   * when cheats are enabled: executes commands that AceCore's in-game menu writes to "cmd", reports the
#     result in "result" and the game state in "state"
#
# Rules: never raise into the game, never write into the game's folder, never touch save files directly.
# Cheats only call the game's own methods, after checking they exist (respond_to? / method_defined?), so a
# change only becomes permanent when the player saves in-game.

module AceCore
  DIR = AceCoreBoot::DIR
  POLL_FRAMES = 12
  @frame = 0
  @ready = false
  @flags = AceCoreBoot::FLAGS.dup
  @hooks_installed = false

  class << self
    attr_reader :flags

    def now_ms
      (Time.now.to_f * 1000).to_i
    end

    def write(name, text)
      path = File.join(DIR, name)
      tmp = path + ".tmp"
      File.open(tmp, "wb") { |f| f.write(text.to_s.dup.force_encoding("ASCII-8BIT")) }
      File.rename(tmp, path)
    rescue Exception
      nil
    end

    def log(text)
      File.open(File.join(DIR, "log"), "ab") { |f| f.write("#{now_ms} #{text}\n") }
    rescue Exception
      nil
    end

    # Called after every Graphics.update.
    def tick
      @frame += 1
      unless @ready
        @ready = true
        write("ready", "#{now_ms}\n")
      end
      install_hooks unless @hooks_installed
      poll if AceCoreBoot::CHEATS && (@frame % POLL_FRAMES) == 0
      Cheats.every_frame if AceCoreBoot::CHEATS
    rescue Exception => e
      log("tick: #{e.class}: #{e.message}")
    end

    def install_hooks
      return unless AceCoreBoot::CHEATS
      return unless defined?(::Game_Player) && defined?(::Game_Character)
      Cheats.install_hooks
      @hooks_installed = true
    end

    def poll
      path = File.join(DIR, "cmd")
      return unless File.exist?(path)
      text = File.open(path, "rb") { |f| f.read }.to_s.force_encoding("UTF-8")
      File.delete(path)
      results = []
      text.to_s.each_line do |line|
        line = line.chomp
        next if line.empty?
        id, verb, *args = line.split("\t")
        begin
          msg = Cheats.run(verb.to_s, args)
          results << "#{id}\tok\t#{clean(msg)}"
        rescue Exception => e
          results << "#{id}\terror\t#{clean(e.message)}"
        end
      end
      write("state", Cheats.state)
      write("result", results.join("\n") + "\n")
    end

    def clean(s)
      s.to_s.dup.force_encoding("UTF-8").scrub("?").gsub(/[\t\r\n]+/, " ")
    end

    def on_exit
      e = $!
      if e && !e.is_a?(SystemExit)
        where = (e.backtrace || []).first.to_s
        write("error", "#{e.class}: #{e.message}\n#{where}\n")
      end
    rescue Exception
      nil
    end
  end

  # ------------------------------------------------------------------------------------------------ cheats
  module Cheats
    STATS_NEW = [:HP, :ATTACK, :DEFENSE, :SPECIAL_ATTACK, :SPECIAL_DEFENSE, :SPEED]
    # Essentials v17/v18 order (PBStats): HP, Attack, Defense, Speed, Sp. Atk, Sp. Def
    STATS_OLD = [0, 1, 2, 4, 5, 3]
    STAT_NAMES = %w[HP Atk Def SpA SpD Spe]

    # Essentials v20+ sets the speed through move_speed= at the start of every step (set_movement_type), older
    # games and plain RPG Maker assign @move_speed directly before moving. Both paths are covered.
    module PlayerSpeed
      def update_move
        sp = AceCore.flags["walk_speed"].to_i
        if sp > 0
          if respond_to?(:move_speed=) then self.move_speed = sp else @move_speed = sp end
        end
        super
      end

      def move_speed=(val)
        sp = AceCore.flags["walk_speed"].to_i
        super(sp > 0 ? sp : val)
      end
    end

    module EncountersOffNew
      def encounter_triggered?(*args)
        return false if AceCore.flags["no_encounters"] == "1"
        super
      end
    end

    module EncountersOffOld
      def isEncounterPossibleHere?(*args)
        return false if AceCore.flags["no_encounters"] == "1"
        super
      end
    end

    class << self
      # ---------------------------------------------------------------- environment
      def essentials?
        defined?(::PokemonGlobalMetadata) || defined?(::PBSpecies) || defined?(::GameData) && defined?(::GameData::Species)
      end

      def new_api?
        defined?(::GameData) && defined?(::GameData::Item)
      end

      def essentials_version
        return ::Essentials::VERSION.to_s if defined?(::Essentials) && defined?(::Essentials::VERSION)
        return ::ESSENTIALS_VERSION.to_s if defined?(::ESSENTIALS_VERSION)
        return "17 or older" if defined?(::PBSpecies)
        nil
      end

      def player
        return $player if defined?($player) && $player
        return $Trainer if defined?($Trainer) && $Trainer
        nil
      end

      def bag
        return $bag if defined?($bag) && $bag
        return $PokemonBag if defined?($PokemonBag) && $PokemonBag
        nil
      end

      def party
        p = player
        p && p.respond_to?(:party) ? p.party : nil
      end

      def rgss
        AceCoreBoot::RGSS
      end

      def rpg_party
        return nil unless defined?($game_party) && $game_party
        return $game_party.members if $game_party.respond_to?(:members)
        return $game_party.actors if $game_party.respond_to?(:actors)
        nil
      end

      # ---------------------------------------------------------------- capabilities (what THIS game supports now)
      def caps
        c = []
        if essentials?
          pl = player
          c << "money" if pl && pl.respond_to?(:money=)
          b = bag
          c << "items" if b && (b.respond_to?(:add) || b.respond_to?(:pbStoreItem))
          c << "balls" if c.include?("items")
          pt = party
          if pt
            c << "party_heal" if pt.all? { |pk| pk.respond_to?(:heal) }
            c << "pkmn_edit"
            c << "pkmn_add" if defined?(pbAddPokemonSilent)
            c << "pkmn_to_pc" if defined?($PokemonStorage) && $PokemonStorage && pt.size > 1
          end
        elsif defined?($game_party) && $game_party
          c << "gold" if $game_party.respond_to?(:gain_gold)
          c << "rpg_items" if $game_party.respond_to?(:gain_item) && defined?($data_items) && $data_items
          c << "rpg_heal" if rpg_party
          c << "rpg_level" if rpg_party
        end
        c << "walk_speed" if defined?(::Game_Player) && ::Game_Player.ancestors.include?(PlayerSpeed)
        if encounters_hook_available?
          c << "no_encounters"
        end
        c
      end

      def encounters_hook_available?
        return true if defined?(::PokemonEncounters) && (::PokemonEncounters.ancestors.include?(EncountersOffNew) || ::PokemonEncounters.ancestors.include?(EncountersOffOld))
        !essentials? && defined?($game_system) && $game_system && $game_system.respond_to?(:encounter_disabled=)
      end

      def install_hooks
        unless ::Game_Player.ancestors.include?(PlayerSpeed)
          ::Game_Player.send(:prepend, PlayerSpeed) if ::Game_Character.method_defined?(:update_move) || ::Game_Character.private_method_defined?(:update_move)
        end
        if defined?(::PokemonEncounters)
          if ::PokemonEncounters.method_defined?(:encounter_triggered?)
            ::PokemonEncounters.send(:prepend, EncountersOffNew) unless ::PokemonEncounters.ancestors.include?(EncountersOffNew)
          elsif ::PokemonEncounters.method_defined?(:isEncounterPossibleHere?)
            ::PokemonEncounters.send(:prepend, EncountersOffOld) unless ::PokemonEncounters.ancestors.include?(EncountersOffOld)
          end
        end
      end

      def every_frame
        # RPG Maker's own switch is the most compatible way to stop random encounters in non-Essentials games.
        if !essentials? && defined?($game_system) && $game_system && $game_system.respond_to?(:encounter_disabled=)
          want = AceCore.flags["no_encounters"] == "1"
          if want
            $game_system.encounter_disabled = true
            @forced_encounters_off = true
          elsif @forced_encounters_off
            $game_system.encounter_disabled = false
            @forced_encounters_off = false
          end
        end
      end

      # ---------------------------------------------------------------- state for AceCore's menu
      def state
        out = []
        out << "rgss=#{rgss}"
        out << "kind=#{essentials? ? 'essentials' : 'rpgmaker'}"
        v = essentials_version
        out << "essentials_version=#{v}" if v
        out << "caps=#{caps.join(',')}"
        out << "flags=#{AceCore.flags.map { |k, val| "#{k}:#{val}" }.join(',')}"
        if essentials?
          pl = player
          out << "money=#{pl.money}" if pl && pl.respond_to?(:money)
          out << "money_max=#{money_max}"
          pt = party
          if pt
            pt.each_with_index { |pk, i| out << "pkmn#{i}=#{pkmn_line(pk)}" }
          end
        elsif defined?($game_party) && $game_party
          out << "gold=#{$game_party.gold}" if $game_party.respond_to?(:gold)
          (rpg_party || []).each_with_index do |a, i|
            out << "actor#{i}=#{AceCore.clean(a.name)}\t#{a.level}"
          end
        end
        out.join("\n") + "\n"
      end

      def money_max
        return ::Settings::MAX_MONEY if defined?(::Settings) && defined?(::Settings::MAX_MONEY)
        return ::MAX_MONEY if defined?(::MAX_MONEY)
        999_999
      end

      def pkmn_line(pk)
        species = pk.respond_to?(:speciesName) ? pk.speciesName : (defined?(::PBSpecies) ? ::PBSpecies.getName(pk.species) : pk.species.to_s)
        nature = nature_name(pk)
        ability = ability_name(pk)
        ivs = stat_values(pk, :iv)
        evs = stat_values(pk, :ev)
        shiny = pk.respond_to?(:shiny?) && pk.shiny? ? 1 : 0
        happiness = pk.respond_to?(:happiness) ? pk.happiness : ""
        egg = (pk.respond_to?(:egg?) && pk.egg?) || (pk.respond_to?(:isEgg?) && pk.isEgg?) ? 1 : 0
        [pk.name, species, pk.level, nature, ability, happiness, ivs.join(','), evs.join(','), shiny, egg].map { |x| AceCore.clean(x) }.join("\t")
      end

      def nature_name(pk)
        n = pk.nature
        return n.name if n.respond_to?(:name)
        return ::PBNatures.getName(n) if defined?(::PBNatures)
        n.to_s
      rescue Exception
        ""
      end

      def ability_name(pk)
        a = pk.ability
        return "" if a.nil?
        return a.name if a.respond_to?(:name)
        return ::PBAbilities.getName(a) if defined?(::PBAbilities)
        a.to_s
      rescue Exception
        ""
      end

      def stat_values(pk, which)
        arr = pk.send(which)
        if arr.is_a?(Hash)
          STATS_NEW.map { |s| arr[s] || 0 }
        else
          STATS_OLD.map { |i| arr[i] || 0 }
        end
      end

      def recalc(pk)
        if pk.respond_to?(:calc_stats) then pk.calc_stats
        elsif pk.respond_to?(:calcStats) then pk.calcStats
        end
      end

      # ---------------------------------------------------------------- commands
      def need(cap)
        raise "Not available in this game right now (load your save first?)" unless caps.include?(cap)
      end

      def int(s, min = nil, max = nil)
        v = Integer(s.to_s, 10)
        v = min if min && v < min
        v = max if max && v > max
        v
      rescue ArgumentError
        raise "Invalid number: #{s}"
      end

      def run(verb, a)
        case verb
        when "probe" then "ok"
        when "set" # persistent toggles: walk_speed 0..6, no_encounters 0/1
          key = a[0].to_s
          raise "Unknown setting #{key}" unless %w[walk_speed no_encounters].include?(key)
          AceCore.flags[key] = a[1].to_s
          "#{key} = #{a[1]}"
        when "money" then cheat_money(a)
        when "item" then give_item(a[0], int(a[1], 1, 999))
        when "items" then list_items
        when "party_heal" then heal_party
        when "pkmn" then edit_pkmn(int(a[0], 0), a[1].to_s, a[2..-1] || [])
        when "pkmn_add" then add_pkmn(a[0], int(a[1], 1, max_level))
        when "pkmn_to_pc" then to_pc(int(a[0], 0))
        when "natures" then list_natures
        when "species" then list_species
        when "gold" then cheat_gold(a)
        when "rpg_item" then rpg_give_item(int(a[0], 1), int(a[1], 1, 99))
        when "rpg_heal" then rpg_heal
        when "rpg_level" then rpg_level(int(a[0], 0), int(a[1], 1, 99))
        else raise "Unknown command #{verb}"
        end
      end

      def max_level
        return ::GameData::GrowthRate.max_level if defined?(::GameData::GrowthRate) && ::GameData::GrowthRate.respond_to?(:max_level)
        return ::Settings::MAXIMUM_LEVEL if defined?(::Settings) && defined?(::Settings::MAXIMUM_LEVEL)
        return ::MAXIMUM_LEVEL if defined?(::MAXIMUM_LEVEL)
        100
      end

      def cheat_money(a)
        need("money")
        pl = player
        v = a[0] == "add" ? pl.money + int(a[1]) : int(a[1])
        pl.money = [[v, 0].max, money_max].min
        "Money is now #{pl.money}"
      end

      def resolve_item(id)
        if new_api?
          sym = id.to_s.to_sym
          raise "Unknown item #{id}" unless ::GameData::Item.exists?(sym)
          sym
        else
          num = id.to_s =~ /\A\d+\z/ ? id.to_i : (defined?(::PBItems) && ::PBItems.const_defined?(id.to_s) ? ::PBItems.const_get(id.to_s) : nil)
          raise "Unknown item #{id}" unless num
          num
        end
      end

      def give_item(id, qty)
        need("items")
        item = resolve_item(id)
        b = bag
        ok = b.respond_to?(:add) ? b.add(item, qty) : b.pbStoreItem(item, qty)
        raise "The bag is full" unless ok
        "Added #{qty} × #{item_name(item)}"
      end

      def item_name(item)
        return ::GameData::Item.get(item).name if new_api?
        return ::PBItems.getName(item) if defined?(::PBItems)
        item.to_s
      end

      def list_items
        need("items")
        lines = []
        if new_api?
          ::GameData::Item.each do |it|
            ball = it.respond_to?(:is_poke_ball?) && it.is_poke_ball? ? 1 : 0
            lines << [it.id, AceCore.clean(it.name), it.pocket, ball].join("\t")
          end
        elsif defined?(::PBItems)
          ::PBItems.constants.each do |c|
            id = ::PBItems.const_get(c)
            next unless id.is_a?(Integer) && id > 0
            name = ::PBItems.getName(id) rescue nil
            next if name.nil? || name.empty?
            pocket = defined?(pbGetPocket) ? pbGetPocket(id) : 0
            ball = defined?(pbIsPokeBall?) && pbIsPokeBall?(id) ? 1 : 0
            lines << [c, AceCore.clean(name), pocket, ball].join("\t")
          end
        end
        AceCore.write("items", lines.join("\n") + "\n")
        "#{lines.size} items"
      end

      def heal_party
        need("party_heal")
        party.each(&:heal)
        "Party healed"
      end

      def pk_at(i)
        need("pkmn_edit")
        pk = party[i]
        raise "No Pokémon in slot #{i + 1}" unless pk
        pk
      end

      def edit_pkmn(i, what, a)
        pk = pk_at(i)
        case what
        when "level"
          lv = int(a[0], 1, max_level)
          raise "This game doesn't support changing levels" unless pk.respond_to?(:level=)
          pk.level = lv
          recalc(pk)
          "#{pk.name} is now level #{pk.level}"
        when "exp"
          raise "This game doesn't support changing experience" unless pk.respond_to?(:exp=)
          pk.exp = [pk.exp + int(a[0]), 0].max
          recalc(pk)
          "#{pk.name}: level #{pk.level}, #{pk.exp} EXP"
        when "iv", "ev"
          set_stats(pk, what.to_sym, a[0].to_s, what == "iv" ? int(a[1], 0, 31) : int(a[1], 0, 252))
          recalc(pk)
          "#{pk.name}: #{what.upcase}s updated"
        when "nature"
          set_nature(pk, a[0].to_s)
          recalc(pk)
          "#{pk.name}'s nature is now #{nature_name(pk)}"
        when "ability"
          idx = int(a[0], 0, 2)
          if pk.respond_to?(:ability_index=) then pk.ability_index = idx
          elsif pk.respond_to?(:setAbility) then pk.setAbility(idx)
          else raise "This game doesn't support changing abilities"
          end
          # Essentials v19+: a forced ability (@ability) would hide the index change.
          pk.instance_variable_set(:@ability, nil) if new_api? && pk.instance_variable_defined?(:@ability)
          "#{pk.name}'s ability is now #{ability_name(pk)}"
        when "happiness"
          raise "This game doesn't support friendship" unless pk.respond_to?(:happiness=)
          pk.happiness = int(a[0], 0, 255)
          "#{pk.name}'s friendship is now #{pk.happiness}"
        when "shiny"
          on = a[0] == "1"
          if pk.respond_to?(:shiny=) then pk.shiny = on
          elsif on && pk.respond_to?(:makeShiny) then pk.makeShiny
          elsif !on && pk.respond_to?(:makeNotShiny) then pk.makeNotShiny
          else raise "This game doesn't support changing shininess"
          end
          "#{pk.name} is #{on ? '' : 'not '}shiny"
        when "heal"
          pk.heal
          "#{pk.name} healed"
        else
          raise "Unknown Pokémon change #{what}"
        end
      end

      def set_stats(pk, which, stat, value)
        arr = pk.send(which)
        idx = stat == "all" ? (0..5).to_a : [int(stat, 0, 5)]
        if arr.is_a?(Hash)
          idx.each { |i| arr[STATS_NEW[i]] = value }
        else
          idx.each { |i| arr[STATS_OLD[i]] = value }
        end
        if which == :ev
          total = arr.is_a?(Hash) ? arr.values.sum : arr.sum
          raise "EVs can total at most 510 (now #{total}); lower some first" if total > 510 && !(defined?(::Settings) && defined?(::Settings::NO_EV_LIMIT))
        end
      end

      def set_nature(pk, id)
        if defined?(::GameData::Nature)
          sym = id.to_sym
          raise "Unknown nature #{id}" unless ::GameData::Nature.exists?(sym)
          if pk.respond_to?(:nature=) then pk.nature = sym else raise "This game doesn't support changing natures" end
        elsif defined?(::PBNatures)
          num = id =~ /\A\d+\z/ ? id.to_i : ::PBNatures.const_get(id)
          if pk.respond_to?(:setNature) then pk.setNature(num) else raise "This game doesn't support changing natures" end
        else
          raise "This game doesn't support changing natures"
        end
      end

      def list_natures
        need("pkmn_edit")
        lines = []
        if defined?(::GameData::Nature)
          ::GameData::Nature.each { |n| lines << "#{n.id}\t#{AceCore.clean(n.name)}" }
        elsif defined?(::PBNatures)
          (0...::PBNatures.getCount).each { |i| lines << "#{i}\t#{AceCore.clean(::PBNatures.getName(i))}" }
        end
        AceCore.write("natures", lines.join("\n") + "\n")
        "#{lines.size} natures"
      end

      def list_species
        need("pkmn_add")
        lines = []
        if defined?(::GameData::Species)
          ::GameData::Species.each_species { |s| lines << "#{s.id}\t#{AceCore.clean(s.name)}" } if ::GameData::Species.respond_to?(:each_species)
          ::GameData::Species.each { |s| lines << "#{s.id}\t#{AceCore.clean(s.name)}" if s.form == 0 } if lines.empty?
        elsif defined?(::PBSpecies)
          max = defined?(::PBSpecies.maxValue) ? ::PBSpecies.maxValue : 0
          (1..max).each { |i| n = ::PBSpecies.getName(i) rescue nil; lines << "#{i}\t#{AceCore.clean(n)}" if n && !n.empty? }
        end
        AceCore.write("species", lines.join("\n") + "\n")
        "#{lines.size} species"
      end

      def add_pkmn(species, level)
        need("pkmn_add")
        sp = if defined?(::GameData::Species)
          sym = species.to_s.to_sym
          raise "Unknown species #{species}" unless ::GameData::Species.exists?(sym)
          sym
        else
          species.to_s =~ /\A\d+\z/ ? species.to_i : ::PBSpecies.const_get(species.to_s)
        end
        ok = pbAddPokemonSilent(sp, level)
        raise "No space in the party or the PC" unless ok
        "Added a level #{level} Pokémon"
      end

      def to_pc(i)
        need("pkmn_to_pc")
        pk = pk_at(i)
        st = $PokemonStorage
        box = st.respond_to?(:pbStoreCaught) ? st.pbStoreCaught(pk) : -1
        raise "The PC is full" if box.nil? || box < 0
        party.delete_at(i)
        "#{pk.name} was sent to box #{box + 1}"
      end

      # ---------------------------------------------------------------- RPG Maker XP / VX / VX Ace
      def cheat_gold(a)
        need("gold")
        cur = $game_party.gold
        target = a[0] == "add" ? cur + int(a[1]) : int(a[1], 0)
        $game_party.gain_gold(target - cur)
        "Gold is now #{$game_party.gold}"
      end

      def rpg_give_item(id, qty)
        need("rpg_items")
        item = $data_items[id]
        raise "Unknown item #{id}" unless item
        if rgss == 1 then $game_party.gain_item(id, qty) else $game_party.gain_item(item, qty) end
        "Added #{qty} × #{item.name}"
      end

      def rpg_heal
        need("rpg_heal")
        rpg_party.each { |a| a.recover_all }
        "Party recovered"
      end

      def rpg_level(i, level)
        need("rpg_level")
        actor = rpg_party[i]
        raise "No party member #{i + 1}" unless actor
        if actor.respond_to?(:change_level) then actor.change_level(level, false)
        elsif actor.respond_to?(:level=) then actor.level = level
        else raise "This game doesn't support changing levels"
        end
        "#{actor.name} is now level #{actor.level}"
      end
    end
  end
end

class << Graphics
  alias_method :acecore_original_update, :update
  def update(*args)
    r = acecore_original_update(*args)
    AceCore.tick
    r
  end
end

at_exit { AceCore.on_exit }
