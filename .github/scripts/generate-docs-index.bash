#!/usr/bin/env bash
set -euo pipefail

# Determine docs directory (fallback to RUNNER_TEMP/docs)
DOCS_DIR="${DOCS_DIR:-${RUNNER_TEMP}/docs}"
mkdir -p "$DOCS_DIR"
REPO_NAME="${GITHUB_REPOSITORY#*/}"

# Collect SemVer tags (descending)
TAGS=$(git tag | grep -E '^v[0-9]+(\.[0-9]+)+$' | sort -V -r || true)

# Build version list items (latest first, then versions descending)
MVN_ITEMS='<li><a href="latest/">latest <span class="badge">development</span></a></li>'
JAVADOC_ITEMS='<li><a href="latest/apidocs/">latest <span class="badge">development</span></a></li>'

while IFS= read -r tag; do
  [ -z "$tag" ] && continue
  MVN_ITEMS+="<li><a href=\"${tag}/\">${tag}</a></li>"
  JAVADOC_ITEMS+="<li><a href=\"${tag}/apidocs/\">${tag}</a></li>"
done <<< "$TAGS"

cat > "$DOCS_DIR/index.html" <<ENDOFPAGE
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${REPO_NAME} — Documentation</title>
<style>
:root {
  --bg: #1a1a1a;
  --bg-alt: #1d1d1d;
  --fg: #e0e0e0;
  --fg-bright: #f4f2f2;
  --accent: #c8ffe9;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
  line-height: 1.6;
  background: var(--bg);
  color: var(--fg);
}
nav {
  display: flex;
  justify-content: center;
  padding: 1rem 0;
  background: var(--bg-alt);
  box-shadow: 0 1px 2px rgba(0,0,0,.1);
}
nav .logo {
  font-size: 1.5rem;
  color: var(--fg);
}
:is(nav, h1) a {
  color: inherit;
  text-decoration: inherit;
}
:is(nav, h1) a:hover {
  color: var(--fg-bright);
}
main {
  max-width: 800px;
  margin: 6rem auto;
  padding: 0 1rem;
  text-align: center;
}
h1 { font-size: 3rem; color: var(--fg-bright); letter-spacing: -1px; margin-bottom: .5rem; }
.subtitle { font-size: 1.4rem; color: #999; margin-bottom: 3rem; }
.sections {
  display: flex;
  gap: 2rem;
  justify-content: center;
  flex-wrap: wrap;
}
.section { flex: 1; min-width: 200px; max-width: 340px; }
.section-title {
  font-size: 1rem;
  font-weight: 600;
  letter-spacing: .08em;
  text-transform: uppercase;
  color: #888;
  margin-bottom: 1rem;
}
ul {
  list-style: none;
}
li { margin: .5rem 0; }
li a {
  display: block;
  padding: .9rem 1.5rem;
  border: 1px solid rgba(255,255,255,.1);
  border-radius: 8px;
  color: var(--accent);
  text-decoration: none;
  font-size: 1.2rem;
  transition: all .2s ease;
}
li a:hover { background: var(--bg-alt); border-color: var(--accent); }
.badge {
  font-size: .75rem;
  background: var(--accent);
  color: var(--bg);
  padding: .15rem .5rem;
  border-radius: 4px;
  margin-left: .5rem;
  vertical-align: middle;
  font-weight: 600;
}
@media (max-width: 768px) { h1 { font-size: 2rem; } main { margin: 3rem auto; } }
</style>
</head>
<body>
<nav><div class="logo"><a href="/">codemine.io</a></div></nav>
<main>
<h1><a href="https://github.com/codemine-io/${REPO_NAME}">${REPO_NAME}</a></h1>
<p class="subtitle">Documentation</p>
<div class="sections">
<div class="section">
<p class="section-title">mvn</p>
<ul>
${MVN_ITEMS}
</ul>
</div>
<div class="section">
<p class="section-title">javadoc</p>
<ul>
${JAVADOC_ITEMS}
</ul>
</div>
</div>
</main>
</body>
</html>
ENDOFPAGE
