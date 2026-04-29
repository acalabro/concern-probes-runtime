#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# start.sh — avvio interattivo di concern-probes-runtime
#
# Uso:
#   ./scripts/start.sh                  interattivo
#   ./scripts/start.sh --auto           usa i default del .env senza chiedere
#   ./scripts/start.sh --profile test-local   profilo test sulla stessa macchina
#                                             del monitor (broker embedded + MySQL host)
#   ./scripts/start.sh --help
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

BLUE='\033[0;34m'; CYAN='\033[0;36m'; GREEN='\033[0;32m'
YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'

info()    { printf "${CYAN}  →  %s${NC}\n" "$*"; }
success() { printf "${GREEN}  ✓  %s${NC}\n" "$*"; }
header()  { printf "\n${BOLD}${BLUE}%s${NC}\n" "$*"; }
ask()     { printf "${YELLOW}  ?  $1 ${NC}"; }

# ── Banner ────────────────────────────────────────────────────────────────────
printf "\n${BOLD}${BLUE}"
echo "  ╔══════════════════════════════════════╗"
echo "  ║   Concern Probes Runtime — Startup   ║"
echo "  ╚══════════════════════════════════════╝"
printf "${NC}\n"

# ── Help ──────────────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--help" ]]; then
  cat <<HELP
Usage: $0 [--auto | --profile <name> | --help]

Profiles:
  test-local    Collegati al broker ActiveMQ embedded del monitor e al MySQL
                già in esecuzione su questa macchina (host.docker.internal).
                Non avvia nessun servizio aggiuntivo.
                Usa: .env.test-local e docker-compose.test-local.yml

  (default)     Interattivo: scegli se avviare broker/MySQL localmente
                o connetterti a indirizzi esterni.

Options:
  --auto            usa i valori del .env senza fare domande
  --profile <name>  salta le domande e usa il profilo specificato
HELP
  exit 0
fi

# ── Profilo test-local ────────────────────────────────────────────────────────
if [[ "${1:-}" == "--profile" && "${2:-}" == "test-local" ]]; then
  ENV_FILE=".env.test-local"
  COMPOSE_FILE="docker-compose.test-local.yml"

  [[ -f "$ENV_FILE" ]] || { echo "File $ENV_FILE non trovato."; exit 1; }
  set -a; source <(grep -E '^[A-Z_]+=.+' "$ENV_FILE" | grep -v '^\s*#'); set +a
  export UID GID

  printf "\n${BOLD}Profilo: test-local${NC}\n"
  printf "  Broker  : ${CYAN}%s${NC} (user: %s)\n" \
    "${BROKER_URL:-tcp://host.docker.internal:61616}" "${BROKER_USER:-system}"
  printf "  MySQL   : ${CYAN}%s:%s/%s${NC}\n" \
    "${MYSQL_HOST:-host.docker.internal}" "${MYSQL_PORT:-3306}" "${MYSQL_DATABASE:-concern}"
  printf "  Node    : ${CYAN}%s${NC} → http://localhost:%s/ui\n\n" \
    "${PROBE_NODE_ID:-test-local-01}" "${HTTP_PORT:-8080}"

  printf "${YELLOW}  ?  Verificare che il monitor sia già in esecuzione su questa macchina."
  printf "\n     Procedere? [Y/n] ${NC}"
  read -r confirm
  [[ "${confirm,,}" == "n" ]] && { echo "Annullato."; exit 0; }

  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up --build
  exit 0
fi

# ── Modalità auto o interattiva ───────────────────────────────────────────────
AUTO=false
[[ "${1:-}" == "--auto" ]] && AUTO=true

[[ -f .env ]] && { set -a; source <(grep -E '^[A-Z_]+=.+' .env | grep -v '^\s*#'); set +a; }

yn() {
  local prompt="$1" default="${2:-y}"
  $AUTO && { [[ "$default" == "y" ]] && return 0 || return 1; }
  local hint; [[ "$default" == "y" ]] && hint="[Y/n]" || hint="[y/N]"
  ask "$prompt $hint"; local a; read -r a; a="${a:-$default}"
  [[ "${a,,}" == "y" ]]
}

read_val() {
  local prompt="$1" default="$2" varname="$3"
  $AUTO && { printf -v "$varname" '%s' "$default"; return; }
  ask "$prompt [${default}]:"; local v; read -r v
  printf -v "$varname" '%s' "${v:-$default}"
}

PROFILES=()

# ── Scelta modalità rapida ────────────────────────────────────────────────────
header "Modalità di avvio"
echo "  1) test-local   — probe sulla stessa macchina del monitor (broker embedded)"
echo "  2) personalizza — scegli se avviare broker/MySQL localmente o esternamente"
ask "Scelta [1/2, default 2]:"
MODE_CHOICE="${REPLY:-2}"
[[ -z "${MODE_CHOICE}" ]] && { read -r MODE_CHOICE; MODE_CHOICE="${MODE_CHOICE:-2}"; }

if [[ "$MODE_CHOICE" == "1" ]]; then
  exec "$0" --profile test-local
fi

# ─────────────────────────────────────────────────────────────────────────────
header "1/3  ActiveMQ broker"
if yn "Avviare ActiveMQ localmente (dentro questo compose stack)?"; then
  PROFILES+=("local-broker")
  read_val "  Porta OpenWire sull'host" "${LOCAL_BROKER_OPENWIRE_PORT:-61616}" LOCAL_BROKER_OPENWIRE_PORT
  read_val "  Porta web console sull'host" "${LOCAL_BROKER_CONSOLE_PORT:-8161}" LOCAL_BROKER_CONSOLE_PORT
  read_val "  Username broker" "${BROKER_USER:-system}" BROKER_USER
  read_val "  Password broker" "${BROKER_PASSWORD:-manager}" BROKER_PASSWORD
  BROKER_URL="tcp://activemq:61616"
  success "ActiveMQ avviato localmente su porte ${LOCAL_BROKER_OPENWIRE_PORT}/${LOCAL_BROKER_CONSOLE_PORT}"
else
  read_val "  URL broker (es. tcp://192.168.1.10:61616)" \
           "${BROKER_URL:-tcp://localhost:61616}" BROKER_URL
  read_val "  Username" "${BROKER_USER:-system}" BROKER_USER
  read_val "  Password" "${BROKER_PASSWORD:-manager}" BROKER_PASSWORD
  info "Connessione a broker esterno: ${BROKER_URL}"
fi

header "2/3  MySQL"
info "(MySQL è usato dal monitor — non direttamente dal probe runtime)"
if yn "Avviare MySQL localmente?"; then
  PROFILES+=("local-mysql")
  read_val "  Porta MySQL sull'host" "${LOCAL_MYSQL_PORT:-3306}" LOCAL_MYSQL_PORT
  read_val "  Database" "${MYSQL_DATABASE:-concern}" MYSQL_DATABASE
  read_val "  Utente MySQL" "${MYSQL_USER:-concern}" MYSQL_USER
  read_val "  Password MySQL" "${MYSQL_PASSWORD:-concern}" MYSQL_PASSWORD
  read_val "  Password root MySQL" "${MYSQL_ROOT_PASSWORD:-rootpassword}" MYSQL_ROOT_PASSWORD
  MYSQL_HOST="mysql"
  success "MySQL avviato localmente su porta ${LOCAL_MYSQL_PORT}"
else
  read_val "  Host MySQL" "${MYSQL_HOST:-localhost}" MYSQL_HOST
  read_val "  Porta MySQL" "${MYSQL_PORT:-3306}" MYSQL_PORT
  read_val "  Database" "${MYSQL_DATABASE:-concern}" MYSQL_DATABASE
  read_val "  Utente" "${MYSQL_USER:-concern}" MYSQL_USER
  read_val "  Password" "${MYSQL_PASSWORD:-concern}" MYSQL_PASSWORD
  info "MySQL esterno: ${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
fi

header "3/3  Probe runtime"
read_val "  Node ID" "${PROBE_NODE_ID:-$(hostname)}" PROBE_NODE_ID
read_val "  Admin token" "${ADMIN_TOKEN:-change-me-admin}" ADMIN_TOKEN
read_val "  Porta HTTP" "${HTTP_PORT:-8080}" HTTP_PORT

# Salva .env
cat > .env << ENVFILE
# Generato da scripts/start.sh — $(date)
PROBE_NODE_ID=${PROBE_NODE_ID}
ADMIN_TOKEN=${ADMIN_TOKEN}
HTTP_PORT=${HTTP_PORT}
BROKER_URL=${BROKER_URL}
BROKER_HOST=${BROKER_HOST:-localhost}
BROKER_PORT=${BROKER_PORT:-61616}
BROKER_USER=${BROKER_USER}
BROKER_PASSWORD=${BROKER_PASSWORD}
LOCAL_BROKER_OPENWIRE_PORT=${LOCAL_BROKER_OPENWIRE_PORT:-61616}
LOCAL_BROKER_CONSOLE_PORT=${LOCAL_BROKER_CONSOLE_PORT:-8161}
MYSQL_HOST=${MYSQL_HOST:-localhost}
MYSQL_PORT=${MYSQL_PORT:-3306}
MYSQL_DATABASE=${MYSQL_DATABASE:-concern}
MYSQL_USER=${MYSQL_USER:-concern}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-concern}
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-rootpassword}
LOCAL_MYSQL_PORT=${LOCAL_MYSQL_PORT:-3306}
ENVFILE
success ".env aggiornato"

export UID GID

COMPOSE_CMD="docker compose"
for p in "${PROFILES[@]}"; do COMPOSE_CMD="$COMPOSE_CMD --profile $p"; done
COMPOSE_CMD="$COMPOSE_CMD up --build"

printf "\n${BOLD}Avvio con:${NC}\n"
printf "  Profili   : ${GREEN}%s${NC}\n" "${PROFILES[*]:-nessuno (tutti esterni)}"
printf "  Broker    : ${CYAN}%s${NC} (user: %s)\n" "$BROKER_URL" "$BROKER_USER"
printf "  MySQL     : ${CYAN}%s:%s/%s${NC}\n" \
       "${MYSQL_HOST:-localhost}" "${MYSQL_PORT:-3306}" "$MYSQL_DATABASE"
printf "  Node ID   : ${CYAN}%s${NC} → http://localhost:%s/ui\n\n" "$PROBE_NODE_ID" "$HTTP_PORT"

$AUTO || { ask "Procedere? [Y/n]"; read -r c; [[ "${c,,}" == "n" ]] && { echo "Annullato."; exit 0; }; }

eval "$COMPOSE_CMD"
