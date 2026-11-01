#!/usr/bin/env python3
"""AceArcade patch for Ruby 3.1.0 (applied by CI before compiling Ruby): Marshal.load from an object whose read(n)
returns more than n bytes no longer writes past its read buffer.

Marshal.load(io) with an object that is not a String reads ahead into a fixed BUFSIZ buffer (r_byte1_buffered,
r_bytes1_buffered) and copies whatever read(n) returned into it, trusting that IO#read(n) never returns more than n
bytes. Pokémon Essentials' StringInput#read(len) returns @string[@pos, len]: on a UTF-8 string that is len
*characters*, more bytes than len once the data contains "é" (Pokémon Flux's message file: "Pokémon Center"). The
copy then overran the heap buffer and the engine crashed later at an address made of that text ("mon Cent").

The buffer now grows to hold everything read() returned. The extra bytes are the data that follows in the stream
(the reader advanced past them), so keeping them loads the file correctly instead of crashing or losing data.
Readers that return at most n bytes behave exactly as before. An empty read is reported as "marshal data too short"
instead of underflowing the buffer length.
"""
import sys

path = sys.argv[1] + "/marshal.c"
s = open(path, encoding="utf-8").read()
if "AceArcade" in s:
    print("already patched"); sys.exit(0)

def sub(old, new):
    global s
    assert s.count(old) == 1, "pattern not found in marshal.c (review this patch for this Ruby version):\n" + old
    s = s.replace(old, new)

sub('''struct load_arg {
    VALUE src;
    char *buf;
    long buflen;
    long readable;''', '''struct load_arg {
    VALUE src;
    char *buf;
    long buflen;
    long readable;
    long bufsize; /* AceArcade: allocated size of buf (ci/patches/ruby/marshal_read_overflow.py) */''')

sub('''static unsigned char
r_byte1_buffered(struct load_arg *arg)
{''', '''/* AceArcade: make room for len bytes in the read-ahead buffer (read() may return more than it was asked for). */
static void
load_buf_reserve(struct load_arg *arg, long len)
{
    if (len > arg->bufsize) {
	arg->buf = xrealloc(arg->buf, len);
	arg->bufsize = len;
    }
}

static unsigned char
r_byte1_buffered(struct load_arg *arg)
{''')

sub('''	str = load_funcall(arg, arg->src, s_read, 1, &n);
	if (NIL_P(str)) too_short();
	StringValue(str);
	memcpy(arg->buf, RSTRING_PTR(str), RSTRING_LEN(str));''', '''	str = load_funcall(arg, arg->src, s_read, 1, &n);
	if (NIL_P(str)) too_short();
	StringValue(str);
	if (RSTRING_LEN(str) == 0) too_short();
	load_buf_reserve(arg, RSTRING_LEN(str));
	memcpy(arg->buf, RSTRING_PTR(str), RSTRING_LEN(str));''')

sub('''	    buflen = tmp_len - need_len;
	    memcpy(arg->buf, RSTRING_PTR(tmp)+need_len, buflen);''', '''	    buflen = tmp_len - need_len;
	    load_buf_reserve(arg, buflen);
	    memcpy(arg->buf, RSTRING_PTR(tmp)+need_len, buflen);''')

sub('''    if (NIL_P(v))
	arg->buf = xmalloc(BUFSIZ);
    else
	arg->buf = 0;''', '''    if (NIL_P(v)) {
	arg->buf = xmalloc(BUFSIZ);
	arg->bufsize = BUFSIZ;
    }
    else {
	arg->buf = 0;
	arg->bufsize = 0;
    }''')

open(path, "w", encoding="utf-8").write(s)
print("patched", path)
