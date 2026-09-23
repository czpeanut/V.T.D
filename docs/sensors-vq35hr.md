# Observed sensor inventory — Q50 3.5 Hybrid (V37, VQ35HR)

Measured on the car itself — a Taiwan-market Q50 3.5 Hybrid, on the InTouch lower display — first
by listing everything `SensorManager` reports, then by watching the live values on the road. It
supersedes the figures carried over from the 2018 Q60 Red Sport 400 (VR30DDTT) the original
project was developed on: the type numbers are the same, the meanings and scalings often are not.

The screens used to gather this are no longer in the app; they were scaffolding, and this file is
what they were for. `git log` has them if they are ever needed again.

**If you have a different Q50 or Q60, do not assume this table applies to your car.** Verify each
signal against your own gauges before building anything on it.

**39 sensors, types 12–50, contiguous with no gaps. Every one has vendor `Ygomi`.** There are
no standard Android sensors (no accelerometer, no gyro) — the whole list is the vehicle bus.

## The headline result: there is no HV battery signal

Nothing in the list carries `BATTERY`, `SOC`, `HV`, `CHARGE`, `MOTOR` or `HYBRID`. The hybrid
system's state of charge is **not exposed through the Android Sensor API** on this unit, even
though the factory InTouch energy-flow screen clearly has the data. It presumably stays in the
Linux/AV layer.

Type 26 `VS_ID_REGENERATION`, the signal actually named for it, holds a flat zero across a full
drive. Type 13 `ENGINE_RPM` is dead too.

**But the hybrid energy flow is on this bus, under another name.** Type 12
`VS_ID_EFFECTIVE_TORQUE` is the **electric motor's** torque, and it is **signed**:

| Sign | Meaning |
|---|---|
| **positive** | regenerative torque — the motor is acting as a generator, charging |
| **negative** | drive torque — the motor is propelling the car |

Cross-checked on-car against the factory energy display, which agrees on direction. This was
missed twice over: once by reading the name as engine torque, and once by treating the −400 it
holds at a standstill as a placeholder.

**The reading is provisional, and one measurement does not fit comfortably.** Over more driving:

| Condition | Value |
|---|---|
| stationary in D, on the brake | −400 |
| gentle acceleration | −200 to −300 |
| accelerator released (coasting **or** braking) | ≈ +1600 |
| peak seen | +1647.5 |

Driving should not ask the motor for **less** torque than creep does, and that is what the
middle row says. Two explanations survive:

1. **It really is motor torque.** On a parallel hybrid the engine carries the car above walking
   pace, so under light throttle the motor contributes little — possibly less than the creep it
   holds against the brake at a standstill. Counter-intuitive, but not contradictory. The
   accelerator has never been pushed past 39 % in any recorded drive, so the motor has never
   been asked for much.
2. **The Ygomi bridge is mis-scaling it.** Types 12, 13, 16 and 32 share the identical
   placeholder `max=879.0`, and type 13 in that same group is outright dead — this is visibly
   a signal group the bridge did not calibrate for this model.

What the figures *do* rule out is a simple offset. Shifting the scale by +400 to put the
standstill at zero puts drive and regeneration on the same side, ordered standstill <
accelerating < coasting, which is not a coherent physical quantity. **The sign change carries
real meaning**, whatever the magnitudes turn out to mean.

### Two measurements would settle it

Both are still unrecorded, and neither needs any new code:

- **P / N / D at a standstill.** Motor torque → P and N read near zero (no creep path), D reads
  −400. A fixed offset → all three read −400. This is a thirty-second test in a parking space
  and it is the decisive one.
- **Full throttle from a stop.** Motor torque → far past −400. If it stays around −300, reading
  1 is dead.

Until that is settled the signal is **off the dashboard**. It had a full column, scaled
independently at each end because the observed range is lopsided, and an uncalibrated number in
an unknown unit does not earn that much of a screen read at a glance while driving. The column
now carries the two pedals, which are calibrated and continuous. This file is where the finding
lives; if either measurement above comes back the way reading 1 predicts, the gauge is ten lines
of code away.

What is still missing is state of charge. Torque says which way the energy is flowing and how
hard, but nothing on this bus says how full the battery is.

## Confirmed on-car (measured, not inferred)

| Type | Result |
|---|---|
| 17 `VEHICLE_SPEED` | **raw value is km/h directly.** The original notes were right; the `max=655340` reading was not a clue to anything. |
| 25 `STEERING_ANGLE` | **raw value is degrees directly**, **signed: right positive, left negative**, roughly **±390 at full lock**, carrying one decimal. Note this is *not* the 0.1-degree unit the `resolution` field implies — 390 raw is 390°, matching the quick DAS rack. Fully calibrated; ready to use as-is. |
| 12 `EFFECTIVE_TORQUE` | **read as electric motor torque, signed** — positive regenerates, negative drives — but **provisional**: gentle acceleration reads −200 to −300, i.e. *less* than the −400 held at a standstill. See above for what would settle it. Unit unknown; the two ends are scaled independently from observation. |
| 26 `REGENERATION` | **flat zero.** Held 0.000 for min, max and current across a full 578 s drive with braking, having previously looked alive only because a constant zero counts events like any other value. Nothing in the Sensor API carries hybrid energy flow. |
| 13 `ENGINE_RPM` | **dead.** Stayed at 0.000 across repeated drives on a warm car, including a whole trip watched by an indicator that would have lit the moment it left zero. It never did. Nothing can be built on it, and the electric-drive inference that depended on it has been removed. |
| 23 `ACCELERATOR_PEDAL_POSITION` | peaked at **39.25** under ordinary throttle, which reads as a percentage despite the declared maximum of 1000. |
| 24 `BRAKE_PEDAL_POSITION` | 0 to **54** observed under normal braking, against a declared maximum of 90. |
| 26 `REGENERATION` (earlier note) | **live.** Delivers events continuously (n rose with every other signal); it simply reads a flat 0.000 while stationary with the engine off. An earlier note recording it as dead was a misreading — a constant zero is not an absent signal. Its behaviour under load is still unknown and is the one open hybrid question. |
| 28 `ECO_MODE` | **dead.** Declared in the inventory and never delivers an event, while every neighbouring signal counts up. |
| 24 `BRAKE_PEDAL_POSITION` | live; 10 to 68 observed over one brake application against a declared max of 90. |
| 20 / 21 G axes | raw value is **g directly** — −0.010 to −0.020 on type 21 at rest on a slight slope. `max=2000` is another placeholder. |

The distinction that matters: `n=` counts events. A signal showing `0.000  n=144` is being fed
and happens to be zero; a signal showing `no data yet` is never fed at all. Only the second is
dead.

## Platform: Android is a guest, not the system

`/proc/mounts` shows two systems at once. The host is a systemd Linux — `aufs` on `/var`,
cgroups, and the navigation application under `/home/naviwork`. Android 2.3 runs inside it,
with its own root on a read-only `tmpfs` and its partitions mounted from `/dev/mmcblk0p5`
(`/system`) and `/dev/mmcblk0p9` (`/data`). App Garage apps are guests of a guest, which is why
so little of the filesystem is reachable.

## Storage: writing works, retrieval is the problem

`getFilesDir()` (`/data/data/vtd.dashboard/files`) **is writable**. Nothing else found so
far is. On an unrooted Android 2.3 head unit with no file manager and no adb, that directory is
readable only by this app, so a recording written there cannot be carried indoors.

Recording therefore always targets internal storage — guaranteed present, needs no permission,
and cannot disappear mid-drive the way a USB stick pulled from the socket can — and a separate
EXPORT step copies the files out to whatever reachable location exists at that moment. The
probe re-runs on demand, so a stick plugged in after boot (or after the drive) is picked up.

### What the probe actually found, with a stick plugged in

| Path | fs | Verdict |
|---|---|---|
| `/data/data/vtd.dashboard/files` | ext4 | writable, **persistent**, unreachable from outside the app |
| `/mnt/sdcard` and below | **tmpfs** | writable, **RAM disk** — dies at power-off, a PC can never read it |
| `/data/system/tmp` | **tmpfs** | same |
| `/data/system/tmp/sdb1` | **vfat** | **this is the USB stick.** Mounted `rw`, but with `fmask=0022,dmask=0022` and owned by root, so this app may read it and cannot write to it |
| `/cache`, `/data`, `/data/local/tmp`, `/mnt/asec`, `/mnt/obb` | — | permission denied |

`externalStorageState` reports `removed`: there is no real external volume: `/mnt/sdcard` is
only a tmpfs placeholder wearing the name.

**So there is exactly one persistent writable location — the app's own private directory — and
it cannot be read by anything else on an unrooted unit.** Ranking candidate directories by the
words in their path was wrong for this reason: `/mnt/sdcard` scored highest on the strength of
the word "sdcard" while being RAM. Volatility is now read from the filesystem type.

### The consequence

**The screen is the only output channel this unit has.** The USB stick is readable but not
writable, so it can carry data *in* but not *out*. Recordings are worth keeping in the private
directory for on-device analysis, but any conclusion that has to leave the car has to leave it
through the display and a camera — which is what the min/max latch is for.

## `maximumRange` is only sometimes real

Types **12, 13, 16 and 32 all report `max=879.0`** — a shared placeholder, since 879 rpm is
obviously not the engine's ceiling. Do not calibrate against `max`.

Where it *is* self-consistent it can be trusted: types 36–39 report `max=63.75 res=0.25`, which
is exactly 255 × 0.25, confirming the TPMS raw value is already psi in quarter-psi steps.

`max=-0.0 res=-0.0` (types 22, 27, 28) means the platform filled in nothing, which is normal for
**enumerations** — type 22 `GEAR_POSITION` reports that and is confirmed working on-car
(P/R/N/D = 1/2/3/4, M1–M7 = 16–22). So `-0.0` is not evidence that a signal is dead.

## Full list

| Type | Name | max | res | Notes |
|---|---|---|---|---|
| 12 | `VS_ID_EFFECTIVE_TORQUE` | 879.0 | 1.0 | **motor torque, signed: + regen, − drive — provisional.** Unit unknown; max is a placeholder |
| 13 | `VS_ID_ENGINE_RPM` | 879.0 | 1.0 | **dead — 0.000 across repeated warm drives** |
| 14 | `VS_ID_ENGINE_COOLANT_TEMPERATURE` | 214.0 | 1.0 | °C direct |
| 15 | `VS_ID_ENGINE_OIL_TEMPERATURE` | 205.0 | 1.0 | reported −50 at rest on this car |
| 16 | `VS_ID_ENGINE_OIL_PRESSURE` | 879.0 | 1.0 | placeholder max; MPa on VR30 |
| 17 | `VS_ID_VEHICLE_SPEED` | 655340.0 | 1.0 | **confirmed km/h directly** — the max field meant nothing |
| 18 | `VS_ID_DISTANCE_TO_EMPTY` | 6553400.0 | 1.0 | not in the original notes |
| 19 | `VS_ID_FUEL_CONSUMPTION_FINE` | 2048000.0 | 0.001 | fine-grained instantaneous |
| 20 | `VS_ID_TRANSVERSAL_ACCELERATION` | 2000.0 | 1.0 | **raw is g directly**; max is a placeholder |
| 21 | `VS_ID_LONGITUDINAL_ACCELERATION` | 2000.0 | 1.0 | **raw is g directly** (−0.01 at rest on a slope) |
| 22 | `VS_ID_GEAR_POSITION` | −0.0 | −0.0 | enum, confirmed on-car |
| 23 | `VS_ID_ACCELERATOR_PEDAL_POSITION` | 1000.0 | 0.001 | **pedal, not throttle** — distinct on a hybrid |
| 24 | `VS_ID_BRAKE_PEDAL_POSITION` | 90.0 | 1.0 | **live**, 10–68 observed against a declared max of 90 |
| 25 | `VS_ID_STEERING_ANGLE` | 9000.0 | 0.1 | **confirmed: degrees, right +, left −, ±390 full lock, 1 decimal** |
| 26 | `VS_ID_REGENERATION` | 63500.0 | 1.0 | **dead** — flat 0.000 across a full drive. What it is named for lives in type 12 |
| 27 | `VS_ID_ILLUMI` | −0.0 | −0.0 | enum, day/night illumination |
| 28 | `VS_ID_ECO_MODE` | −0.0 | −0.0 | **dead — never delivers an event** |
| 29 | `VS_ID_FUEL_CONSUMPTION_HISTORY` | 200000.0 | 1.0 | |
| 30 | `VS_ID_AVERAGE_OF_FUEL_CONSUMPTION` | 99000.0 | 1.0 | |
| 31 | `VS_ID_MOMENT_FUEL_CONSUMPTION` | 200000.0 | 1.0 | should fall to zero under EV drive → engine on/off proxy |
| 32 | `VS_ID_ENGINE_POWER` | 879.0 | 1.0 | placeholder max; rpm×Nm on VR30 |
| 33 | `VS_ID_DISPLAY_STYLE` | 3.0 | 1.0 | enum |
| 34 | `VS_ID_FLAT_TIRE` | 15.0 | 1.0 | bit field (4 wheels) |
| 35 | `VS_ID_LOW_TIRE_PRESSURE` | 15.0 | 1.0 | bit field |
| 36 | `VS_ID_TIRE_PRESSURE_DATA_FR` | 63.75 | 0.25 | psi, 255 × 0.25 |
| 37 | `VS_ID_TIRE_PRESSURE_DATA_FL` | 63.75 | 0.25 | psi |
| 38 | `VS_ID_TIRE_PRESSURE_DATA_RR` | 63.75 | 0.25 | psi |
| 39 | `VS_ID_TIRE_PRESSURE_DATA_RL` | 63.75 | 0.25 | psi |
| 40 | `VS_ID_TRANSMITTER_STATUS` | 15.0 | 1.0 | TPMS sensor health |
| 41 | `VS_ID_SETTING_PRESSURE_FRONT` | 15.0 | 1.0 | target pressure |
| 42 | `VS_ID_SETTING_PRESSURE_REAR` | 15.0 | 1.0 | target pressure |
| 43 | `VS_ID_DISTANCETOTALIZER` | 999999.0 | 1.0 | odometer — **not** rpm |
| 44 | `VS_ID_FUEL_LOW` | 1.0 | 1.0 | boolean |
| 45 | `VS_ID_HAND_BRAKE` | 1.0 | 1.0 | boolean |
| 46 | `VS_ID_SELECT_PRESSURE_SUPPORT` | 1.0 or 3.0 | 1.0 | photos disagree; re-check |
| 47 | `VS_ID_RESET_TPMS` | 3.0 or 1.0 | 1.0 | photos disagree; re-check |
| 48 | `VS_ID_ICCExistance` | 1.0 | 1.0 | adaptive cruise fitted? |
| 49 | `VS_ID_MARKET` | 0.0 | 1.0 | region code |
| 50 | `VS_ID_IOP_ILL` | 1.0 | 0.0 | |

Rows 46/47 differ between two photographs of the same screen; the values above are whichever
the sharper frame showed. Re-read them on the unit before relying on either.

## Gear position while in D

Type 22 publishes the selector position, not the ratio: `P/R/N/D = 1/2/3/4`, with the
individual gears appearing only as `16..22` for `M1..M7` in manual mode. In D it reports a
bare `4` and nothing more, on the reference car.

Whether the hybrid differs is worth one look, which is why type 22 is now on the watcher's
panel: drive in D and see whether the number ever leaves 4. If it does not, the ratio is
simply not published, and the two ways to have it on screen are to use the manual gate or the
paddles (which already display correctly as M1–M7), or to infer it from the rpm-to-speed
ratio. That inference needs type 13, which this car is not reliably publishing, so it is
blocked until the rpm question is settled.

Note the car is a **7-speed**, so the manual range is M1–M7 rather than 1–5.

## Corrections to the original handover notes

- Types run 12–50, not 12–53.
- Type 23 is the **accelerator pedal position**, not throttle opening. On a hybrid these
  diverge: pedal travel with the engine off produces no throttle angle at all.
- Types 36–39 are FR/FL/RR/RL in that order, matching `GaugeView`'s existing constants.
- `maximumRange` cannot be used for calibration (see above). Type 17 settled this: its max
  reads 655340 and its raw value is plain km/h, so the field is not even a scaled bound.
- Type 25's raw value is degrees, not the 0.1-degree unit `resolution` suggests. `resolution`
  describes the precision of the number, not its unit.
