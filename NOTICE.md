# AceCore licensing

AceCore is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License, version 3 or (at your option) any later version** (see `LICENSE`).

AceCore links the RGSS runtime (mkxp-z, GPL-2.0-or-later) and may in future link EasyRPG Player (GPL-3.0),
so the combined work is distributed under GPL-3.0-or-later. Anyone who receives an AceCore APK is entitled
to the complete corresponding source code, including the build scripts in `.github/workflows/` and the
exact revisions of all native dependencies they fetch.

The GPL covers AceCore's code. It does not by itself grant rights to use the AceCore name, logo and icon
(`app/src/main/res/drawable-nodpi/acecore_*`, launcher icons) as branding for other projects.

## Third-party components

| Component | Where | Licence |
|---|---|---|
| mkxp-z (RGSS runtime) via mkxp-z-android-reworked | native libs built by CI | GPL-2.0-or-later |
| mkxp-z's Ruby (mkxp-z-3.1 fork of CRuby) | native libs built by CI | Ruby licence / BSD-2-Clause |
| SDL2 2.26.3, SDL2_image, SDL2_ttf | native libs + `app/src/main/java/org/libsdl/app` | zlib |
| SDL_sound | native libs | zlib |
| OpenAL Soft | native libs | LGPL-2.0-or-later |
| PhysicsFS | native libs | zlib |
| libogg, libvorbis, libtheora | native libs | BSD-3-Clause |
| pixman | native libs | MIT |
| uchardet | native libs | MPL-1.1 / GPL-2.0+ / LGPL-2.1+ |
| GNU libiconv | native libs | LGPL-2.1-or-later |
| OpenSSL 1.1.1 | native libs | OpenSSL / SSLeay |
| Liberation Sans font | embedded in mkxp-z | SIL OFL 1.1 |
| WenQuanYi Micro Hei font | embedded in mkxp-z | Apache-2.0 or GPL-3.0 with font exception |
| Ruffle 0.7.1 | `app/src/main/assets/ruffle` | MIT OR Apache-2.0 |
| AndroidX, Kotlin, kotlinx, Coil, Material icons | Gradle dependencies | Apache-2.0 |
