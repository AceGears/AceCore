//
//  encoding.h
//  mkxp-z
//
//  Created by ゾロアーク on 6/22/21.
//
//  AceCore patch (replaces mkxp-z-android-reworked@b668e08 src/util/encoding.h, applied by CI before building):
//  the original called iconv() without checking iconv_open(). uchardet 0.0.8 reports some charsets under names GNU
//  libiconv does not know (e.g. a UTF-8 "Pokémon …" Game.ini title is detected as "MAC-CENTRALEUROPE", libiconv only
//  knows "MACCENTRALEUROPE"), so iconv_open returned (iconv_t)-1 and iconv() dereferenced it: SIGSEGV at
//  0xffffffffffffffff on the SDL thread while reading Game.ini, before Ruby started.
//  Now: text that is already valid UTF-8 is returned unchanged; uchardet names are mapped to libiconv names; and a
//  charset libiconv still can't open raises the same Exception every caller already handles.
//

#ifndef encoding_h
#define encoding_h

#include <string>
#include <string.h>

#include "util/encoding.h"
#include <iconv.h>
#include <uchardet.h>
#include <errno.h>

namespace Encoding {

/* Strict UTF-8 validation (no overlongs, no surrogates, max U+10FFFF). */
static bool isValidUTF8(const std::string &s) {
    const unsigned char *p = (const unsigned char *)s.data();
    const unsigned char *end = p + s.size();
    while (p < end) {
        unsigned char c = *p;
        if (c < 0x80) { p++; continue; }
        int n; unsigned int cp;
        if (c >= 0xC2 && c <= 0xDF) { n = 1; cp = c & 0x1F; }
        else if (c >= 0xE0 && c <= 0xEF) { n = 2; cp = c & 0x0F; }
        else if (c >= 0xF0 && c <= 0xF4) { n = 3; cp = c & 0x07; }
        else return false;
        if (end - p <= n) return false;
        for (int i = 1; i <= n; i++) {
            if ((p[i] & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (p[i] & 0x3F);
        }
        if ((n == 2 && cp < 0x800) || (n == 3 && cp < 0x10000) || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))
            return false;
        p += n + 1;
    }
    return true;
}

static std::string getCharset(std::string &str) {
    uchardet_t ud = uchardet_new();
    uchardet_handle_data(ud, str.c_str(), str.length());
    uchardet_data_end(ud);
    
    std::string ret(uchardet_get_charset(ud));
    uchardet_delete(ud);
    
    if (ret.empty())
        throw Exception(Exception::MKXPError, "Could not detect string encoding", str.c_str());
    return ret;
}

/* uchardet charset name -> name GNU libiconv accepts (only where they differ). */
static const char *iconvName(const std::string &charset) {
    if (charset == "MAC-CENTRALEUROPE") return "MACCENTRALEUROPE";
    if (charset == "MAC-CYRILLIC") return "MACCYRILLIC";
    return charset.c_str();
}

static std::string convertString(std::string &str) {
    
    // Already UTF-8 (this includes plain ASCII): nothing to convert. uchardet can mislabel short UTF-8 strings.
    if (isValidUTF8(str))
        return std::string(str);
    
    std::string charset = getCharset(str);
    
    // Conversion doesn't need to happen if it's already UTF-8
    if (!strcmp(charset.c_str(), "UTF-8") || !strcmp(charset.c_str(), "ASCII")) {
        return std::string(str);
    }
    
    iconv_t cd = iconv_open("UTF-8", iconvName(charset));
    if (cd == (iconv_t)-1)
        throw Exception(Exception::MKXPError, "Unsupported string encoding (Guessed encoding: %s)", charset.c_str());
    
    size_t inLen = str.size();
    size_t outLen = inLen * 4;
    std::string buf(outLen, '\0');
    char *inPtr = const_cast<char*>(str.c_str());
    char *outPtr = const_cast<char*>(buf.c_str());
    
    errno = 0;
    size_t result = iconv(cd, &inPtr, &inLen, &outPtr, &outLen);
    
    iconv_close(cd);
    
    if (result != (size_t)-1 && errno == 0)
    {
        buf.resize(buf.size()-outLen);
    }
    else {
        throw Exception(Exception::MKXPError, "Unable to convert string (Guessed encoding: %s)", charset.c_str());
    }
    
    return buf;
}
}

#endif /* encoding_h */
