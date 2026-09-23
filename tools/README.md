# tools/epk_tool.py — wrap the APK for the App Garage USB loader

The head unit's `AppManager` only loads apps from USB when they're wrapped in its `.epk` container.
`epk_tool.py` reimplements that format so a normal APK can be wrapped into an `.epk` the unit will
decrypt and install.

```
python tools/epk_tool.py build build/vtd.apk build/vtd.epk --cert keys/obu_cert.pem
python tools/epk_tool.py parse vtd.epk out/ --key keys/obu_key.pem     # verify
python tools/epk_tool.py selftest                                       # round-trip test
```
Requires `cryptography` (`pip install cryptography`).

## Keys

The `.epk` container is **encryption-only**. Building one needs the **public** OBU certificate,
which is included at [`keys/obu_cert.pem`](../keys/README.md) — so `build` works out of the box.
The **private** key is *not* included (it isn't needed to build); without it, `parse` and `selftest`
won't run, but `build` does. No firmware or p12 password is distributed.

If your head unit runs different firmware, replace `keys/obu_cert.pem` with the cert from your own
unit (see [`keys/README.md`](../keys/README.md)). This tooling is for loading software onto
**a vehicle you own**.

---

# The other tools here

| Tool | What it does |
|---|---|
| `preview.sh` / `Preview.java` | Renders the driving screen to PNG at the real 800×480, so layout can be judged without a car. A desktop port of `DashView`'s geometry — keep the two in step by hand. It models **layout only**, not Android `Paint` state. |
| `MakeCarAsset.java` | Turns an ordinary light-on-black line drawing into `assets/car.png`: brightness becomes opacity, JPEG haze is floored out, strokes are tinted, and the result is cropped and scaled. |
| `MakeIcon.java` | Generates the launcher icon, so the mark stays tied to the screen's own cyan, glow and chamfer. |

```
javac -d build/tools tools/MakeCarAsset.java && java -cp build/tools MakeCarAsset drawing.jpg assets/car.png
javac -d build/tools tools/MakeIcon.java     && java -cp build/tools MakeIcon 96 res/drawable-nodpi/ic_launcher.png
bash tools/preview.sh
```
