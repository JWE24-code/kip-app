# Windows code-signing

## Current state — self-signed (decided 2026-08-28, issue #19)

Windows builds are Authenticode-signed in CI with a **self-signed** code-signing
certificate. This does **not** remove the SmartScreen "Windows protected your
PC / unknown publisher" prompt — a self-signed cert has no reputation and its
root isn't trusted. What it does give us:

- a **stable publisher identity** on `Kip.exe` and the bundled DLLs, so
  Add/Remove Programs and some corporate allow-listing behave better;
- **signature continuity** the in-app updater (#16) can pin — an update whose
  signature doesn't match the installed one can be refused;
- a zero-cost, zero-lead-time path while a real cert is sorted out.

The plan is to swap in a real certificate (OV/EV, Azure Trusted Signing, or
SignPath's free-for-OSS program) later. Nothing downstream changes — see below.

## How it works

`packaging/windows/build.ps1` signs every `.exe` / root `.dll` in
`out/Kip-win32-x64/` when `$env:KIP_SIGN_CMD` is set, appending each file path
to that command. In CI, the **"Prepare code-signing (Windows)"** step in
`.github/workflows/build.yml` builds `KIP_SIGN_CMD` from two repo secrets:

| secret | value |
|---|---|
| `KIP_SIGN_PFX_B64` | base64 of a PKCS#12 (`.pfx`) holding the cert + private key |
| `KIP_SIGN_PFX_PASSWORD` | the PFX password |

Empty on forks → the step is skipped and the build is unsigned, as before.

## Regenerating the self-signed cert

```sh
# 10-year self-signed code-signing cert
cat > cs.cnf <<'EOF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3
[dn]
CN = Joeri Weitmann
O  = Kip
[v3]
basicConstraints       = critical,CA:FALSE
keyUsage               = critical,digitalSignature
extendedKeyUsage       = critical,codeSigning
subjectKeyIdentifier   = hash
EOF

openssl req -x509 -newkey rsa:3072 -keyout cs.key -out cs.crt -days 3650 -nodes -config cs.cnf
PW=$(openssl rand -base64 18 | tr -d '/+=' | head -c 24)
openssl pkcs12 -export -out cs.pfx -inkey cs.key -in cs.crt -passout pass:"$PW" -name "Kip"

base64 -w0 cs.pfx | gh secret set KIP_SIGN_PFX_B64      --repo JWE24-code/kip-app
printf '%s' "$PW"  | gh secret set KIP_SIGN_PFX_PASSWORD --repo JWE24-code/kip-app
rm cs.key cs.crt cs.pfx cs.cnf   # keep nothing on disk
```

## Moving to a real certificate

Replace **only** the two secrets with the real cert's PFX + password — or, for
a provider with no exportable key (Azure Trusted Signing, SignPath), swap the
"Prepare code-signing" step for that provider's action so it produces a
`KIP_SIGN_CMD` (or signs `out/Kip-win32-x64/` directly). `build.ps1`, the
packaging, the updater, and the release flow are untouched.
