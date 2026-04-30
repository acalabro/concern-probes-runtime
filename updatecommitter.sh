#!/usr/bin/env bash
set -euo pipefail

# Uso:
#   ./rewrite_committer.sh /percorso/repo
#
# Cambia autore e committer di TUTTA la history del repository
# in:
#   acalabro <antonello.calabro@gmail.com>
#
# ATTENZIONE:
# - Riscrive tutta la cronologia (SHA cambiano)
# - Da usare solo su repository controllati
# - Eseguire un backup prima
# - Dopo serve force push:
#     git push --force --all
#     git push --force --tags

NEW_NAME="acalabro"
NEW_EMAIL="antonello.calabro@gmail.com"

REPO_PATH="${1:-.}"

if [[ ! -d "$REPO_PATH/.git" ]]; then
  echo "Errore: '$REPO_PATH' non sembra un repository git valido."
  exit 1
fi

cd "$REPO_PATH"

echo "[*] Backup consigliato prima di procedere."
echo "[*] Riscrittura completa della history in corso..."

git filter-branch --env-filter '
export GIT_AUTHOR_NAME="'"$NEW_NAME"'"
export GIT_AUTHOR_EMAIL="'"$NEW_EMAIL"'"
export GIT_COMMITTER_NAME="'"$NEW_NAME"'"
export GIT_COMMITTER_EMAIL="'"$NEW_EMAIL"'"
' --tag-name-filter cat -- --all

echo
echo "[+] Completato."
echo "[*] Pulizia riferimenti vecchi..."

rm -rf .git/refs/original/
git reflog expire --expire=now --all
git gc --prune=now --aggressive

echo
echo "[+] Repository aggiornato."
echo "[!] Se il repo è remoto:"
echo "    git push --force --all"
echo "    git push --force --tags"
