#!/bin/sh
# 校招雷达安装脚本（macOS、Linux）。需要 Java 21 或更高版本。
#   curl -fsSL https://raw.githubusercontent.com/Ajax-1/campus-radar/main/install.sh | sh
#   加 -s -- --autostart 可以设置开机自启，每天的采集和推送才不会断
set -eu

JAR_URL="${RADAR_JAR_URL:-https://github.com/Ajax-1/campus-radar/releases/latest/download/campus-radar.jar}"
HOME_DIR="$HOME/.campus-radar"
AUTOSTART=0
[ "${1:-}" = "--autostart" ] && AUTOSTART=1

if ! command -v java >/dev/null 2>&1; then
  echo "没有找到 Java。请先安装 Java 21：https://adoptium.net/ （macOS 也可以用 brew install --cask temurin）" >&2
  exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
  echo "当前 Java 版本是 ${JAVA_MAJOR:-未知}，需要 21 或更高。" >&2
  exit 1
fi

mkdir -p "$HOME_DIR/bin" "$HOME_DIR/data"
echo "下载校招雷达..."
curl -fsSL "$JAR_URL" -o "$HOME_DIR/campus-radar.jar.tmp"
mv "$HOME_DIR/campus-radar.jar.tmp" "$HOME_DIR/campus-radar.jar"

# 配置放在单独的文件里，升级时不会被覆盖。里面有密钥，只有自己能读
if [ ! -f "$HOME_DIR/env" ]; then
  umask 077
  cat > "$HOME_DIR/env" <<'CONF'
# 校招雷达配置。改完重启生效
# 推到个人微信：sct.ftqq.com 微信扫码后复制 SendKey
# export RADAR_SERVERCHAN_KEY=
# export RADAR_WECOM_WEBHOOK=
# export RADAR_FEISHU_WEBHOOK=
# 自动识别笔试面试邮件，密码填邮箱的授权码
# export RADAR_MAIL_HOST=imap.qq.com
# export RADAR_MAIL_USERNAME=
# export RADAR_MAIL_PASSWORD=
# 人在海外时设置，日程会同时显示当地时间
# export RADAR_DISPLAY_ZONE=America/New_York
CONF
fi

cat > "$HOME_DIR/bin/campus-radar" <<'LAUNCH'
#!/bin/sh
cd "$HOME/.campus-radar"
. ./env
exec java -jar campus-radar.jar "$@"
LAUNCH
chmod +x "$HOME_DIR/bin/campus-radar"

if [ "$AUTOSTART" = 1 ]; then
  case "$(uname -s)" in
    Darwin)
      PLIST="$HOME/Library/LaunchAgents/io.github.ajax1.campus-radar.plist"
      mkdir -p "$(dirname "$PLIST")"
      cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>io.github.ajax1.campus-radar</string>
  <key>ProgramArguments</key><array><string>$HOME_DIR/bin/campus-radar</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME_DIR/radar.log</string>
  <key>StandardErrorPath</key><string>$HOME_DIR/radar.log</string>
</dict></plist>
PL
      [ -n "${RADAR_SKIP_SERVICE:-}" ] || launchctl load -w "$PLIST"
      ;;
    Linux)
      UNIT="$HOME/.config/systemd/user/campus-radar.service"
      mkdir -p "$(dirname "$UNIT")"
      cat > "$UNIT" <<UN
[Unit]
Description=campus-radar

[Service]
ExecStart=$HOME_DIR/bin/campus-radar
Restart=on-failure

[Install]
WantedBy=default.target
UN
      [ -n "${RADAR_SKIP_SERVICE:-}" ] || systemctl --user enable --now campus-radar
      ;;
    *)
      echo "这个系统不支持自动设置开机自启，请手动运行 $HOME_DIR/bin/campus-radar" >&2
      ;;
  esac
  echo "已设置开机自启。打开 http://localhost:8090 开始使用"
else
  echo "安装完成。运行下面的命令启动，然后打开 http://localhost:8090"
  echo "  $HOME_DIR/bin/campus-radar"
fi
