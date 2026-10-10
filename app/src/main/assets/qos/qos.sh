# qos: sourced by every login shell.

export ETAMIL_PATH="$PREFIX/lib/etamil"

if [ ! -f "$HOME/.qos/setup-done" ]; then
    echo "qos: setting up your development tools (first run only)."
    qos-setup || echo "qos: setup did not finish. It will retry next login, or run: qos-setup"
fi
