#!/usr/bin/env bash
set -euo pipefail

# Uso:
#   ./rewrite_identity.sh /percorso/repo
#
# Riscrive ricorsivamente TUTTA la history del repository,
# cambiando sia AUTHOR sia COMMITTER in:
#   acalabro <antonello.calabro@gmail.com>
#
# ATTENZIONE:
# - Tutti gli SHA dei commit cambieranno
# - Da usare solo su repository di cui controlli la history
# - Effettua un backup prima
# - Dopo serve force push:
#     git push --force --all
#     git push --force --tags

NEW_NAME="acalabro"
NEW_EMAIL="antonello.calabro@gmail.com"

REPO_PATH="${1:-.}"

if [[ ! -d "$REPO_PATH/.git" ]]; then
    echo "Errore: '$REPO_PATH' non è un repository git valido."
    exit 1
fi

cd "$REPO_PATH"

echo "[*] Riscrittura completa di AUTHOR e COMMITTER..."
echo "[*] Repository: $REPO_PATH"

git filter-branch --force --env-filter '
GIT_AUTHOR_NAME="'"$NEW_NAME"'"
GIT_AUTHOR_EMAIL="'"$NEW_EMAIL"'"
GIT_COMMITTER_NAME="'"$NEW_NAME"'"
GIT_COMMITTER_EMAIL="'"$NEW_EMAIL"'"

export GIT_AUTHOR_NAME
export GIT_AUTHOR_EMAIL
export GIT_COMMITTER_NAME
export GIT_COMMITTER_EMAIL
' --tag-name-filter cat -- --all

echo "[*] Pulizia riferimenti precedenti..."

rm -rf .git/refs/original/ || true
git reflog expire --expire=now --all
git gc --prune=now --aggressive

echo
echo "[+] Operazione completata."
echo "[!] Se il repository ha un remoto:"
echo "    git push --force --all"
echo "    git push --force --tags"
