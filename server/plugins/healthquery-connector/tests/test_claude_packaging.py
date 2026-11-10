"""Static checks for the Claude Code plugin and local marketplace entry."""

import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[3]
PLUGIN_ROOT = REPO_ROOT / "plugins" / "healthquery-connector"


def read_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


class ClaudePackagingTests(unittest.TestCase):
    def test_marketplace_points_at_the_plugin(self) -> None:
        marketplace = read_json(REPO_ROOT / ".claude-plugin" / "marketplace.json")
        self.assertEqual(marketplace["name"], "healthquery")
        self.assertEqual(marketplace["owner"]["name"], "HealthQuery project")
        self.assertEqual(len(marketplace["plugins"]), 1)
        entry = marketplace["plugins"][0]
        self.assertEqual(entry["name"], "healthquery-connector")
        self.assertEqual(entry["source"], "./plugins/healthquery-connector")

    def test_manifest_and_components_use_claude_defaults(self) -> None:
        manifest = read_json(PLUGIN_ROOT / ".claude-plugin" / "plugin.json")
        self.assertEqual(manifest["name"], "healthquery-connector")
        self.assertEqual(manifest["version"], "0.1.1")
        self.assertFalse(manifest["defaultEnabled"])
        self.assertTrue((PLUGIN_ROOT / ".mcp.json").is_file())
        self.assertTrue(
            (PLUGIN_ROOT / "skills" / "healthquery-readonly" / "SKILL.md").is_file()
        )

    def test_mcp_config_is_remote_http_oauth_without_credentials(self) -> None:
        config = read_json(PLUGIN_ROOT / ".mcp.json")
        server = config["mcpServers"]["healthquery"]
        self.assertEqual(server["type"], "http")
        self.assertEqual(server["url"], "${HEALTHQUERY_MCP_URL}")
        self.assertEqual(server["oauth"]["scopes"], "health:read")
        self.assertNotIn("headers", server)


if __name__ == "__main__":
    unittest.main()
