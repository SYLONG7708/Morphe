"""Offline policy regressions using public signed test vectors, without signing credentials."""
import os
import tempfile
from pathlib import Path
import build

ROOT = Path(__file__).resolve().parent
def main():
    out = ROOT/'build/policy-tests'
    out.mkdir(parents=True, exist_ok=True)
    jar = ROOT/'tools/json-20250517.jar'
    with (ROOT/'build/policy-tests.log').open('wb') as log:
        build.run([build.jtool('javac'), '-encoding', 'UTF-8', '-cp', jar, '-d', out,
                   ROOT/'src/client/com/sylong/bluem/update/UpdatePolicy.java',
                   ROOT/'src/client/com/sylong/bluem/update/TlsCompatibility.java',
                   ROOT/'src/client/com/sylong/bluem/update/RecoveryPolicy.java',
                   ROOT/'src/client/com/sylong/bluem/update/PlaybackSignals.java',
                   ROOT/'src/client/com/sylong/bluem/update/ClientPolicy.java',
                   ROOT/'src/tests/ClientPolicyTest.java',
                   ROOT/'src/tests/UpdateTlsTest.java',
                   ROOT/'src/tests/TlsCompatibilityTest.java',
                   ROOT/'src/tests/TlsHandshakeTest.java',
                   ROOT/'src/tests/PolicySelfTest.java', ROOT/'src/tests/RecoveryPolicyTest.java',
                   ROOT/'src/tests/PlaybackSignalsTest.java'], log)
        build.run([build.jtool('java'), '-cp', str(out)+os.pathsep+str(jar), 'PolicySelfTest',
                   ROOT/'test-fixtures', ROOT/'assets/release-cert.der'], log)
        build.run([build.jtool('java'), '-cp', out, 'RecoveryPolicyTest'], log)
        build.run([build.jtool('java'), '-cp', out, 'PlaybackSignalsTest'], log)
        build.run([build.jtool('java'), '-cp', out, 'ClientPolicyTest'], log)
        build.run([build.jtool('java'), '-cp', str(out)+os.pathsep+str(jar), 'UpdateTlsTest'], log)
        build.run([build.jtool('java'), '-cp', str(out)+os.pathsep+str(jar), 'TlsCompatibilityTest'], log)
        # A disposable localhost certificate proves that fallback keeps certificate
        # and hostname validation. No production signing material is used.
        with tempfile.TemporaryDirectory(prefix='bluem-tls-regression-') as temporary:
            key = Path(temporary)/'localhost.p12'
            password = 'bluem-qa-only'
            build.run([build.jtool('keytool'), '-J-Duser.language=en', '-J-Duser.country=US',
                       '-genkeypair', '-alias', 'qa-localhost',
                       '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3',
                       '-storetype', 'PKCS12', '-keystore', key, '-storepass', password,
                       '-keypass', password, '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost'], log)
            build.run([build.jtool('java'), '-cp', out, 'TlsHandshakeTest', key, password], log)
    print((ROOT/'build/policy-tests.log').read_text(encoding='utf-8'))

if __name__ == '__main__': main()
