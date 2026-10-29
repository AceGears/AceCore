# Signing the desktop apps (no security warnings)

Windows and macOS warn about any app that isn't signed by a verified developer. This can't be
switched off from inside the app; it needs certificates that only you can buy, because they're
issued to a verified person or company. Once the GitHub secrets below are set, every desktop build
(`.github/workflows/desktop.yml`) signs the apps automatically. Without them the build still works
and prints a warning annotation.

## macOS: Developer ID + notarization (removes the "can't be opened" warning)

1. Join the Apple Developer Program (US$99 per year): https://developer.apple.com/programs/
2. In Xcode or on developer.apple.com, create a **Developer ID Application** certificate. Export it
   from Keychain Access as a `.p12` file with a password.
3. At https://appleid.apple.com, create an **app-specific password**.
4. Add these repository secrets (GitHub › Settings › Secrets and variables › Actions):
   | Secret | Value |
   |---|---|
   | `MACOS_CERT_P12` | `base64 -i cert.p12` output |
   | `MACOS_CERT_PASSWORD` | the .p12 password |
   | `APPLE_ID` | your Apple ID email |
   | `APPLE_TEAM_ID` | your 10-character Team ID |
   | `APPLE_APP_PASSWORD` | the app-specific password |

The build signs every program inside the app (including the bundled engines) with the hardened
runtime, sends it to Apple for notarization, staples the ticket and checks it with Gatekeeper. A
notarized app opens normally. macOS only shows its standard one-time confirmation for any app
downloaded from the internet.

## Windows: code-signing certificate (removes the SmartScreen block)

Option A, recommended: **Azure Trusted Signing** (Microsoft's service, about US$10 per month).
Certificates from it build SmartScreen reputation fastest. Add the secrets `AZURE_TENANT_ID`,
`AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `AZURE_SIGNING_ENDPOINT` (for example
`https://eus.codesigning.azure.net/`), `AZURE_SIGNING_ACCOUNT` and `AZURE_CERT_PROFILE`.

Option B: an OV or EV code-signing certificate from a certificate authority (DigiCert, Sectigo, …)
as a `.pfx` file. Add `WINDOWS_CERT_PFX` (base64 of the .pfx) and `WINDOWS_CERT_PASSWORD`.
CAs now usually issue certificates on hardware tokens or in the cloud. If yours can't be exported
as a .pfx, use option A.

Note: Microsoft SmartScreen also weighs reputation. A newly signed app can still show a softer
"unrecognized app" notice for its first downloads. The notice names you as the publisher, and it
goes away as people download and run the app.
