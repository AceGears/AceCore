# Builds the engine's Ruby 1.8 String#[] (ci/patches/mkxp-z/binding/acearcade-ruby18.h, compiled into mkxp-z) as a
# Ruby extension and loads it, so the tests run the same C code as the device. Needs a C compiler and Ruby headers.
require 'tmpdir'
require 'rbconfig'
module EngineRuby18
  HEADER = File.expand_path('../../../../ci/patches/mkxp-z/binding/acearcade-ruby18.h', __dir__)
  def self.load!
    dir = Dir.mktmpdir('aa18')
    File.write(File.join(dir, 'aa18.c'), "#include #{HEADER.inspect}\nvoid Init_aa18(void) { aa18_define(); }\n")
    File.write(File.join(dir, 'extconf.rb'), "require 'mkmf'\ncreate_makefile('aa18')\n")
    ok = Dir.chdir(dir) { system(RbConfig.ruby, 'extconf.rb', out: File::NULL) && system('make', out: File::NULL) }
    raise "could not build #{HEADER}" unless ok
    require File.join(dir, "aa18.#{RbConfig::CONFIG['DLEXT']}")
  end
end
EngineRuby18.load!
