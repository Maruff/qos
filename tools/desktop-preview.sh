#!/data/data/com.termux/files/usr/bin/bash
# desktop-preview.sh: render qos Desktop's XFCE session, as a phone would show
# it, and take screenshots. Runs inside termux/termux-docker, so the packages
# are Termux's own; see .github/workflows/desktop_preview.yml.
#
#   desktop-preview.sh WIDTHxHEIGHT SCALE [LANGUAGE]   (repository at /work, output to /out)
set -u
SIZE="${1:-2400x1080}"
SCALE="${2:-2}"
LANGUAGE_SETTING="${3:-}"
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
install -D -m 700 "$ASSETS/qos-open" "$PREFIX/bin/qos-open"
install -D -m 644 "$ASSETS/wallpaper.jpg" "$PREFIX/share/backgrounds/qos/qos.jpg"
install -D -m 644 "$ASSETS/gtk-theme.css" "$PREFIX/share/themes/qos/gtk-3.0/gtk.css"
install -D -m 644 "$ASSETS/gtk-theme.index" "$PREFIX/share/themes/qos/index.theme"
install -D -m 644 "$ASSETS/qos-mark.png" "$PREFIX/share/pixmaps/qos.png"
install -D -m 644 "$ASSETS/qos-mark.png" "$HOME/.local/share/icons/hicolor/256x256/apps/org.xfce.about.png"
install -D -m 644 "$ASSETS/qos-mark.png" "$HOME/.local/share/icons/hicolor/256x256/apps/xfce4-logo.png"
install -D -m 644 "$ASSETS/xfce4-about.desktop" "$HOME/.local/share/applications/xfce4-about.desktop"

# The Android menu as qos writes it (QosApps.syncDesktopMenu), for three
# sample apps; the container has no Android apps of its own.
install -D -m 644 /dev/stdin "$HOME/.config/menus/applications-merged/qos-android.menu" <<'MENU'
<!DOCTYPE Menu PUBLIC "-//freedesktop//DTD Menu 1.0//EN"
  "http://www.freedesktop.org/standards/menu-spec/1.0/menu.dtd">
<Menu>
  <Name>Xfce</Name>
  <Menu>
    <Name>Android</Name>
    <Directory>qos-android.directory</Directory>
    <Include><Category>X-Qos-Android</Category></Include>
  </Menu>
</Menu>
MENU
printf '[Desktop Entry]
Version=1.0
Type=Directory
Name=Android
Name[ta]=ஆண்ட்ராய்டு
Icon=%s
'     "$PREFIX/share/pixmaps/qos.png" | install -D -m 644 /dev/stdin "$HOME/.local/share/desktop-directories/qos-android.directory"
for app in "Camera:com.android.camera2" "Chrome:com.android.chrome" "Files:com.google.android.documentsui"; do
    printf '[Desktop Entry]
Version=1.0
Type=Application
Name=%s
Exec=qos-open %s
Icon=%s
Terminal=false
NoDisplay=false
Categories=X-Qos-Android;
'         "${app%%:*}" "${app#*:}" "$PREFIX/share/pixmaps/qos.png" > "$HOME/.local/share/applications/qos-android-${app#*:}.desktop"
done

{ ls -laR "$HOME/.config/menus" "$HOME/.local/share/desktop-directories"; ls -la "$HOME/.local/share/applications"
  cat "$HOME/.config/menus/applications-merged/qos-android.menu" "$HOME/.local/share/applications/qos-android-com.android.chrome.desktop"
  ls "$PREFIX/etc/xdg/menus" 2>&1; env | grep -E '^XDG' ; } > "$OUT/menu-files.txt" 2>&1

# The language qos's Settings would write.
mkdir -p "$HOME/.qos"
[ -n "$LANGUAGE_SETTING" ] && echo "$LANGUAGE_SETTING" > "$HOME/.qos/language"

# Tamil: what qos installs (TermuxInstaller.installQosFiles) for a Tamil desktop.
if [ "$LANGUAGE_SETTING" = ta ]; then
    # libqosintl, built here for the container's x86_64; the APK carries it per ABI.
    pkg install -y clang
    clang -shared -fPIC -O2 -o "$PREFIX/lib/libqosintl.so" /work/app/src/main/cpp/qos-intl/qosintl.c
    install -D -m 644 -t "$PREFIX/share/locale/ta/LC_MESSAGES" "$ASSETS"/locale/ta/LC_MESSAGES/*.mo
    install -D -m 644 "$ASSETS/tamil-names.tsv" "$PREFIX/share/qos/tamil-names.tsv"
    # Android's own Noto Sans Tamil (in /system/fonts on a phone) is not in the
    # container: put it where fontconfig looks, for the preview only.
    mkdir -p "$PREFIX/share/fonts/noto"
    curl -fsSL -o "$PREFIX/share/fonts/noto/NotoSansTamil-Regular.ttf" \
        https://github.com/notofonts/notofonts.github.io/raw/main/fonts/NotoSansTamil/hinted/ttf/NotoSansTamil-Regular.ttf
    fc-cache -f > /dev/null 2>&1
fi

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

# The desktop's own menu: a right click on an empty part of it.
xdotool mousemove $((900 * $SCALE)) $((300 * $SCALE)) click 3; sleep 3; shot 1b-desktop-menu; xdotool key Escape; sleep 1

xfce4-popup-applicationsmenu; sleep 4; shot 2-menu
# Hover over Android, the eighth row of the menu, to open its submenu.
xdotool mousemove $((75 * $SCALE)) $((196 * $SCALE)); sleep 3; shot 5-android
xdotool key Escape; sleep 1

thunar "\$HOME" > /dev/null 2>&1 &
sleep 6
xfce4-terminal --geometry 70x18 > /dev/null 2>&1 &
sleep 6; shot 3-windows

xfce4-about > /dev/null 2>&1 &
sleep 5; shot 4-about

xfce4-session-logout > /dev/null 2>&1 &
sleep 5; shot 6-logout

xrandr --query > "$OUT/xrandr.txt" 2>&1
for c in xsettings xfce4-desktop xfce4-panel xfwm4; do
    echo "== \$c"; xfconf-query -c \$c -l -v
done > "$OUT/xfconf.txt" 2>&1
EOF
dbus-launch --exit-with-session bash "$TMPDIR/inside.sh"
ls -la "$OUT"
