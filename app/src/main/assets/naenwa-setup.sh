#!/data/data/com.termux/files/usr/bin/bash
# Naenwa Auto Setup Script
# Claude CLI + Android SDK 자동 설치

set -e

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
INSTALL_FLAG="$HOME_DIR/.naenwa_installed"
SETUP_LOG="$HOME_DIR/naenwa_setup.log"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log() {
    echo -e "${GREEN}[Naenwa]${NC} $1"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$SETUP_LOG"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1" >> "$SETUP_LOG"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 이미 설치되었는지 확인
if [ -f "$INSTALL_FLAG" ]; then
    exit 0
fi

clear
echo -e "${BLUE}"
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║                                                           ║"
echo "║     _   _                                                 ║"
echo "║    | \ | | __ _  ___ _ ____      ____ _                   ║"
echo "║    |  \| |/ _\` |/ _ \ '_ \ \ /\ / / _\` |                  ║"
echo "║    | |\  | (_| |  __/ | | \ V  V / (_| |                  ║"
echo "║    |_| \_|\__,_|\___|_| |_|\_/\_/ \__,_|                  ║"
echo "║                                                           ║"
echo "║         Claude CLI Terminal for Android                   ║"
echo "║                                                           ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo ""
log "Naenwa 첫 실행 - 자동 설치를 시작합니다..."
log "설치에 약 15-30분 소요됩니다. Wi-Fi 연결을 권장합니다."
echo ""
echo -e "${YELLOW}[1/7]${NC} 기본 패키지 업데이트 중..."

# 1. 기본 패키지 업데이트
export DEBIAN_FRONTEND=noninteractive
yes | pkg update -y 2>&1 | tee -a "$SETUP_LOG"
yes | pkg upgrade -y -o Dpkg::Options::="--force-confnew" 2>&1 | tee -a "$SETUP_LOG"

log "기본 패키지 업데이트 완료"

# 2. proot-distro 설치
echo -e "${YELLOW}[2/7]${NC} proot-distro 설치 중..."
yes | pkg install proot-distro -y 2>&1 | tee -a "$SETUP_LOG"
log "proot-distro 설치 완료"

# 3. Ubuntu 설치
echo -e "${YELLOW}[3/7]${NC} Ubuntu 환경 설치 중... (시간이 걸립니다)"
proot-distro install ubuntu 2>&1 | tee -a "$SETUP_LOG"
log "Ubuntu 설치 완료"

# 4. Ubuntu 내부 설정 스크립트 생성
echo -e "${YELLOW}[4/7]${NC} Ubuntu 환경 설정 중..."

# Ubuntu에서 직접 설정 실행 (최적화 버전)
proot-distro login ubuntu -- /bin/bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive

echo "[Ubuntu] 패키지 업데이트 중..."
apt update && apt upgrade -y

echo "[Ubuntu] Box64 및 필수 패키지 설치 중..."
apt install -y box64 wget curl unzip openjdk-17-jdk

echo "[Ubuntu] x86_64 Node.js 다운로드 중..."
cd /root
wget -q --show-progress https://nodejs.org/dist/v20.11.0/node-v20.11.0-linux-x64.tar.xz
tar -xf node-v20.11.0-linux-x64.tar.xz
rm node-v20.11.0-linux-x64.tar.xz
mv node-v20.11.0-linux-x64 /opt/node

echo "[Ubuntu] Claude CLI 설치 중..."
export PATH="/opt/node/bin:$PATH"
# node를 통해 npm 실행 (box64는 node 바이너리만 에뮬레이션)
box64 /opt/node/bin/node /opt/node/lib/node_modules/npm/bin/npm-cli.js install -g @anthropic-ai/claude-code

echo "[Ubuntu] Android SDK 설치 중..."
mkdir -p /opt/android-sdk/cmdline-tools
cd /opt/android-sdk/cmdline-tools
wget -q --show-progress https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip -q cmdline-tools.zip
rm cmdline-tools.zip
mv cmdline-tools latest

export ANDROID_HOME=/opt/android-sdk
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "[Ubuntu] 환경 변수 설정 중..."
cat >> /root/.bashrc << EOF

# Naenwa Environment
export PATH="/opt/node/bin:\$PATH"
export ANDROID_HOME=/opt/android-sdk
export PATH="\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/34.0.0"

# Box64 최적화 설정 (성능 향상 + 경고 숨김)
export BOX64_LOG=0
export BOX64_DYNAREC=1
export BOX64_DYNAREC_BIGBLOCK=1
export BOX64_DYNAREC_STRONGMEM=0
export BOX64_DYNAREC_FASTNAN=1
export BOX64_DYNAREC_FASTROUND=1
export BOX64_DYNAREC_SAFEFLAGS=0
export BOX64_DYNAREC_X87DOUBLE=1

# Claude CLI alias
alias claude="box64 /opt/node/bin/node /opt/node/lib/node_modules/@anthropic-ai/claude-code/cli.js"

EOF

echo "[Ubuntu] 설치 완료!"
' 2>&1 | tee -a "$SETUP_LOG"

log "Ubuntu 환경 설정 완료"

# 5. 편의 스크립트 생성
echo -e "${YELLOW}[5/7]${NC} 편의 명령어 설정 중..."

# claude 명령어 wrapper
cat > "$PREFIX/bin/claude" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
# Box64 최적화 설정
export BOX64_LOG=0
export BOX64_DYNAREC=1
export BOX64_DYNAREC_BIGBLOCK=1
export BOX64_DYNAREC_STRONGMEM=0
export BOX64_DYNAREC_FASTNAN=1
export BOX64_DYNAREC_FASTROUND=1
export BOX64_DYNAREC_SAFEFLAGS=0
export BOX64_DYNAREC_X87DOUBLE=1

# PRoot 환경 내부인지 확인
if [ -f /etc/os-release ] && grep -q "Ubuntu" /etc/os-release 2>/dev/null; then
    # 이미 Ubuntu PRoot 환경 내부 - 직접 실행
    export PATH="/opt/node/bin:$PATH"
    box64 /opt/node/bin/node /opt/node/lib/node_modules/@anthropic-ai/claude-code/cli.js "$@"
else
    # Termux 환경 - proot-distro로 Ubuntu 진입 후 실행
    proot-distro login ubuntu -- /bin/bash -c "source ~/.bashrc && claude $*"
fi
EOF
chmod +x "$PREFIX/bin/claude"

# ubuntu 진입 명령어
cat > "$PREFIX/bin/naenwa-ubuntu" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
proot-distro login ubuntu
EOF
chmod +x "$PREFIX/bin/naenwa-ubuntu"

log "편의 명령어 설정 완료"

# 6. 작업 디렉터리 생성
echo -e "${YELLOW}[6/7]${NC} 작업 디렉터리 설정 중..."
mkdir -p "$HOME/projects"

log "작업 디렉터리 설정 완료"

# 7. 설치 완료 플래그
echo -e "${YELLOW}[7/7]${NC} 설치 마무리 중..."
date > "$INSTALL_FLAG"

log "Naenwa 설치가 완료되었습니다!"

clear
echo -e "${GREEN}"
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║                                                           ║"
echo "║              Naenwa 설치 완료!                            ║"
echo "║                                                           ║"
echo "╠═══════════════════════════════════════════════════════════╣"
echo "║                                                           ║"
echo "║  사용 방법:                                               ║"
echo "║                                                           ║"
echo "║  $ claude                                                 ║"
echo "║    → Claude CLI 실행                                      ║"
echo "║                                                           ║"
echo "║  $ naenwa-ubuntu                                          ║"
echo "║    → Ubuntu 환경 직접 진입                                ║"
echo "║                                                           ║"
echo "║  프로젝트 디렉터리: ~/projects                            ║"
echo "║                                                           ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo ""
echo "터미널을 재시작하거나 'source ~/.bashrc'를 실행하세요."
echo ""
