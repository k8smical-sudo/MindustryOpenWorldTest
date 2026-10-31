#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-build/inventario}"
mkdir -p "$OUT"
ERR=0

find src -type f | LC_ALL=C sort > "$OUT/fuentes.txt"
grep -v '\.java$' "$OUT/fuentes.txt" > "$OUT/no-java.txt" || true
grep -v '^src/mundoinfinito/[^/]*\.java$' "$OUT/fuentes.txt" > "$OUT/fuera-de-lugar.txt" || true
: > "$OUT/paquete-incorrecto.txt"
: > "$OUT/clases-duplicadas.txt"

while IFS= read -r f; do
  case "$f" in *.java) ;; *) continue ;; esac
  pkg=$(grep -m1 '^package ' "$f" | sed -E 's/^package ([A-Za-z0-9_.]+);.*/\1/')
  [ "$pkg" = "mundoinfinito" ] || echo "$f (paquete: ${pkg:-ninguno})" >> "$OUT/paquete-incorrecto.txt"
done < "$OUT/fuentes.txt"

grep -hoE '^(public |final |abstract )*(class|interface|enum) [A-Za-z0-9_]+' src/mundoinfinito/*.java 2>/dev/null \
  | awk '{print $NF}' | sort | uniq -d > "$OUT/clases-duplicadas.txt"

for par in "no-java.txt" "fuera-de-lugar.txt" "paquete-incorrecto.txt" "clases-duplicadas.txt"; do
  if [ -s "$OUT/$par" ]; then
    echo "::error::Inventario: $par"
    cat "$OUT/$par"
    ERR=1
  fi
done

echo "Archivos en src: $(wc -l < "$OUT/fuentes.txt") (ordenados en $OUT/fuentes.txt)"
if [ "$ERR" -ne 0 ]; then
  echo "Inventario: FALLO (ver los listados anteriores en $OUT/)"
  exit 1
fi
echo "Inventario: OK"
