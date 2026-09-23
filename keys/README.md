# keys/

## `obu_cert.pem` — the public OBU certificate (included)

`build.sh` wraps the APK into an `.epk` by RSA-encrypting to this **public** certificate, which the
head unit then decrypts with its matching private key (baked into the unit's firmware). Only the
**public** cert is needed to build a loadable `.epk`, so that's all that's here.

- Subject `CN=tt18002`, issuer `IT5.YGOMI.COM CA`, RSA-1024. It is a fleet/type-test certificate
  that ships in the InTouch firmware — it is **not** a personal secret, and it carries no private key.
- An `.epk` built with it only loads on head units running firmware that carries the **matching**
  OBU keypair (i.e. the same InTouch generation this project targets). Units with different firmware
  need their own cert.

## What is deliberately NOT here

- ❌ `obu_key.pem` — the **private** key. Not needed to build; withheld. (Without it,
  `epk_tool.py parse` / `selftest` won't run, but `build` works fine.)
- ❌ the firmware `obu.p12` and its password.

## Using a different unit's key

If your head unit runs different firmware, extract the public cert from **your own** `obu.p12`
and overwrite `obu_cert.pem`:

```
openssl pkcs12 -in obu.p12 -passin pass:<your-p12-password> -legacy -nokeys -clcerts > keys/obu_cert.pem
```

This is for loading software onto **a vehicle you own.**
