#!/system/bin/sh
# Naenwa Terminal Startup Script
# This runs automatically when terminal opens

HOME_DIR="/data/data/com.naenwa.remote/files/home"
INSTALL_FLAG="$HOME_DIR/.naenwa_installed"
SETUP_SCRIPT="$HOME_DIR/naenwa-setup.sh"

cd "$HOME_DIR"

# Check if setup is needed
if [ ! -f "$INSTALL_FLAG" ] && [ -f "$SETUP_SCRIPT" ]; then
    echo ""
    echo "Naenwa 첫 실행 - 자동 설정을 시작합니다..."
    echo ""

    # Check if bash is available, use it for setup
    if [ -x "/data/data/com.naenwa.remote/files/usr/bin/bash" ]; then
        exec /data/data/com.naenwa.remote/files/usr/bin/bash "$SETUP_SCRIPT"
    else
        # Fallback: just inform user to run setup manually
        echo "bash가 설치되지 않았습니다."
        echo "수동으로 설정을 실행하세요: sh naenwa-setup.sh"
        echo ""
        exec /system/bin/sh
    fi
else
    # Already installed or no setup script
    if [ -f "$INSTALL_FLAG" ]; then
        echo ""
        echo "Naenwa Terminal Ready!"
        echo "Run 'claude' to start Claude CLI"
        echo "Run 'naenwa-ubuntu' to enter Ubuntu environment"
        echo ""
    fi

    # Try bash first, fallback to sh
    if [ -x "/data/data/com.naenwa.remote/files/usr/bin/bash" ]; then
        export PATH="/data/data/com.naenwa.remote/files/usr/bin:/system/bin:/system/xbin"
        export LD_LIBRARY_PATH="/data/data/com.naenwa.remote/files/usr/lib"
        exec /data/data/com.naenwa.remote/files/usr/bin/bash --login
    else
        exec /system/bin/sh
    fi
fi
