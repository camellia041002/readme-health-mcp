# HealthQuery connector

This repo-local connector targets the deployed HealthQuery Streamable HTTP MCP
endpoint. It contains no credential. Claude Code uses the root `.mcp.json` and
resolves the endpoint from `HEALTHQUERY_MCP_URL`. Codex uses the deployment-
specific inline `mcpServers` entry in `.codex-plugin/plugin.json`, because Codex
requires an absolute HTTP URL in a bundled plugin configuration.

Set the endpoint in the process environment before starting Codex or Claude
Code. It must be the externally reachable `/mcp` URL, for example:

```sh
export HEALTHQUERY_MCP_URL='https://health.example.com/mcp'
```

Install the Claude Code plugin from the repository marketplace, then enable it
explicitly because it connects to sensitive personal health data:

```text
/plugin marketplace add .
/plugin install healthquery-connector@healthquery
/plugin enable healthquery-connector
```

For a session-only development load, use
`claude --plugin-dir ./plugins/healthquery-connector`. Direct MCP setup without
the plugin is also supported; in an interactive Claude Code session, add the
project-scoped server and let Claude perform protected-resource / authorization-
server discovery:

```sh
claude mcp add --transport http --scope project healthquery "$HEALTHQUERY_MCP_URL"
# Then run /mcp (or: claude mcp login healthquery) to complete OAuth.
```

Codex consumes its absolute-URL configuration through the plugin manifest.
Install the repository root as the marketplace, then install the plugin:

```sh
codex plugin marketplace add /path/to/this-repo --json
codex plugin add healthquery-connector@personal --json
```

If OAuth is unavailable, use the separate bearer template. Copy
`config/healthquery-bearer.mcp.json` to a client-local MCP config (do not
replace the OAuth config in a shared checkout), set both environment variables,
and ensure the PAT has only the HealthQuery read scope. A configured
`Authorization` header intentionally disables OAuth fallback in Claude Code;
choose one mode per client.

The bearer template is deliberately only a template:

```sh
export HEALTHQUERY_MCP_BEARER_TOKEN='(load from a secret manager)'
```

The connector is read-only. Use the bundled `healthquery-readonly` skill to
discover the schema before querying, state the time range and data coverage,
separate observed values from inference, and avoid diagnosis or treatment
recommendations. OAuth and bearer credentials request only `health:read`; the
hardened deployment exposes only typed, bounded read tools.

## Client limitations

- OAuth is an interactive client flow. Claude Code can complete it from
  `/mcp` or `claude mcp login`; a non-interactive `claude -p` run cannot open or
  finish the browser consent flow.
- Codex and Claude each decide when to prompt for MCP setup/authentication. The
  plugin does not bypass host approval or persist credentials itself.
- The checked-in Codex plugin uses the placeholder
  `https://health.example.com/mcp`. Change only the URL in
  `.codex-plugin/plugin.json` to match your self-hosted instance before packaging.
  Never add a bearer token to the plugin.
- OAuth and bearer modes are alternatives. A static `Authorization` header can
  prevent Claude Code from trying OAuth, so do not combine the two templates.
