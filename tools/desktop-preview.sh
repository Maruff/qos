#!/data/data/com.termux/files/usr/bin/bash
# desktop-preview.sh: render qos Desktop's XFCE session, as a phone would show
# it, and take screenshots. Runs inside termux/termux-docker, so the packages
# are Termux's own; see .github/workflows/desktop_preview.yml.
#
#   desktop-preview.sh WIDTHxHEIGHT SCALE   (repository at /work, output to /out)
set -u
SIZE="${1:-2400x1080}"
SCALE="${2:-2}"
ASSETS=/work/app/src/main/assets/qos
OUT=/out

# XFCE, exactly as qos-desktop installs it, then the tools for the preview.
pkg install -y x11-repo
pkg update -y
pkg install -y xfce4 xfce4-terminal dbus
pkg install -y xorg-xrandr
dpkg -l | awk '/^ii/ {print $2, $3}' > "$OUT/packages.txt"
pkg install -y xorg-server-xvfb xdotool
command -v xfdesktop >/dev/null || { echo "xfdesktop is not part of xfce4's install" >> "$OUT/notes.txt"; pkg install -y xfdesktop; }

# The files qos copies at start (TermuxInstaller.installQosFiles).
install -D -m 700 "$ASSETS/qos-xsession" "$PREFIX/bin/qos-xsession"
install -D -m 644 "$ASSETS/wallpaper.jpg" "$PREFIX/share/backgrounds/qos/qos.jpg"
install -D -m 644 "$ASSETS/gtk-theme.css" "$PREFIX/share/themes/qos/gtk-3.0/gtk.css"
install -D -m 644 "$ASSETS/gtk-theme.index" "$PREFIX/share/themes/qos/index.theme"
install -D -m 644 "$ASSETS/qos-mark.png" "$PREFIX/share/pixmaps/qos.png"
install -D -m 644 "$ASSETS/qos-mark.png" "$HOME/.local/share/icons/hicolor/256x256/apps/org.xfce.about.png"
install -D -m 644 "$ASSETS/qos-mark.png" "$HOME/.local/share/icons/hicolor/256x256/apps/xfce4-logo.png"
install -D -m 644 "$ASSETS/xfce4-about.desktop" "$HOME/.local/share/applications/xfce4-about.desktop"

FB="$TMPDIR/fb"
mkdir -p "$FB"
Xvfb :1 -screen 0 "${SIZE}x24" -fbdir "$FB" -nolisten tcp > "$OUT/xvfb.log" 2>&1 &
export DISPLAY=:1
sleep 3

cat > "$TMPDIR/inside.sh" <<EOF
# The container has no getprop, so set the phone's scaling the way qos-xsession would.
mkdir -p "\$HOME/.qos"
xfconf-query -c xsettings -p /Gdk/WindowScalingFactor -n -t int -s $SCALE
[ "$SCALE" = 2 ] && xfconf-query -c xfwm4 -p /general/theme -n -t string -s Default-xhdpi
date > "\$HOME/.qos/desktop-defaults"

qos-xsession > "$OUT/session.log" 2>&1 &
sleep 45
shot() { cp "$FB/Xvfb_screen0" "$OUT/\$1.xwd"; }
shot 1-desktop

xfce4-popup-applicationsmenu; sleep 4; shot 2-menu; xdotool key Escape; sleep 1

thunar "\$HOME" > /dev/null 2>&1 &
sleep 6
xfce4-terminal --geometry 70x18 > /dev/null 2>&1 &
sleep 6; shot 3-windows

xfce4-about > /dev/null 2>&1 &
sleep 5; shot 4-about

xrandr --query > "$OUT/xrandr.txt" 2>&1
for c in xsettings xfce4-desktop xfce4-panel xfwm4; do
    echo "== \$c"; xfconf-query -c \$c -l -v
done > "$OUT/xfconf.txt" 2>&1
EOF
dbus-launch --exit-with-session bash "$TMPDIR/inside.sh"
ls -la "$OUT"
