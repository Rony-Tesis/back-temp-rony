#!/usr/bin/env bash
set -euo pipefail
if [[ "${EUID}" -ne 0 ]]; then
  echo 'Ejecuta: sudo bash install.sh' >&2
  exit 1
fi
bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
getent passwd hiwonder >/dev/null || { echo 'No existe el usuario hiwonder del JetMax.' >&2; exit 1; }
test -s "$bundle_dir/agent.env" || { echo 'Falta agent.env generado por prepare.ps1.' >&2; exit 1; }
if systemctl is-active --quiet rony-edge.service; then
  echo 'El servicio autonomo rony-edge esta activo. Actualiza con activate-edge.ps1; no instales el banco encima.' >&2
  exit 1
fi
if systemctl is-active --quiet rony-agent.service; then
  systemctl stop rony-agent.service
fi
install -d -m 755 /opt/rony-agent
install -d -m 750 -o root -g hiwonder /etc/rony-agent
install -m 644 "$bundle_dir/agent.py" "$bundle_dir/rony_profile.json" /opt/rony-agent/
install -m 755 "$bundle_dir/start.sh" /opt/rony-agent/start.sh
install -m 600 -o root -g root "$bundle_dir/agent.env" /etc/rony-agent/agent.env
install -m 644 "$bundle_dir/rony-agent.service" /etc/systemd/system/rony-agent.service
systemctl daemon-reload
systemctl enable rony-agent.service
systemctl restart rony-agent.service
echo 'Agente instalado; arranque sin movimiento. Estado: systemctl status rony-agent'
echo 'Diagnóstico: journalctl -u rony-agent -n 80 --no-pager'
