import json
import unittest
from pathlib import Path
from urllib.parse import urlparse


PLUGIN_ROOT = Path(__file__).resolve().parents[1]


class CodexPackagingTests(unittest.TestCase):
    def test_manifest_uses_codex_specific_mcp_config(self) -> None:
        manifest = json.loads((PLUGIN_ROOT / ".codex-plugin" / "plugin.json").read_text())
        self.assertIn("healthquery", manifest["mcpServers"])

    def test_codex_remote_url_is_absolute_https_mcp_endpoint(self) -> None:
        manifest = json.loads((PLUGIN_ROOT / ".codex-plugin" / "plugin.json").read_text())
        server = manifest["mcpServers"]["healthquery"]
        parsed = urlparse(server["url"])
        self.assertEqual(parsed.scheme, "https")
        self.assertTrue(parsed.netloc)
        self.assertEqual(parsed.path, "/mcp")
        self.assertNotIn("${", server["url"])
        self.assertEqual(server["auth"], "oauth")
        self.assertNotIn("headers", server)

    def test_codex_package_contains_no_credentials(self) -> None:
        config_text = (PLUGIN_ROOT / ".codex-plugin" / "plugin.json").read_text()
        for marker in ("Bearer ", "hqmcp_", "access_token", "refresh_token"):
            self.assertNotIn(marker, config_text)


if __name__ == "__main__":
    unittest.main()
