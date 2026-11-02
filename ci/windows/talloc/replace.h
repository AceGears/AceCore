/* Minimal stand-in for Samba's lib/replace/replace.h, enough to build talloc.c on Android (bionic). */
#ifndef ACEARCADE_TALLOC_REPLACE_H
#define ACEARCADE_TALLOC_REPLACE_H
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>
#include <limits.h>
#define HAVE_VA_COPY 1
#define HAVE_INTPTR_T 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_SYS_AUXV_H 1
#ifndef _PUBLIC_
#define _PUBLIC_ __attribute__((visibility("default")))
#endif
#ifndef _PRIVATE_
#define _PRIVATE_ __attribute__((visibility("hidden")))
#endif
#ifndef likely
#define likely(x) __builtin_expect(!!(x), 1)
#endif
#ifndef unlikely
#define unlikely(x) __builtin_expect(!!(x), 0)
#endif
#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
#ifndef MAX
#define MAX(a,b) ((a)>(b)?(a):(b))
#endif
#ifndef PRINTF_ATTRIBUTE
#define PRINTF_ATTRIBUTE(a1, a2) __attribute__ ((format (__printf__, a1, a2)))
#endif
#ifndef __STRING
#define __STRING(x) #x
#endif
#ifndef __STRINGSTRING
#define __STRINGSTRING(x) __STRING(x)
#endif
#ifndef __LINESTR__
#define __LINESTR__ __STRINGSTRING(__LINE__)
#endif
#ifndef __location__
#define __location__ __FILE__ ":" __LINESTR__
#endif
#ifndef discard_const
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#endif
#ifndef discard_const_p
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))
#endif
#ifndef _DEPRECATED_
#define _DEPRECATED_ __attribute__ ((deprecated))
#endif
#ifndef FALL_THROUGH
#define FALL_THROUGH __attribute__ ((fallthrough))
#endif
#endif
