#!/bin/bash
# Naenwa Auto Setup Script (External Storage Version)
# Ubuntu rootfs + Node.js + Claude CLI 자동 설치
# 외부 저장소(/sdcard/naenwa/)에 저장되어 앱 삭제해도 유지됨

set -e

# 외부 저장소 경로
NAENWA_DIR="/sdcard/naenwa"
ROOTFS_DIR="$NAENWA_DIR/ubuntu-rootfs"
SETUP_FLAG="$NAENWA_DIR/.setup_complete"
SETUP_LOG="$NAENWA_DIR/setup.log"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[Naenwa]${NC} $1"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$SETUP_LOG" 2>/dev/null || true
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1" >> "$SETUP_LOG" 2>/dev/null || true
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 디렉토리 생성
mkdir -p "$NAENWA_DIR"
mkdir -p "$NAENWA_DIR/home"
mkdir -p "$NAENWA_DIR/tmp"

# 이미 설치되었는지 확인
if [ -f "$SETUP_FLAG" ] && [ -f "$ROOTFS_DIR/bin/bash" ]; then
    log "이미 설치되어 있습니다. Ubuntu 환경으로 진입합니다..."
    exit 0
fi

clear
echo -e "${BLUE}"
echo "======================================"
echo "                                      "
echo "     Naenwa Setup                     "
echo "     Claude CLI for Android           "
echo "                                      "
echo "======================================"
echo -e "${NC}"
echo ""
log "Naenwa 첫 실행 - 자동 설치를 시작합니다..."
log "설치에 약 10-20분 소요됩니다. Wi-Fi 연결을 권장합니다."
echo ""

# Ubuntu rootfs 다운로드
echo -e "${YELLOW}[1/5]${NC} Ubuntu rootfs 다운로드 중..."

# Ubuntu aarch64 rootfs URL (official Ubuntu base)
ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
elif [ "$ARCH" = "x86_64" ]; then
    ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-amd64.tar.gz"
else
    ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
fi

ROOTFS_TAR="$NAENWA_DIR/ubuntu-rootfs.tar.gz"

if [ ! -f "$ROOTFS_DIR/bin/bash" ]; then
    log "Ubuntu rootfs를 다운로드합니다... (약 30MB)"

    # curl 또는 wget 사용
    if command -v curl &> /dev/null; then
        curl -L -o "$ROOTFS_TAR" "$ROOTFS_URL" --progress-bar
    elif command -v wget &> /dev/null; then
        wget -O "$ROOTFS_TAR" "$ROOTFS_URL" --show-progress
    else
        error "curl 또는 wget이 필요합니다."
        exit 1
    fi

    log "Ubuntu rootfs 다운로드 완료"

    # 압축 해제
    echo -e "${YELLOW}[2/5]${NC} Ubuntu rootfs 압축 해제 중..."
    mkdir -p "$ROOTFS_DIR"
    cd "$ROOTFS_DIR"
    tar -xzf "$ROOTFS_TAR"
    rm -f "$ROOTFS_TAR"
    log "Ubuntu rootfs 압축 해제 완료"
else
    log "Ubuntu rootfs가 이미 존재합니다."
fi

# DNS 설정
echo -e "${YELLOW}[3/5]${NC} 네트워크 설정 중..."
echo "nameserver 8.8.8.8" > "$ROOTFS_DIR/etc/resolv.conf"
echo "nameserver 8.8.4.4" >> "$ROOTFS_DIR/etc/resolv.conf"
log "네트워크 설정 완료"

# Ubuntu 내부 설정 스크립트 생성
echo -e "${YELLOW}[4/5]${NC} Ubuntu 환경 설정 스크립트 생성 중..."

cat > "$ROOTFS_DIR/root/setup-ubuntu.sh" << 'UBUNTU_SCRIPT'
#!/bin/bash
set -e

export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

echo "[Ubuntu] 패키지 소스 업데이트 중..."
apt update

echo "[Ubuntu] 필수 패키지 설치 중..."
apt install -y curl wget ca-certificates gnupg

echo "[Ubuntu] Node.js 20 LTS 설치 중..."
# NodeSource Node.js 20.x repository
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs

echo "[Ubuntu] Node.js 버전 확인:"
node --version
npm --version

echo "[Ubuntu] npm 글로벌 디렉토리 설정..."
mkdir -p /root/.npm-global
npm config set prefix '/root/.npm-global'
export PATH="/root/.npm-global/bin:$PATH"

echo "[Ubuntu] Claude CLI 설치 중..."
npm install -g @anthropic-ai/claude-code

echo "[Ubuntu] 환경 변수 설정 중..."
cat >> /root/.bashrc << 'BASHRC'

# Naenwa Environment
export PATH="/root/.npm-global/bin:$PATH"
export TERM=xterm-256color
export LANG=C.UTF-8

# Welcome message
echo ""
echo "Welcome to Naenwa Ubuntu Environment"
echo "Claude CLI: claude --help"
echo ""
BASHRC

echo "[Ubuntu] 설치 완료!"
UBUNTU_SCRIPT

chmod +x "$ROOTFS_DIR/root/setup-ubuntu.sh"
log "Ubuntu 설정 스크립트 생성 완료"

# 설치 완료 표시 (proot 진입 후 실제 설정은 별도로 실행)
echo -e "${YELLOW}[5/5]${NC} 설치 마무리 중..."

# 기본 설치 플래그 (Ubuntu rootfs만 설치됨)
echo "Ubuntu rootfs installed: $(date)" > "$SETUP_FLAG"
echo "Run /root/setup-ubuntu.sh inside proot to complete setup" >> "$SETUP_FLAG"

log "Ubuntu rootfs 설치 완료!"
echo ""
echo -e "${GREEN}"
echo "======================================"
echo "                                      "
echo "       Ubuntu rootfs 설치 완료!       "
echo "                                      "
echo "======================================"
echo -e "${NC}"
echo ""
echo -e "${CYAN}다음 단계:${NC}"
echo "1. Ubuntu 환경으로 자동 진입합니다."
echo "2. 처음 실행시 Node.js와 Claude CLI가 자동 설치됩니다."
echo "3. 설치 완료 후 'claude' 명령어를 사용할 수 있습니다."
echo ""
