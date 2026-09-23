#!/usr/bin/env bash
# Render DashView's layout to PNG without a car or an emulator.
#
# tools/Preview.java is a desktop port of DashView's geometry. It exists because the head unit
# is the slowest possible place to discover that two labels overlap: catching that here takes
# seconds, and on the car it costs a trip. It caught five real collisions on the first run.
#
# Keep it in step with DashView by hand -- it mirrors the layout maths, not the code.
#
# It models layout, not Paint state. Android's drawBitmap multiplies by the paint's alpha and
# Java2D's drawImage takes no paint at all, so a stale paint colour is invisible here and very
# visible on the unit. That is how the car artwork came to be drawn at four percent opacity
# while every render looked correct.
#
# It picks up assets/car.png the same way the app does, so this is also how to check your own
# car drawing before taking it to the vehicle.
#
#   bash tools/preview.sh            # normal running state
#   bash tools/preview.sh warn       # redline, hot coolant, soft tyre, hard braking
#   bash tools/preview.sh ev         # electric drive, engine off, still warming up
#   bash tools/preview.sh normal en  # English labels
#
# It uses assets/dash.ttf for the readouts, the same file the app loads, so dropping a
# different TTF in there and re-running is how to compare display faces.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/preview
javac -encoding UTF-8 -d build/preview tools/Preview.java
FONT="$PWD/assets/dash.ttf"
[ -f "$FONT" ] || FONT=""
( cd build/preview && java Preview "${1:-normal}" "${2:-zh}" "$FONT" )
echo "-> build/preview/*.png"
