# qos: sourced by every login shell.

export ETAMIL_PATH="$PREFIX/lib/etamil"

# The qos prompt, q:~/projects>  with the q in qos teal. Termux's bash.bashrc
# keeps a PS1 that is already set, and ~/.bashrc, read after it, can still
# replace this one.
if [ -n "${BASH_VERSION:-}" ]; then
    PS1='\[\e[1;38;2;16;243;190m\]q\[\e[0m\]:\[\e[0;32m\]\w\[\e[0m\]> '
fi

if [ ! -f "$HOME/.qos/setup-done" ]; then
    echo "qos: setting up your development tools (first run only)."
    qos-setup || echo "qos: setup did not finish. It will retry next login, or run: qos-setup"
fi
