// AceArcade (new file, included by binding-mri.cpp): Ruby 1.8 String#[] / String#[]= for RPG Maker XP game scripts,
// implemented in C.
//
// RPG Maker XP ran Ruby 1.8, where str[i] is the byte as an Integer and str[i] = n stores a byte. acecore_compat.rb
// decides when a game needs that (Ruby18Strings) and calls Kernel#__acearcade_ruby18_strings to install these.
// They have to be C methods: String#[] with a Regexp sets $~ ("if name[/Trainer\((\d+)\)/] then $~[1]"), and
// Ruby stores $~ in the frame of the method that ran the match. A replacement written in Ruby is a frame of its own,
// so the game's $~ stayed nil (Pokémon Xenoverse Game_Player:151, Pokémon Z "Event Size":69: undefined method `[]'
// for nil). A C method has no Ruby frame: the original method it calls sets $~ in the game's frame, as before.
//
// Only a single Integer index (and Integer value for []=) from game code is changed; the caller is the nearest
// Ruby-level frame (rb_sourcefile), and AceArcade's own scripts (file names containing "acecore_") keep Ruby 3
// behaviour. Every other form goes to the original method unchanged.
#pragma once
#include <ruby.h>
#include <string.h>

static ID aa18_orig_aref, aa18_orig_aset;
static long aa18_conversions = 0;
static int aa18_installed = 0;

static int aa18_game_caller(void)
{
	const char *f = rb_sourcefile();
	return f && !strstr(f, "acecore_");
}

static VALUE aa18_aref(int argc, VALUE *argv, VALUE self)
{
	if (argc == 1 && FIXNUM_P(argv[0]) && aa18_game_caller()) {
		long n = RSTRING_LEN(self), i = FIX2LONG(argv[0]);
		if (i < 0) i += n;
		aa18_conversions++;
		if (i < 0 || i >= n) return Qnil;
		return INT2FIX((unsigned char)RSTRING_PTR(self)[i]);
	}
	return rb_funcallv(self, aa18_orig_aref, argc, argv);
}

static VALUE aa18_aset(int argc, VALUE *argv, VALUE self)
{
	if (argc == 2 && FIXNUM_P(argv[0]) && FIXNUM_P(argv[1]) && aa18_game_caller()) {
		long n = RSTRING_LEN(self), i = FIX2LONG(argv[0]);
		if (i < 0) i += n;
		if (i < 0 || i >= n) rb_raise(rb_eIndexError, "index %ld out of string", FIX2LONG(argv[0]));
		rb_str_modify(self);
		RSTRING_PTR(self)[i] = (char)(FIX2LONG(argv[1]) & 0xFF);
		aa18_conversions++;
		return argv[1];
	}
	return rb_funcallv(self, aa18_orig_aset, argc, argv);
}

// Kernel#__acearcade_ruby18_strings -> true once installed.
static VALUE aa18_install(VALUE)
{
	if (!aa18_installed) {
		aa18_installed = 1;
		aa18_orig_aref = rb_intern("__acearcade_aref");
		aa18_orig_aset = rb_intern("__acearcade_aset");
		rb_define_alias(rb_cString, "__acearcade_aref", "[]");
		rb_define_alias(rb_cString, "__acearcade_aset", "[]=");
		rb_define_method(rb_cString, "[]", RUBY_METHOD_FUNC(aa18_aref), -1);
		rb_define_method(rb_cString, "[]=", RUBY_METHOD_FUNC(aa18_aset), -1);
	}
	return Qtrue;
}

// Kernel#__acearcade_ruby18_conversions -> number of byte reads/writes done for game code.
static VALUE aa18_count(VALUE)
{
	return LONG2NUM(aa18_conversions);
}

static void aa18_define(void)
{
	rb_define_private_method(rb_mKernel, "__acearcade_ruby18_strings", RUBY_METHOD_FUNC(aa18_install), 0);
	rb_define_private_method(rb_mKernel, "__acearcade_ruby18_conversions", RUBY_METHOD_FUNC(aa18_count), 0);
}
