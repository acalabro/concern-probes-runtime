#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# start.sh — interactive startup for concern-probes-runtime
#
# Usage:
#   ./scripts/start.sh                       interactive mode
#   ./scripts/start.sh --auto                use .env defaults without prompts
#   ./scripts/start.sh --profile test-local  test profile on the same machine
#                                            as the monitor
#                                            embedded broker + host MySQL
#   ./scripts/start.sh --help
# ══════════════════════════════════════════════════════════════════════════════

set -euo pipefail
cd "$(dirname "$0")/.."

BLUE='\033[0;34m'
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

info()    { printf "%b  →  %s%b\n" "$CYAN" "$*" "$NC"; }
success() { printf "%b  ✓  %s%b\n" "$GREEN" "$*" "$NC"; }
header()  { printf "\n%b%b%s%b\n" "$BOLD" "$BLUE" "$*" "$NC"; }
ask()     { printf "%b  ?  %s %b" "$YELLOW" "$*" "$NC"; }

# ── Banner ───────────────────────────────────────────────────────────────────
printf "\n%b%b" "$BOLD" "$BLUE"
echo "  ╔══════════════════════════════════════╗"
echo "  ║   Concern Probes Runtime — Startup   ║"
echo "  ╚══════════════════════════════════════╝"
printf "%b\n" "$NC"

# ── Help ─────────────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--help" ]]; then
  cat <<HELP
Usage: $0 [--auto | --profile <name> | --help]

Profiles:
  test-local    Connect to the monitor's embedded ActiveMQ broker and to the
                MySQL instance already running on this machine
                through host.docker.internal.
                Does not start any additional services.
                Uses: .env.test-local and docker-compose.test-local.yml

  default       Interactive mode: choose whether to start broker/MySQL locally
                or connect to external addresses.

Options:
  --auto            use .env values without asking questions
  --profile <name>  skip questions and use the specified profile
HELP
  exit 0
fi

# ── test-local profile ───────────────────────────────────────────────────────
if [[ "${1:-}" == "--profile" && "${2:-}" == "test-local" ]]; then
  ENV_FILE=".env.test-local"
  COMPOSE_FILE="docker-compose.test-local.yml"

  [[ -f "$ENV_FILE" ]] || { echo "File $ENV_FILE not found."; exit 1; }

  set -a
  source <(grep -E '^[A-Z_]+=.+' "$ENV_FILE" | grep -v '^\s*#')
  set +a

  export UID GID

  printf "\n%bProfile: test-local%b\n" "$BOLD" "$NC"
  printf "  Broker  : %b%s%b (user: %s)\n" \
    "$CYAN" "${BROKER_URL:-tcp://host.docker.internal:61616}" "$NC" "${BROKER_USER:-system}"
  printf "  MySQL   : %b%s:%s/%s%b\n" \
    "$CYAN" "${MYSQL_HOST:-host.docker.internal}" "${MYSQL_PORT:-3306}" "${MYSQL_DATABASE:-concern}" "$NC"
  printf "  Node    : %b%s%b → http://localhost:%s/ui\n\n" \
    "$CYAN" "${PROBE_NODE_ID:-test-local-01}" "$NC" "${HTTP_PORT:-8080}"

  printf "%b  ?  Make sure the monitor is already running on this machine." "$YELLOW"
  printf "\n     Proceed? [Y/n] %b" "$NC"
  read -r confirm

  [[ "${confirm,,}" == "n" ]] && { echo "Cancelled."; exit 0; }

  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up --build
  exit 0
fi

# ── Auto or interactive mode ─────────────────────────────────────────────────
AUTO=false
[[ "${1:-}" == "--auto" ]] && AUTO=true

if [[ -f .env ]]; then
  set -a
  source <(grep -E '^[A-Z_]+=.+' .env | grep -v '^\s*#')
  set +a
fi

yn() {
  local prompt="$1"
  local default="${2:-y}"

  if $AUTO; then
    [[ "$default" == "y" ]] && return 0 || return 1
  fi

  local hint
  [[ "$default" == "y" ]] && hint="[Y/n]" || hint="[y/N]"

  ask "$prompt $hint"
  local a
  read -r a
  a="${a:-$default}"

  [[ "${a,,}" == "y" ]]
}

read_val() {
  local prompt="$1"
  local default="$2"
  local varname="$3"

  if $AUTO; then
    printf -v "$varname" '%s' "$default"
    return
  fi

  ask "$prompt [${default}]:"
  local v
  read -r v

  printf -v "$varname" '%s' "${v:-$default}"
}

PROFILES=()

# ── Quick startup mode selection ─────────────────────────────────────────────
if ! $AUTO; then
  header "Startup mode"
  echo "  1) test-local  — probe on the same machine as the monitor, using the embedded broker"
  echo "  2) custom      — choose whether to start broker/MySQL locally or use external services"

  while true; do
    ask "Choice [1/2, default 2]:"
    read -r MODE_CHOICE
    MODE_CHOICE="${MODE_CHOICE:-2}"

    case "$MODE_CHOICE" in
      1)
        exec "$0" --profile test-local
        ;;
      2)
        break
        ;;
      *)
        echo "Invalid choice. Please enter 1 or 2."
        ;;
    esac
  done
fi

# ─────────────────────────────────────────────────────────────────────────────
header "1/3  ActiveMQ broker"

if yn "Start ActiveMQ locally inside this compose stack?"; then
  PROFILES+=("local-broker")

  read_val "  Host OpenWire port" "${LOCAL_BROKER_OPENWIRE_PORT:-61616}" LOCAL_BROKER_OPENWIRE_PORT
  read_val "  Host web console port" "${LOCAL_BROKER_CONSOLE_PORT:-8161}" LOCAL_BROKER_CONSOLE_PORT
  read_val "  Broker username" "${BROKER_USER:-system}" BROKER_USER
  read_val "  Broker password" "${BROKER_PASSWORD:-manager}" BROKER_PASSWORD

  BROKER_URL="tcp://activemq:61616"

  success "ActiveMQ will be started locally on ports ${LOCAL_BROKER_OPENWIRE_PORT}/${LOCAL_BROKER_CONSOLE_PORT}"
else
  read_val "  Broker URL, for example tcp://192.168.1.10:61616" \
           "${BROKER_URL:-tcp://localhost:61616}" BROKER_URL
  read_val "  Username" "${BROKER_USER:-system}" BROKER_USER
  read_val "  Password" "${BROKER_PASSWORD:-manager}" BROKER_PASSWORD

  info "Connecting to external broker: ${BROKER_URL}"
fi

header "2/3  MySQL"
info "MySQL is used by the monitor, not directly by the probe runtime."

if yn "Start MySQL locally?"; then
  PROFILES+=("local-mysql")

  read_val "  Host MySQL port" "${LOCAL_MYSQL_PORT:-3306}" LOCAL_MYSQL_PORT
  read_val "  Database" "${MYSQL_DATABASE:-concern}" MYSQL_DATABASE
  read_val "  MySQL user" "${MYSQL_USER:-concern}" MYSQL_USER
  read_val "  MySQL password" "${MYSQL_PASSWORD:-concern}" MYSQL_PASSWORD
  read_val "  MySQL root password" "${MYSQL_ROOT_PASSWORD:-rootpassword}" MYSQL_ROOT_PASSWORD

  MYSQL_HOST="mysql"

  success "MySQL will be started locally on port ${LOCAL_MYSQL_PORT}"
else
  read_val "  MySQL host" "${MYSQL_HOST:-localhost}" MYSQL_HOST
  read_val "  MySQL port" "${MYSQL_PORT:-3306}" MYSQL_PORT
  read_val "  Database" "${MYSQL_DATABASE:-concern}" MYSQL_DATABASE
  read_val "  User" "${MYSQL_USER:-concern}" MYSQL_USER
  read_val "  Password" "${MYSQL_PASSWORD:-concern}" MYSQL_PASSWORD

  info "External MySQL: ${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
fi

header "3/3  Probe runtime"

read_val "  Node ID" "${PROBE_NODE_ID:-$(hostname)}" PROBE_NODE_ID
read_val "  Admin token" "${ADMIN_TOKEN:-change-me-admin}" ADMIN_TOKEN
read_val "  HTTP port" "${HTTP_PORT:-8080}" HTTP_PORT

# Save .env
cat > .env << ENVFILE
# Generated by scripts/start.sh — $(date)
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

success ".env updated"

export UID GID

COMPOSE_CMD="docker compose"

for p in "${PROFILES[@]}"; do
  COMPOSE_CMD="$COMPOSE_CMD --profile $p"
done

COMPOSE_CMD="$COMPOSE_CMD up --build"

printf "\n%bStartup configuration:%b\n" "$BOLD" "$NC"
printf "  Profiles  : %b%s%b\n" "$GREEN" "${PROFILES[*]:-none, all services external}" "$NC"
printf "  Broker    : %b%s%b (user: %s)\n" "$CYAN" "$BROKER_URL" "$NC" "$BROKER_USER"
printf "  MySQL     : %b%s:%s/%s%b\n" \
       "$CYAN" "${MYSQL_HOST:-localhost}" "${MYSQL_PORT:-3306}" "$MYSQL_DATABASE" "$NC"
printf "  Node ID   : %b%s%b → http://localhost:%s/ui\n\n" \
       "$CYAN" "$PROBE_NODE_ID" "$NC" "$HTTP_PORT"

if ! $AUTO; then
  ask "Proceed? [Y/n]"
  read -r c
  [[ "${c,,}" == "n" ]] && { echo "Cancelled."; exit 0; }
fi

eval "$COMPOSE_CMD"