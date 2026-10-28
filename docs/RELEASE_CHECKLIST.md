# Release checklist

- [ ] `./gradlew testDebugUnitTest` green
- [ ] Manual smoke test on a real ARM64 phone and a tablet: import MV, MZ, HTML5 and Construct games; play, pause, exit, relaunch; rotate during play; disconnect/reconnect a Bluetooth controller
- [ ] Verify saves persist across app restart and app update (install over the previous build)
- [ ] Large-font (200%) and TalkBack pass over Home, Details, Settings
- [ ] Revoke folder permission in system settings → game shows "Folder access lost" instead of crashing
- [ ] Room schema exported in `app/schemas`; any schema change has a Migration and a migration test
- [ ] `RuntimeRegistry` only marks runtimes as bundled when they actually ship
- [ ] Licence notices (About screen) list every bundled library; GPL decision recorded if native runtimes are added
- [ ] Release signing via `keystore.properties` / CI secrets; no credentials committed
- [ ] targetSdk meets the current Play requirement (36 as of Aug 2026)
- [ ] Play Console Data safety: no data collected or shared
- [ ] Play policy review: no downloaded executable code; native runtimes are bundled in the AAB
