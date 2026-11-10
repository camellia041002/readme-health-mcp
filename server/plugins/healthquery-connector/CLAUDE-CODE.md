# Claude Code packaging

This directory is a Claude Code plugin. The manifest is
`.claude-plugin/plugin.json`; the plugin-root `.mcp.json` registers the
HealthQuery Streamable HTTP endpoint and the `skills/` directory supplies the
read-only workflow.

## Local session test (no install)

Set the deployed endpoint in the environment, then load the plugin for one
session:

```sh
export HEALTHQUERY_MCP_URL='https://health.example.com/mcp'
claude --plugin-dir ./plugins/healthquery-connector
```

The manifest deliberately sets `defaultEnabled: false` because this plugin
connects to personal health data. Enable it explicitly after installation:

```text
/plugin enable healthquery-connector
```

## Install from this repository's Claude marketplace

From the repository root, add the marketplace and install the plugin. The
marketplace is the repository-root `.claude-plugin/marketplace.json` (it is
separate from the Codex `.agents/plugins/marketplace.json`):

```text
/plugin marketplace add .
/plugin install healthquery-connector@healthquery
```

The same operations are available from a shell:

```sh
claude plugin marketplace add /path/to/healthquery
claude plugin install healthquery-connector@healthquery
claude plugin enable healthquery-connector
```

After an install that reports `Run /reload-plugins to activate.`, run that
command in the current Claude Code session. Check `/plugin` for the plugin and
`/mcp` for the HealthQuery server. Without `HEALTHQUERY_MCP_URL`, Claude Code
will report a missing environment variable and will not connect; set it in the
Claude Code process environment before authenticating.

## Direct remote MCP setup (without the plugin)

For a project-scoped configuration, add the endpoint directly and authenticate
interactively:

```sh
claude mcp add --transport http --scope project \
  healthquery "$HEALTHQUERY_MCP_URL"
claude mcp login healthquery
claude mcp get healthquery
```

Use `/mcp` instead of `claude mcp login healthquery` when working inside an
interactive session. Do not pass a bearer `Authorization` header in this OAuth
mode; a static header prevents OAuth fallback. For bearer mode, use the
separate `config/healthquery-bearer.mcp.json` template and keep the token in
the environment or a secret manager.

## Verification boundaries

These commands validate packaging and configuration without opening OAuth:

```sh
claude plugin validate .claude-plugin/marketplace.json --strict
claude plugin validate plugins/healthquery-connector --strict
jq empty .claude-plugin/marketplace.json \
  plugins/healthquery-connector/.claude-plugin/plugin.json \
  plugins/healthquery-connector/.mcp.json
```

They do not prove that a deployment is reachable, that OAuth metadata is
correct, or that a user has completed consent. Run `/mcp` or
`claude mcp login healthquery` only with an explicit endpoint and the account
owner's consent.
