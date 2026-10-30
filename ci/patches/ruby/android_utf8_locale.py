#!/usr/bin/env python3
"""AceArcade patch for Ruby 3.1.0 as built by mkxp-z-android-reworked (applied by CI before compiling Ruby).

Problem (found from a device crash report, symbolized against this build):
  The port builds Ruby for android-23, where <langinfo.h>/nl_langinfo() don't exist, so Ruby compiles with
  NO_LOCALE_CHARMAP=1 and its locale and *filesystem* encodings are hard-wired to US-ASCII. mkxp-z puts the game
  folder into $LOAD_PATH as a UTF-8 string. For a folder such as ".../Pokémon Z", every require expands the load path
  and file_path_convert() converts that path to the filesystem encoding (US-ASCII). The UTF-8 -> US-ASCII converter
  is a transcoder that Ruby loads with require, which expands the load path again and converts the same path again:
  rb_feature_p -> rb_construct_expanded_load_path -> file_path_convert -> rb_str_conv_enc_opts -> rb_econv_open ->
  (transcoder require) -> rb_feature_p -> ... until the engine thread's stack is exhausted ("[BUG] Segmentation
  fault" in the very first require). Games in ASCII-only folders were unaffected.

Fix: Android file systems store UTF-8 names and Android's C library only supports UTF-8 (and ASCII) locales, so on
Android without nl_langinfo Ruby now reports UTF-8 for the locale charmap and the filesystem encoding (the same
choice Ruby makes on Cygwin). No conversion is needed for UTF-8 paths, and file names keep their real encoding.
"""
import sys

path = sys.argv[1] + "/localeinit.c"
s = open(path, encoding="utf-8").read()
if "AceArcade" in s:
    print("already patched"); sys.exit(0)

def rep(old, new):
    global s
    assert s.count(old) == 1, "pattern not found in localeinit.c (review this patch for this Ruby version): " + old
    s = s.replace(old, new)

rep('''#if NO_LOCALE_CHARMAP
    return rb_usascii_str_new_cstr("US-ASCII");''',
    '''#if NO_LOCALE_CHARMAP && defined __ANDROID__
    return rb_usascii_str_new_cstr("UTF-8"); /* AceArcade: Android locales are UTF-8 */
#elif NO_LOCALE_CHARMAP
    return rb_usascii_str_new_cstr("US-ASCII");''')
rep('''#if NO_LOCALE_CHARMAP
    return ENCINDEX_US_ASCII;
#else
    return (int)locale_charmap(enc_find_index);''',
    '''#if NO_LOCALE_CHARMAP && defined __ANDROID__
    return ENCINDEX_UTF_8; /* AceArcade: Android locales are UTF-8 */
#elif NO_LOCALE_CHARMAP
    return ENCINDEX_US_ASCII;
#else
    return (int)locale_charmap(enc_find_index);''')
rep('''#if NO_LOCALE_CHARMAP
    idx = ENCINDEX_US_ASCII;
#elif defined _WIN32''',
    '''#if NO_LOCALE_CHARMAP && defined __ANDROID__
    idx = ENCINDEX_UTF_8; /* AceArcade: Android file names are UTF-8 (see ci/patches/ruby) */
#elif NO_LOCALE_CHARMAP
    idx = ENCINDEX_US_ASCII;
#elif defined _WIN32''')
open(path, "w", encoding="utf-8").write(s)
print("patched", path)
