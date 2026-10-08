"""Offline policy regressions using public signed test vectors, without signing credentials."""
import os
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
                   ROOT/'src/client/com/sylong/bluem/update/RecoveryPolicy.java',
                   ROOT/'src/tests/PolicySelfTest.java', ROOT/'src/tests/RecoveryPolicyTest.java'], log)
        build.run([build.jtool('java'), '-cp', str(out)+os.pathsep+str(jar), 'PolicySelfTest',
                   ROOT/'test-fixtures', ROOT/'assets/release-cert.der'], log)
        build.run([build.jtool('java'), '-cp', out, 'RecoveryPolicyTest'], log)
    print((ROOT/'build/policy-tests.log').read_text(encoding='utf-8'))

if __name__ == '__main__': main()
