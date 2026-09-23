#!/usr/bin/env python3
"""
epk_tool.py - build / parse / selftest for Infiniti "App Garage" EPK packages.

The Infiniti InTouch head unit's AppManager (com.connexis.appsmanager) loads
native Android apps from a USB stick only when they are wrapped in a ".epk" file.
This tool reimplements that format from the decompiled framework.jar
(com.connexis.ivi.utils.epk.v2.EpkUtil / AESUtil / RSAUtil) so a custom APK can
be wrapped into an .epk the head unit will decrypt and install.

The format has NO signature/MAC -- it is confidentiality only, keyed by an OBU
certificate that ships in the unit firmware. Building an EPK needs only the OBU
*public* cert, which IS included at ../keys/obu_cert.pem. parse/selftest need the
*private* key, which is NOT shipped. See README.md for the full write-up.

Usage:
  python epk_tool.py build  app.apk  app.epk            [--cert keys/obu_cert.pem] [--type 2]
  python epk_tool.py build  a.apk b.apk  bundle.epk     # multiple files -> one entry, N blocks
  python epk_tool.py parse  app.epk  outdir/            [--key  keys/obu_key.pem]  (private key)
  python epk_tool.py selftest                            # round-trip test (needs the private key)

Requires: cryptography  (pip install cryptography)
"""
import argparse, os, struct, sys, uuid
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from cryptography.x509 import load_pem_x509_certificate
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

MAGIC = b".epk"
VERSION = 2                                             # v2.0; device accepts v1/v2.0/v2.2
IV = bytes([0x01,0x23,0x45,0x67,0x89,0xab,0xcd,0xef]) + b"\x00"*8   # AESUtil._init, fixed
HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(HERE)                          # repo root (this tool lives in tools/)
DEF_CERT = os.path.join(_ROOT, "keys", "obu_cert.pem") # public cert, included
DEF_KEY  = os.path.join(_ROOT, "keys", "obu_key.pem")  # private key, NOT shipped

# ---- crypto helpers (mirror AESUtil / RSAUtil) ------------------------------
def _pkcs5_pad(b):   n = 16 - (len(b) % 16); return b + bytes([n])*n   # PKCS5: always 1..16
def _pkcs5_unpad(b): return b[:-b[-1]]
def _aes_key(dk20):  return dk20 + b"\x00"*(32-len(dk20))              # 20B dataKey -> AES-256

def _aes_enc(data, dk20):
    c = Cipher(algorithms.AES(_aes_key(dk20)), modes.CBC(IV)).encryptor()
    return c.update(_pkcs5_pad(data)) + c.finalize()

def _aes_dec(ct, dk20):
    d = Cipher(algorithms.AES(_aes_key(dk20)), modes.CBC(IV)).decryptor()
    return _pkcs5_unpad(d.update(ct) + d.finalize())

# ---- key loading ------------------------------------------------------------
def load_pub(path):
    return load_pem_x509_certificate(open(path,"rb").read()).public_key()

def load_priv(path):
    return load_pem_private_key(open(path,"rb").read(), password=None)

# ---- build (mirror v2.EpkUtil.buildEpk) -------------------------------------
def build_epk(in_paths, out_path, pub, payload_type=2):
    dk = uuid.uuid4().hex[:20].encode("ascii")          # 20-byte random data key (opaque to the device)
    enc_key = pub.encrypt(dk, padding.PKCS1v15())       # RSA/NONE/PKCS1Padding, pub key
    with open(out_path, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">H", VERSION))
        f.write(struct.pack(">H", payload_type))        # written; loader never validates it
        f.write(struct.pack(">H", len(in_paths)))       # blockCount
        f.write(struct.pack(">H", len(enc_key)))        # keySize (=128 for RSA-1024)
        f.write(enc_key + b"\x00"*(256-len(enc_key)))   # 256B key field, zero-padded
        for p in in_paths:
            name = os.path.basename(p).encode("ascii")
            if len(name) > 128: raise ValueError(f"block name >128 bytes: {name!r}")
            ct = _aes_enc(open(p,"rb").read(), dk)
            f.write(name + b"\x00"*(128-len(name)))      # 128B filename
            f.write(struct.pack(">i", len(ct)))          # int32 block length (actual ct len)
            f.write(ct)
    return dk

# ---- parse (mirror v2.EpkUtil.parseEpk) -------------------------------------
def parse_epk(in_path, out_dir, priv):
    os.makedirs(out_dir, exist_ok=True)
    blob = open(in_path, "rb").read(); off = 0; out = []
    while off < len(blob):
        if blob[off:off+4] != MAGIC: raise ValueError("bad magic")
        off += 4
        ver, ptype, bcount, ksize = struct.unpack(">HHHH", blob[off:off+8]); off += 8
        if ver != VERSION: raise ValueError(f"unsupported version {ver}")
        dk = priv.decrypt(blob[off:off+ksize], padding.PKCS1v15()); off += 256
        for _ in range(bcount):
            name = blob[off:off+128].rstrip(b"\x00").decode("ascii"); off += 128
            (blen,) = struct.unpack(">i", blob[off:off+4]); off += 4
            data = _aes_dec(blob[off:off+blen], dk); off += blen
            dst = os.path.join(out_dir, name); open(dst,"wb").write(data); out.append(dst)
    return out

# ---- cli --------------------------------------------------------------------
def cmd_build(a):
    pub = load_pub(a.cert)
    dk = build_epk(a.inputs, a.output, pub, a.type)
    print(f"built {a.output} ({os.path.getsize(a.output)} bytes) wrapping {len(a.inputs)} file(s); "
          f"payloadType={a.type}, dataKey={dk!r}")
    print("copy to the USB root and select it in AppManager on the head unit.")

def cmd_parse(a):
    outs = parse_epk(a.epk, a.outdir, load_priv(a.key))
    print("extracted:"); [print("  ", o) for o in outs]

def cmd_selftest(a):
    pub, priv = load_pub(a.cert), load_priv(a.key)
    work = os.path.join(HERE, "_selftest"); os.makedirs(work, exist_ok=True)
    src = os.path.join(work, "custom.apk")
    payload = b"PK\x03\x04" + b"forged-custom-app;" * 41 + b"end"     # len % 16 != 0
    open(src,"wb").write(payload)
    epk = os.path.join(work, "custom.epk")
    build_epk([src], epk, pub, payload_type=2)
    rec = open(parse_epk(epk, os.path.join(work,"out"), priv)[0],"rb").read()
    ok = rec == payload
    print(f"round-trip (build with pubkey -> parse with privkey) MATCH: {ok}")
    sys.exit(0 if ok else 1)

def main():
    ap = argparse.ArgumentParser(description="Infiniti App Garage EPK builder/parser")
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build", help="wrap file(s) into an .epk")
    b.add_argument("inputs", nargs="+", help="input file(s); last positional is the output .epk")
    b.add_argument("--cert", default=DEF_CERT); b.add_argument("--type", type=int, default=2)
    b.set_defaults(func=cmd_build)
    p = sub.add_parser("parse", help="decrypt an .epk")
    p.add_argument("epk"); p.add_argument("outdir"); p.add_argument("--key", default=DEF_KEY)
    p.set_defaults(func=cmd_parse)
    s = sub.add_parser("selftest", help="round-trip proof (needs the private key; not shipped)")
    s.add_argument("--cert", default=DEF_CERT); s.add_argument("--key", default=DEF_KEY)
    s.set_defaults(func=cmd_selftest)
    a = ap.parse_args()
    if a.cmd == "build":                 # split trailing output path off the inputs list
        *ins, outp = a.inputs
        if not ins: ap.error("build needs at least one input file plus an output .epk path")
        a.inputs, a.output = ins, outp
    a.func(a)

if __name__ == "__main__":
    main()
