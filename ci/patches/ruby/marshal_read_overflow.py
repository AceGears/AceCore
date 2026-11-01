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

Second fix, same function family: Marshal.load(port) first asks the port for a String (to_str). Pokémon Flux's
StringInput has "alias to_str read", which returns a new String holding the whole file; that String is then the data
Marshal reads from, but nothing kept it alive: struct load_arg's mark function didn't mark src (or proc), and the
local variable was dead after "arg->src = port". A garbage collection during a large load (Flux's 2.5 MB message file)
freed it and Marshal went on reading from a recycled object, then called a method on garbage built from the message
text ("mon Cent", from "Pokémon Center": the crash address in Flux's reports). src and proc are now marked while a
load runs, and port/proc are kept on the stack until it ends.
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

sub('''static void
mark_load_arg(void *ptr)
{
    struct load_arg *p = ptr;
    if (!p->symbols)
        return;''', '''static void
mark_load_arg(void *ptr)
{
    struct load_arg *p = ptr;
    if (!p->symbols)
        return;
    /* AceArcade: the source (possibly a String made by port.to_str, referenced nowhere else) and the proc */
    rb_gc_mark(p->src);
    if (p->proc) rb_gc_mark(p->proc);''')

sub('''    v = r_object(arg);
    clear_load_arg(arg);
    RB_GC_GUARD(wrapper);''', '''    v = r_object(arg);
    clear_load_arg(arg);
    RB_GC_GUARD(wrapper);
    RB_GC_GUARD(port); /* AceArcade */
    RB_GC_GUARD(proc);''')

open(path, "w", encoding="utf-8").write(s)
print("patched", path)
