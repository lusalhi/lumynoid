#!/bin/sh
set -eu

printf 'Alpine: %s\n' "$(cat /etc/alpine-release)"
printf '%s\n' 'lumynoid-workspace-ok' > input.txt
[ "$(cat input.txt)" = lumynoid-workspace-ok ]
printf '%s\n' 'Workspace read/write: PASS'

node --version
npm --version
printf '%s\n' '{"name":"lumynoid-proot-probe","version":"1.0.0","private":true}' > package.json
npm install --offline --ignore-scripts --no-audit --no-fund --no-package-lock ./lumynoid-fixture.tgz
[ -f node_modules/lumynoid-fixture/index.js ]
printf '%s\n' 'Offline npm install: PASS'

cat > verify.js <<'JS'
const fs = require("node:fs");
const answer = require("lumynoid-fixture");
if (answer !== "lumynoid-offline-ok") throw new Error("Unexpected fixture result");
fs.writeFileSync("verified.txt", answer + "\n");
JS
node verify.js
[ "$(cat verified.txt)" = lumynoid-offline-ok ]
printf '%s\n' 'Node script: PASS'
