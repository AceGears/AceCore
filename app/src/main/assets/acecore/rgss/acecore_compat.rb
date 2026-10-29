# AceArcade engine compatibility layer for mkxp-z-android-reworked (loaded through mkxp-z's "preloadScript",
# before the game's own scripts, also when the game helper is off).
#
# The Android port of mkxp-z is older than the mkxp-z that PC games ship with (e.g. Pokémon Essentials v21.1).
# This file makes the Android engine match upstream mkxp-z where the port is behind, so the same game scripts run:
#   * loads Ruby's zlib extension at start-up, as upstream mkxp-z does (Essentials' PluginManager needs Zlib);
#   * adds the upstream mkxp-z functions the port lacks, each only if the engine doesn't define it already.
# AceArcade prepends a header defining AceCoreCompat::DISPLAY_W / DISPLAY_H (the device screen in landscape pixels).
# Never touches game files.

begin
  require 'zlib'
rescue LoadError => e
  ($stderr.puts("AceArcade: Ruby zlib extension unavailable: #{e.message}") rescue nil)
end

module AceCoreCompat
  module_function

  # Case-insensitive file lookup relative to the game folder (upstream checks through its asset file system, which
  # ignores case like Windows does).
  def exist?(path)
    path = path.to_s.tr('\\', '/')
    return true if File.exist?(path)
    dir = path.start_with?('/') ? '/' : '.'
    path.split('/').reject(&:empty?).each do |part|
      next if part == '.'
      entries = (Dir.entries(dir) rescue nil)
      return false unless entries
      match = entries.find { |e| e.casecmp?(part) }
      return false unless match
      dir = (dir == '/' ? "/#{match}" : File.join(dir, match))
    end
    File.exist?(dir)
  end

  # Same output shape as upstream's rapidcsv call: array of rows, each an array of strings; "," separator, quoted
  # fields with "" escapes and line breaks inside quotes; CR/LF line endings; rows padded to the widest row.
  def parse_csv(str)
    rows = []
    row = []
    field = +''
    quoted = false
    i = 0
    s = str.to_s
    n = s.length
    while i < n
      c = s[i]
      if quoted
        if c == '"'
          if s[i + 1] == '"'
            field << '"'
            i += 1
          else
            quoted = false
          end
        else
          field << c
        end
      else
        case c
        when '"' then quoted = true
        when ',' then row << field; field = +''
        when "\r" then nil
        when "\n" then row << field; rows << row; row = []; field = +''
        else field << c
        end
      end
      i += 1
    end
    row << field
    rows << row unless row.length == 1 && row[0].empty?
    width = rows.map(&:length).max || 0
    rows.each { |r| r.concat(Array.new(width - r.length, '')) }
    rows
  end
end

if defined?(Graphics)
  unless Graphics.respond_to?(:display_width)
    def Graphics.display_width; AceCoreCompat::DISPLAY_W; end
  end
  unless Graphics.respond_to?(:display_height)
    def Graphics.display_height; AceCoreCompat::DISPLAY_H; end
  end
end

if defined?(System)
  unless System.respond_to?(:file_exist?)
    def System.file_exist?(path); AceCoreCompat.exist?(path); end
  end
  unless System.respond_to?(:parse_csv)
    def System.parse_csv(str); AceCoreCompat.parse_csv(str); end
  end
  def System.is_bsd?; false; end unless System.respond_to?(:is_bsd?)
  def System.is_really_bsd?; false; end unless System.respond_to?(:is_really_bsd?)
end

if defined?(Audio)
  # Upstream keeps a separate volume per BGM track. The port has a single BGM channel and no such control, so the
  # value is remembered and reported back but does not change loudness (the volume passed to bgm_play still does).
  unless Audio.respond_to?(:bgm_volume)
    module AceCoreCompat
      @bgm_volumes = Hash.new(100)
      class << self; attr_reader :bgm_volumes; end
    end
    def Audio.bgm_volume(track = nil); AceCoreCompat.bgm_volumes[track]; end
    def Audio.bgm_set_volume(volume, track = nil); AceCoreCompat.bgm_volumes[track] = volume.to_i; nil; end
  end
end
