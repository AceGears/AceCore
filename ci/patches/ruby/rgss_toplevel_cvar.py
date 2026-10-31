#!/usr/bin/env python3
"""AceArcade patch for Ruby 3.1.0 (applied by CI before compiling Ruby): class variables at the top level, as in RGSS.

RPG Maker XP games were written for RGSS1's Ruby 1.8, where a class variable used outside any class body (for example
in a method defined at the top level, or in script code that RGSS evaluates at the top level) refers to Object's class
variable, with at most a warning. Ruby 2.7 still allowed it with a warning; Ruby 3.0 made it a RuntimeError ("class
variable access from toplevel"), which stops such games while their scripts load (Pokémon Xenoverse,
004_Win32API.rb:62). This restores the Ruby 1.8-2.7 behaviour (a warning in verbose mode only). Code written for
Ruby 3 never relied on the error, so its behaviour is unchanged.
"""
import sys

path = sys.argv[1] + "/vm_insnhelper.c"
s = open(path, encoding="utf-8").read()
if "AceArcade" in s:
    print("already patched"); sys.exit(0)
old = '''    if (top_level_raise && !CREF_NEXT(cref)) {
        rb_raise(rb_eRuntimeError, "class variable access from toplevel");
    }'''
new = '''    if (top_level_raise && !CREF_NEXT(cref)) {
        /* AceArcade: Ruby 1.8-2.7 behaviour for RGSS games (see ci/patches/ruby/rgss_toplevel_cvar.py) */
        rb_warning("class variable access from toplevel");
    }'''
assert s.count(old) == 1, "pattern not found in vm_insnhelper.c (review this patch for this Ruby version)"
open(path, "w", encoding="utf-8").write(s.replace(old, new))
print("patched", path)
