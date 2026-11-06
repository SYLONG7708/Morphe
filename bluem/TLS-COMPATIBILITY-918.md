# Blue M 918: guarded TLS backend compatibility

Local repair based on the verified 21.23.492 release input. Version code
`2026100918`, update client revision `9`. Publication is a separate operation.

The YouTube socket guard may be registered before MicroG has completed provider
initialization. Its backend used an unqualified `SSLContext.getInstance`, which
can choose the same guard again and throw `Unable to find a default SSL provider`.

The patch recognizes both socket factory classes by their superclass and error
signature. It replaces only their backend context lookups. The existing callback,
key managers, trust managers and initialization remain in place. Normal provider
selection is unchanged unless it selects the guard recursively; the fallback then
uses an available platform provider. It does not remove providers, modify global
TLS defaults, trust every certificate, or skip hostname verification.

Both backend classes and exactly two rewrites are required. Build and bytecode
contract checks fail if an upstream APK changes this structure. This is deliberate:
a new upstream version must be inspected rather than silently losing the repair.
The updater uses the same platform selection and requires repair revision >= 9 for
future YouTube updates. Official MicroG updates remain independently available.

Regression commands (JDK 21 and project tools required):

```
python bluem/run_tests.py
python -m unittest discover -s bluem -p test_cloud.py
python -m unittest discover -s bluem -p test_recommended_policy.py
python bluem/build.py release --code 2026100918
```

The signed build reads signing material from the existing private configuration;
no credentials belong in this repository or a delivery archive. Release input
selection and hashes remain pinned in the project's selection manifest.

Verification on 2026-11-06:

- Update, recovery, client policy, provider isolation and real localhost TLS tests
  pass. Handshakes reject an untrusted certificate and a wrong hostname.
- In an isolated Android 13 process, the actual APK guard is deliberately left
  without its initialization callback. The old 916 APK reproduces the recursive
  provider error; 918's actual client and server factories succeed. This fault
  injection is external instrumentation and is absent from the release APK.
- The real UIS7865 Android 14 bench plays video; the owner confirmed speaker sound.
  Playback, seeking, fullscreen and cockpit media controls have separate evidence.
- Android emulator results and outstanding physical-device acceptance are listed
  in the accompanying delivery report. They do not establish UIS7870 acceptance.

The locally updated cloud publisher reserves the next public code as >= 2026100919.
Deploying or publishing those changes is not part of this local APK repair.
